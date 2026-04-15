package keycloak.spi.migration.flows

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.keycloak.representations.idm.AuthenticationFlowRepresentation
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation

/**
 * Класс данных импортируемого потока и конфигурации аутентификации
 * @author Belotserkovskii Vitaly (c) 2025
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ImportFlowDto(

    var authenticationFlows: MutableList<AuthenticationFlowRepresentation> = mutableListOf(),
    var authenticatorConfigs: MutableList<AuthenticatorConfigRepresentation> = mutableListOf()
) {

    fun isAuthenticationFlowsPartialImport(): Boolean {
        return authenticationFlows.isNotEmpty()
    }

}
