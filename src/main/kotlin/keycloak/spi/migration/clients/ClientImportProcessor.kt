package keycloak.spi.migration.clients

import keycloak.spi.migration.constants.Constants
import org.jboss.logging.Logger
import org.keycloak.authorization.AuthorizationProvider
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.ClientModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.keycloak.models.utils.KeycloakModelUtils
import org.keycloak.models.utils.ModelToRepresentation
import org.keycloak.models.utils.RepresentationToModel
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.keycloak.services.resources.admin.AdminEventBuilder


/**
 * Процессор импорта клиента. Реализует логику раздельного создания/обновления
 * с точечным управлением мапперами, ролями и UMA.
 */
class ClientImportProcessor(
    private val session: KeycloakSession,
    private val realm: RealmModel,
    private val adminEventBuilder: AdminEventBuilder
) {

    companion object {
        private val logger = Logger.getLogger(ClientImportProcessor::class.java.name)
    }
    private val realmName = realm.name


    /**
     * Выполняет создание нового сервиса в области realm. После успешного создания, добавляются mappers,
     * client роли, системный пользователь (если он имеется), авторизация - ресурсы, scopes, политики
     *
     * @param clientExportDto экспортная сущность нового сервиса
     * @return сущность нового, созданного Client, или null в случае ошибки
     */
    fun createClientImported(clientExportDto: ClientExportDto): ClientRepresentation? {

        val importClientRepresentation = clientExportDto.clientRepresentation ?: return null
        val clientId = importClientRepresentation.clientId
        logger.info(">>>> Procedure creating client = [$clientId] started")

        try {
            // Сохраняем новые mappers и создаем сервис с пустыми
            val keepMappers = importClientRepresentation.protocolMappers

            importClientRepresentation.id = null
            importClientRepresentation.protocolMappers = null
            importClientRepresentation.authorizationSettings = null
            importClientRepresentation.authenticationFlowBindingOverrides = null

            val clientModel =
                RepresentationToModel.createClient(session, realm, importClientRepresentation) ?: return null

            logger.info(">>>> Client client id = [$clientId] successfully created in realm = [$realmName]")

            adminEventBuilder.operation(OperationType.CREATE)
                .resource(ResourceType.CLIENT).resourcePath(clientModel.id)
                .representation(importClientRepresentation).success()

            // возвращаем mappers для последующей обработки
            importClientRepresentation.protocolMappers = keepMappers

            // вызываем методы обогащения клиента
            createOrUpdateClientProtocolMappers(importClientRepresentation, clientModel)
            createOrUpdateClientRoles(clientExportDto, clientModel)
            updateServiceAccountUser(clientExportDto, clientModel)
            updateAuthorizationSettings(clientExportDto, clientModel)

            logger.info(">>>> Client [$clientId] created successfully")
            return ModelToRepresentation.toRepresentation(clientModel, session)

        } catch (ex: Exception) {
            logger.error(">>>> Exception during creating client $clientId", ex)
            return null
        }
    }

    /**
     * Обновление существующего клиента
     */
    fun updateClientImported(clientExportDto: ClientExportDto, foundClientModel: ClientModel): ClientRepresentation? {
        val importClientRepresentation = clientExportDto.clientRepresentation ?: return null
        val clientId = foundClientModel.clientId
        logger.info(">>>> Procedure updating for [$clientId] started")

        try {
            importClientRepresentation.id = foundClientModel.id
            val keepMappers = importClientRepresentation.protocolMappers

            // REFACTORING: В SPI для базового обновления скармливаем старые мапперы, чтобы ядро их не затерло раньше времени
            importClientRepresentation.protocolMappers = foundClientModel.protocolMappersStream
                .map { ModelToRepresentation.toRepresentation(it) }.toList()

            RepresentationToModel.updateClient(importClientRepresentation, foundClientModel, session)

            adminEventBuilder.operation(OperationType.UPDATE)
                .resource(ResourceType.CLIENT).resourcePath(foundClientModel.id)
                .representation(importClientRepresentation).success()

            // Возвращаем новые mappers и запускаем твою логику обновления
            importClientRepresentation.protocolMappers = keepMappers

            createOrUpdateClientProtocolMappers(importClientRepresentation, foundClientModel)
            createOrUpdateClientRoles(clientExportDto, foundClientModel)
            updateServiceAccountUser(clientExportDto, foundClientModel)
            updateAuthorizationSettings(clientExportDto, foundClientModel)

            logger.info(">>>> Client = [$clientId] updated successfully")
            return ModelToRepresentation.toRepresentation(foundClientModel, session)

        } catch (ex: Exception) {
            logger.error(">>>> Update of client $clientId failed by [${ex.message}]", ex)
            return null
        }
    }


    /**
     * Добавляет и обновляет protocol mappers для Clients, заданного ресурсом управления
     * @param importClientRepresentation импортируемая сущность со списком протоколов
     * @param clientModel ресурс управления сервисом
     */
    private fun createOrUpdateClientProtocolMappers(
        importClientRepresentation: ClientRepresentation,
        clientModel: ClientModel
    ) {
        val clientId = importClientRepresentation.clientId
        val importProtocolMappers = importClientRepresentation.protocolMappers ?: emptyList()
        val foundProtocolMappers = clientModel.protocolMappersStream.toList().associateBy { it.name }

        try {
            if (importProtocolMappers.isNotEmpty()) {
                importProtocolMappers.forEach { importMapper ->

                    val name = importMapper.name
                    val id = foundProtocolMappers[name]?.id
                    if (id != null) {
                        importMapper.id = id // update
                        clientModel.updateProtocolMapper(RepresentationToModel.toModel(importMapper))
                        logger.debug(">>>> ProtocolMapper name = [$name] updated for client = [$clientId]")
                    } else {
                        importMapper.id = null // create
                        clientModel.addProtocolMapper(RepresentationToModel.toModel(importMapper))
                        logger.debug(">>>> ProtocolMapper name = [$name] created for client id = $clientId")
                    }
                }
                // удаляем неактуальные
                foundProtocolMappers.values.forEach { protocolMapper ->
                    val name = protocolMapper.name
                    if (importProtocolMappers.none { it.name.equals(name) }) {
                        clientModel.removeProtocolMapper(protocolMapper)
                    }
                }
            } else {
                // если импорт пустой, удаляем все текущие
                if (foundProtocolMappers.isNotEmpty()) {
                    foundProtocolMappers.values.forEach { protocolMapper ->
                        clientModel.removeProtocolMapper(protocolMapper)
                    }
                }
            }
        } catch (ex: Exception) {
            logger.error(">>>> Mapping of ProtocolMappers for client: [$clientId] failed", ex)
        }
    }


    /**
     * Выполняет создание или обновление ролей, назначенной для сервиса Client
     *
     * @param importedClientExportDto экспортная сущность нового сервиса
     * @param clientModel ресурс управления сервисом Client
     */
    private fun createOrUpdateClientRoles(
        importedClientExportDto: ClientExportDto,
        clientModel: ClientModel
    ) {

        val clientId = clientModel.clientId ?: return
        val importedClientRoles = importedClientExportDto.clientRoles ?: return
        logger.debug(">>>> Create or update client roles = [$clientId] started")

        val foundClientRoles = clientModel.rolesStream.toList().associateBy { it.name }

        importedClientRoles.forEach { importedClientRole ->
            val name = importedClientRole.name
            try {
                val existingRole = foundClientRoles[name]

                if (existingRole == null)    {
                    val createdRole = clientModel.addRole(name)
                    createdRole.description = importedClientRole.description
                    logger.debug(">>>> Role name = [$name] successfully created for client = [$clientId]")
                } else {
                    existingRole.description = importedClientRole.description
                    logger.debug(">>>> Role name = [$name] successfully updated for client = [$clientId]")
                }
            } catch (ex: Exception) {
                logger.error(">>>> Failed creating of [$name] for client id = [$clientId]", ex)
            }
        }

        // удаляем устаревшие роли, которых нет в списке импортируемых
        try {
            foundClientRoles.values.forEach { clientRole ->
                if (importedClientRoles.none { it.name == clientRole.name }) {
                    clientModel.removeRole(clientRole)
                    logger.debug(">>>> Role with name = [${clientRole.name}] deleted successfully")
                }
            }
        } catch (ex: Exception) {
            logger.error(">>>> Deleting outdated roles failed", ex)
        }
    }


    /**
     * Выполняет обновление учетной записи системного пользователя для Client
     *
     * @param importedClientExportDto экспортная сущность нового сервиса
     * @param clientModel ресурс управления сервисом Client
     */
    private fun updateServiceAccountUser(
        importedClientExportDto: ClientExportDto,
        clientModel: ClientModel
    ) {

        val importedServiceUserAccount = importedClientExportDto.serviceAccountUser ?: return
        if (clientModel.isServiceAccountsEnabled.not()) return

        val serviceAccountUserModel = session.users().getServiceAccount(clientModel) ?: return
        val userName = serviceAccountUserModel.username

        try {
            importedServiceUserAccount.attributes?.forEach { (key, values) ->
                serviceAccountUserModel.setAttribute(key, values)
            }

            assignRealmRolesToUser(importedServiceUserAccount, serviceAccountUserModel)
            assignClientRolesToUser(importedServiceUserAccount, serviceAccountUserModel)
            assignGroupsToUser(importedServiceUserAccount, serviceAccountUserModel)

            logger.info(">>>> Service account user $userName successfully updated")
        } catch (ex: Exception) {
            logger.error(">>>> Failed to update serviceAccountUser = $userName", ex)
        }
    }


    /**
     * Назначение пользователю realm ролей из импортной сущности в существующую
     *
     * @param importedServiceAccountRepresentation импортная сущность системного пользователя
     * @param serviceAccountUserModel ресурс управления существующим системным пользователем
     */
    private fun assignRealmRolesToUser(
        importedServiceAccountRepresentation: UserRepresentation,
        serviceAccountUserModel: UserModel
    ) {

        val userName = serviceAccountUserModel.username
        logger.debug(">>>> Start to add realm roles for service user = $userName")

        val rolesToRemove = serviceAccountUserModel.realmRoleMappingsStream
            .filter { it.description?.startsWith("$") == false }.toList()

        rolesToRemove.forEach { serviceAccountUserModel.deleteRoleMapping(it) }

        importedServiceAccountRepresentation.realmRoles?.forEach { roleName ->

            var roleModel = session.roles().getRealmRole(realm, roleName)
            if (roleModel == null) {
                roleModel = session.roles().addRealmRole(realm, roleName)
                roleModel.description = Constants.MIGRATION_DESC_SA
                logger.debug(">>>> Realm role = $roleName created for adding to user = $userName")
            }
            serviceAccountUserModel.grantRole(roleModel)
        }
    }


    /**
     * Назначение пользователю client ролей из импортной сущности в существующую
     *
     * @param importedServiceAccount импортная сущность системного пользователя
     * @param serviceAccountUserModel ресурс управления существующим системным пользователем
     */
    private fun assignClientRolesToUser(
        importedServiceAccount: UserRepresentation,
        serviceAccountUserModel: UserModel
    ) {

        val userName = serviceAccountUserModel.username
        val userClientRoles = importedServiceAccount.clientRoles ?: return

        // Удаляем все старые client roles у пользователя
        serviceAccountUserModel.roleMappingsStream.filter { it.isClientRole }.toList().forEach {
            serviceAccountUserModel.deleteRoleMapping(it)
        }

        // присваиваем пользователю новый список импортируемых ролей
        userClientRoles.forEach { (clientId, roles) ->
            try {
                // Ищем client или создаем нового клиента-пустышку
                var clientModel = session.clients().getClientByClientId(realm, clientId)
                if (clientModel == null) {
                    clientModel = session.clients().addClient(realm, clientId)
                    clientModel.description = Constants.MIGRATION_DESC_SA
                }

                roles.forEach { roleName ->
                    var roleModel = session.roles().getClientRole(clientModel, roleName)
                    if (roleModel == null) {
                        roleModel = session.roles().addClientRole(clientModel, roleName)
                        roleModel.description = Constants.MIGRATION_DESC_SA
                    }
                    serviceAccountUserModel.grantRole(roleModel)
                }
            } catch (ex: Exception) {
                logger.error(">>>> Error while assigning client role to user $userName", ex)
            }
        }
    }


    /**
     * Выполняет обновление учетной записи системного пользователя для Client
     *
     * @param importedServiceAccount импортная сущность нового системного пользователя
     * @param serviceAccountUserModel ресурс управления текущим системным пользователем
     */
    private fun assignGroupsToUser(
        importedServiceAccount: UserRepresentation,
        serviceAccountUserModel: UserModel
    ) {
        val importedUserGroups = importedServiceAccount.groups ?: return
        val foundUserGroups = serviceAccountUserModel.groupsStream?.toList() ?: emptyList()

        if (importedUserGroups.isNotEmpty()) {
            importedUserGroups.forEach { groupPath ->

                val groupModel = KeycloakModelUtils.findGroupByPath(session, realm, groupPath)
                if (groupModel != null
                    && foundUserGroups.none { ModelToRepresentation.buildGroupPath(it) == groupPath }) {
                    serviceAccountUserModel.joinGroup(groupModel)
                }
            }
            foundUserGroups.forEach { groupModel ->
                val path = ModelToRepresentation.buildGroupPath(groupModel)
                if (importedUserGroups.none { it == path }) {
                    serviceAccountUserModel.leaveGroup(groupModel)
                }
            }
        } else {
            // если в импорте нет групп, значит существующие нужно удалить
            foundUserGroups.forEach { serviceAccountUserModel.leaveGroup(it) }
        }
    }


    /**
     * Выполняет обновление настроек авторизации для клиента. Вначале метод удаляет все имеющиеся
     * ресурсы, scopes и политики, затем импортирует эти настройки из экспортной сущности client
     *
     * @param importedClientExportDto импортируемый новый client
     * @param clientModel ресурс управления client
     */
    private fun updateAuthorizationSettings(
        importedClientExportDto: ClientExportDto,
        clientModel: ClientModel
    ) {

        val isAuthorizationEnabled =
            importedClientExportDto.clientRepresentation?.authorizationServicesEnabled ?: false

        if (isAuthorizationEnabled) {
            try {
                val exportedSettings = importedClientExportDto.exportSettings ?: return
                val authorization = session.getProvider(AuthorizationProvider::class.java)

                exportedSettings.clientId = clientModel.id
                RepresentationToModel.toModel(exportedSettings, authorization, clientModel)

                adminEventBuilder.resource(ResourceType.AUTHORIZATION_RESOURCE_SERVER)
                    .operation(OperationType.UPDATE)
                    .resourcePath(session.context.uri)
                    .representation(exportedSettings).success()

                logger.info(">>>> Authorization settings successfully imported for client [${clientModel.clientId}]")

            } catch (ex: Exception) {
                logger.error(">>>> Export import settings failed for ${clientModel.clientId}", ex)
            }
        }
    }

}