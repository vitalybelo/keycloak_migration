@file:Suppress("unused")
package keycloak.spi.migration.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object JsonMapper {

    // Обычный маппер для компактного JSON
    val default: ObjectMapper = jacksonObjectMapper()

    // Маппер с форматированием (Pretty Print)
    val pretty: ObjectMapper = jacksonObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }
}

fun Any.toPrettyJsonString(): String = JsonMapper.pretty.writeValueAsString(this)

fun Any.toJsonString(): String = JsonMapper.default.writeValueAsString(this)