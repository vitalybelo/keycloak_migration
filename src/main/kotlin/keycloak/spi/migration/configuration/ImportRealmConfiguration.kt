package keycloak.spi.migration.configuration

import jakarta.ws.rs.core.Response
import keycloak.spi.migration.groups.ImportGroupsService
import keycloak.spi.migration.flows.ImportAuthenticationFlows
import keycloak.spi.migration.flows.ImportFlowDto
import keycloak.spi.migration.roles.ImportRealmRolesService
import keycloak.spi.migration.scopes.ClientScopeExportDto
import keycloak.spi.migration.scopes.ImportClientScopeService
import org.jboss.logging.Logger
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.utils.KeycloakModelUtils
import org.keycloak.models.utils.RepresentationToModel
import org.keycloak.representations.idm.GroupRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.idm.RolesRepresentation
import org.keycloak.services.managers.RealmManager
import org.keycloak.services.resources.admin.AdminEventBuilder
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator

/**
 * Сервисный слой для обеспечения методов импорта конфигурации рабочей области сервисов
 * @author Vitaly Belotserkovskii 10.04.2026
 */
class ImportRealmConfiguration(
    private val session: KeycloakSession,
    private val auth: AdminPermissionEvaluator,
    private val adminEventBuilder: AdminEventBuilder
) {

    private val processor = RealmConfigurationProcessor()

    companion object {
        private val logger = Logger.getLogger(ImportRealmConfiguration::class.java)
    }

    /**
     * Выполняет создание новой или изменение существующей области сервисов realm.
     * В начале метод проверяет существование realm в заданной области сервисов
     *
     * @param realmName название новой (обновляемой) рабочей области сервисов
     * @param importRealmRepresentation сущность новых настроек для области
     * @param conditions условия импортирования области сервисов
     *
     * @return статус выполнения, сущность настроек или сообщение об ошибке
     */
    fun importRealmConfiguration(
        realmName: String,
        conditions: RealmImportConditions,
        importRealmRepresentation: RealmRepresentation
    ): Response {

        logger.info(">>>> Importing realm configuration started for realm = $realmName")

        try {
            val manager = RealmManager(session)
            val foundRealmModel = manager.getRealmByName(realmName)

            val foundRealmRepresentation = if (foundRealmModel != null) {
                logger.info(">>>> Update realm configuration started for realm = $realmName")
                processor.partialExportRealmRepresentation(true, session, foundRealmModel)
            } else {
                logger.info(">>>> Create realm configuration started for realm = $realmName")
                null
            }

            // создаем новый или обновляем realm
            val realmModel = createOrUpdateRealm(
                realmName,
                conditions,
                importRealmRepresentation,
                foundRealmRepresentation,
                foundRealmModel,
                manager
            )

            val importResponseDto = RealmImportResponseDto(representation = importRealmRepresentation)

            partialRealmMigration(
                realmModel,
                conditions,
                importResponseDto
            )

            return Response.ok(importResponseDto).build()

        } catch (ex: Exception) {
            logger.error(">>>> Error during importing realm configuration", ex)
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity("error" to "import realm configuration failed").build()
    }

    /**
     * Выполняет манипуляции с id при создании или обновлении рабочей области сервисов
     *
     * @param importRealmName название области сервисов
     * @param importedRepresentation новая сущность области сервисов
     * @param existingRepresentation существующая модель области сервисов или null
     * @param importConditions условия импортирования области сервисов
     * @param manager ресурс управления realm
     */
    private fun createOrUpdateRealm(
        importRealmName: String,
        importConditions: RealmImportConditions,
        importedRepresentation: RealmRepresentation,
        existingRepresentation: RealmRepresentation?,
        existingRealmModel: RealmModel?,
        manager: RealmManager
    ): RealmModel {

        logger.info(">>>> Create | Update realm method started for realm = $importRealmName")
        cleanAssignedFlowNames(importedRepresentation)
        updateComponents(importedRepresentation, existingRepresentation)
        importConditions.copyAndClear(importedRepresentation)

        try {
            if (existingRealmModel == null) {
                // СОЗДАНИЕ НОВОЙ ОБЛАСТИ
                importedRepresentation.id = null
                importedRepresentation.realm = importRealmName

                // manager.importRealm сделает всю магию: создаст запись, накатит настройки,
                val newRealm = manager.importRealm(importedRepresentation)
                logger.info(">>>> Successfully created realm = [$importRealmName] configuration")

                // Аудит: Фиксируем создание нового Realm
                adminEventBuilder.operation(OperationType.CREATE)
                    .resource(ResourceType.REALM)
                    .resourcePath("")
                    .representation(importedRepresentation)
                    .success()

                return newRealm

            } else {
                // ОБНОВЛЕНИЕ СУЩЕСТВУЮЩЕЙ ОБЛАСТИ
                importedRepresentation.id = existingRealmModel.id
                importedRepresentation.realm = existingRealmModel.name

                // Используем RepresentationToModel для наката настроек на существующую модель
                RepresentationToModel.updateRealm(importedRepresentation, existingRealmModel, session)
                logger.info(">>>> Successfully updated realm [$importRealmName] configuration via SPI")

                // Аудит: Фиксируем обновление существующего Realm
                adminEventBuilder.operation(OperationType.UPDATE)
                    .resource(ResourceType.REALM)
                    .resourcePath("")
                    .representation(importedRepresentation)
                    .success()

                return existingRealmModel
            }

        } catch (ex: Exception) {
            logger.error(">>>> Failed to create | update Realm configuration for [$importRealmName]", ex)
            throw ex
        }
    }

    /**
     * Обнуляет названия назначенных по умолчанию потоков аутентификации (keycloak сам их создаст)
     * @param importedRepresentation новая сущность области сервисов
     */
    private fun cleanAssignedFlowNames(
        importedRepresentation: RealmRepresentation,
    ) {
        importedRepresentation.browserFlow = null
        importedRepresentation.registrationFlow = null
        importedRepresentation.directGrantFlow = null
        importedRepresentation.resetCredentialsFlow = null
        importedRepresentation.clientAuthenticationFlow = null
        importedRepresentation.dockerAuthenticationFlow = null
        importedRepresentation.firstBrokerLoginFlow = null
    }

    /**
     * Создает новые или перезаписывает существующие компоненты в настройках области сервисов
     * При создании новой области - нужно обнулить все id в импортных сущностях компонентов.
     * То же самое мы делаем если в существующей области вообще нет провайдеров с компонентами.
     * Когда в обновляемой области существует похожий провайдер с найдем набором компонентов,
     * мы будем проверять существование конкретного компонента, и если не найдем, тогда id = null.
     * Если в существующей области мы найдем конкретный провайдер - подставим его id в импортный.
     *
     * @param importedRepresentation новая сущность области сервисов
     * @param existingRepresentation существующая модель области сервисов или null
     */
    private fun updateComponents(

        importedRepresentation: RealmRepresentation,
        existingRepresentation: RealmRepresentation?
    ) {
        logger.info(">>>> Update components started for realm = $importedRepresentation")
        val importedComponents = importedRepresentation.components
        if (importedComponents.isNullOrEmpty()) return
        val foundComponents = existingRepresentation?.components

        if (existingRepresentation == null || foundComponents.isNullOrEmpty()) {
            importedComponents.forEach { component ->
                component.value?.forEach { it.id = null }
            }
            logger.info(">>>> All imported components ids cleared successfully for new realm")
        } else {
            importedComponents.forEach { importedProvider ->

                val foundComponentList = foundComponents[importedProvider.key]
                importedProvider.value.forEach { importedComponent ->

                    val importedProviderId = importedComponent.providerId!!
                    val foundComponentId = foundComponentList
                        ?.firstOrNull { component -> component.providerId == importedProviderId }?.id

                    if (foundComponentId != null) {
                        importedComponent.id = foundComponentId
                        logger.info(">>>> Component [$importedProviderId] update with id = $foundComponentId successfully")
                    } else {
                        importedComponent.id = null
                        logger.info(">>>> Component [$importedProviderId] not found, and would be created freshly")
                    }
                }
            }
        }
        logger.info(">>>> Update components finished for realm = $importedRepresentation")
    }


    /**
     * Выполняет частичную дополнительную миграцию roles, client scopes, groups, authentication flows
     *
     * @param realm модель обновляемой рабочей области сервисов
     * @param importConditions условия выполнения миграции настроек области сервисов
     * @param migrationResponse сущность ответа по миграции настроек области сервисов
     */
    private fun partialRealmMigration(
        realm: RealmModel,
        importConditions: RealmImportConditions,
        migrationResponse: RealmImportResponseDto
    ) {

        if (importConditions.isPartialImportNotRequired()) {
            logger.info(">>>> Partial import Realm configuration [${realm.name}] is not necessary")
            return
        }

        try {
            if (importConditions.isMigrateRealmRoles) {
                updateRealmRoles(realm, importConditions.roles)
                migrationResponse.rolesCount = importConditions.roles?.realm?.size ?: 0
            }
            if (importConditions.isMigrateClientScopes) {
                updateClientScopes(realm, importConditions.clientScopes)
                migrationResponse.scopesCount = importConditions.clientScopes.clientScopes?.size ?: 0
            }
            if (importConditions.isMigrateRealmGroups) {
                updateGroups(realm, importConditions.groups)
                assignDefaultGroups(realm, importConditions)
                migrationResponse.groupsCount = importConditions.groups?.size ?: 0
            }
            if (importConditions.isMigrateFlows) {
                updateAuthenticationFlows(realm, importConditions.importFlowsDto)
                migrationResponse.flowsCount = importConditions.importFlowsDto.authenticationFlows.size
            }

        } catch (ex: Exception) {
            logger.error(">>>> Partial realm import [${realm.name}] crashed by [${ex.message}", ex)
        }
    }


    /**
     * Выполняет обновление ролей области сервисов, если в переданной сущности они есть
     *
     * @param realm модель обновляемой рабочей области сервисов
     * @param rolesRepresentation сущность импортированных ролей
     */
    private fun updateRealmRoles(
        realm: RealmModel,
        rolesRepresentation: RolesRepresentation?
    ) {
        val realmName = realm.name
        val importedRealmRoles = rolesRepresentation?.realm

        if (!importedRealmRoles.isNullOrEmpty()) {

            val importRoles = ImportRealmRolesService(session, realm, adminEventBuilder)
            importRoles.createOrUpdateRealmRoles(importedRealmRoles)

            logger.info(">>>> Partial migration Realm Roles for realm = [$realmName] finished")
        } else {
            logger.info(">>>> Realm Roles not found in realm = [$realmName] for partial import")
        }
    }


    /**
     * Выполняет обновление client scopes в рабочей области сервисов, если в переданной сущности они есть
     *
     * @param realm модель обновляемой рабочей области сервисов
     * @param importedClientScopes импортированные client scopes
     */
    private fun updateClientScopes(
        realm: RealmModel,
        importedClientScopes: ClientScopeExportDto?
    ) {
        val realmName = realm.name
        val isPartialImpossible = importedClientScopes?.isClientScopesImportPossible() ?: false

        if (importedClientScopes != null && isPartialImpossible) {

            val importClientScopes = ImportClientScopeService(session, realm, adminEventBuilder)
            importClientScopes.updateAllRealmClientScopes(importedClientScopes)

            logger.info(">>>> Partial migration Client Scopes for [$realmName] is finished")
        } else {
            logger.info(">>>> Client Scopes not found in [$realmName] for partial import")
        }
    }


    /**
     * Выполняет обновление групп рабочей области сервисов, если в переданной сущности они есть
     *
     * @param realm модель обновляемой рабочей области сервисов
     * @param importedGroups список импортируемых групп
     */
    private fun updateGroups(
        realm: RealmModel,
        importedGroups: List<GroupRepresentation>?,
    ) {
        val realmName = realm.name
        if (!importedGroups.isNullOrEmpty()) {

            val importGroups = ImportGroupsService(session, realm, adminEventBuilder)
            importGroups.createOrUpdateAllRealmGroups(importedGroups)

            logger.info(">>>> Partial migration Groups for realm = [$realmName] is finished")
        } else {
            logger.info(">>>> Groups not found in realm = [$realmName] for partial import")
        }
    }

    /**
     * Выполняет добавление дефолтных групп в область сервисов. На этапе создания или обновления области
     * сервисов невозможно сразу добавить дефолтные группы, если их на данный момент нет в realm. Поэтому,
     * мы пробуем добавить дефолтные группы сразу после того, как добавили группы в рабочую область.
     *
     * @param realm модель обновляемой рабочей области сервисов
     * @param importConditions условия импорта рабочей области
     */
    fun assignDefaultGroups(
        realm: RealmModel,
        importConditions: RealmImportConditions
    ) {
        importConditions.defaultGroups?.forEach { path ->

            val targetGroup = KeycloakModelUtils.findGroupByPath(session, realm, path)
            if (targetGroup != null) {
                val targetGroupId = targetGroup.id
                val isAlreadyDefault = realm.defaultGroupsStream.anyMatch { it.id == targetGroupId }
                if (!isAlreadyDefault) {
                    realm.addDefaultGroup(targetGroup)
                    logger.info(">>>> Group = [$path] added to default groups successfully")
                }
            }
        }
    }


    /**
     * Выполняет обновление потоков аутентификации для импортируемой рабочей области сервисов
     *
     * @param realm модель обновляемой рабочей области сервисов
     * @param importFlowDto список потоков и конфигураций
     */
    private fun updateAuthenticationFlows(
        realm: RealmModel,
        importFlowDto: ImportFlowDto?,
    ) {
        val realmName = realm.name
        if (importFlowDto?.isAuthenticationFlowsPartialImport() == true) {

            // важный момент, здесь вы меняем в сессии модель управления рабочей областью
            // на вновь созданную (или обновляемую) вместо той, которая попала в сессию из контекста запроса
            val savedRealmModel = session.context.realm
            session.context.realm = realm
            val importFlow = ImportAuthenticationFlows(session, auth, adminEventBuilder)

            // импортируем потоки переданные в конфигурации realm, и возвращаем значение realm модели
            importFlow.createAuthenticationFlows(null, importFlowDto)
            session.context.realm = savedRealmModel

            logger.info(">>>> Partial migration Flows and Configurations for [$realmName] is finished")
        } else {
            logger.info(">>>> Flows and Configurations not found in [$realmName] for partial import")
        }
    }

}