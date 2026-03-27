package keycloak.spi.migration.groups

import jakarta.ws.rs.core.Response
import keycloak.spi.migration.constants.Constants
import keycloak.spi.migration.models.ResponseDto
import org.jboss.logging.Logger
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.GroupModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.representations.idm.GroupRepresentation
import org.keycloak.services.resources.admin.AdminEventBuilder

/**
 * Внутренний сервис выполняет импорт групп
 * @author Belotserkovskii Vitalii (c) 2026
 */
class ImportGroupsService(
    private val session: KeycloakSession,
    private val realm: RealmModel,
    private val adminEventBuilder: AdminEventBuilder
) {

    companion object {
        private val logger = Logger.getLogger(ImportGroupsService::class.java.name)
    }
    private val realmName = realm.name


    /**
     * Выполняет создание или обновление сущностей всех групп и подгрупп в заданной области сервисов realm
     * @param importGroupList список корневых групп с включенными подгруппами, полученный методом GET
     * @return статус выполнения и сообщение
     */
    fun createOrUpdateAllRealmGroups(
        importGroupList: List<GroupRepresentation>?
    ): Response {

        if (importGroupList.isNullOrEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "groups not provided properly")).build()
        }
        logger.info(">>>> Procedure importing groups to [$realmName] started")
        val responseDto = ResponseDto()
        try {
            // получаем карту существующих корневых групп для быстрого поиска
            val existingRootGroups =
                session.groups().getTopLevelGroupsStream(realm).toList().associateBy { it.name }

            // итерируемся по списку корневых групп
            importGroupList.forEach { importGroup ->
                val groupName = importGroup.name
                try {
                    val existingGroup = existingRootGroups[groupName]
                    if (existingGroup != null) {
                        // группа существует, необходимо обновление со всеми подгруппами
                        createOrUpdateGroupRecursive(importGroup, existingGroup, null)
                        responseDto.updated.add(groupName)
                    } else {
                        // необходимо создать новую группу со всеми подгруппами
                        createOrUpdateGroupRecursive(importGroup, null, null)
                        responseDto.created.add(groupName)
                    }

                } catch (ex: Exception) {
                    logger.error(">>>> Failed to process root group: $groupName", ex)
                    responseDto.failed.add(groupName)
                }
            }
            deleteTopLevelGroup(importGroupList, responseDto)

            adminEventBuilder.operation(OperationType.ACTION)
                .resource(ResourceType.GROUP)
                .resourcePath("migrations/groups/import")
                .representation(responseDto)
                .success()

            responseDto.display("Group imported successfully")
            return Response.ok(responseDto).build()

        } catch (ex: Exception) {
            logger.error(">>>> Procedure importing groups failed", ex)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to "internal server error: ${ex.message}")).build()
        }
    }


    /**
     * Выполняет удаление корневой группы, которой больше нет в списке импортируемых
     * @param importGroupList список сущностей импортируемых корневых групп
     * @param responseDto сущность ответа о результате выполнения операции импорта
     */
    private fun deleteTopLevelGroup(
        importGroupList: List<GroupRepresentation>,
        responseDto: ResponseDto) {

        session.groups().getTopLevelGroupsStream(realm).toList().forEach { groupModel ->
            val groupName = groupModel.name
            if (importGroupList.none { it.name == groupName }) {

                val groupId = groupModel.id
                session.groups().removeGroup(realm, groupModel)

                adminEventBuilder.operation(OperationType.DELETE)
                    .resource(ResourceType.GROUP)
                    .resourcePath("groups", groupId)
                    .success()

                responseDto.deleted.add(groupName)
            }
        }
    }


    /**
     * Этот метод — createOrUpdateGroupRecursive — это самое сердце нового SPI-сервиса для миграции групп.
     * Именно он выполняет основную работу по синхронизации, аккуратно спускаясь по дереву групп сверху вниз.
     * @param importGroupRepresentation "чертеж" группы, который пришел из файла импорта (JSON)
     * @param existingGroupModel если мы обновляем существующую группу, сюда передается объект
     * @param parentGroupModel "живая" сущность родительской группы
     * @return обновленную (созданную) модель группы с подгруппами
     */
    private fun createOrUpdateGroupRecursive(
        importGroupRepresentation: GroupRepresentation,
        existingGroupModel: GroupModel?,
        parentGroupModel: GroupModel?

    ): GroupModel {

        val groupName = importGroupRepresentation.name
        val groupModel = if (existingGroupModel != null) {
            // существующая группа передана, необходимо выполнить обновление
            existingGroupModel
        } else {
            // группа не существует, создает новую, если это подгруппа - прикрепляем к корневой (дел за гланды)
            val newGroup = session.groups().createGroup(realm, groupName)
            parentGroupModel?.addChild(newGroup)

            logger.debug(">>>> Created new group: $groupName")
            newGroup
        }

        updateGroupAttributes(importGroupRepresentation, groupModel)
        syncGroupRoles(importGroupRepresentation, groupModel)
        syncSubGroups(importGroupRepresentation, groupModel)

        // аудит создания или обновление группы
        val operation = if (existingGroupModel == null) OperationType.CREATE else OperationType.UPDATE

        adminEventBuilder.operation(operation)
            .resource(ResourceType.GROUP)
            .resourcePath("groups", groupModel.id)
            .representation(importGroupRepresentation)
            .success()

        return groupModel
    }


    /**
     * Выполняет добавление или удаление атрибутов для группы. Если в импортируемой новой сущности
     * нет атрибута который раньше был в существующей группе, мы его удалим. А затем добавим или обновим
     * остальные атрибуты, если импортируемая сущность группы содержит атрибуты
     * @param importedGroup импортируемая сущность группы, она содержит новую информацию о группе
     * @param groupModel вновь созданная или существующая группа, для которой обновляются атрибуты
     */
    private fun updateGroupAttributes(
        importedGroup: GroupRepresentation,
        groupModel: GroupModel
    ) {
        val importedAttributes = importedGroup.attributes ?: emptyMap()
        val currentAttributes = groupModel.attributes ?: emptyMap()

        // сначала удаляем атрибуты, которых в импортируемой сущности нет (строгая синхронизация)
        currentAttributes.keys.toList().forEach { key ->
            if (!importedAttributes.containsKey(key)) {
                groupModel.removeAttribute(key)
            }
        }
        // а теперь добавляем новые атрибуты или изменяем существующие, пустые атрибуты считаем ненужными
        importedAttributes.forEach { (key, values) ->
            if (values.isNullOrEmpty()) {
                if (currentAttributes.containsKey(key)) {
                    groupModel.removeAttribute(key)
                }
            } else {
                groupModel.setAttribute(key, values)
            }
        }
    }


    /**
     * Вначале метод очищает группу от атрибутов, если они имелись (например выполняется обновление).
     * Следующим шагом, выполняется назначение realm ролей, причем если роль не существует в текущем
     * realm, метод создает новую роль, а затем выполняет назначение созданной или имеющейся роли. Далее
     * выполняется назначение клиентских ролей. Здесь ситуация более масштабная, нам нужно иметь действующего
     * client и внутри него еще иметь нужную для назначения роль. Для этого, если клиента нет, мы его создаем,
     * и потом тоже самое делаем с ролью, создаем новую роль если нет в текущем или созданном client.
     *
     * @param importedGroup импортируемая сущность группы, она содержит новую информацию о группе
     * @param groupModel вновь созданная или существующая группа, которой назначаются роли
     */
    private fun syncGroupRoles(
        importedGroup: GroupRepresentation,
        groupModel: GroupModel
    ) {
        // очищаем старые роли для строгой синхронизации
        groupModel.roleMappingsStream.toList().forEach { groupModel.deleteRoleMapping(it) }

        // назначаем Realm Roles (используем session.roles() API)
        importedGroup.realmRoles?.forEach { roleName ->

            var roleModel = session.roles().getRealmRole(realm, roleName)
            if (roleModel == null) {
                roleModel = session.roles().addRealmRole(realm, roleName)
                roleModel.description = Constants.MIGRATION_DESC
                logger.debug(">>>> Created missing realm role = [$roleName]")
            }
            groupModel.grantRole(roleModel)
            logger.debug(">>>> Granted realm role = [$roleName] to group = [${groupModel.name}]")
        }

        // назначаем Client Roles
        importedGroup.clientRoles?.forEach { (clientId, roles) ->

            // получаем ресурс управления нужным нам клиентом, но его может и не существовать
            var clientModel = session.clients().getClientByClientId(realm, clientId)
            if (clientModel == null) {
                // тот случай когда в текущей области не клиента - создаем новый
                clientModel = session.clients().addClient(realm, clientId)
                clientModel.description = Constants.MIGRATION_DESC
                logger.debug(">>>> Created missing client: $clientId")
            }
            // раз у нас уже есть ресурс клиента, можно начать назначение роли
            roles?.forEach { roleName ->
                // получаем нужную нам роль для выполнения назначения, но роли может не быть
                var roleModel = session.roles().getClientRole(clientModel, roleName)
                if (roleModel == null) {
                    // тот случай когда роли нет, нужно создать новую и назначить группе
                    roleModel = session.roles().addClientRole(clientModel, roleName)
                    roleModel.description = Constants.MIGRATION_DESC
                    logger.debug(">>>> Created missing client role = [$roleName] in client [${clientModel.name}]")
                }
                groupModel.grantRole(roleModel)
            }
        }
    }


    /**
     * Выполняет добавление подгрупп к заданной родительской группе.
     * Метод вначале синхронизирует список подгруппы, удаляя несуществующие в импорте.
     * Затем запускается итеративное создание/обновление под-группы, которая опять вернется сюда.
     * @param importedGroupRepresentation импортируемая сущность группы, она содержит новую информацию о группе
     * @param parentGroupModel родительская существующая группа, в которую добавляются подгруппы
     */
    private fun syncSubGroups(
        importedGroupRepresentation: GroupRepresentation,
        parentGroupModel: GroupModel
    ) {
        val importedSubGroups = importedGroupRepresentation.subGroups ?: emptyList()
        val importedSubGroupNames = importedSubGroups.map { it.name }.toSet()

        // удаляем из Keycloak те подгруппы, которых нет в файле импорта
        val existingSubGroups = parentGroupModel.subGroupsStream?.toList() ?: emptyList()
        existingSubGroups.forEach { existingSubGroup ->
            if (!importedSubGroupNames.contains(existingSubGroup.name)) {
                parentGroupModel.removeChild(existingSubGroup)
                session.groups().removeGroup(realm, existingSubGroup)
            }
        }

        // итерируемся по импортируемым подгруппам и уходим в рекурсию
        importedSubGroups.forEach { importSubGroup ->

            val subGroupName = importSubGroup.name
            val existingSubGroup = existingSubGroups.firstOrNull { it.name == subGroupName }
            createOrUpdateGroupRecursive(
                importSubGroup, existingSubGroup, parentGroupModel
            )
        }
    }
}