package keycloak.spi.migration.clients

import jakarta.ws.rs.core.Response
import keycloak.spi.migration.models.ClientImportResponseDto
import keycloak.spi.migration.models.ClientListExportDto
import org.jboss.logging.Logger
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.services.resources.admin.AdminEventBuilder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Внутренний SPI сервис по экспорту и импорту Clients (Оркестратор)
 * @author Belotserkovskii Vitalii (c) 2026
 */
class InternalImportClientService(
    private val session: KeycloakSession,
    private val realm: RealmModel,
    private val adminEventBuilder: AdminEventBuilder
) {

    companion object {
        private val logger = Logger.getLogger(InternalImportClientService::class.java.name)
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyy-HHmm")
    }
    private val realmName = realm.name

    /**
     * ИМПОРТ: Создание или обновление клиентов
     */
    fun createOrUpdateRealmClients(
        stamp: String?,
        isAlwaysCreate: Boolean,
        importClients: ClientListExportDto
    ): Response {

        val importedClients = importClients.clients
        if (importedClients.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "clients import list is empty")).build()
        }
        logger.info(">>>> Import procedure clients to realm = [$realmName] started")

        val responseDto = ClientImportResponseDto()
        val actualStamp = if (stamp.isNullOrBlank()) LocalDateTime.now().format(FORMATTER) else stamp
        val processor = ClientImportProcessor(session, realm, adminEventBuilder)

        importedClients.forEach { importedClientDto ->

            val importClientRepresentation = importedClientDto.clientRepresentation ?: return@forEach
            var clientId = importClientRepresentation.clientId

            try {
                // ищем клиента в keycloak для определения логики: создания или обновления
                val foundClientModel = session.clients().getClientByClientId(realm, clientId)

                if (isAlwaysCreate) {
                    clientId = "$clientId-$actualStamp-migrated"
                    importClientRepresentation.clientId = clientId
                    importClientRepresentation.description = (importClientRepresentation.description ?: "") + " (migrated)"
                }

                val finalClientRepresentation = if (foundClientModel == null || isAlwaysCreate) {
                    processor.createClientImported(importedClientDto)
                } else {
                    processor.updateClientImported(importedClientDto, foundClientModel)
                }

                if (finalClientRepresentation != null) {
                    logger.info(">>>> Client [$clientId] has been successfully created|updated")
                    responseDto.addSuccess(finalClientRepresentation)
                } else {
                    logger.info(">>>> Unknown error during import [$clientId] occurred")
                    responseDto.failedImported.add(clientId)
                }

            } catch (ex: Exception) {
                logger.error(">>>> Failed to import client [$clientId]", ex)
                responseDto.failedImported.add(clientId)
            }
        }

        logger.info(">>>> In realm [$realmName] imported [${responseDto.successImported.size}] clients")
        return Response.ok(responseDto).build()
    }
}