package dev.thenets.pocketd.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ContextSizeStressTest"

/**
 * Stress tests for context sizes beyond the standard 64K limit.
 *
 * These tests explore how far the Gemma-4-E2B-it model can go on the
 * current device. They test context sizes from 128K up to 1M tokens,
 * and also send longer prompts to actually fill some of the context window.
 *
 * Requirements:
 *  - Model file at the standard pocketd path or legacy path
 *  - Runs on a real device (androidTest)
 *  - Device should have 8+ GB RAM for the larger tests
 *
 * Note: These tests are VERY slow — each one loads the engine from scratch,
 * and larger context sizes require significantly more memory and time.
 */
@RunWith(AndroidJUnit4::class)
class ContextSizeStressTest {

    companion object {
        private const val MODEL_PATH_NEW = "/sdcard/Download/pocketd/Gemma-4-E2B-it/gemma-4-E2B-it.litertlm"
        private const val MODEL_PATH_LEGACY = "/sdcard/Download/gemma-4-E2B-it.litertlm"
        private val MODEL_PATH: String
            get() = if (java.io.File(MODEL_PATH_NEW).exists()) MODEL_PATH_NEW else MODEL_PATH_LEGACY

        private const val SHORT_PROMPT = "Say hello in one word."
        private const val INFERENCE_TIMEOUT_MS = 600_000L // 10 minutes per inference
    }

    // ── Extended context size tests ──────────────────────────────────────

    @Test
    fun contextSize_131072_tokens() {
        testContextSize(131_072, SHORT_PROMPT)
    }

    @Test
    fun contextSize_262144_tokens() {
        testContextSize(262_144, SHORT_PROMPT)
    }

    @Test
    fun contextSize_524288_tokens() {
        testContextSize(524_288, SHORT_PROMPT)
    }

    @Test
    fun contextSize_1048576_tokens() {
        testContextSize(1_048_576, SHORT_PROMPT)
    }

    // ── Long-prompt stress tests ────────────────────────────────────────
    //
    // These tests send increasingly long prompts to find the practical
    // throughput limit. Context is set high enough to accommodate each prompt.
    // Prompt processing time scales with length, so these find the wall.

    @Test
    fun longPrompt_1kTokens() {
        val prompt = buildLongPrompt(targetTokens = 1000)
        testContextSize(4096, prompt)
    }

    @Test
    fun longPrompt_2kTokens() {
        val prompt = buildLongPrompt(targetTokens = 2000)
        testContextSize(4096, prompt)
    }

    @Test
    fun longPrompt_4kTokens() {
        val prompt = buildLongPrompt(targetTokens = 4000)
        testContextSize(8192, prompt)
    }

    @Test
    fun longPrompt_8kTokens() {
        val prompt = buildLongPrompt(targetTokens = 8000)
        testContextSize(16384, prompt)
    }

    @Test
    fun longPrompt_16kTokens() {
        val prompt = buildLongPrompt(targetTokens = 16_000)
        testContextSize(32_768, prompt)
    }

    @Test
    fun longPrompt_32kTokens() {
        val prompt = buildLongPrompt(targetTokens = 32_000)
        testContextSize(65_536, prompt)
    }

    @Test
    fun longPrompt_64kTokens() {
        val prompt = buildLongPrompt(targetTokens = 64_000)
        testContextSize(131_072, prompt)
    }

    @Test
    fun longPrompt_128kTokens() {
        val prompt = buildLongPrompt(targetTokens = 128_000)
        testContextSize(262_144, prompt)
    }

    // ── Multi-turn conversation stress test ─────────────────────────────
    //
    // Simulates a multi-turn conversation by sending multiple messages
    // in sequence to the same engine instance.

    @Test
    fun multiTurn_32768ctx_10turns() {
        testMultiTurn(contextSize = 32_768, turns = 10)
    }

    @Test
    fun multiTurn_65536ctx_10turns() {
        testMultiTurn(contextSize = 65_536, turns = 10)
    }

