package keycloak.spi.migration.constants

class Constants {

    companion object {

        const val CLIENT_SCOPE_MANAGED_KEY = "managed-by"
        const val CLIENT_SCOPE_MANAGED_VALUE = "migration-importer"
        const val MIGRATION_DESC = "Created by Migration SPI"
        const val MIGRATION_DESC_SA = "Created by Migration SPI (Service Account)"
    }
}