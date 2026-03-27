package keycloak.spi.migration

import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.services.resources.admin.AdminEventBuilder
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator

class MigrationAdminResourceProvider : AdminRealmResourceProvider {

    override fun getResource(

        session: KeycloakSession,
        realm: RealmModel,
        adminPermissionEvaluator: AdminPermissionEvaluator,
        adminEventBuilder: AdminEventBuilder

    ): Any {
        return MigrationAdminResource(
            session,
            realm,
            adminPermissionEvaluator,
            adminEventBuilder
        )
    }

    override fun close() {}

}