package keycloak.spi.migration.configuration

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.logging.Logger
import org.keycloak.exportimport.ExportAdapter
import org.keycloak.exportimport.ExportAdapter.ConsumerOfOutputStream
import org.keycloak.exportimport.ExportOptions
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.storage.DatastoreProvider
import org.keycloak.util.JsonSerialization
import java.io.ByteArrayOutputStream
import java.io.OutputStream


class RealmConfigurationProcessor {

    companion object {
        private val logger = Logger.getLogger(RealmConfigurationProcessor::class.java.name)
    }


    /**
     * Выполняет экспорт конфигурации рабочей области сервисов
     * @param exportGroupAndRoles признак экспорта групп и ролей
     * @param session ресурс управления сессий keycloak
     * @param realm модель рабочей области сервисов
     * @return сущность настроек рабочей области сервисов или null
     */
    fun partialExportRealmRepresentation(
        exportGroupAndRoles: Boolean,
        session: KeycloakSession,
        realm: RealmModel
    ): RealmRepresentation? {

        try {
            val response = partialExport(exportGroupAndRoles, session, realm)
            logger.info(">>>> Response export realm status = ${response.status}")

            if (response.statusInfo.family == Response.Status.Family.SUCCESSFUL) {

                val streamingOutput = response.entity as StreamingOutput
                val baos = ByteArrayOutputStream()
                streamingOutput.write(baos)
                val configuration =
                    JsonSerialization.readValue(baos.toByteArray(), RealmRepresentation::class.java)

                logger.info(">>>> Configuration received successfully for realm = ${configuration.realm}")
                return configuration
            }
        } catch (ex: Exception) {
            logger.error(">>>> Partial export configuration failed, message = ${ex.message}, cause = ${ex.cause}")
        }
        return null
    }


    fun partialExport(
        exportGroupsAndRoles: Boolean,
        session: KeycloakSession,
        realm: RealmModel,
    ): Response {

        val options =
            ExportOptions(false, false, exportGroupsAndRoles, false, true)

        val exportProvider =
            session.getProvider(DatastoreProvider::class.java).exportImportManager

        val response = Response.ok()

        exportProvider.exportRealm(realm, options, object : ExportAdapter {

            override fun setType(mediaType: String?) { response.type(mediaType) }

            override fun writeToOutputStream(consumer: ConsumerOfOutputStream) {
                response.entity(StreamingOutput { t: OutputStream? -> consumer.accept(t) })
            }
        })
        return response.build()
    }
}