package keycloak.spi.migration.flows

import jakarta.ws.rs.core.Response
import keycloak.spi.migration.models.ExportFlowDto
import org.jboss.logging.Logger
import org.keycloak.models.KeycloakSession
import org.keycloak.representations.idm.AuthenticationExecutionExportRepresentation
import org.keycloak.representations.idm.AuthenticationExecutionRepresentation
import org.keycloak.representations.idm.AuthenticationFlowRepresentation
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation
import org.keycloak.services.resources.admin.AdminEventBuilder
import org.keycloak.services.resources.admin.AuthenticationManagementResource
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator

/**
 * Сервисный слой для обеспечения методов импорта потоков аутентификации
 * @author Vitaly Belotserkovskii 07.04.2026
 */
class ImportAuthenticationFlows(

    session: KeycloakSession,
    auth: AdminPermissionEvaluator,
    adminEventBuilder: AdminEventBuilder
) {

    private val authResource = AuthenticationManagementResource(session, auth, adminEventBuilder)
    private val processor = TimeStampProcessor()

    companion object {
        private val logger = Logger.getLogger(ImportAuthenticationFlows::class.java)
    }


    /**
     * Выполняет создание нового потока аутентификации realm (копию переданного в параметрах)
     *
     * @param stamp заданный в параметрах запроса штамп модификации имени (optional)
     * @param importedFlowDto импортируемый dto класс потока аутентификации (required)
     * @return статус выполнения, список сущностей потоков или сообщение об ошибке
     */
    fun createAuthenticationFlows(
        stamp: String?,
        importedFlowDto: ExportFlowDto
    ): Response {

        val importedRootFlows = importedFlowDto.authenticationFlows.filter { it.isTopLevel }
        if (importedRootFlows.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "No one authentication flow provided")).build()
        }
        logger.info(">>>> Import started for authentication flows count = ${importedRootFlows.size}")
        try {
            processor.setNameModificationStamp(stamp)
            importedRootFlows.forEach { importedRootFlow ->

                val createdRootFlow = createAuthFlow(importedRootFlow)
                if (createdRootFlow != null) {
                    createAuthFlowEnvironment(
                        importedRootFlow,
                        createdRootFlow,
                        importedFlowDto
                    )
                }
            }
            return Response.ok("Flows created successfully").build()

        } catch (ex: Exception) {
            logger.error(">>>> Error in time of importing authentication flows", ex)
        }
        return Response
            .status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(mapOf("error" to "An unexpected error occurred")).build()
    }


    /**
     * Выполняет создание потока аутентификации. API Keycloak при создании возвращает ответ выполнения
     *
     * @param authenticationFlowRepresentation сущность потока, для которого создается окружение
     * @return сущность созданного потока аутентификации
     */

    private fun createAuthFlow(
        authenticationFlowRepresentation: AuthenticationFlowRepresentation
    ): AuthenticationFlowRepresentation? {

        var response: Response? = null
        val flowAlias = processor.createTimeStampedAlias(authenticationFlowRepresentation.alias)
        logger.info(">>>> Create authentication flow alias = [$flowAlias] started")

        try {
            authenticationFlowRepresentation.id = null
            authenticationFlowRepresentation.isBuiltIn = false
            authenticationFlowRepresentation.alias = flowAlias
            response = authResource.createFlow(authenticationFlowRepresentation)

            if (response?.statusInfo?.family == Response.Status.Family.SUCCESSFUL) {

                val flowId = response.location?.path?.substringAfterLast("/")
                if (flowId != null) {
                    logger.info(">>>> Flow :: [$flowAlias] created successfully :: flow id = $flowId")
                    return authResource.getFlow(flowId)
                }
            }
            logger.error(""">>>> Creating authentication flow [$flowAlias] failed ::
                | status = ${response.status}
                | path = ${response?.location?.path}
                """.trimIndent())

        } catch (ex: Exception) {
            logger.error(">>>> Failed to create flow :: [$flowAlias]", ex)
        } finally {
            response?.close()
        }
        return null
    }


    /**
     * Выполняет создание потока аутентификации. Добавляет к нему executions и конфигурации - если они имеются
     * в составе потока. Рекурсивно создаются вложенные потоки наполнением шагами и конфигурациями и так далее.
     *
     * @param justCreatedParentFlow импортная сущность потока, для которого создается окружение
     * @param justCreatedParentFlow вновь созданная сущность потока, для которого создается окружение
     * @param importedFlowDto импортируемый dto класс потока аутентификации
     */
    private fun createAuthFlowEnvironment(
        importedParentFlow: AuthenticationFlowRepresentation,
        justCreatedParentFlow: AuthenticationFlowRepresentation,
        importedFlowDto: ExportFlowDto
    ) {
        val parentFlowId = justCreatedParentFlow.id
        val parentFlowAlias = justCreatedParentFlow.alias

        importedParentFlow.authenticationExecutions?.forEach { execution ->
            try {
                if (!execution.isAuthenticatorFlow) {
                    // здесь создаем исполняемый шаг и добавляем конфигурацию, если необходимо
                    createAuthExecution(
                        null,
                        parentFlowId,
                        execution,
                        importedFlowDto
                    )
                } else {
                    // находим вложенный поток, создаем его, назначаем шаг и отправляемся в рекурсию
                    findImportedFlow(execution.flowAlias, importedFlowDto)?.let { importedFlow ->
                        createAuthFlow(importedFlow)?.let { createdFlow ->
                            createAuthExecution(
                                createdFlow.id, parentFlowId, execution, importedFlowDto
                            )?.let {
                                createAuthFlowEnvironment(
                                    importedFlow,
                                    createdFlow,
                                    importedFlowDto
                                )
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                logger.error(">>>> Failed to create execution = [$execution] in flow [$parentFlowAlias]", ex)
            }
        }
    }


    /**
     * Создает исполняемый шаг для потока аутентификации - execution.
     *
     * @param flowId идентификатор потока, если шаг это вложенный поток
     * @param parentFlowId идентификатор потока, для которого создается execution
     * @param importedExecution экспортная сущность исполняемого шага
     * @param importedFlowDto импортируемый dto класс потока аутентификации
     * @return true in success
     */
    private fun createAuthExecution(
        flowId: String?,
        parentFlowId: String?,
        importedExecution: AuthenticationExecutionExportRepresentation,
        importedFlowDto: ExportFlowDto
    ): AuthenticationExecutionRepresentation? {

        var response: Response? = null
        val authenticator = importedExecution.authenticator ?: importedExecution.flowAlias
        try {
            val execution = createExecutionRepresentation(importedExecution)
            execution.flowId = flowId
            execution.parentFlow = parentFlowId
            response = authResource.addExecution(execution)

            if (response?.statusInfo?.family == Response.Status.Family.SUCCESSFUL) {

                val id = response.location?.path?.substringAfterLast("/")
                if (id != null) {
                    // шаг создан успешно, теперь если в нем была конфигурация, необходимо ее добавить
                    val createdExecution = authResource.getExecution(id)
                    logger.info(">>>> Successfully created authentication execution = [$authenticator]")

                    importedExecution.authenticatorConfig?.let { configAlias ->

                        // нужно найти сущность конфигурации в импорте и создать config для нового шага
                        val authenticationConfig =
                            findImportedConfig(configAlias, importedFlowDto)

                        if (authenticationConfig != null) {
                            authenticationConfig.id = null
                            authenticationConfig.alias = processor.createTimeStampedAlias(configAlias)
                            authResource.newExecutionConfig(createdExecution.id, authenticationConfig)
                            logger.info(">>>> Successfully created configuration = [$configAlias] for execution [$authenticator]")
                        }
                    }
                    return createdExecution
                }
            }
            logger.warn(">>>> Creating of execution = [$authenticator] is failed with code = [${response.status}]")

        } catch (ex: Exception) {
            logger.error("Failed to create execution = [$authenticator] for flow = [$parentFlowId]", ex)
        } finally {
            response?.close()
        }
        return null
    }


    /**
     * Инициализирует сущность для создания исполняемого шага в потоке аутентификации
     *
     * @param authenticationExecution экспортная сущность шага
     * @return модель сущности шага для метода create
     */
    private fun createExecutionRepresentation(
        authenticationExecution: AuthenticationExecutionExportRepresentation
    ): AuthenticationExecutionRepresentation {

        return AuthenticationExecutionRepresentation().apply {
            this.authenticator = authenticationExecution.authenticator
            this.isAuthenticatorFlow = authenticationExecution.isAuthenticatorFlow
            this.requirement = authenticationExecution.requirement
            this.priority = authenticationExecution.priority
            /* оставляю для предков, это поле должно всегда быть null для создания
            this.authenticatorConfig = authenticationExecution.authenticatorConfig
            */
        }
    }


    /**
     * @return сущность вложенного потока, или null
     */
    private fun findImportedFlow(
        flowAlias: String,
        importedFlowDto: ExportFlowDto
    ): AuthenticationFlowRepresentation? {

        return importedFlowDto.authenticationFlows.firstOrNull { it.alias.equals(flowAlias) }
    }


    /**
     * Ищет в экспортной сущности конфигурацию по названию аутентификатора
     *
     * @param authenticatorConfigName название конфигурации
     * @param flowImportDto импортируемая сущность потоков
     * @return сущность найденной конфигурации
     */
    private fun findImportedConfig(
        authenticatorConfigName: String,
        flowImportDto: ExportFlowDto
    ): AuthenticatorConfigRepresentation? {

        return flowImportDto.authenticatorConfigs
            .firstOrNull { it.alias.equals(authenticatorConfigName,true) }
    }

}