package keycloak.spi.migration.clients

import jakarta.ws.rs.core.Response
import keycloak.spi.migration.utils.splitToList
import org.jboss.logging.Logger
import org.keycloak.authorization.AuthorizationProvider
import org.keycloak.models.ClientModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.keycloak.models.utils.ModelToRepresentation
import org.keycloak.representations.idm.UserRepresentation


/**
 * Внутренний SPI сервис по экспорту и импорту Clients
 * @author Belotserkovskii Vitalii (c) 2026
 */
class ExportClientService(
    private val session: KeycloakSession,
    private val realm: RealmModel
) {

    companion object {
        private val logger = Logger.getLogger(ExportClientService::class.java.name)
    }
    private val realmName = realm.name


    /**
     * Выполняет чтение списка всех сущностей Clients для заданной входным параметром области сервисов.
     * @param clientIdsString: Строка, содержащая список client_id, которые необходимо экспортировать.
     * @return статус выполнения, список сервисов Clients - либо сообщение об ошибке
     */
    fun getRealmClients(clientIdsString: String?): Response {

        if (clientIdsString.isNullOrBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "client ids not provided")).build()
        }
        logger.info(">>>> Procedure export clients in = [$clientIdsString] started")
        val exportList = ClientListExportDto()

        splitToList(clientIdsString).forEach { clientId ->
            try {
                val clientModel = session.clients().getClientByClientId(realm, clientId)
                if (clientModel == null) {
                    exportList.addNotFound(clientId)
                    logger.warn(">>>> Client [$clientId] not found in realm = [$realmName]")
                    return@forEach
                }
                val clientExportDto = getClientRepresentation(clientModel)

                exportList.addSuccess(clientExportDto)
                logger.info(">>>> Client [$clientId] has been successfully exported")

            } catch (ex: Exception) {
                logger.error(">>>> CRITICAL: Failed to export client [$clientId] due to internal error", ex)
            }
        }

        if (exportList.clients.isNotEmpty()) {
            return Response.ok(exportList).build()
        }

        return Response.status(Response.Status.NOT_FOUND)
            .entity(mapOf("error" to "Clients not found")).build()
    }


    /**
     * Выполняет формирование экспортной сущности клиента, обогащенной ролями, настройками авторизации,
     * учётной записью системного пользователя.
     * @param clientModel ресурс управления клиентом
     * @return обогащенную экспортную сущность
     */
    private fun getClientRepresentation(clientModel: ClientModel): ClientExportDto {

        val clientRepresentation = ModelToRepresentation.toRepresentation(clientModel, session)
        val clientRoles = clientModel.rolesStream?.map { ModelToRepresentation.toRepresentation(it) }?.toList() ?: emptyList()

        val clientExportDto = ClientExportDto(
            clientRepresentation = clientRepresentation,
            clientRoles = clientRoles)

        val isAuthorizationEnabled = clientRepresentation.authorizationServicesEnabled ?: false
        if (isAuthorizationEnabled) {

            val authProvider = session.getProvider(AuthorizationProvider::class.java)
            val storeFactory = authProvider.storeFactory
            val resourceServer = storeFactory.resourceServerStore.findByClient(clientModel)
            if (resourceServer != null) {

                val settings = ModelToRepresentation.toResourceServerRepresentation(session, clientModel)
                clientRepresentation.authorizationSettings = settings
                clientExportDto.exportSettings = settings
            }
        }

        val isServiceAccountEnabled = clientRepresentation.isServiceAccountsEnabled ?: false
        if (isServiceAccountEnabled) {
            val serviceAccountUserModel = session.users().getServiceAccount(clientModel)
            if (serviceAccountUserModel != null) {
                clientExportDto.serviceAccountUser = enrichServiceAccountUser(serviceAccountUserModel)
            }
        }

        return clientExportDto
    }


    /**
     * Обогащает Service Account ролями и группами
     * @param userModel ресурс управления системным пользователем
     */
    private fun enrichServiceAccountUser(userModel: UserModel): UserRepresentation {

        val userRepresentation = ModelToRepresentation.toRepresentation(session, realm, userModel)
        val clientRoles = mutableMapOf<String, List<String>>()

        userModel.roleMappingsStream
            .filter { it.isClientRole }
            .toList()
            .groupBy { it.containerId }
            .forEach { (containerId, userClientRoles) ->
                val clientModel = session.clients().getClientById(realm, containerId)
                if (clientModel != null) {
                    clientRoles[clientModel.clientId] = userClientRoles.map { it.name }
                }
            }

        val realmRoles = userModel.realmRoleMappingsStream.map { it.name }.toList()
        val groups = userModel.groupsStream.map { ModelToRepresentation.buildGroupPath(it) }.toList()

        userRepresentation.realmRoles = realmRoles
        userRepresentation.clientRoles = clientRoles
        userRepresentation.groups = groups

        return userRepresentation
    }

}