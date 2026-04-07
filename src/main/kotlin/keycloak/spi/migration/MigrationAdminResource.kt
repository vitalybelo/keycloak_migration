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
import keycloak.spi.migration.flows.ExportAuthenticationFlows
import keycloak.spi.migration.flows.ImportAuthenticationFlows
import keycloak.spi.migration.groups.ExportGroupsService
import keycloak.spi.migration.scopes.ClientScopeExportDto
import keycloak.spi.migration.scopes.ImportClientScopeService
import keycloak.spi.migration.groups.ImportGroupsService
import keycloak.spi.migration.models.ClientListExportDto
import keycloak.spi.migration.models.ExportFlowDto
import keycloak.spi.migration.roles.ExportRealmRolesService
import keycloak.spi.migration.roles.ImportRealmRolesService
import keycloak.spi.migration.scopes.ExportClientScopeService
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.representations.idm.GroupRepresentation
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
        @RequestBody importedFlowDto: ExportFlowDto
    ): Response {

        auth.clients().requireManage()
        val authFlowImportService = ImportAuthenticationFlows(session, auth, adminEventBuilder)
        return authFlowImportService.createAuthenticationFlows(stamp, importedFlowDto)
    }

}