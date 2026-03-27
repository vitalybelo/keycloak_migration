package keycloak.spi.migration.scopes

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.keycloak.representations.idm.ClientScopeRepresentation

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClientScopeExportDto(

    @JsonProperty("clientScopes")
    var clientScopes: List<ClientScopeRepresentation>? = null,

    @JsonProperty("default")
    var defaultScopes: List<String>? = null,

    @JsonProperty("optional")
    var optionalScopes: List<String>? = null

)