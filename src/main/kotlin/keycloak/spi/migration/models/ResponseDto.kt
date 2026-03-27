package keycloak.spi.migration.models

import org.jboss.logging.Logger

data class ResponseDto(

    var status: String = "success",
    val created: MutableList<String> = mutableListOf(),
    val updated: MutableList<String> = mutableListOf(),
    val deleted: MutableList<String> = mutableListOf(),
    val failed: MutableList<String> = mutableListOf()
) {

    companion object {
        private val logger = Logger.getLogger(ResponseDto::class.java)
    }


    fun display(title: String) {
        logger.debug(""">>>>
                | ------------------------------------
                | $title
                | ------------------------------------
                | Created: ${created.size}
                | Updated: ${updated.size}
                | Failed: ${failed.size}
                | Deleted: ${deleted.size}
                | ------------------------------------
            """.trimMargin())

    }
}