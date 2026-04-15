package keycloak.spi.migration.clients

import org.keycloak.representations.idm.ClientRepresentation

data class ClientImportResponseDto(

    var successImported: MutableList<String> = mutableListOf(),
    var failedImported: MutableList<String> = mutableListOf(),
    var clientsImported: MutableList<ClientRepresentation> = mutableListOf()

) {

    fun addSuccess(client: ClientRepresentation) {
        successImported.add(client.clientId)
        clientsImported.add(client)
    }
}