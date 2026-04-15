package keycloak.spi.migration.configuration

import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.representations.idm.RealmRepresentation

/**
 * Сервисный слой для обеспечения методов экспорта конфигурации рабочей области сервисов
 * @author Vitaly Belotserkovskii 06.04.2026
 */
class ExportRealmConfiguration(
    private val session: KeycloakSession,
    private val realm: RealmModel
) {

    private val processor = RealmConfigurationProcessor()

    companion object {
        private val logger = Logger.getLogger(ExportRealmConfiguration::class.java)
    }


    /**
     * Выполняет чтение настроек области сервисов realm
     *
     * @param exportConditions условия формирования экспортной сущности настроек области сервисов
     * @return статус выполнения, сущность настроек или сообщение об ошибке
     */
    fun getRealmConfiguration(
         exportConditions: RealmExportConditions
     ): Response {

        logger.info(">>>> Exporting realm configuration for realm = [${realm.name} started")
        try {
            val isGroupsRoles = exportConditions.isMigrateRealmGroups || exportConditions.isMigrateRealmRoles
            val configuration =
                processor.partialExportRealmRepresentation(isGroupsRoles, session, realm)

            if (configuration != null) {
                cleanConditionalRealConfiguration(
                    configuration,
                    exportConditions
                )
                return Response.ok(configuration).build()
            }
        } catch (ex: Exception) {
            logger.error(">>>> Export realm configuration failed", ex)
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity("error" to "failed export realm configuration").build()
    }


    /**
     * Вычищает из экспортной сущности ненужные параметры. Признаки попадания в экспортную сущность определенных
     * данных задается параметрами запроса. Параметры не обязательные, и если они не заданы, будет сформирована
     * максимально облегченная экспортная сущность.
     */
    private fun cleanConditionalRealConfiguration(

        realmRepresentation: RealmRepresentation,
        migrationConditions: RealmExportConditions
    ) {
        if (!migrationConditions.isMigrateRealmRoles) {
            realmRepresentation.roles = null
        }
        if (!migrationConditions.isMigrateClientScopes) {
            realmRepresentation.clientScopes = null
            realmRepresentation.defaultDefaultClientScopes = null
            realmRepresentation.defaultOptionalClientScopes = null
        }
        if (!migrationConditions.isMigrateRealmGroups) {
            realmRepresentation.groups = null
        }
        if (!migrationConditions.isMigrateFlows) {
            realmRepresentation.authenticationFlows = null
            realmRepresentation.authenticatorConfig = null
        }
    }

}