package keycloak.spi.migration.utils


/**
 * Возвращает список строк из переданной параметров запроса строки мульти значений.
 * В строке перечислены названия сервисов или потоков, по которым нужно вернуть экспортные сущности
 * @param multiValuedSting строка с именами сервисов, разделенные запятой
 */
fun splitToList(multiValuedSting: String): List<String> {
    return multiValuedSting.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

