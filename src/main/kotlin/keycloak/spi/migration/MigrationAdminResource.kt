package keycloak.spi.migration

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import keycloak.spi.migration.clients.ExportClientService
import keycloak.spi.migration.clients.ImportClientService
import keycloak.spi.migration.configuration.ExportRealmConfiguration
import keycloak.spi.migration.configuration.ImportRealmConfiguration
import keycloak.spi.migration.configuration.RealmExportConditions
import keycloak.spi.migration.configuration.RealmImportConditions
import keycloak.spi.migration.flows.ExportAuthenticationFlows
import keycloak.spi.migration.flows.ImportAuthenticationFlows
import keycloak.spi.migration.groups.ExportGroupsService
import keycloak.spi.migration.scopes.ClientScopeExportDto
import keycloak.spi.migration.scopes.ImportClientScopeService
import keycloak.spi.migration.groups.ImportGroupsService
import keycloak.spi.migration.clients.ClientListExportDto
import keycloak.spi.migration.flows.ImportFlowDto
import keycloak.spi.migration.roles.ExportRealmRolesService
import keycloak.spi.migration.roles.ImportRealmRolesService
import keycloak.spi.migration.scopes.ExportClientScopeService
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.representations.idm.GroupRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.services.resources.admin.AdminEventBuilder
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator

/**
 * Custom Keycloak API Ресурс для миграции
 * @author Belotserkovskii Vitaly (c) 2026
 */
class MigrationAdminResource(
    private val session: KeycloakSession,
    private val realm: RealmModel,
    private val auth: AdminPermissionEvaluator,
    private val adminEventBuilder: AdminEventBuilder
) {

    @GET
    @Path("/client-scopes")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    fun exportClientScopes(): Response {

        auth.clients().requireView() // проверяем права администратора на просмотр
        val service = ExportClientScopeService(session, realm)
        return service.getRealmClientScopes()
    }

    @POST
    @Path("/client-scopes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun importClientScopes(
        @RequestBody importedClientScopesDto: ClientScopeExportDto
    ): Response {

        auth.clients().requireManage() // проверяем права администратора на управление
        val service = ImportClientScopeService(session, realm, adminEventBuilder)
        return service.updateAllRealmClientScopes(importedClientScopesDto)
    }

    @GET
    @Path("/realm-roles")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    fun exportRealmRoles(): Response {

        auth.clients().requireView()
        val service = ExportRealmRolesService(session, realm)
        return service.getRealmRoles()
    }

    @POST
    @Path("/realm-roles")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    fun importRealmRoles(
        @RequestBody importedRealmRoles: List<RoleRepresentation>
    ): Response {

        auth.clients().requireManage()
        val realmRolesService = ImportRealmRolesService(session, realm, adminEventBuilder)
        return realmRolesService.createOrUpdateRealmRoles(importedRealmRoles)
    }

    @GET
    @Path("/groups")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    fun exportGroups(): Response {

        auth.groups().requireView()
        val service = ExportGroupsService(session, realm)
        return service.getAllRealmGroups()
    }

    @POST
    @Path("/groups")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    fun importGroups(
        @RequestBody importGroupList: List<GroupRepresentation>
    ): Response {
        auth.groups().requireManage()
        val service = ImportGroupsService(session, realm, adminEventBuilder)
        return service.createOrUpdateAllRealmGroups(importGroupList)
    }

    @GET
    @Path("/clients")
    @Produces(MediaType.APPLICATION_JSON)
    fun exportClients(
        @QueryParam("client_ids") clientIds: String?
    ): Response {

        auth.clients().requireView()
        val clientsService = ExportClientService(session, realm)
        return clientsService.getRealmClients(clientIds)
    }

    @POST
    @Path("/clients")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun importClients(
        @QueryParam("stamp") stamp: String?,
        @QueryParam("isAlwaysCreate") @DefaultValue("false") isAlwaysCreate: Boolean,
        @RequestBody importedClients: ClientListExportDto
    ): Response {

        auth.clients().requireManage()
        val clientsService = ImportClientService(session, realm, adminEventBuilder)
        return clientsService.createOrUpdateRealmClients(stamp, isAlwaysCreate, importedClients)
    }

    @GET
    @Path("/flows")
    @Produces(MediaType.APPLICATION_JSON)
    fun getAuthenticationFlows(
        @QueryParam("alias") alias: String?
    ): Response {

        auth.clients().requireView()
        val authFlowExportService = ExportAuthenticationFlows(session, realm, auth, adminEventBuilder)
        return authFlowExportService.getRealmAuthenticationFlow(alias)

    }

    @POST
    @Path("/flows")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun createAuthenticationFlows(
        @QueryParam("stamp") stamp: String?,
        @RequestBody importedFlowDto: ImportFlowDto
    ): Response {

        auth.clients().requireManage()
        val authFlowImportService = ImportAuthenticationFlows(session, auth, adminEventBuilder)
        return authFlowImportService.createAuthenticationFlows(stamp, importedFlowDto)
    }

    @GET
    @Path("/configuration")
    @Consumes(MediaType.APPLICATION_JSON)
    fun getRealmConfiguration(
        @QueryParam(value = "isMigrateRealmRoles") isMigrateRealmRoles: Boolean?,
        @QueryParam(value = "isMigrateClientScopes") isMigrateClientScopes: Boolean?,
        @QueryParam(value = "isMigrateRealmGroups") isMigrateRealmGroups: Boolean?,
        @QueryParam(value = "isMigrateFlows") isMigrateFlows: Boolean?
    ): Response {

        auth.clients().requireView() // хотя внутри тоже есть проверка, первичная остановит сразу

        val exportConfigService = ExportRealmConfiguration(session, realm)
        return exportConfigService.getRealmConfiguration(
            RealmExportConditions().apply {
                this.isMigrateRealmRoles = isMigrateRealmRoles ?: false
                this.isMigrateClientScopes = isMigrateClientScopes ?: false
                this.isMigrateRealmGroups = isMigrateRealmGroups ?: false
                this.isMigrateFlows = isMigrateFlows ?: false
            })
    }

    @POST
    @Path("/configuration")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun createOrUpdateRealmConfiguration(
        @QueryParam(value = "isMigrateRealmRoles") isMigrateRealmRoles: Boolean?,
        @QueryParam(value = "isMigrateClientScopes") isMigrateClientScopes: Boolean?,
        @QueryParam(value = "isMigrateRealmGroups") isMigrateRealmGroups: Boolean?,
        @QueryParam(value = "isMigrateFlows") isMigrateFlows: Boolean?,
        @QueryParam(value = "realm") realmName: String?,
        @RequestBody realmConfiguration: RealmRepresentation?
    ): Response {

        auth.clients().requireManage() // хотя внутри тоже есть проверка, первичная остановит сразу

        if (realmName.isNullOrEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("error" to "new realm name should be provided").build()
        }
        if (realmConfiguration == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("error" to "realm representation should be provided").build()
        }

        val importConfigService = ImportRealmConfiguration(session, auth, adminEventBuilder)
        return importConfigService.importRealmConfiguration(
            realmName,
            RealmImportConditions().apply {
                this.isMigrateRealmRoles = isMigrateRealmRoles ?: false
                this.isMigrateClientScopes = isMigrateClientScopes ?: false
                this.isMigrateRealmGroups = isMigrateRealmGroups ?: false
                this.isMigrateFlows = isMigrateFlows ?: false
            },
            realmConfiguration
        )
    }


}