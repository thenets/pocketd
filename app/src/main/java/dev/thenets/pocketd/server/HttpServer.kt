package dev.thenets.pocketd.server

import android.util.Log
import dev.thenets.pocketd.llm.ContentPart
import dev.thenets.pocketd.llm.InferenceParams
import dev.thenets.pocketd.llm.LlmEngine
import dev.thenets.pocketd.llm.PromptFormatter
import dev.thenets.pocketd.llm.ToolCallParser
import dev.thenets.pocketd.model.FunctionCallDelta
import dev.thenets.pocketd.model.ToolCallDelta
import dev.thenets.pocketd.model.ApiLogEntry
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
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

private const val TAG = "HttpServer"

private fun Throwable.isContextLengthExceeded() =
    message?.contains("too long", ignoreCase = true) == true ||
    message?.contains("Exceeding the maximum number of tokens", ignoreCase = true) == true

/**
 * Embedded Ktor/Netty HTTP server exposing OpenAI-compatible endpoints.
 *
 * Lifecycle: instantiate → [start] → [stop]
 */
class HttpServer(
    private val llmEngine: LlmEngine,
    private val port: Int = 8080,
    private val host: String = "0.0.0.0",
    private val bearerToken: String? = null,
    private val defaultTopK: Int = 64,
    private val onRequestLogged: ((ApiLogEntry) -> Unit)? = null
) {
    private var server: ApplicationEngine? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults    = true
        isLenient         = true
        explicitNulls     = false  // omit null fields (e.g. content=null on tool_calls responses)
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
                    val startTime = System.currentTimeMillis()
                    if (!call.checkBearerAuth(bearerToken)) {
                        onRequestLogged?.invoke(ApiLogEntry(id = System.nanoTime(), timestamp = startTime, method = "GET", path = "/v1/models", statusCode = 401, durationMs = System.currentTimeMillis() - startTime))
                        return@get
                    }
                    call.respondTextWriter(contentType = ContentType.Application.Json) {
                        write("""{"object":"list","data":[{"id":"local","object":"model","owned_by":"pocketd"}]}""")
                    }
                    onRequestLogged?.invoke(ApiLogEntry(id = System.nanoTime(), timestamp = startTime, method = "GET", path = "/v1/models", statusCode = 200, durationMs = System.currentTimeMillis() - startTime))
                }

                post("/v1/chat/completions") {
                    val startTime = System.currentTimeMillis()
                    if (!call.checkBearerAuth(bearerToken)) {
                        onRequestLogged?.invoke(ApiLogEntry(id = System.nanoTime(), timestamp = startTime, method = "POST", path = "/v1/chat/completions", statusCode = 401, durationMs = System.currentTimeMillis() - startTime))
                        return@post
                    }
                    val req = try {
                        call.receive<ChatCompletionRequest>()
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            OpenAiError(ErrorDetail("Invalid request body: ${e.message}"))
                        )
                        return@post
                    }
                    val requestBodyJson = try { json.encodeToString(req) } catch (_: Exception) { null }

                    if (req.messages.isEmpty()) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            OpenAiError(ErrorDetail("messages must not be empty"))
                        )
                        return@post
                    }

                    // ── Extract system instruction for native ConversationConfig ──
                    val systemInstruction = PromptFormatter.extractSystemInstruction(req.messages)

                    // ── Build InferenceParams from request ────────────────────────
                    val inferenceParams = InferenceParams(
                        topK = req.topK ?: defaultTopK,
                        topP = req.topP ?: 0.95,
                        temperature = req.temperature ?: 1.0,
                        systemInstruction = systemInstruction,
                        tools = req.tools ?: emptyList()
                    )

                    // ── Build content parts (text + images) from last user message ──
                    val contentParts = buildContentParts(req.messages)

                    // ── Format prompt (skip system messages — handled natively) ───
                    val prompt = PromptFormatter.formatWithoutSystem(req.messages, req.tools ?: emptyList())

                    val requestId = "chatcmpl-${UUID.randomUUID()}"
                    val created   = System.currentTimeMillis() / 1000L
                    val modelName = req.model.ifBlank { "local" }

                    val hasTools   = !req.tools.isNullOrEmpty()
                    val toolChoice = req.toolChoice?.toString()?.trim('"')

                    // Decide whether to use structured content (images present) or text-only prompt
                    val hasImages = contentParts.any { it.imageBytes != null }

                    if (req.stream) {
                        // ── SSE streaming ────────────────────────────────
                        var tokenCount = 0
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            val roleDelta = ChatCompletionChunk(
                                id = requestId, created = created, model = modelName,
                                choices = listOf(StreamChoice(0, Delta(role = "assistant"), null))
                            )
                            write("data: ${json.encodeToString(roleDelta)}\n\n"); flush()

                            var errorOccurred = false

                            // Choose input: structured content for images, text prompt otherwise
                            val inputContents = if (hasImages) contentParts
                                else listOf(ContentPart(text = prompt))

                            if (hasTools && toolChoice != "none") {
                                // Buffer full output so we can detect tool calls before emitting
                                val fullOutput = StringBuilder()
                                try {
                                    llmEngine.generateStream(inputContents, inferenceParams)
                                        .catch { e ->
                                            Log.e(TAG, "Stream error", e)
                                            errorOccurred = true
                                            if (e.isContextLengthExceeded()) {
                                                val errChunk = """{"error":{"message":"This model's maximum context length has been exceeded. Reduce the length of the messages.","type":"invalid_request_error","code":"context_length_exceeded"}}"""
                                                try { write("data: $errChunk\n\n"); flush() } catch (_: Exception) {}
                                            } else {
                                                try { write("data: [ERROR]\n\n"); flush() } catch (_: Exception) {}
                                            }
                                        }
                                        .collect { token ->
                                            if (!token.done) { tokenCount++; fullOutput.append(token.text) }
                                            if (token.thinkingText != null) {
                                                Log.d(TAG, "Thinking: ${token.thinkingText}")
                                            }
                                        }
                                } catch (e: Exception) {
                                    Log.e(TAG, "SSE write error", e)
                                    errorOccurred = true
                                    llmEngine.cancelGeneration()
                                }

                                if (!errorOccurred) {
                                    val parsedCall = ToolCallParser.parse(fullOutput.toString())
                                    if (parsedCall != null) {
                                        val tcChunk = ChatCompletionChunk(
                                            id = requestId, created = created, model = modelName,
                                            choices = listOf(
                                                StreamChoice(
                                                    index = 0,
                                                    delta = Delta(toolCalls = listOf(
                                                        ToolCallDelta(
                                                            index    = 0,
                                                            id       = parsedCall.id,
                                                            type     = "function",
                                                            function = FunctionCallDelta(
                                                                name      = parsedCall.function.name,
                                                                arguments = parsedCall.function.arguments
                                                            )
                                                        )
                                                    )),
                                                    finishReason = null
                                                )
                                            )
                                        )
                                        write("data: ${json.encodeToString(tcChunk)}\n\n"); flush()
                                        val stopChunk = ChatCompletionChunk(
                                            id = requestId, created = created, model = modelName,
                                            choices = listOf(StreamChoice(0, Delta(), "tool_calls"))
                                        )
                                        write("data: ${json.encodeToString(stopChunk)}\n\n"); flush()
                                    } else {
                                        val contentChunk = ChatCompletionChunk(
                                            id = requestId, created = created, model = modelName,
                                            choices = listOf(StreamChoice(0, Delta(content = fullOutput.toString()), null))
                                        )
                                        write("data: ${json.encodeToString(contentChunk)}\n\n"); flush()
                                        val stopChunk = ChatCompletionChunk(
                                            id = requestId, created = created, model = modelName,
                                            choices = listOf(StreamChoice(0, Delta(), "stop"))
                                        )
                                        write("data: ${json.encodeToString(stopChunk)}\n\n"); flush()
                                    }
                                }
                            } else {
                                // No tools — stream tokens directly
                                try {
                                    llmEngine.generateStream(inputContents, inferenceParams)
                                        .catch { e ->
                                            Log.e(TAG, "Stream error", e)
                                            errorOccurred = true
                                            if (e.isContextLengthExceeded()) {
                                                val errChunk = """{"error":{"message":"This model's maximum context length has been exceeded. Reduce the length of the messages.","type":"invalid_request_error","code":"context_length_exceeded"}}"""
                                                try { write("data: $errChunk\n\n"); flush() } catch (_: Exception) {}
                                            } else {
                                                try { write("data: [ERROR]\n\n"); flush() } catch (_: Exception) {}
                                            }
                                        }
                                        .collect { token ->
                                            if (!token.done && token.text.isNotEmpty()) {
                                                tokenCount++
                                                val chunk = ChatCompletionChunk(
                                                    id = requestId, created = created, model = modelName,
                                                    choices = listOf(StreamChoice(0, Delta(content = token.text), null))
                                                )
                                                write("data: ${json.encodeToString(chunk)}\n\n"); flush()
                                            }
                                            if (token.thinkingText != null) {
                                                Log.d(TAG, "Thinking: ${token.thinkingText}")
                                            }
                                        }
                                } catch (e: Exception) {
                                    Log.e(TAG, "SSE write error", e)
                                    errorOccurred = true
                                    llmEngine.cancelGeneration()
                                }

                                if (!errorOccurred) {
                                    val stopChunk = ChatCompletionChunk(
                                        id = requestId, created = created, model = modelName,
                                        choices = listOf(StreamChoice(0, Delta(), "stop"))
                                    )
                                    try { write("data: ${json.encodeToString(stopChunk)}\n\n"); flush() } catch (_: Exception) {}
                                }
                            }

                            try { write("data: [DONE]\n\n"); flush() } catch (_: Exception) {}
                            onRequestLogged?.invoke(ApiLogEntry(id = System.nanoTime(), timestamp = startTime, method = "POST", path = "/v1/chat/completions", statusCode = if (errorOccurred) 500 else 200, durationMs = System.currentTimeMillis() - startTime, tokensGenerated = tokenCount, isStreaming = true, requestBody = requestBodyJson, responseBody = "SSE stream — $tokenCount tokens generated"))
                        }
                    } else {
                        // ── Non-streaming ─────────────────────────────────
                        try {
                            val inputContents = if (hasImages) contentParts
                                else listOf(ContentPart(text = prompt))

                            val result = llmEngine.generate(inputContents, inferenceParams)

                            if (result.thinkingText != null) {
                                Log.d(TAG, "Thinking: ${result.thinkingText}")
                            }

                            val parsedCall = if (hasTools && toolChoice != "none")
                                ToolCallParser.parse(result.text) else null

                            val (responseMessage, finishReason) = if (parsedCall != null) {
                                ChatMessage(role = "assistant", toolCalls = listOf(parsedCall)) to "tool_calls"
                            } else {
                                ChatMessage(role = "assistant", content = JsonPrimitive(result.text)) to "stop"
                            }

                            val response = ChatCompletionResponse(
                                id      = requestId,
                                created = created,
                                model   = modelName,
                                choices = listOf(Choice(index = 0, message = responseMessage, finishReason = finishReason)),
                                usage   = Usage()
                            )
                            call.respond(response)
                            val responseBodyJson = try { json.encodeToString(response) } catch (_: Exception) { null }
                            onRequestLogged?.invoke(ApiLogEntry(id = System.nanoTime(), timestamp = startTime, method = "POST", path = "/v1/chat/completions", statusCode = 200, durationMs = System.currentTimeMillis() - startTime, requestBody = requestBodyJson, responseBody = responseBodyJson))
                        } catch (e: Exception) {
                            Log.e(TAG, "Inference error", e)
                            val (statusCode, errBody) = if (e.isContextLengthExceeded()) {
                                HttpStatusCode.BadRequest to OpenAiError(ErrorDetail(
                                    message = "This model's maximum context length has been exceeded. Reduce the length of the messages.",
                                    type = "invalid_request_error",
                                    code = "context_length_exceeded"
                                ))
                            } else {
                                HttpStatusCode.InternalServerError to OpenAiError(ErrorDetail(message = "Inference failed: ${e.message}", type = "server_error"))
                            }
                            call.respond(statusCode, errBody)
                            val responseBodyJson = try { json.encodeToString(errBody) } catch (_: Exception) { null }
                            onRequestLogged?.invoke(ApiLogEntry(id = System.nanoTime(), timestamp = startTime, method = "POST", path = "/v1/chat/completions", statusCode = statusCode.value, durationMs = System.currentTimeMillis() - startTime, requestBody = requestBodyJson, responseBody = responseBodyJson))
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

/**
 * Builds [ContentPart] list from the conversation messages.
 * Collects text from the formatted prompt and images from the last user message.
 */
private fun buildContentParts(messages: List<ChatMessage>): List<ContentPart> {
    val parts = mutableListOf<ContentPart>()

    // Collect images from all user messages (primarily the last one)
    val lastUserMsg = messages.lastOrNull { it.role == "user" }
    if (lastUserMsg != null) {
        val images = lastUserMsg.imageParts()
        for (imageBytes in images) {
            parts.add(ContentPart(imageBytes = imageBytes))
        }
    }

    return parts
}
