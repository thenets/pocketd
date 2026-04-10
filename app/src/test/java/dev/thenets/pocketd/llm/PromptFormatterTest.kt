package dev.thenets.pocketd.llm

import dev.thenets.pocketd.model.ChatMessage
import dev.thenets.pocketd.model.FunctionCall
import dev.thenets.pocketd.model.FunctionDefinition
import dev.thenets.pocketd.model.ToolCall
import dev.thenets.pocketd.model.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFormatterTest {

    // ── format() ─────────────────────────────────────────────────────────

    @Test
    fun `format - single user message`() {
        val messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Hello")))
        val result = PromptFormatter.format(messages)

        assertTrue(result.startsWith("<bos>\n"))
        assertTrue(result.contains("<start_of_turn>user\nHello<end_of_turn>\n"))
        assertTrue(result.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `format - system message rendered as user turn with System prefix`() {
        val messages = listOf(
            ChatMessage(role = "system", content = JsonPrimitive("You are helpful")),
            ChatMessage(role = "user", content = JsonPrimitive("Hi"))
        )
        val result = PromptFormatter.format(messages)

        assertTrue(result.contains("<start_of_turn>user\n[System: You are helpful]\n<end_of_turn>"))
        assertTrue(result.contains("<start_of_turn>user\nHi<end_of_turn>"))
    }

    @Test
    fun `format - assistant message rendered as model turn`() {
        val messages = listOf(
            ChatMessage(role = "user", content = JsonPrimitive("Hi")),
            ChatMessage(role = "assistant", content = JsonPrimitive("Hello!"))
        )
        val result = PromptFormatter.format(messages)

        assertTrue(result.contains("<start_of_turn>model\nHello!<end_of_turn>"))
    }

    @Test
    fun `format - assistant message with tool calls`() {
        val toolCall = ToolCall(
            id = "call_123",
            type = "function",
            function = FunctionCall(name = "get_weather", arguments = """{"city":"NYC"}""")
        )
        val messages = listOf(
            ChatMessage(role = "user", content = JsonPrimitive("What's the weather?")),
            ChatMessage(role = "assistant", toolCalls = listOf(toolCall))
        )
        val result = PromptFormatter.format(messages)

        assertTrue(result.contains("<tool_call>"))
        assertTrue(result.contains(""""name": "get_weather""""))
        assertTrue(result.contains(""""arguments": {"city":"NYC"}"""))
        assertTrue(result.contains("</tool_call>"))
    }

    @Test
    fun `format - tool result rendered as user turn`() {
        val messages = listOf(
            ChatMessage(role = "tool", content = JsonPrimitive("72°F"), toolCallId = "call_123")
        )
        val result = PromptFormatter.format(messages)

        assertTrue(result.contains("<start_of_turn>user\n<tool_result>"))
        assertTrue(result.contains(""""tool_call_id": "call_123""""))
        assertTrue(result.contains(""""content": "72°F""""))
        assertTrue(result.contains("</tool_result>"))
    }

    @Test
    fun `format - empty messages list produces BOS and model turn`() {
        val result = PromptFormatter.format(emptyList())

        assertEquals("<bos>\n<start_of_turn>model\n", result)
    }

    @Test
    fun `format - multipart content extracts text parts`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "Look at this")
            })
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/png;base64,abc") }
            })
        }
        val messages = listOf(ChatMessage(role = "user", content = content))
        val result = PromptFormatter.format(messages)

        assertTrue(result.contains("Look at this"))
    }

    @Test
    fun `format - null content renders as empty string`() {
        val messages = listOf(ChatMessage(role = "user", content = null))
        val result = PromptFormatter.format(messages)

        assertTrue(result.contains("<start_of_turn>user\n<end_of_turn>"))
    }

    @Test
    fun `format - unknown role treated as user turn`() {
        val messages = listOf(ChatMessage(role = "custom", content = JsonPrimitive("test")))
        val result = PromptFormatter.format(messages)

        assertTrue(result.contains("<start_of_turn>user\ntest<end_of_turn>"))
    }

    @Test
    fun `format - with tool definitions injects tool system prompt`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "get_weather",
                    description = "Get the weather",
                    parameters = buildJsonObject { put("type", "object") }
                )
            )
        )
        val messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Hi")))
        val result = PromptFormatter.format(messages, tools)

        assertTrue(result.contains("You have access to the following tools"))
        assertTrue(result.contains("get_weather: Get the weather"))
    }

    @Test
    fun `format - multi-turn conversation preserves order`() {
        val messages = listOf(
            ChatMessage(role = "user", content = JsonPrimitive("Hello")),
            ChatMessage(role = "assistant", content = JsonPrimitive("Hi there")),
            ChatMessage(role = "user", content = JsonPrimitive("How are you?"))
        )
        val result = PromptFormatter.format(messages)

        val userIdx1 = result.indexOf("Hello")
        val assistantIdx = result.indexOf("Hi there")
        val userIdx2 = result.indexOf("How are you?")
        assertTrue(userIdx1 < assistantIdx)
        assertTrue(assistantIdx < userIdx2)
    }

    // ── formatWithoutSystem() ────────────────────────────────────────────

    @Test
    fun `formatWithoutSystem - skips system messages`() {
        val messages = listOf(
            ChatMessage(role = "system", content = JsonPrimitive("You are helpful")),
            ChatMessage(role = "user", content = JsonPrimitive("Hi"))
        )
        val result = PromptFormatter.formatWithoutSystem(messages)

        assertTrue(!result.contains("[System:"))
        assertTrue(result.contains("<start_of_turn>user\nHi<end_of_turn>"))
    }

    @Test
    fun `formatWithoutSystem - retains non-system messages`() {
        val messages = listOf(
            ChatMessage(role = "system", content = JsonPrimitive("System prompt")),
            ChatMessage(role = "user", content = JsonPrimitive("Question")),
            ChatMessage(role = "assistant", content = JsonPrimitive("Answer"))
        )
        val result = PromptFormatter.formatWithoutSystem(messages)

        assertTrue(result.contains("Question"))
        assertTrue(result.contains("Answer"))
        assertTrue(!result.contains("System prompt"))
    }

    // ── extractSystemInstruction() ───────────────────────────────────────

    @Test
    fun `extractSystemInstruction - returns text from system messages`() {
        val messages = listOf(
            ChatMessage(role = "system", content = JsonPrimitive("You are helpful")),
            ChatMessage(role = "user", content = JsonPrimitive("Hi"))
        )
        assertEquals("You are helpful", PromptFormatter.extractSystemInstruction(messages))
    }

    @Test
    fun `extractSystemInstruction - concatenates multiple system messages`() {
        val messages = listOf(
            ChatMessage(role = "system", content = JsonPrimitive("Be concise")),
            ChatMessage(role = "system", content = JsonPrimitive("Be accurate")),
            ChatMessage(role = "user", content = JsonPrimitive("Hi"))
        )
        assertEquals("Be concise\nBe accurate", PromptFormatter.extractSystemInstruction(messages))
    }

    @Test
    fun `extractSystemInstruction - returns null when no system messages`() {
        val messages = listOf(
            ChatMessage(role = "user", content = JsonPrimitive("Hi"))
        )
        assertNull(PromptFormatter.extractSystemInstruction(messages))
    }

    @Test
    fun `extractSystemInstruction - returns null for empty list`() {
        assertNull(PromptFormatter.extractSystemInstruction(emptyList()))
    }

    @Test
    fun `extractSystemInstruction - skips system messages with null content`() {
        val messages = listOf(
            ChatMessage(role = "system", content = null),
            ChatMessage(role = "user", content = JsonPrimitive("Hi"))
        )
        assertNull(PromptFormatter.extractSystemInstruction(messages))
    }
}
