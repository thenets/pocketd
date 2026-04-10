package dev.thenets.pocketd.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolCallParserTest {

    // ── Valid tool calls ─────────────────────────────────────────────────

    @Test
    fun `parse - valid tool call in tags`() {
        val input = """
            <tool_call>
            {"name": "get_weather", "arguments": {"city": "NYC"}}
            </tool_call>
        """.trimIndent()

        val result = ToolCallParser.parse(input)

        assertNotNull(result)
        assertEquals("get_weather", result!!.function.name)
        assertEquals("""{"city":"NYC"}""", result.function.arguments)
        assertEquals("function", result.type)
        assert(result.id.startsWith("call_"))
    }

    @Test
    fun `parse - tool call with string arguments`() {
        val input = """
            <tool_call>
            {"name": "search", "arguments": "{\"query\": \"hello\"}"}
            </tool_call>
        """.trimIndent()

        val result = ToolCallParser.parse(input)

        assertNotNull(result)
        assertEquals("search", result!!.function.name)
        assertEquals("""{"query": "hello"}""", result.function.arguments)
    }

    @Test
    fun `parse - tool call with nested arguments`() {
        val input = """
            <tool_call>
            {"name": "create_user", "arguments": {"user": {"name": "Alice", "age": 30}, "notify": true}}
            </tool_call>
        """.trimIndent()

        val result = ToolCallParser.parse(input)

        assertNotNull(result)
        assertEquals("create_user", result!!.function.name)
        // Arguments should be a valid JSON string
        assert(result.function.arguments.contains("Alice"))
        assert(result.function.arguments.contains("notify"))
    }

    @Test
    fun `parse - tool call with empty arguments`() {
        val input = """
            <tool_call>
            {"name": "list_items", "arguments": {}}
            </tool_call>
        """.trimIndent()

        val result = ToolCallParser.parse(input)

        assertNotNull(result)
        assertEquals("list_items", result!!.function.name)
        assertEquals("{}", result.function.arguments)
    }

    // ── Bare JSON fallback ───────────────────────────────────────────────

    @Test
    fun `parse - bare JSON object without tags`() {
        val input = """{"name": "get_time", "arguments": {"timezone": "UTC"}}"""

        val result = ToolCallParser.parse(input)

        assertNotNull(result)
        assertEquals("get_time", result!!.function.name)
    }

    // ── No tool call ─────────────────────────────────────────────────────

    @Test
    fun `parse - plain text returns null`() {
        val result = ToolCallParser.parse("The weather in NYC is 72°F and sunny.")
        assertNull(result)
    }

    @Test
    fun `parse - empty string returns null`() {
        val result = ToolCallParser.parse("")
        assertNull(result)
    }

    @Test
    fun `parse - whitespace only returns null`() {
        val result = ToolCallParser.parse("   \n\t  ")
        assertNull(result)
    }

    // ── Malformed JSON ───────────────────────────────────────────────────

    @Test
    fun `parse - malformed JSON inside tags returns null`() {
        val input = """
            <tool_call>
            {not valid json}
            </tool_call>
        """.trimIndent()

        val result = ToolCallParser.parse(input)
        assertNull(result)
    }

    @Test
    fun `parse - missing name field returns null`() {
        val input = """
            <tool_call>
            {"arguments": {"city": "NYC"}}
            </tool_call>
        """.trimIndent()

        val result = ToolCallParser.parse(input)
        assertNull(result)
    }

    @Test
    fun `parse - missing arguments field returns null`() {
        val input = """
            <tool_call>
            {"name": "get_weather"}
            </tool_call>
        """.trimIndent()

        val result = ToolCallParser.parse(input)
        assertNull(result)
    }

    // ── ID generation ────────────────────────────────────────────────────

    @Test
    fun `parse - generates unique IDs`() {
        val input = """
            <tool_call>
            {"name": "test", "arguments": {}}
            </tool_call>
        """.trimIndent()

        val result1 = ToolCallParser.parse(input)
        val result2 = ToolCallParser.parse(input)

        assertNotNull(result1)
        assertNotNull(result2)
        assert(result1!!.id != result2!!.id) { "IDs should be unique" }
    }

    // ── Case insensitivity of tags ───────────────────────────────────────

    @Test
    fun `parse - case insensitive tags`() {
        val input = """
            <TOOL_CALL>
            {"name": "test", "arguments": {}}
            </TOOL_CALL>
        """.trimIndent()

        val result = ToolCallParser.parse(input)
        assertNotNull(result)
        assertEquals("test", result!!.function.name)
    }
}
