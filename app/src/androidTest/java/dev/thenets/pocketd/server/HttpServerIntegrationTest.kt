package dev.thenets.pocketd.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.thenets.pocketd.llm.ContentPart
import dev.thenets.pocketd.llm.GenerateResult
import dev.thenets.pocketd.llm.InferenceParams
import dev.thenets.pocketd.llm.LlmEngine
import dev.thenets.pocketd.llm.StreamToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Integration tests for the HTTP server using a stub LlmEngine.
 *
 * These tests start a real Ktor/Netty server and send actual HTTP requests
 * to verify OpenAI-compatible endpoint behavior.
 *
 * IMPORTANT: Must run on a real device or emulator (androidTest).
 */
@RunWith(AndroidJUnit4::class)
class HttpServerIntegrationTest {

    private val testPort = 18080 // Use non-standard port to avoid conflicts
    private val baseUrl = "http://127.0.0.1:$testPort"
    private val testToken = "test-secret-token"

    private lateinit var stubEngine: StubLlmEngine
    private lateinit var server: HttpServer

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        stubEngine = StubLlmEngine()
        server = HttpServer(
            llmEngine = stubEngine,
            port = testPort,
            host = "127.0.0.1",
            bearerToken = testToken
        )
        server.start()
        // Give the server a moment to bind
        Thread.sleep(500)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── GET /v1/models ───────────────────────────────────────────────────

