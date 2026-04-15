package keycloak.spi.migration.roles

import jakarta.ws.rs.core.Response
import keycloak.spi.migration.models.ResponseDto
import org.jboss.logging.Logger
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.RoleModel
import org.keycloak.models.utils.ModelToRepresentation
import org.keycloak.models.utils.RepresentationToModel
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.services.resources.admin.AdminEventBuilder

/**
 * Внутренний сервис для импорта Realm Roles
 * @author Belotserkovskii Vitalii (c) 2026
 */
class ImportRealmRolesService(
    private val session: KeycloakSession,
    private val realm: RealmModel,
    private val adminEventBuilder: AdminEventBuilder
) {

    companion object {
        private val logger = Logger.getLogger(ImportRealmRolesService::class.java.name)
    }
    private val realmName = realm.name


    /**
     * Выполняет создание или обновление сущностей realm roles рабочей области сервисов
     * @param importedRealmRoles список импортируемых ролей области
     * @return статус и результат выполнения
     */
    fun createOrUpdateRealmRoles(importedRealmRoles: List<RoleRepresentation>): Response {

        if (importedRealmRoles.isEmpty()) {
            logger.warn(">>>> Imported roles list is empty")
            return Response
                .status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "import role list empty")).build()
        }

        logger.info(">>>> Procedure importing of Realm Roles in realm = [$realmName] started")
        val existingRolesNames =
            session.roles().getRealmRolesStream(realm)?.map { it.name }?.toList()?.toSet() ?: emptySet()

        val responseDto = ResponseDto()
        importedRealmRoles.forEach { importedRoleRepresentation ->

            val name = importedRoleRepresentation.name
            try {
                if (existingRolesNames.contains(name)) {
                    // обновляем существующую роль
                    updateRealmRoles(importedRoleRepresentation, responseDto)
                } else {
                    // создаем новую роль
                    createRealmRole(importedRoleRepresentation, responseDto)
                }
            } catch (ex: Exception) {
                logger.error(">>>> Importing Realm Role = [$realmName] failed", ex)
                responseDto.failed.add(name)
            }
        }
        // удаляем устаревшие роли, которых нет в импорте
        deleteRottenRealRoles(importedRealmRoles, responseDto)
        responseDto.display("Realm Roles import finished")

        adminEventBuilder.operation(OperationType.ACTION)
            .resource(ResourceType.REALM)
            .resourcePath("/migrations/realm-roles/import")
            .representation(responseDto)
            .success()

        return Response.ok(responseDto).build()
    }


    /**
     * Выполняет удаление устаревшей realm role и регистрацию admin события.
     * @param importedRealmRoles сущность импортированной роли
     * @param responseDto сущность ответа метода импорта
     */
    private fun deleteRottenRealRoles(
        importedRealmRoles: List<RoleRepresentation>,
        responseDto: ResponseDto
    ) {
        session.roles().getRealmRolesStream(realm)?.filter {
            it.description.isNullOrEmpty() || !it.description.startsWith("$")
        }?.forEach { role ->
            val name = role.name
            if (importedRealmRoles.none { it.name == name }) {
                // удаляем роль, ее нет в импорте, мусор долой
                session.roles().removeRole(role)

                responseDto.deleted.add(name)
                logger.debug(">>>> Realm role = [$name] deleted successfully")

                val representation = ModelToRepresentation.toRepresentation(role)
                adminEventBuilder.operation(OperationType.CREATE)
                    .resource(ResourceType.REALM_ROLE)
                    .resourcePath(role.id)
                    .representation(representation)
                    .success()
            }
        }
    }


    /**
     * Выполняет создание новой realm role и регистрацию admin события.
     * @param importedRoleRepresentation сущность импортированной роли
     * @param responseDto сущность ответа метода импорта
     */
    private fun createRealmRole(
        importedRoleRepresentation: RoleRepresentation,
        responseDto: ResponseDto
    ) {

        importedRoleRepresentation.id = null
        importedRoleRepresentation.containerId = null
        val name = importedRoleRepresentation.name
        val roleModel =
            RepresentationToModel.createRole(realm, importedRoleRepresentation)

        responseDto.created.add(name)
        logger.debug(">>>> Realm role = [$name] created successfully")

        adminEventBuilder.operation(OperationType.CREATE)
            .resource(ResourceType.REALM_ROLE)
            .resourcePath(roleModel.id)
            .representation(importedRoleRepresentation)
            .success()
    }


    /**
     * Выполняет обновление сущности realm role и регистрацию admin события.
     * @param importedRoleRepresentation сущность импортированной роли
     * @param responseDto сущность ответа метода импорта
     */
    private fun updateRealmRoles(
        importedRoleRepresentation: RoleRepresentation,
        responseDto: ResponseDto
    ) {
        val name = importedRoleRepresentation.name
        val roleModel = session.roles().getRealmRole(realm, name)
        roleModel.updateFromRepresentation(importedRoleRepresentation)

        responseDto.updated.add(name)
        logger.debug(">>>> Realm role = [$name] updated successfully")

        adminEventBuilder.operation(OperationType.UPDATE)
            .resource(ResourceType.REALM_ROLE)
            .resourcePath(roleModel.id)
            .representation(importedRoleRepresentation)
            .success()
    }


    /**
     * Выполняет обновление роли. Изменение происходит только для описания и атрибутов
     * @param representation импортируемая сущность новой роли
     */
    fun RoleModel.updateFromRepresentation(representation: RoleRepresentation) {

        this.description = representation.description
        val importedAttributes = representation.attributes ?: emptyMap()

        // удаляем атрибуты, которых больше нет в импорте
        this.attributes.keys.toList().forEach { key ->
            if (!importedAttributes.containsKey(key)) {
                this.removeAttribute(key)
            }
        }

        // добавляем новые и обновляем существующие атрибуты
        importedAttributes.forEach { (key, values) ->
            this.setAttribute(key, values)
        }
    }

}