    @Test
    fun multiTurn_131072ctx_10turns() {
        testMultiTurn(contextSize = 131_072, turns = 10)
    }

    // ── Backend comparison stress tests ─────────────────────────────────
    //
    // Test the same context size on both CPU and GPU to compare behavior.

    @Test
    fun gpuBackend_32768ctx() {
        testContextSizeWithBackend(32_768, SHORT_PROMPT, BackendType.GPU)
    }

    @Test
    fun gpuBackend_65536ctx() {
        testContextSizeWithBackend(65_536, SHORT_PROMPT, BackendType.GPU)
    }

    @Test
    fun gpuBackend_131072ctx() {
        testContextSizeWithBackend(131_072, SHORT_PROMPT, BackendType.GPU)
    }

    // ── Memory monitoring test ──────────────────────────────────────────
    //
    // Tests with memory tracking to log how much RAM each context size needs.

    @Test
    fun memoryProfile_contextSizes() {
        val sizes = listOf(4096, 8192, 16384, 32768, 65536, 131072)
        val results = mutableListOf<String>()

        for (size in sizes) {
            val memBefore = getMemoryUsageMb()
            Log.i(TAG, "=== Memory profile: $size tokens, before=${memBefore}MB ===")

            val engine = LlmEngine(
                modelPath = MODEL_PATH,
                idleTimeoutMs = Long.MAX_VALUE,
                contextSize = size,
                backend = BackendType.CPU
            )

            try {
                val result = runBlocking {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        engine.generate(SHORT_PROMPT)
                    }
                }

                val memAfter = getMemoryUsageMb()
                val memDelta = memAfter - memBefore
                val summary = "ctx=$size: OK, mem_before=${memBefore}MB, mem_after=${memAfter}MB, delta=${memDelta}MB, response_len=${result.length}"
                results.add(summary)
                Log.i(TAG, summary)
            } catch (e: Exception) {
                val memAfter = getMemoryUsageMb()
                val summary = "ctx=$size: FAILED (${e::class.simpleName}: ${e.message}), mem=${memAfter}MB"
                results.add(summary)
                Log.e(TAG, summary, e)
            } finally {
                engine.close()
                // Give the GC a chance to reclaim memory
                System.gc()
                Thread.sleep(1000)
            }
        }

        Log.i(TAG, "=== Memory profile summary ===")
        results.forEach { Log.i(TAG, "  $it") }

