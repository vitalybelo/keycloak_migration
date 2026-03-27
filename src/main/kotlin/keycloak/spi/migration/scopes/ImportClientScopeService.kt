package keycloak.spi.migration.scopes

import jakarta.ws.rs.core.Response
import keycloak.spi.migration.constants.Constants
import keycloak.spi.migration.models.ResponseDto
import org.jboss.logging.Logger
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.ClientScopeModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.utils.RepresentationToModel
import org.keycloak.representations.idm.ClientScopeRepresentation
import org.keycloak.services.resources.admin.AdminEventBuilder

/**
 * Внутренний сервис для экспорта и импорта Client Scopes
 * @author Belotserkovskii Vitalii (c) 2026
 */
class ImportClientScopeService(
    private val session: KeycloakSession,
    private val realm: RealmModel,
    private val adminEventBuilder: AdminEventBuilder
) {

    companion object {
        private val logger = Logger.getLogger(ImportClientScopeService::class.java.name)
    }
    private val realmName = realm.name


    /**
     * Выполняет добавление Realm Client Scopes для заданной параметром области сервисов.
     * Список сущностей импорта маппинга метод получает как параметр, переданный в теле запроса.
     *
     * @param importedClientScopesDto список сущностей маппинга Client Scopes
     * @return статус выполнения и отчет
     */
    fun updateAllRealmClientScopes(
        importedClientScopesDto: ClientScopeExportDto
    ): Response {

        val importedClientScopes = importedClientScopesDto.clientScopes
        if (importedClientScopes.isNullOrEmpty()) {
            logger.warn("Invalid parameter. Client scopes import not provided")
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("error" to "client scopes import is null or empty").build()
        }
        logger.info(">>>> Procedure importing client scopes to [$realmName] started")

        val responseDto = ResponseDto()
        val foundScopes =  session.clientScopes()
            .getClientScopesStream(realm)?.toList()?.associateBy { it.name } ?: emptyMap()

        importedClientScopes.forEach { importedClientScope ->

            val importScopeName = importedClientScope.name
            try {
                // здесь мы добавляем в атрибуты scopes = "migration-importer" = означает наш импорт
                importedClientScope.attributes[Constants.CLIENT_SCOPE_MANAGED_KEY] = Constants.CLIENT_SCOPE_MANAGED_VALUE

                val foundScopeModel = foundScopes[importScopeName]
                if (foundScopeModel != null) {
                    // обновляем существующий scope
                    val isUpdated = updateClientScopeAndMappers(
                        importedClientScope,
                        foundScopeModel,
                        importedClientScopesDto
                    )
                    if (isUpdated) responseDto.updated.add(importScopeName)

                } else {
                    // добавляем новый scope
                    val isCreated = createClientScopeAndMappers(
                        importedClientScope,
                        importedClientScopesDto
                    )
                    if (isCreated) responseDto.created.add(importScopeName)
                }
            } catch (ex: Exception) {
                logger.error(">>>> Procedure importing client scopes failed :: message = [${ex.message}]", ex)
                responseDto.failed.add(importScopeName)
            }
        }
        // удаляем scopes, которые мигрировали когда-то мы, но уже не нужны
        deleteRottenClientScopes(importedClientScopes, responseDto)

        responseDto.display("Client scopes import finished")

        adminEventBuilder.operation(OperationType.ACTION)
            .resource(ResourceType.REALM)
            .resourcePath("/migrations/client-scopes/import")
            .representation(responseDto)
            .success()

        return Response.ok(responseDto).build()
    }


    /**
     * Выполняет создание Client Scope в заданной области сервисов. Если Client Scope содержит
     * protocol mappers, они тоже добавляются к вновь созданному Client Scope
     *
     * @param importedClientScopeRepresentation сущность создаваемого маппинга Client Scope
     * @param importedClientScopesDto импортируемая сущность client scopes
     * @return true если выполнено успешно
     */
    private fun createClientScopeAndMappers(
        importedClientScopeRepresentation: ClientScopeRepresentation,
        importedClientScopesDto: ClientScopeExportDto
    ): Boolean {
        try {
            importedClientScopeRepresentation.id = null
            importedClientScopeRepresentation.protocolMappers?.forEach { it.id = null }

            val scopeModel =
                RepresentationToModel.createClientScope(realm, importedClientScopeRepresentation)
            logger.debug(">>>> Created new client scope with name: ${scopeModel.name}")

            assignClientScope(scopeModel, importedClientScopesDto)

            adminEventBuilder.operation(OperationType.CREATE)
                .resource(ResourceType.CLIENT_SCOPE)
                .resourcePath(scopeModel.id)
                .representation(importedClientScopeRepresentation)
                .success()

            return true

        } catch (ex: Exception) {
            logger.error("Creating client scope failed: ${ex.message}, cause: ${ex.cause}", ex)
            return false
        }
    }


    /**
     * Выполняет обновление существующего Client Scope с набором ProtocolMappers.
     * Если добавляемый ProtocolMappers совпадает существующим, перезаписываем сохраняя старый id.
     *
     * @param importedClientScopeRepresentation импортная сущность нового Client Scope
     * @param existingClientScopeModel сущность существующего Client Scope
     * @param importedExportDto импортируемая сущность Client Scope
     * @return true если выполнено успешно
     *
     */
    private fun updateClientScopeAndMappers(
        importedClientScopeRepresentation: ClientScopeRepresentation,
        existingClientScopeModel: ClientScopeModel,
        importedExportDto: ClientScopeExportDto
    ): Boolean {
        try {
            // обновляем базовые атрибуты самого Client Scope
            logger.debug(">>>> Updated client scope with name: [${existingClientScopeModel.name}] started")
            RepresentationToModel.updateClientScope(importedClientScopeRepresentation, existingClientScopeModel)

            // получаем текущие мапперы из БД
            val existingProtocolMappers =
                existingClientScopeModel.protocolMappersStream?.toList()?.associateBy { it.name } ?: emptyMap()
            logger.debug(">>>> Found existing protocolMappers: ${existingProtocolMappers.size}")

            // синхронизируем мапперы из импорта
            val importedMappersNames = mutableSetOf<String>()
            importedClientScopeRepresentation.protocolMappers?.forEach { importedMapperRepresentation ->

                val mapperName = importedMapperRepresentation.name
                val existingMapper = existingProtocolMappers[mapperName]
                importedMappersNames.add(mapperName)

                if (existingMapper != null) {
                    // protocol mapper существует — сохраняем старый ID и обновляем
                    importedMapperRepresentation.id = existingMapper.id
                    val mapperModelToSave = RepresentationToModel.toModel(importedMapperRepresentation)
                    existingClientScopeModel.updateProtocolMapper(mapperModelToSave)
                } else {
                    // добавляем protocol mapper как новый
                    importedMapperRepresentation.id = null
                    val mapperModelToSave = RepresentationToModel.toModel(importedMapperRepresentation)
                    existingClientScopeModel.addProtocolMapper(mapperModelToSave)
                }
            }

            // удаляем мапперы, которых нет в импортируемом файле (строгая синхронизация)
            existingProtocolMappers.values.forEach { oldMapper ->
                if (!importedMappersNames.contains(oldMapper.name)) {
                    existingClientScopeModel.removeProtocolMapper(oldMapper)
                }
            }
            logger.debug(">>>> Successfully updated client scope with name: ${existingClientScopeModel.name}")

            adminEventBuilder.operation(OperationType.UPDATE)
                .resource(ResourceType.CLIENT_SCOPE)
                .resourcePath(existingClientScopeModel.id)
                .representation(importedClientScopeRepresentation)
                .success()

            assignClientScope(existingClientScopeModel, importedExportDto)
            return true

        } catch (ex: Exception) {
            logger.error("Updating client scope failed: ${ex.message}", ex)
        }
        return false
    }


    /**
     * Выполняет назначение созданному или обновляемому client scope значение Default или Optional
     *
     * @param clientScopeModel сущность создаваемого маппинга Client Scope
     * @param importedClientScopesDto импортируемая сущность Client Scopes
     */
    private fun assignClientScope(
        clientScopeModel: ClientScopeModel,
        importedClientScopesDto: ClientScopeExportDto
    ) {
        val scopeName = clientScopeModel.name

        // выясняем какое назначение = default или optional
        val isDefault = importedClientScopesDto.defaultScopes?.contains(scopeName) == true
        val isOptional = importedClientScopesDto.optionalScopes?.contains(scopeName) == true
        // очищаем default или optional scopes для следующей привязки (без этого не сработает)
        realm.removeDefaultClientScope(clientScopeModel)

        if (isDefault) {
            realm.addDefaultClientScope(clientScopeModel, true)
        } else if (isOptional) {
            realm.addDefaultClientScope(clientScopeModel, false)
        }
    }


    /**
     * Выполняет удаление устаревших client scopes которые сейчас существуют, но отменены импортом.
     * Для удаления фильтруются только client scopes которые импортировались ранее нашим алгоритмом.
     * @param importedClientScopes список импортируемых client scopes
     */
    private fun deleteRottenClientScopes(
        importedClientScopes: List<ClientScopeRepresentation>,
        responseDto: ResponseDto) {

        session.clientScopes().getClientScopesStream(realm)
            .filter { scopeModel ->
                scopeModel.attributes[Constants.CLIENT_SCOPE_MANAGED_KEY] == Constants.CLIENT_SCOPE_MANAGED_VALUE
            }.filter { scopeModel ->
                val name = scopeModel.name
                importedClientScopes.none { it.name == name }
            }.toList().forEach { scope ->
                try {
                    val scopeName = scope.name

                    realm.removeClientScope(scope.id)
                    responseDto.deleted.add(scopeName)

                    logger.info(">>>> Managed client scope successfully removed: [$scopeName]")
                    adminEventBuilder.resource(ResourceType.CLIENT_SCOPE)
                        .operation(OperationType.DELETE)
                        .resourcePath(session.context.uri)
                        .success()

                } catch (ex: Exception) {
                    logger.error(">>>> Deleting client scope failed: ${ex.message}", ex)
                }
            }
    }

}