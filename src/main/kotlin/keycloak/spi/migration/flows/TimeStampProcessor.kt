package keycloak.spi.migration.flows

import keycloak.spi.migration.constants.Constants.Companion.FORMATTER
import java.time.LocalDateTime

class TimeStampProcessor {


    private var stamp: String = ""

    /**
     * Устанавливает модификатор изменения имени сервисов, потоков, шагов и конфигураций при миграции.
     * В запросе на создание сервиса или потоков может передаваться необязательный параметр stamp.
     * Это строка на основе которой, создается модифицированное новое название сервиса или потока.
     * Если в запросе не передан такой параметр, в качестве штампа используется дата и время.
     *
     * @param requestStamp параметр штампа из запроса (может быть null)
     */
    fun setNameModificationStamp(requestStamp: String?) {
        stamp = if (requestStamp.isNullOrBlank()) { LocalDateTime.now().format(FORMATTER) } else { requestStamp }
    }


    /**
     * Выполняет изменения названия потока аутентификации или конфига, добавляя в него штамп времени
     * @param flowName текущее название потока или конфигурации
     */
    fun createTimeStampedAlias(flowName: String): String {

        val migrated = "migrated"
        val migratedTimeStamp = " $stamp $migrated"
        val lengthTimeStamp = migratedTimeStamp.length
        val lengthFlowName = flowName.length

        if (lengthFlowName > lengthTimeStamp && flowName.endsWith(migrated)) {
            // найден старый фирменный знак миграции, удаляем и заменяем на новый
            val endIndex = lengthFlowName - lengthTimeStamp
            val originalFlowName = flowName.take(endIndex) + migratedTimeStamp
            return originalFlowName
        }
        val firstTimeStamped = flowName + migratedTimeStamp
        return firstTimeStamped
    }

}