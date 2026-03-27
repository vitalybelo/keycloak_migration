package keycloak.spi.migration.scopes

import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.utils.ModelToRepresentation

/**
 * Внутренний сервис для экспорта Client Scopes
 * @author Belotserkovskii Vitalii (c) 2026
 */
class ExportClientScopeService(
    private val session: KeycloakSession,
    private val realm: RealmModel,
) {

    companion object {
        private val logger = Logger.getLogger(ExportClientScopeService::class.java.name)
    }
    private val realmName = realm.name


    /**
     * Выполняет чтение списка сущностей Realm Client Scopes.
     * Вначале метод получает полный список всех realm client scopes, затем списки назначений default/optional
     * @return экземпляр класса экспорта client scopes
     */
    fun getRealmClientScopes(): Response {

        logger.info(">>>> Procedure exporting client scopes realm = [$realmName] started")
        try {
            val scopeExportDto = ClientScopeExportDto()

            scopeExportDto.clientScopes = session.clientScopes().getClientScopesStream(realm)
                .map { scopeModel -> ModelToRepresentation.toRepresentation(scopeModel) }
                .toList()

            if (scopeExportDto.clientScopes.isNullOrEmpty()) {
                val message = "client scopes not found in realm = [$realmName]"
                logger.warn(">>>> $message")
                return Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to message)).build()
            }

            // достаем список назначений скоупов (true = default, false = optional)
            scopeExportDto.defaultScopes = realm.getDefaultClientScopesStream(true).map { it.name }.toList()
            scopeExportDto.optionalScopes = realm.getDefaultClientScopesStream(false).map { it.name }.toList()

            logger.info(">>>> Client scopes for realm [$realmName] exported successfully")
            return Response.ok(scopeExportDto).build()

        } catch (ex: Exception) {

            logger.error(">>>> Exporting client scopes failed in realm = [$realmName]", ex)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to "internal server error: ${ex.message}"))
                .build()
        }
    }

}