    @Test
    fun models_returnsValidModelList() {
        val (status, body) = httpGet("/v1/models")

        assertEquals(200, status)
        val parsed = json.parseToJsonElement(body).jsonObject
        assertEquals("list", parsed["object"]?.jsonPrimitive?.content)
        val data = parsed["data"]?.jsonArray
        assertNotNull(data)
        assertEquals(1, data!!.size)
        assertEquals("local", data[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("pocketd", data[0].jsonObject["owned_by"]?.jsonPrimitive?.content)
    }

    @Test
    fun models_rejectsUnauthorized() {
        val (status, _) = httpGet("/v1/models", authToken = null)
        assertEquals(401, status)
    }

    @Test
    fun models_rejectsWrongToken() {
        val (status, body) = httpGet("/v1/models", authToken = "wrong-token")
        assertEquals(401, status)
        val parsed = json.parseToJsonElement(body).jsonObject
        assertNotNull(parsed["error"])
    }

    // ── POST /v1/chat/completions (non-streaming) ────────────────────────

    @Test
    fun completions_nonStreaming_returnsValidResponse() {
        stubEngine.cannedResponse = "Hello from the stub!"

        val requestBody = """
            {
                "model": "local",
                "messages": [{"role": "user", "content": "Hi"}],
                "stream": false
            }
        """.trimIndent()

        val (status, body) = httpPost("/v1/chat/completions", requestBody)

        assertEquals(200, status)
        val parsed = json.parseToJsonElement(body).jsonObject
        assertEquals("chat.completion", parsed["object"]?.jsonPrimitive?.content)
        assertTrue(parsed["id"]?.jsonPrimitive?.content?.startsWith("chatcmpl-") == true)

        val choices = parsed["choices"]?.jsonArray
        assertNotNull(choices)
        assertEquals(1, choices!!.size)

        val message = choices[0].jsonObject["message"]?.jsonObject
        assertEquals("assistant", message?.get("role")?.jsonPrimitive?.content)
        assertEquals("Hello from the stub!", message?.get("content")?.jsonPrimitive?.content)
        assertEquals("stop", choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun completions_nonStreaming_rejectsUnauthorized() {
        val requestBody = """{"model":"local","messages":[{"role":"user","content":"Hi"}]}"""
        val (status, _) = httpPost("/v1/chat/completions", requestBody, authToken = null)
        assertEquals(401, status)
    }

    @Test
    fun completions_emptyMessages_returns422() {
        val requestBody = """{"model":"local","messages":[],"stream":false}"""
        val (status, body) = httpPost("/v1/chat/completions", requestBody)

        assertEquals(422, status)
        val parsed = json.parseToJsonElement(body).jsonObject
        assertTrue(parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.contains("empty") == true)
    }

    @Test
    fun completions_invalidBody_returns400() {
        val (status, body) = httpPost("/v1/chat/completions", "not json at all")

        assertEquals(400, status)
        val parsed = json.parseToJsonElement(body).jsonObject
        assertNotNull(parsed["error"])
    }

    // ── POST /v1/chat/completions (streaming) ────────────────────────────

    @Test
    fun completions_streaming_returnsSSEChunks() {
        stubEngine.cannedStreamTokens = listOf(
            StreamToken(text = "Hello"),
            StreamToken(text = " world"),
            StreamToken(text = "", done = true)
        )

        val requestBody = """
            {
                "model": "local",
                "messages": [{"role": "user", "content": "Hi"}],
                "stream": true
            }
        """.trimIndent()

        val (status, body) = httpPost("/v1/chat/completions", requestBody)

        assertEquals(200, status)

        // Parse SSE lines
        val dataLines = body.lines()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ") }

        // Should have at least: role delta, content deltas, stop delta, [DONE]
        assertTrue("Expected multiple SSE data lines, got ${dataLines.size}", dataLines.size >= 3)

        // Last line should be [DONE]
        assertEquals("[DONE]", dataLines.last())

        // First chunk should have role = "assistant"
        val firstChunk = json.parseToJsonElement(dataLines[0]).jsonObject
        assertEquals("chat.completion.chunk", firstChunk["object"]?.jsonPrimitive?.content)
        val firstDelta = firstChunk["choices"]?.jsonArray?.get(0)?.jsonObject?.get("delta")?.jsonObject
        assertEquals("assistant", firstDelta?.get("role")?.jsonPrimitive?.content)

        // One of the chunks should have content
        val contentChunks = dataLines
            .filter { it != "[DONE]" }
            .map { json.parseToJsonElement(it).jsonObject }
            .filter { chunk ->
                val delta = chunk["choices"]?.jsonArray?.get(0)?.jsonObject?.get("delta")?.jsonObject
                delta?.get("content")?.jsonPrimitive?.content != null
            }
        assertTrue("Expected content chunks", contentChunks.isNotEmpty())
    }

    // ── System instruction extraction ────────────────────────────────────

    @Test
    fun completions_systemInstructionPassedToEngine() {
        stubEngine.cannedResponse = "OK"

        val requestBody = """
            {
                "model": "local",
                "messages": [
                    {"role": "system", "content": "You are a pirate"},
                    {"role": "user", "content": "Hi"}
                ],
                "stream": false
            }
        """.trimIndent()

        val (status, _) = httpPost("/v1/chat/completions", requestBody)
        assertEquals(200, status)

        // The stub captures the InferenceParams — verify system instruction was passed
        assertNotNull(stubEngine.lastParams)
        assertEquals("You are a pirate", stubEngine.lastParams?.systemInstruction)
    }

    // ── Parameter pass-through ───────────────────────────────────────────

    @Test
    fun completions_temperatureTopPTopKPassedThrough() {
        stubEngine.cannedResponse = "OK"

        val requestBody = """
            {
                "model": "local",
                "messages": [{"role": "user", "content": "Hi"}],
                "stream": false,
                "temperature": 0.5,
                "top_p": 0.8,
                "top_k": 20
            }
        """.trimIndent()

        val (status, _) = httpPost("/v1/chat/completions", requestBody)
        assertEquals(200, status)

        assertNotNull(stubEngine.lastParams)
        assertEquals(0.5, stubEngine.lastParams!!.temperature, 0.001)
        assertEquals(0.8, stubEngine.lastParams!!.topP, 0.001)
        assertEquals(20, stubEngine.lastParams!!.topK)
    }

    // ── Multipart content (text array) ───────────────────────────────────

    @Test
    fun completions_multipartTextContent() {
        stubEngine.cannedResponse = "I see the image"

        val requestBody = """
            {
                "model": "local",
                "messages": [{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "What is in this image?"},
                        {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="}}
                    ]
                }],
                "stream": false
            }
        """.trimIndent()

        val (status, body) = httpPost("/v1/chat/completions", requestBody)
        assertEquals(200, status)

        val parsed = json.parseToJsonElement(body).jsonObject
        val content = parsed["choices"]?.jsonArray?.get(0)
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        assertEquals("I see the image", content)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun httpGet(path: String, authToken: String? = testToken): Pair<Int, String> {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (authToken != null) {
            conn.setRequestProperty("Authorization", "Bearer $authToken")
        }
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        val status = conn.responseCode
        val body = try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return status to body
    }

    private fun httpPost(path: String, body: String, authToken: String? = testToken): Pair<Int, String> {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (authToken != null) {
            conn.setRequestProperty("Authorization", "Bearer $authToken")
        }
        conn.connectTimeout = 5000
        conn.readTimeout = 10000

        conn.outputStream.write(body.toByteArray())
        conn.outputStream.flush()

        val status = conn.responseCode
        val responseBody = try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return status to responseBody
    }
}

/**
 * A stub LlmEngine subclass that returns canned responses without running
 * actual inference. Captures the last InferenceParams for test verification.
 *
 * Note: LlmEngine's constructor requires a modelPath but since we override
 * generate/generateStream, the model is never loaded.
 */
class StubLlmEngine : LlmEngine(
    modelPath = "/dev/null",
    idleTimeoutMs = Long.MAX_VALUE
) {
    var cannedResponse: String = "stub response"
    var cannedStreamTokens: List<StreamToken> = listOf(
        StreamToken(text = "stub"),
        StreamToken(text = " response"),
        StreamToken(text = "", done = true)
    )
    var lastParams: InferenceParams? = null

    override suspend fun generate(contents: List<ContentPart>, params: InferenceParams): GenerateResult {
        lastParams = params
        return GenerateResult(text = cannedResponse)
    }

    override fun generateStream(contents: List<ContentPart>, params: InferenceParams): Flow<StreamToken> {
        lastParams = params
        return flowOf(*cannedStreamTokens.toTypedArray())
    }
}
