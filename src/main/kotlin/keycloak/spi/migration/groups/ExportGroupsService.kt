package keycloak.spi.migration.groups

import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.keycloak.models.GroupModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.utils.ModelToRepresentation
import org.keycloak.representations.idm.GroupRepresentation


/**
 * Внутренний сервис выполняющий экспорт групп
 * @author Belotserkovskii Vitalii (c) 2026
 */
class ExportGroupsService(
    private val session: KeycloakSession,
    private val realm: RealmModel,
) {

    companion object {
        private val logger = Logger.getLogger(ExportGroupsService::class.java.name)
    }
    private val realmName = realm.name


    /**
     * Выполняет чтение сущностей всех групп в области сервисов realm и сбор подгрупп.
     * Вначале метод читает все корневые группы как group model. Для каждой полученные корневой группы
     * начинается рекурсивный сбор подгрупп. Каждая полученная подгруппа конвертируется в сущность и
     * для нее снова запускается рекурсивный метод сбора подгрупп, до тех пор пока не будут собраны
     * все подгруппы. Результирующий список представляет собой список сущностей групп.
     * @return статус выполнения, список сущностей groups или сообщение об ошибке
     */
    fun getAllRealmGroups(): Response {

        logger.info(">>>> Procedure exporting groups in realm = [$realmName] started")
        try {
            // читаем только корневые группы, остальные keycloak все равно уже не отдает
            // все что требует алгоритма больше N - keycloak больше не хочет выполнять при обращении к БД
            val rootGroupModels = session.groups().getTopLevelGroupsStream(realm).toList()
            val groupList = mutableListOf<GroupRepresentation>()

            for (groupModel in rootGroupModels) {

                val groupRepresentation =
                    ModelToRepresentation.toRepresentation(groupModel, true)

                populateSubGroups(groupModel, groupRepresentation) // вручную рекурсивно собираем подгруппы
                groupList.add(groupRepresentation)
            }

            if (groupList.isNotEmpty()) {
                logger.info(">>>> Exported [${groupList.size}] root groups for [$realmName]")
                return Response.ok(groupList).build()
            }

            logger.info(">>>> Not found any groups in [$realmName] for export")
            return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Not found groups in realm = $realmName"))
                .build()

        } catch (ex: Exception) {
            logger.error(">>>> Error exporting realm groups in realm = [$realmName]", ex)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to "internal server error: ${ex.message}"))
                .build()
        }
    }


    /**
     * Рекурсивно читает подгруппы из GroupModel и укладывает их в GroupRepresentation.
     * @param groupModel ресурс управления группой, нужен для выполнения сбора подгрупп
     * @param groupRepresentation итоговая сущность группы, суда складываем найденное
     */
    private fun populateSubGroups(
        groupModel: GroupModel,
        groupRepresentation: GroupRepresentation
    ) {
        // инициализируем массив, чтобы в JSON не было null
        if (groupRepresentation.subGroups == null) {
            groupRepresentation.subGroups = mutableListOf()
        }
        // поехали сканировать группы для сбора всех дочерних подгрупп
        val subGroupModels = groupModel.subGroupsStream?.toList() ?: return
        for (subModel in subGroupModels) {

            val subGroupRepresentation = ModelToRepresentation.toRepresentation(subModel, true)
            populateSubGroups(subModel, subGroupRepresentation) // идем глубже в дерево
            groupRepresentation.subGroups.add(subGroupRepresentation)
        }
    }
}