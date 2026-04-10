package dev.thenets.pocketd.llm

import dev.thenets.pocketd.model.ToolDefinition
import dev.thenets.pocketd.model.FunctionDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmEngineTypesTest {

    // ── BackendType ──────────────────────────────────────────────────────

    @Test
    fun `BackendType - all values exist`() {
        val values = BackendType.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(BackendType.CPU))
        assertTrue(values.contains(BackendType.GPU))
        assertTrue(values.contains(BackendType.NPU))
        assertTrue(values.contains(BackendType.GPU_WITH_CPU_FALLBACK))
    }

    @Test
    fun `BackendType - valueOf`() {
        assertEquals(BackendType.CPU, BackendType.valueOf("CPU"))
        assertEquals(BackendType.GPU, BackendType.valueOf("GPU"))
    }

    // ── InferenceParams ──────────────────────────────────────────────────

    @Test
    fun `InferenceParams - defaults`() {
        val params = InferenceParams()
        assertEquals(40, params.topK)
        assertEquals(0.95, params.topP, 0.001)
        assertEquals(1.0, params.temperature, 0.001)
        assertNull(params.systemInstruction)
        assertTrue(params.tools.isEmpty())
    }

    @Test
    fun `InferenceParams - custom values`() {
        val tools = listOf(ToolDefinition(function = FunctionDefinition(name = "test")))
        val params = InferenceParams(
            topK = 10,
            topP = 0.5,
            temperature = 0.7,
            systemInstruction = "Be helpful",
            tools = tools
        )
        assertEquals(10, params.topK)
        assertEquals(0.5, params.topP, 0.001)
        assertEquals(0.7, params.temperature, 0.001)
        assertEquals("Be helpful", params.systemInstruction)
        assertEquals(1, params.tools.size)
    }

    @Test
    fun `InferenceParams - copy`() {
        val original = InferenceParams(topK = 10)
        val copy = original.copy(temperature = 0.5)
        assertEquals(10, copy.topK)
        assertEquals(0.5, copy.temperature, 0.001)
    }

    // ── ContentPart ──────────────────────────────────────────────────────

    @Test
    fun `ContentPart - text only`() {
        val part = ContentPart(text = "hello")
        assertEquals("hello", part.text)
        assertNull(part.imageBytes)
    }

    @Test
    fun `ContentPart - image only`() {
        val bytes = byteArrayOf(1, 2, 3)
        val part = ContentPart(imageBytes = bytes)
        assertNull(part.text)
        assertTrue(bytes.contentEquals(part.imageBytes!!))
    }

    @Test
    fun `ContentPart - equality with same bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val p1 = ContentPart(text = "a", imageBytes = bytes.clone())
        val p2 = ContentPart(text = "a", imageBytes = bytes.clone())
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
    }

    @Test
    fun `ContentPart - inequality with different bytes`() {
        val p1 = ContentPart(imageBytes = byteArrayOf(1, 2))
        val p2 = ContentPart(imageBytes = byteArrayOf(3, 4))
        assertNotEquals(p1, p2)
    }

    @Test
    fun `ContentPart - equality with null bytes`() {
        val p1 = ContentPart(text = "hello")
        val p2 = ContentPart(text = "hello")
        assertEquals(p1, p2)
    }

    // ── GenerateResult ───────────────────────────────────────────────────

    @Test
    fun `GenerateResult - with thinking text`() {
        val result = GenerateResult(text = "answer", thinkingText = "reasoning")
        assertEquals("answer", result.text)
        assertEquals("reasoning", result.thinkingText)
    }

    @Test
    fun `GenerateResult - without thinking text`() {
        val result = GenerateResult(text = "answer")
        assertEquals("answer", result.text)
        assertNull(result.thinkingText)
    }

    // ── StreamToken ──────────────────────────────────────────────────────

    @Test
    fun `StreamToken - defaults`() {
        val token = StreamToken(text = "hi")
        assertEquals("hi", token.text)
        assertNull(token.thinkingText)
        assertEquals(false, token.done)
    }

    @Test
    fun `StreamToken - done token`() {
        val token = StreamToken(text = "", done = true)
        assertTrue(token.done)
    }
}
