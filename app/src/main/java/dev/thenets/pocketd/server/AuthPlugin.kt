package dev.thenets.pocketd.server

import dev.thenets.pocketd.model.ErrorDetail
import dev.thenets.pocketd.model.OpenAiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond

/**
 * Returns true if the request is authorized (or no token is required).
 * If false, a 401 response has already been sent — the caller must return immediately.
 */
suspend fun ApplicationCall.checkBearerAuth(expectedToken: String?): Boolean {
    if (expectedToken.isNullOrBlank()) return true
    val authHeader = request.header("Authorization") ?: ""
    val provided = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
        authHeader.substring(7).trim()
    } else ""
    if (provided != expectedToken) {
        respond(
            HttpStatusCode.Unauthorized,
            OpenAiError(
                ErrorDetail(
                    message = "Invalid or missing Bearer token",
                    type    = "authentication_error",
                    code    = "invalid_api_key"
                )
            )
        )
        return false
    }
    return true
}
