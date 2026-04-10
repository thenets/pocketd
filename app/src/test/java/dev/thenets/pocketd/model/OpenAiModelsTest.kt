package dev.thenets.pocketd.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenAiModelsTest {

    // ── ChatMessage.textContent() ────────────────────────────────────────

    @Test
    fun `textContent - string content`() {
        val msg = ChatMessage(role = "user", content = JsonPrimitive("Hello"))
        assertEquals("Hello", msg.textContent())
    }

    @Test
    fun `textContent - null content`() {
        val msg = ChatMessage(role = "assistant", content = null)
        assertNull(msg.textContent())
    }

    @Test
    fun `textContent - array content with text parts`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "Hello ")
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", "World")
            })
        }
        val msg = ChatMessage(role = "user", content = content)
        assertEquals("Hello World", msg.textContent())
    }

    @Test
    fun `textContent - array content with mixed parts extracts only text`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "Describe this image")
            })
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/png;base64,abc") }
            })
        }
        val msg = ChatMessage(role = "user", content = content)
        assertEquals("Describe this image", msg.textContent())
    }

    @Test
    fun `textContent - array with no text parts returns null`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/png;base64,abc") }
            })
        }
        val msg = ChatMessage(role = "user", content = content)
        assertNull(msg.textContent())
    }

    @Test
    fun `textContent - empty string content`() {
        val msg = ChatMessage(role = "user", content = JsonPrimitive(""))
        assertEquals("", msg.textContent())
    }

    // ── ChatMessage.imageParts() ─────────────────────────────────────────

    @Test
    fun `imageParts - base64 image URL decoded`() {
        // "SGVsbG8=" is base64 for "Hello"
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/png;base64,SGVsbG8=") }
            })
        }
        val msg = ChatMessage(role = "user", content = content)
        val parts = msg.imageParts()

        assertEquals(1, parts.size)
        assertEquals("Hello", String(parts[0]))
    }

    @Test
    fun `imageParts - non-base64 URL returns empty`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "https://example.com/image.png") }
            })
        }
        val msg = ChatMessage(role = "user", content = content)
        val parts = msg.imageParts()

        assertTrue(parts.isEmpty())
    }

    @Test
    fun `imageParts - string content returns empty`() {
        val msg = ChatMessage(role = "user", content = JsonPrimitive("Hello"))
        assertTrue(msg.imageParts().isEmpty())
    }

    @Test
    fun `imageParts - null content returns empty`() {
        val msg = ChatMessage(role = "user", content = null)
        assertTrue(msg.imageParts().isEmpty())
    }

    @Test
    fun `imageParts - multiple images`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/png;base64,SGVsbG8=") }
            })
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64,V29ybGQ=") }
            })
        }
        val msg = ChatMessage(role = "user", content = content)
        val parts = msg.imageParts()

        assertEquals(2, parts.size)
        assertEquals("Hello", String(parts[0]))
        assertEquals("World", String(parts[1]))
    }

    // ── Data class defaults ──────────────────────────────────────────────

    @Test
    fun `ChatCompletionRequest - default values`() {
        val req = ChatCompletionRequest(messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Hi"))))

        assertEquals("local", req.model)
        assertEquals(false, req.stream)
        assertNull(req.temperature)
        assertNull(req.maxTokens)
        assertNull(req.topP)
        assertNull(req.topK)
        assertNull(req.tools)
    }

    @Test
    fun `ChatCompletionResponse - has correct object type`() {
        val response = ChatCompletionResponse(
            id = "test",
            created = 0L,
            model = "local",
            choices = emptyList(),
            usage = Usage()
        )
        assertEquals("chat.completion", response.`object`)
    }

    @Test
    fun `ChatCompletionChunk - has correct object type`() {
        val chunk = ChatCompletionChunk(
            id = "test",
            created = 0L,
            model = "local",
            choices = emptyList()
        )
        assertEquals("chat.completion.chunk", chunk.`object`)
    }

    @Test
    fun `Usage - default values are zero`() {
        val usage = Usage()
        assertEquals(0, usage.promptTokens)
        assertEquals(0, usage.completionTokens)
        assertEquals(0, usage.totalTokens)
    }

    @Test
    fun `Choice - default finish reason is stop`() {
        val choice = Choice(
            index = 0,
            message = ChatMessage(role = "assistant", content = JsonPrimitive("Hi"))
        )
        assertEquals("stop", choice.finishReason)
    }

    @Test
    fun `ToolCall - structure`() {
        val tc = ToolCall(
            id = "call_123",
            type = "function",
            function = FunctionCall(name = "test", arguments = "{}")
        )
        assertEquals("call_123", tc.id)
        assertEquals("function", tc.type)
        assertEquals("test", tc.function.name)
        assertEquals("{}", tc.function.arguments)
    }

    @Test
    fun `ToolDefinition - default type is function`() {
        val td = ToolDefinition(function = FunctionDefinition(name = "test"))
        assertEquals("function", td.type)
    }

    // ── ChatMessage with toolCalls ───────────────────────────────────────

    @Test
    fun `ChatMessage - tool calls only, null content`() {
        val tc = ToolCall(
            id = "call_1",
            function = FunctionCall(name = "fn", arguments = "{}")
        )
        val msg = ChatMessage(role = "assistant", toolCalls = listOf(tc))

        assertNull(msg.textContent())
        assertNotNull(msg.toolCalls)
        assertEquals(1, msg.toolCalls!!.size)
    }

    @Test
    fun `ChatMessage - tool message has toolCallId`() {
        val msg = ChatMessage(role = "tool", content = JsonPrimitive("result"), toolCallId = "call_1")
        assertEquals("call_1", msg.toolCallId)
        assertEquals("result", msg.textContent())
    }
}
