package dev.thenets.pocketd.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
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
 *  - GPU backend with automatic fallback to CPU
 *  - Idle timeout (unloads engine after [idleTimeoutMs] ms of inactivity)
 *  - Coroutine-based API: [generate] (suspend) and [generateStream] (Flow)
 */
class LlmEngine(
    private val modelPath: String,
    private val idleTimeoutMs: Long = 5 * 60 * 1000L
) : AutoCloseable {

    private val mutex = Mutex()
    private var engine: Engine? = null

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pocketd-idle-watchdog").also { it.isDaemon = true }
        }
    private var idleFuture: ScheduledFuture<*>? = null

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Runs inference and returns the complete response as a String.
     * Suspends on the IO dispatcher while waiting for completion.
     */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val conv = getConversation()
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            conv.sendMessageAsync(prompt, object : MessageCallback {
                override fun onMessage(message: Message) {
                    sb.append(message.text ?: "")
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
     * The flow completes when inference finishes or throws on error.
     */
    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val conv = withContext(Dispatchers.IO) { getConversation() }
        conv.sendMessageAsync(prompt, object : MessageCallback {
            override fun onMessage(message: Message) {
                message.text?.let { trySend(it) }
            }
            override fun onDone() {
                resetIdleTimer()
                close()
            }
            override fun onError(t: Throwable) {
                resetIdleTimer()
                close(t)
            }
        })
        awaitClose { /* LiteRT-LM does not expose inference cancellation */ }
    }

    override fun close() {
        idleFuture?.cancel(false)
        scheduler.shutdownNow()
        runCatching { engine?.close() }
        engine = null
        Log.i(TAG, "LlmEngine closed")
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private suspend fun getConversation() = mutex.withLock {
        if (engine == null) loadEngine()
        cancelIdleTimer()
        engine!!.createConversation()
    }

    private fun loadEngine() {
        Log.i(TAG, "Loading model: $modelPath")
        val gpuConfig = EngineConfig.Builder()
            .setModelPath(modelPath)
            .setBackend(Backend.GPU)
            .build()
        val newEngine = Engine()
        try {
            newEngine.initialize(gpuConfig)
            engine = newEngine
            Log.i(TAG, "Engine ready (GPU)")
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend failed (${e.message}), retrying with CPU")
            runCatching { newEngine.close() }
            val cpuConfig = EngineConfig.Builder()
                .setModelPath(modelPath)
                .setBackend(Backend.CPU)
                .build()
            val cpuEngine = Engine()
            cpuEngine.initialize(cpuConfig)
            engine = cpuEngine
            Log.i(TAG, "Engine ready (CPU)")
        }
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
