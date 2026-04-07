package keycloak.spi.migration.flows

import jakarta.ws.rs.core.Response
import keycloak.spi.migration.models.CollectFlowDto
import keycloak.spi.migration.utils.splitToList
import org.jboss.logging.Logger
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.utils.ModelToRepresentation
import org.keycloak.representations.idm.AuthenticationFlowRepresentation
import org.keycloak.services.resources.admin.AdminEventBuilder
import org.keycloak.services.resources.admin.AuthenticationManagementResource
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator
import java.util.stream.Collectors

/**
 * Сервисный слой для обеспечения методов экспорта потоков аутентификации
 * @author Vitaly Belotserkovskii 06.04.2026
 */
class ExportAuthenticationFlows(
    private val session: KeycloakSession,
    private val realm: RealmModel,
    auth: AdminPermissionEvaluator,
    adminEventBuilder: AdminEventBuilder
) {

    private val authResource = AuthenticationManagementResource(session, auth, adminEventBuilder)

    companion object {
        private val logger = Logger.getLogger(ExportAuthenticationFlows::class.java)
    }


    /**
     * Выполняет экспорт всех сущностей потока аутентификации realm, заданного параметром
     *
     * @param aliasPattern названия потоков аутентификации, разделенных запятой
     * @return статус выполнения, сущность настроек или сообщение об ошибке
     */
    fun getRealmAuthenticationFlow(aliasPattern: String?): Response {

        if (aliasPattern.isNullOrBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "flow alias should be provided")).build()
        }
        logger.info(">>>> Procedure export clients in = [$aliasPattern] started")
        val topLevelFlows = getRealmAuthenticationFlowList(aliasPattern)
        if (topLevelFlows.isNotEmpty()) {

            val authenticationFlowsExport = CollectFlowDto(rootFlowsCount = topLevelFlows.size)
            topLevelFlows.forEach { rootFlowRepresentation ->

                val alias = rootFlowRepresentation.alias
                logger.info(">>>> Start collect executors and configs for flow = [$alias]")
                collectAuthenticationSubFlows(
                    rootFlowRepresentation,
                    authenticationFlowsExport
                )
            }
            logger.info(">>>> Finished collection flows and configurations")

            val exportFlowDto = authenticationFlowsExport.getExportDto()
            val rootCount = authenticationFlowsExport.rootFlowsCount
            logger.info(">>>> Received = $rootCount root flows with executions & configurations for [$aliasPattern] successfully")

            return Response.ok(exportFlowDto).build()
        }
        return Response.status(Response.Status.NO_CONTENT)
            .entity(mapOf("error" to "flows alias = [$aliasPattern] not found")).build()
    }


    /**
     * Выполняет поиск всех корневых потоков аутентификации, удовлетворяющих условию поиска по alias.
     * В качестве названия потока, методу можно передать "*" для получения списка всех потоков области,
     * или список имен потоков, разделенных запятой. Названия имен в списке должны точно совпадать
     * с оригинальным названием потока
     *
     * @param aliasPattern паттерн поиска потоков аутентификации
     * @return список найденных корневых потоков по условию поиска
     */
    private fun getRealmAuthenticationFlowList(aliasPattern: String): List<AuthenticationFlowRepresentation> {

        logger.info(">>>> Start collect root flow for pattern :: [$aliasPattern]")
        val aliasList = splitToList(aliasPattern)

        val rootFlowsList = realm.authenticationFlowsStream
            .filter { it.isTopLevel && (aliasPattern == "*" || aliasList.contains(it.alias)) }
            .map { ModelToRepresentation.toRepresentation(session, realm, it) }
            .collect(Collectors.toList()) ?: emptyList()

        logger.info(">>>> Found = ${rootFlowsList.size} root flows")
        return rootFlowsList
    }


    /**
     * Рекурсивный метод, позволяющий собрать конфигурации и под-потоки для основного flow
     *
     * @param authenticationRootFlow сущность потока, для которого выполняется сбор данных
     * @param collectFlowDto экспортная коллекционная сущность, куда собираем данные
     */
    private fun collectAuthenticationSubFlows(
        authenticationRootFlow: AuthenticationFlowRepresentation,
        collectFlowDto: CollectFlowDto,
    ) {
        // сохраняем поток в экспортной карте, если его там ещё нет
        collectFlowDto.addFlow(authenticationRootFlow)

        // получаем список "исполнителей" входящих в поток аутентификации
        val flowAlias = authenticationRootFlow.alias
        val flowExecutions = authResource.getExecutions(flowAlias)
        if (flowExecutions.isNullOrEmpty()) return // выходим если поток пустой
        logger.info(">>>> Start collecting authentication flows and configurations for :: [$flowAlias]")

        flowExecutions.forEach { execution ->
            try {
                execution.authenticationConfig?.let { id ->
                    if (!collectFlowDto.configsMap.contains(id)) {
                        authResource.getAuthenticatorConfig(id)?.let { config ->
                            collectFlowDto.configsMap[id] = config
                            logger.info(">>>> Successfully collected configuration :: [${config.alias}]")
                        }
                    }
                }
                execution.flowId?.let { id ->
                    if (!collectFlowDto.flowsMap.contains(id)) {
                        authResource.getFlow(id)?.let { flow ->
                            collectAuthenticationSubFlows(flow, collectFlowDto)
                            logger.info(">>>> Successfully collected authentication flow [${flow.alias}]")
                        }
                    }
                }
            } catch (ex: Exception) {
                logger.error(">>>> Collecting configuration for $flowAlias is failed", ex)
            }
        }
        logger.info(">>>> Finish collecting authentication flows and configurations for :: [$flowAlias]")
    }


}