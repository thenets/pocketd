package dev.thenets.pocketd.server

import android.util.Log
import dev.thenets.pocketd.llm.LlmEngine
import dev.thenets.pocketd.llm.PromptFormatter
import dev.thenets.pocketd.model.ChatCompletionChunk
import dev.thenets.pocketd.model.ChatCompletionResponse
import dev.thenets.pocketd.model.ChatCompletionRequest
import dev.thenets.pocketd.model.ChatMessage
import dev.thenets.pocketd.model.Choice
import dev.thenets.pocketd.model.Delta
import dev.thenets.pocketd.model.ErrorDetail
import dev.thenets.pocketd.model.OpenAiError
import dev.thenets.pocketd.model.StreamChoice
import dev.thenets.pocketd.model.Usage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private const val TAG = "HttpServer"

/**
 * Embedded Ktor/Netty HTTP server exposing OpenAI-compatible endpoints.
 *
 * Lifecycle: instantiate → [start] → [stop]
 */
class HttpServer(
    private val llmEngine: LlmEngine,
    private val port: Int = 8080,
    private val host: String = "0.0.0.0",
    private val bearerToken: String? = null
) {
    private var server: ApplicationEngine? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults    = true
        isLenient         = true
    }

    fun start() {
        server = embeddedServer(
            factory = Netty,
            port    = port,
            host    = host
        ) {
            install(ContentNegotiation) { json(json) }

            routing {
                // Minimal model list for OpenAI client compatibility
                get("/v1/models") {
                    if (!call.checkBearerAuth(bearerToken)) return@get
                    call.respond(
                        mapOf(
                            "object" to "list",
                            "data" to listOf(
                                mapOf(
                                    "id"       to "local",
                                    "object"   to "model",
                                    "owned_by" to "pocketd"
                                )
                            )
                        )
                    )
                }

                post("/v1/chat/completions") {
                    if (!call.checkBearerAuth(bearerToken)) return@post
                    val req = try {
                        call.receive<ChatCompletionRequest>()
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            OpenAiError(ErrorDetail("Invalid request body: ${e.message}"))
                        )
                        return@post
                    }

                    if (req.messages.isEmpty()) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            OpenAiError(ErrorDetail("messages must not be empty"))
                        )
                        return@post
                    }

                    val prompt    = PromptFormatter.format(req.messages)
                    val requestId = "chatcmpl-${UUID.randomUUID()}"
                    val created   = System.currentTimeMillis() / 1000L
                    val modelName = req.model.ifBlank { "local" }

                    if (req.stream) {
                        // ── SSE streaming ────────────────────────────────
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            // Initial role delta
                            val roleDelta = ChatCompletionChunk(
                                id      = requestId,
                                created = created,
                                model   = modelName,
                                choices = listOf(
                                    StreamChoice(
                                        index        = 0,
                                        delta        = Delta(role = "assistant"),
                                        finishReason = null
                                    )
                                )
                            )
                            write("data: ${json.encodeToString(roleDelta)}\n\n")
                            flush()

                            var errorOccurred = false
                            try {
                                llmEngine.generateStream(prompt)
                                    .catch { e ->
                                        Log.e(TAG, "Stream error", e)
                                        errorOccurred = true
                                        write("data: [ERROR]\n\n")
                                        flush()
                                    }
                                    .collect { token ->
                                        val chunk = ChatCompletionChunk(
                                            id      = requestId,
                                            created = created,
                                            model   = modelName,
                                            choices = listOf(
                                                StreamChoice(
                                                    index        = 0,
                                                    delta        = Delta(content = token),
                                                    finishReason = null
                                                )
                                            )
                                        )
                                        write("data: ${json.encodeToString(chunk)}\n\n")
                                        flush()
                                    }
                            } catch (e: Exception) {
                                Log.e(TAG, "SSE write error", e)
                            }

                            if (!errorOccurred) {
                                val stopChunk = ChatCompletionChunk(
                                    id      = requestId,
                                    created = created,
                                    model   = modelName,
                                    choices = listOf(
                                        StreamChoice(
                                            index        = 0,
                                            delta        = Delta(),
                                            finishReason = "stop"
                                        )
                                    )
                                )
                                write("data: ${json.encodeToString(stopChunk)}\n\n")
                                flush()
                            }
                            write("data: [DONE]\n\n")
                            flush()
                        }
                    } else {
                        // ── Non-streaming ─────────────────────────────────
                        try {
                            val output = llmEngine.generate(prompt)
                            call.respond(
                                ChatCompletionResponse(
                                    id      = requestId,
                                    created = created,
                                    model   = modelName,
                                    choices = listOf(
                                        Choice(
                                            index   = 0,
                                            message = ChatMessage(
                                                role    = "assistant",
                                                content = output
                                            )
                                        )
                                    ),
                                    usage = Usage()
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Inference error", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                OpenAiError(
                                    ErrorDetail(
                                        message = "Inference failed: ${e.message}",
                                        type    = "server_error"
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
        server!!.start(wait = false)
        Log.i(TAG, "HTTP server started on $host:$port")
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000)
        server = null
        Log.i(TAG, "HTTP server stopped")
    }
}
