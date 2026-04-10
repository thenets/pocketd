package dev.thenets.pocketd.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSizeTest {

    // ── CONTEXT_SIZE_OPTIONS list ────────────────────────────────────────

    @Test
    fun `options list has 8 entries`() {
        assertEquals(8, CONTEXT_SIZE_OPTIONS.size)
    }

    @Test
    fun `options are in ascending order by token count`() {
        val tokens = CONTEXT_SIZE_OPTIONS.map { it.tokens }
        assertEquals(tokens.sorted(), tokens)
    }

    @Test
    fun `all token counts are powers of two`() {
        for (opt in CONTEXT_SIZE_OPTIONS) {
            assertTrue(
                "Expected ${opt.tokens} to be a power of 2",
                opt.tokens > 0 && (opt.tokens and (opt.tokens - 1)) == 0
            )
        }
    }

    @Test
    fun `expected token sizes`() {
        val expected = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
        assertEquals(expected, CONTEXT_SIZE_OPTIONS.map { it.tokens })
    }

    @Test
    fun `RAM estimates increase with token count`() {
        val ramValues = CONTEXT_SIZE_OPTIONS.map { it.estimatedRamMb }
        assertEquals(ramValues.sorted(), ramValues)
    }

    @Test
    fun `all options have non-empty label and description`() {
        for (opt in CONTEXT_SIZE_OPTIONS) {
            assertTrue("Label should not be blank", opt.label.isNotBlank())
            assertTrue("Description should not be blank", opt.description.isNotBlank())
        }
    }

    // ── bestContextSize() — boundary tests ───────────────────────────────

    @Test
    fun `very low RAM returns 512 tokens`() {
        assertEquals(512, bestContextSize(1024).tokens)    // 1 GB
        assertEquals(512, bestContextSize(2000).tokens)    // ~2 GB
        assertEquals(512, bestContextSize(0).tokens)       // edge case
    }

    @Test
    fun `2200 MB RAM returns 1024 tokens`() {
        assertEquals(1024, bestContextSize(2200).tokens)
        assertEquals(1024, bestContextSize(2400).tokens)
    }

    @Test
    fun `2200 MB is the lower boundary for 1024 tokens`() {
        assertEquals(512, bestContextSize(2199).tokens)
        assertEquals(1024, bestContextSize(2200).tokens)
    }

    @Test
    fun `2560 MB RAM returns 2048 tokens`() {
        assertEquals(2048, bestContextSize(2560).tokens)
        assertEquals(2048, bestContextSize(3000).tokens)
    }

    @Test
    fun `2560 MB is the lower boundary for 2048 tokens`() {
        assertEquals(1024, bestContextSize(2559).tokens)
        assertEquals(2048, bestContextSize(2560).tokens)
    }

    @Test
    fun `3 GB RAM returns 4096 tokens`() {
        assertEquals(4096, bestContextSize(3 * 1024).tokens)
        assertEquals(4096, bestContextSize(3500).tokens)
    }

    @Test
    fun `3 GB is the lower boundary for 4096 tokens`() {
        assertEquals(2048, bestContextSize(3 * 1024 - 1).tokens)
        assertEquals(4096, bestContextSize(3 * 1024).tokens)
    }

    @Test
    fun `4 GB RAM returns 8192 tokens`() {
        assertEquals(8192, bestContextSize(4 * 1024).tokens)
        assertEquals(8192, bestContextSize(6000).tokens)
    }

    @Test
    fun `4 GB is the lower boundary for 8192 tokens`() {
        assertEquals(4096, bestContextSize(4 * 1024 - 1).tokens)
        assertEquals(8192, bestContextSize(4 * 1024).tokens)
    }

    @Test
    fun `8 GB RAM returns 32768 tokens for default Gemma-4 model`() {
        // Default model is Gemma-4, which gets 32768 at 8 GB
        assertEquals(32768, bestContextSize(8 * 1024).tokens)
        assertEquals(32768, bestContextSize(10000).tokens)
    }

    @Test
    fun `8 GB is the lower boundary for 32768 tokens with Gemma-4`() {
        assertEquals(8192, bestContextSize(8 * 1024 - 1).tokens)
        assertEquals(32768, bestContextSize(8 * 1024).tokens)
    }

    @Test
    fun `12 GB RAM returns 32768 tokens`() {
        assertEquals(32768, bestContextSize(12 * 1024).tokens)
        assertEquals(32768, bestContextSize(20000).tokens)
    }

    @Test
    fun `12 GB boundary for non-Gemma-4 models returns 32768`() {
        // For default Gemma-4 model, 8 GB already gives 32768
        // At 12 GB, all models get 32768
        assertEquals(32768, bestContextSize(12 * 1024).tokens)
    }

    @Test
    fun `24 GB RAM returns 65536 tokens`() {
        assertEquals(65536, bestContextSize(24 * 1024).tokens)
        assertEquals(65536, bestContextSize(48 * 1024).tokens) // very large RAM
    }

    @Test
    fun `24 GB is the lower boundary for 65536 tokens`() {
        assertEquals(32768, bestContextSize(24 * 1024 - 1).tokens)
        assertEquals(65536, bestContextSize(24 * 1024).tokens)
    }

    // ── bestContextSize() returns a full ContextSizeOption ───────────────

    @Test
    fun `bestContextSize returns complete option with RAM estimate`() {
        val opt = bestContextSize(4 * 1024)
        assertEquals(8192, opt.tokens)
        assertEquals(160, opt.estimatedRamMb)
        assertEquals("8,192 tokens", opt.label)
    }

    @Test
    fun `bestContextSize returns object from the shared options list`() {
        val opt = bestContextSize(4 * 1024)
        assertTrue(CONTEXT_SIZE_OPTIONS.contains(opt))
    }

    // ── ContextSizeOption data class ─────────────────────────────────────

    @Test
    fun `ContextSizeOption equality`() {
        val a = ContextSizeOption(2048, "2,048 tokens", 40, "Standard — multi-turn")
        val b = ContextSizeOption(2048, "2,048 tokens", 40, "Standard — multi-turn")
        assertEquals(a, b)
    }

    @Test
    fun `ContextSizeOption copy`() {
        val original = CONTEXT_SIZE_OPTIONS[0]
        val modified = original.copy(tokens = 256)
        assertEquals(256, modified.tokens)
        assertEquals(original.label, modified.label)
    }
}