        // At least the smallest size should succeed
        assertTrue("At least 4096 context should work", results[0].contains("OK"))
    }

    // ── Streaming at large context ──────────────────────────────────────

    @Test
    fun streaming_32768ctx() {
        testStreamingAtContextSize(32_768)
    }

    @Test
    fun streaming_65536ctx() {
        testStreamingAtContextSize(65_536)
    }

    @Test
    fun streaming_131072ctx() {
        testStreamingAtContextSize(131_072)
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private fun testContextSize(contextSize: Int, prompt: String) {
        testContextSizeWithBackend(contextSize, prompt, BackendType.CPU)
    }

    private fun testContextSizeWithBackend(contextSize: Int, prompt: String, backend: BackendType) {
        Log.i(TAG, "=== Testing context=$contextSize, backend=$backend, prompt_len=${prompt.length} ===")

        val engine = LlmEngine(
            modelPath = MODEL_PATH,
            idleTimeoutMs = Long.MAX_VALUE,
            contextSize = contextSize,
            backend = backend
        )

        try {
            val result = runBlocking {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    engine.generate(prompt)
                }
            }

            Log.i(TAG, "Context $contextSize ($backend): SUCCESS — response_len=${result.length}, text=\"${result.take(100)}\"")
            assertNotNull("Response should not be null", result)
            assertTrue("Response should not be empty for context size $contextSize", result.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Context $contextSize ($backend): FAILED — ${e::class.simpleName}: ${e.message}", e)
            fail("Context $contextSize ($backend) is NOT supported: ${e::class.simpleName}: ${e.message}")
        } finally {
            engine.close()
            Log.i(TAG, "Engine closed for context size $contextSize")
        }
    }

    private fun testMultiTurn(contextSize: Int, turns: Int) {
        Log.i(TAG, "=== Multi-turn test: context=$contextSize, turns=$turns ===")

        val engine = LlmEngine(
            modelPath = MODEL_PATH,
            idleTimeoutMs = Long.MAX_VALUE,
            contextSize = contextSize,
            backend = BackendType.CPU
        )

        try {
            val prompts = listOf(
                "What is 2 + 2?",
                "Now multiply that by 3.",
                "Subtract 5 from the result.",
                "Is that a prime number?",
                "What is the square root of 49?",
                "Name three colors.",
                "Which one is a primary color?",
                "What wavelength of light does it correspond to?",
                "Convert that to nanometers.",
                "Summarize what we discussed."
            )

            for (i in 0 until turns) {
                val prompt = prompts[i % prompts.size]
                Log.i(TAG, "Turn ${i + 1}/$turns: \"$prompt\"")

                val result = runBlocking {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        engine.generate(prompt)
                    }
                }

                Log.i(TAG, "Turn ${i + 1} response (${result.length} chars): \"${result.take(80)}\"")
                assertNotNull("Turn ${i + 1} response should not be null", result)
                assertTrue("Turn ${i + 1} response should not be empty", result.isNotEmpty())
            }

            Log.i(TAG, "Multi-turn test PASSED: $turns turns at context=$contextSize")
        } catch (e: Exception) {
            Log.e(TAG, "Multi-turn failed at context=$contextSize: ${e::class.simpleName}: ${e.message}", e)
            fail("Multi-turn at context=$contextSize failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            engine.close()
        }
    }

    private fun testStreamingAtContextSize(contextSize: Int) {
        Log.i(TAG, "=== Streaming test: context=$contextSize ===")

        val engine = LlmEngine(
            modelPath = MODEL_PATH,
            idleTimeoutMs = Long.MAX_VALUE,
            contextSize = contextSize,
            backend = BackendType.CPU
        )

        try {
            var tokenCount = 0
            val fullText = StringBuilder()

            runBlocking {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    engine.generateStream(SHORT_PROMPT).collect { text ->
                        if (text.isNotEmpty()) {
                            tokenCount++
                            fullText.append(text)
                        }
                    }
                }
            }

            Log.i(TAG, "Streaming at $contextSize: SUCCESS — $tokenCount tokens, text=\"${fullText.toString().take(100)}\"")
            assertTrue("Should have generated at least 1 token", tokenCount > 0)
            assertTrue("Concatenated text should not be empty", fullText.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Streaming at $contextSize: FAILED — ${e::class.simpleName}: ${e.message}", e)
            fail("Streaming at context=$contextSize failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            engine.close()
        }
    }

    /**
     * Build a long prompt that approximates [targetTokens] tokens.
     * Uses ~4 chars per token as a rough estimate for English text.
     */
    private fun buildLongPrompt(targetTokens: Int): String {
        // Numbered sentences to create unique, non-repetitive content
        // that won't trigger degenerate tokenization behavior.
        val sb = StringBuilder()
        sb.append("Read the following numbered facts carefully, then answer the question at the end.\n\n")

        var sentenceNum = 1
        while (sb.length < targetTokens * 4) {
            sb.append("$sentenceNum. The number $sentenceNum is ")
            when {
                sentenceNum % 15 == 0 -> sb.append("divisible by both 3 and 5.")
                sentenceNum % 5 == 0  -> sb.append("divisible by 5 but not 3.")
                sentenceNum % 3 == 0  -> sb.append("divisible by 3 but not 5.")
                sentenceNum % 2 == 0  -> sb.append("an even number.")
                else                  -> sb.append("an odd number.")
            }
            sb.append("\n")
            sentenceNum++
        }

        sb.append("\nQuestion: What is the last number in the list above? Reply with just the number.")
        return sb.toString()
    }

    private fun getMemoryUsageMb(): Long {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val appPss = Debug.getPss() / 1024L
        return appPss
    }
}
