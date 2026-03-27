package keycloak.spi.migration.roles

import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.utils.ModelToRepresentation

/**
 * Внутренний сервис для экспорта Realm Roles
 * @author Belotserkovskii Vitalii (c) 2026
 */
class ExportRealmRolesService(
    private val session: KeycloakSession,
    private val realm: RealmModel,
) {

    companion object {
        private val logger = Logger.getLogger(ExportRealmRolesService::class.java.name)
    }
    private val realmName = realm.name


    /**
     * Выполняет чтение списка сущностей всех Realm Roles рабочей области сервисов
     * @return список ролей
     */
    fun getRealmRoles(): Response {

        logger.info(">>>> Procedure exporting realm roles in realm = [$realmName] started")
        try {
            val foundRealmRoles = session.roles().getRealmRolesStream(realm)
                ?.filter { it.description.isNullOrEmpty() || !it.description.startsWith("$") }
                ?.map { ModelToRepresentation.toRepresentation(it) }
                ?.toList()

            if (foundRealmRoles.isNullOrEmpty()) {
                logger.info(">>>> Realm roles not found in realm = [$realm]")
                return Response
                    .status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "realm roles not found")).build()
            }

            logger.info(">>>> Successfully found and exported Realm Roles count = ${foundRealmRoles.size}")
            return Response.ok(foundRealmRoles).build()

        } catch (ex: Exception) {

            logger.error(">>>> Exporting Realm Roles in realm = [$realmName] failed", ex)
            return Response
                .status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to "internal server error: ${ex.message}")).build()
        }
    }

}