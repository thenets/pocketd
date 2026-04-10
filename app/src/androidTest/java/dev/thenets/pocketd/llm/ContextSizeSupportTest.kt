package dev.thenets.pocketd.llm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ContextSizeSupportTest"

/**
 * Integration tests that load the real LLM model on the device and attempt
 * inference at each supported context size. This discovers the actual
 * context size limits of the hardware + model combination.
 *
 * Requirements:
 *  - Model file at /sdcard/Download/gemma-4-E2B-it.litertlm
 *  - Runs on a real device (androidTest)
 *  - Each test loads and unloads the engine, so they are independent
 *
 * Note: These tests are slow (model loading takes several seconds each time).
 */
@RunWith(AndroidJUnit4::class)
class ContextSizeSupportTest {

    companion object {
        private const val MODEL_PATH_NEW = "/sdcard/Download/pocketd/Gemma-4-E2B-it/gemma-4-E2B-it.litertlm"
        private const val MODEL_PATH_LEGACY = "/sdcard/Download/gemma-4-E2B-it.litertlm"
        private val MODEL_PATH: String
            get() = if (java.io.File(MODEL_PATH_NEW).exists()) MODEL_PATH_NEW else MODEL_PATH_LEGACY
        private const val TEST_PROMPT = "Say hello in one word."
        private const val INFERENCE_TIMEOUT_MS = 120_000L // 2 minutes per inference
    }

    @Test
    fun contextSize_512_tokens() {
        testContextSize(512)
    }

    @Test
    fun contextSize_1024_tokens() {
        testContextSize(1024)
    }

    @Test
    fun contextSize_2048_tokens() {
        testContextSize(2048)
    }

    @Test
    fun contextSize_4096_tokens() {
        testContextSize(4096)
    }

    @Test
    fun contextSize_8192_tokens() {
        testContextSize(8192)
    }

    @Test
    fun contextSize_16384_tokens() {
        testContextSize(16384)
    }

    @Test
    fun contextSize_32768_tokens() {
        testContextSize(32768)
    }

    @Test
    fun contextSize_65536_tokens() {
        testContextSize(65536)
    }

    private fun testContextSize(contextSize: Int) {
        Log.i(TAG, "=== Testing context size: $contextSize tokens ===")

        val engine = LlmEngine(
            modelPath = MODEL_PATH,
            idleTimeoutMs = Long.MAX_VALUE,
            contextSize = contextSize,
            backend = BackendType.CPU
        )

        try {
            val result = runBlocking {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    engine.generate(TEST_PROMPT)
                }
            }

            Log.i(TAG, "Context size $contextSize: SUCCESS — response length=${result.length}, text=\"${result.take(100)}\"")
            assertNotNull("Response should not be null", result)
            assertTrue("Response should not be empty for context size $contextSize", result.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Context size $contextSize: FAILED — ${e::class.simpleName}: ${e.message}", e)
            fail("Context size $contextSize is NOT supported: ${e::class.simpleName}: ${e.message}")
        } finally {
            engine.close()
            Log.i(TAG, "Engine closed for context size $contextSize")
        }
    }
}
