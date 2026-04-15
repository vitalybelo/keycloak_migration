package keycloak.spi.migration.configuration

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Data класс, обеспечивающий выполнение частичного экспорта области сервисов
 * @author Belotserkovskii Vitaly (c) 2025
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RealmExportConditions(

    var isMigrateRealmRoles: Boolean = false,
    var isMigrateClientScopes: Boolean = false,
    var isMigrateRealmGroups: Boolean = false,
    var isMigrateFlows: Boolean = false,

)