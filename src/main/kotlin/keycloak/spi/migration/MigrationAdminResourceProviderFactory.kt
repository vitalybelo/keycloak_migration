package keycloak.spi.migration

import org.jboss.logging.Logger
import org.keycloak.Config
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory

class MigrationAdminResourceProviderFactory : AdminRealmResourceProviderFactory {

    companion object {
        private val logger = Logger.getLogger(MigrationAdminResourceProviderFactory::class.java)
        const val PROVIDER_ID = "migrations" // request mapping METHOD is /admin/realm/{realm}/migrations
    }

    override fun create(session: KeycloakSession): AdminRealmResourceProvider {
        return MigrationAdminResourceProvider()
    }

    override fun init(config: Config.Scope?) { logger.info(">>>> Initializing keycloak migrations resource provider") }
    override fun postInit(factory: KeycloakSessionFactory?) {}
    override fun close() { logger.info(">>>> Closing keycloak migrations resource") }
    override fun getId(): String = PROVIDER_ID
}