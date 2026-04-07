package dev.thenets.pocketd.server

import dev.thenets.pocketd.model.ErrorDetail
import dev.thenets.pocketd.model.OpenAiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.response.respond

/**
 * Optional Bearer token authentication Ktor plugin.
 *
 * Install with:
 *   install(BearerAuthPlugin) { token = "my-secret" }
 *
 * If [token] is null or blank the plugin is a no-op — all requests pass through.
 */
class BearerAuthConfig {
    var token: String? = null
}

val BearerAuthPlugin = createApplicationPlugin(
    name = "BearerAuth",
    createConfiguration = ::BearerAuthConfig
) {
    val expectedToken = pluginConfig.token

    if (!expectedToken.isNullOrBlank()) {
        onCall { call ->
            val authHeader = call.request.header("Authorization") ?: ""
            val provided = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                authHeader.substring(7).trim()
            } else ""

            if (provided != expectedToken) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    OpenAiError(
                        ErrorDetail(
                            message = "Invalid or missing Bearer token",
                            type    = "authentication_error",
                            code    = "invalid_api_key"
                        )
                    )
                )
                finish()
            }
        }
    }
}
