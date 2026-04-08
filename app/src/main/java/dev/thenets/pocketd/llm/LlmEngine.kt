package dev.thenets.pocketd.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "LlmEngine"

/**
 * Thread-safe wrapper around the LiteRT-LM [Engine] with:
 *  - Lazy model loading (first request triggers load)
 *  - GPU backend with automatic CPU fallback
 *  - Idle timeout (unloads engine after [idleTimeoutMs] ms of inactivity)
 *  - Coroutine-based API: [generate] (suspend) and [generateStream] (Flow)
 *
 * LiteRT-LM 0.10.0 API notes:
 *  - Engine(EngineConfig) — config passed to constructor
 *  - engine.initialize()  — no-arg; separate blocking call
 *  - Backend.GPU() / Backend.CPU() — instantiate as data classes
 *  - Message.contents.contents — List<Content>; text via Content.Text.text
 */
class LlmEngine(
    private val modelPath: String,
    private val idleTimeoutMs: Long = 5 * 60 * 1000L,
    private val contextSize: Int = 2048
) : AutoCloseable {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var currentConversation: com.google.ai.edge.litertlm.Conversation? = null
    @Volatile private var gpuFailed = false

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pocketd-idle-watchdog").also { it.isDaemon = true }
        }
    private var idleFuture: ScheduledFuture<*>? = null

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Runs inference and returns the complete response string.
     * Suspends on the IO dispatcher until done.
     */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            doGenerate(prompt)
        } catch (e: Exception) {
            if (!gpuFailed && e.message?.contains("OpenCL", ignoreCase = true) == true) {
                Log.w(TAG, "GPU inference failed at runtime, falling back to CPU: ${e.message}")
                reloadWithCpu()
                doGenerate(prompt)
            } else throw e
        }
    }

    private suspend fun doGenerate(prompt: String): String {
        val conv = getConversation()
        return suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            conv.sendMessageAsync(prompt, object : MessageCallback {
                override fun onMessage(message: Message) {
                    sb.append(message.textContent())
                }
                override fun onDone() {
                    resetIdleTimer()
                    cont.resume(sb.toString())
                }
                override fun onError(t: Throwable) {
                    resetIdleTimer()
                    cont.resumeWithException(t)
                }
            })
        }
    }

    /**
     * Runs inference and emits partial tokens as a [Flow].
     * The flow completes when inference finishes, or throws on error.
     */
    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val conv = withContext(Dispatchers.IO) { getConversation() }
        conv.sendMessageAsync(prompt, object : MessageCallback {
            override fun onMessage(message: Message) {
                val text = message.textContent()
                if (text.isNotEmpty()) trySend(text)
            }
            override fun onDone() {
                resetIdleTimer()
                close()
            }
            override fun onError(t: Throwable) {
                resetIdleTimer()
                if (!gpuFailed && t.message?.contains("OpenCL", ignoreCase = true) == true) {
                    Log.w(TAG, "GPU stream failed at runtime, falling back to CPU: ${t.message}")
                    // Signal the caller to retry — close with the error so HttpServer retries
                }
                close(t)
            }
        })
        awaitClose { /* LiteRT-LM does not expose inference cancellation */ }
    }

    override fun close() {
        idleFuture?.cancel(false)
        scheduler.shutdownNow()
        runCatching { currentConversation?.close() }
        currentConversation = null
        runCatching { engine?.close() }
        engine = null
        Log.i(TAG, "LlmEngine closed")
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private suspend fun getConversation() = mutex.withLock {
        if (engine == null) loadEngine()
        cancelIdleTimer()
        // LiteRT-LM only supports one session at a time — close the old one
        runCatching { currentConversation?.close() }
        val conv = engine!!.createConversation()
        currentConversation = conv
        conv
    }

    private fun loadEngine() {
        Log.i(TAG, "Loading model: $modelPath")
        // Engine(EngineConfig) — config is passed to constructor in 0.10.0
        val gpuEngine = Engine(EngineConfig(modelPath = modelPath, backend = Backend.GPU()))
        try {
            gpuEngine.initialize()
            engine = gpuEngine
            Log.i(TAG, "Engine ready (GPU)")
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend failed (${e.message}), retrying with CPU")
            runCatching { gpuEngine.close() }
            val cpuEngine = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
            cpuEngine.initialize()
            engine = cpuEngine
            Log.i(TAG, "Engine ready (CPU)")
        }
    }

    private suspend fun reloadWithCpu() = mutex.withLock {
        gpuFailed = true
        runCatching { currentConversation?.close() }
        currentConversation = null
        runCatching { engine?.close() }
        engine = null
        Log.i(TAG, "Reloading model with CPU backend: $modelPath")
        val cpuEngine = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
        cpuEngine.initialize()
        engine = cpuEngine
        Log.i(TAG, "Engine ready (CPU fallback)")
    }

    private fun cancelIdleTimer() {
        idleFuture?.cancel(false)
        idleFuture = null
    }

    private fun resetIdleTimer() {
        cancelIdleTimer()
        idleFuture = scheduler.schedule({
            Log.i(TAG, "Idle timeout — unloading engine")
            synchronized(this) {
                runCatching { engine?.close() }
                engine = null
            }
        }, idleTimeoutMs, TimeUnit.MILLISECONDS)
    }
}

/**
 * Extracts concatenated text from a [Message]'s [Content.Text] items.
 * In LiteRT-LM 0.10.0, Message has no direct `.text` property;
 * text lives in `message.contents.contents` as `Content.Text` instances.
 */
private fun Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString("") { it.text }
