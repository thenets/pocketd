package dev.thenets.pocketd.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
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

// ── Public types ──────────────────────────────────────────────────────────────

enum class BackendType { CPU, GPU, NPU, GPU_WITH_CPU_FALLBACK }

data class InferenceParams(
    val topK: Int = 40,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
    val systemInstruction: String? = null,
    val tools: List<dev.thenets.pocketd.model.ToolDefinition> = emptyList(),
)

data class ContentPart(
    val text: String? = null,
    val imageBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentPart) return false
        return text == other.text && imageBytes.contentEquals(other.imageBytes)
    }
    override fun hashCode(): Int {
        var result = text?.hashCode() ?: 0
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class GenerateResult(
    val text: String,
    val thinkingText: String? = null,
)

data class StreamToken(
    val text: String,
    val thinkingText: String? = null,
    val done: Boolean = false,
)

// ── Engine ────────────────────────────────────────────────────────────────────

/**
 * Thread-safe wrapper around the LiteRT-LM [Engine] with:
 *  - Lazy model loading (first request triggers load)
 *  - Configurable backend (CPU/GPU/NPU/GPU_WITH_CPU_FALLBACK)
 *  - Sampler parameters via [InferenceParams]
 *  - Thinking token extraction via Channel
 *  - Cancel support via [cancelGeneration]
 *  - Idle timeout (unloads engine after [idleTimeoutMs] ms of inactivity)
 *  - Coroutine-based API: [generate] (suspend) and [generateStream] (Flow)
 */
open class LlmEngine(
    private val modelPath: String,
    private val idleTimeoutMs: Long = 5 * 60 * 1000L,
    private val contextSize: Int = 2048,
    private val backend: BackendType = BackendType.GPU_WITH_CPU_FALLBACK,
    private val nativeLibraryDir: String? = null,
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

    // ── New public API ────────────────────────────────────────────────────

    /**
     * Runs inference with structured content and sampler parameters.
     * Returns the complete response including any thinking text.
     */
    open suspend fun generate(contents: List<ContentPart>, params: InferenceParams = InferenceParams()): GenerateResult =
        withContext(Dispatchers.IO) {
            try {
                doGenerate(contents, params)
            } catch (e: Exception) {
                if (shouldFallbackToCpu(e)) {
                    Log.w(TAG, "GPU inference failed at runtime, falling back to CPU: ${e.message}")
                    reloadWithCpu()
                    doGenerate(contents, params)
                } else throw e
            }
        }

    /**
     * Runs inference and emits partial [StreamToken]s as a [Flow].
     */
    open fun generateStream(contents: List<ContentPart>, params: InferenceParams = InferenceParams()): Flow<StreamToken> =
        kotlinx.coroutines.flow.flow {
            try {
                emitAll(doGenerateStream(contents, params))
            } catch (e: Exception) {
                if (shouldFallbackToCpu(e)) {
                    Log.w(TAG, "GPU stream failed at runtime, falling back to CPU: ${e.message}")
                    reloadWithCpu()
                    emitAll(doGenerateStream(contents, params))
                } else throw e
            }
        }

    /**
     * Cancels any in-flight generation. Safe to call from any thread.
     */
    fun cancelGeneration() {
        try {
            currentConversation?.cancelProcess()
            Log.i(TAG, "Generation cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "cancelGeneration failed: ${e.message}")
        }
    }

    // ── Legacy wrappers (delegate to new API with defaults) ───────────────

    suspend fun generate(prompt: String): String =
        generate(listOf(ContentPart(text = prompt))).text

    fun generateStream(prompt: String): Flow<String> =
        generateStream(listOf(ContentPart(text = prompt))).map { it.text }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun close() {
        idleFuture?.cancel(false)
        scheduler.shutdownNow()
        runCatching { currentConversation?.close() }
        currentConversation = null
        runCatching { engine?.close() }
        engine = null
        Log.i(TAG, "LlmEngine closed")
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private suspend fun doGenerate(contents: List<ContentPart>, params: InferenceParams): GenerateResult {
        val conv = getConversation(params)
        val hasImages = contents.any { it.imageBytes != null }
        return suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            val thinkingSb = StringBuilder()
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    sb.append(message.textContent())
                    message.thinkingContent()?.let { thinkingSb.append(it) }
                }
                override fun onDone() {
                    resetIdleTimer()
                    cont.resume(GenerateResult(
                        text = sb.toString(),
                        thinkingText = thinkingSb.toString().ifEmpty { null }
                    ))
                }
                override fun onError(throwable: Throwable) {
                    resetIdleTimer()
                    cont.resumeWithException(throwable)
                }
            }
            // Use String overload for text-only (most compatible), Contents for multimodal
            if (hasImages) {
                conv.sendMessageAsync(buildContents(contents), callback)
            } else {
                val text = contents.mapNotNull { it.text }.joinToString("")
                conv.sendMessageAsync(text, callback)
            }
            cont.invokeOnCancellation { cancelGeneration() }
        }
    }

    private fun doGenerateStream(contents: List<ContentPart>, params: InferenceParams): Flow<StreamToken> = callbackFlow {
        val conv = withContext(Dispatchers.IO) { getConversation(params) }
        val hasImages = contents.any { it.imageBytes != null }
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                val text = message.textContent()
                val thinking = message.thinkingContent()
                if (text.isNotEmpty() || thinking != null) {
                    trySend(StreamToken(text = text, thinkingText = thinking))
                }
            }
            override fun onDone() {
                resetIdleTimer()
                trySend(StreamToken(text = "", done = true))
                close()
            }
            override fun onError(throwable: Throwable) {
                resetIdleTimer()
                close(throwable)
            }
        }
        if (hasImages) {
            conv.sendMessageAsync(buildContents(contents), callback)
        } else {
            val text = contents.mapNotNull { it.text }.joinToString("")
            conv.sendMessageAsync(text, callback)
        }
        awaitClose { cancelGeneration() }
    }

    private suspend fun getConversation(params: InferenceParams = InferenceParams()) = mutex.withLock {
        if (engine == null) loadEngine()
        cancelIdleTimer()
        runCatching { currentConversation?.close() }

        // Sampler parameters (only set if non-default values provided)
        val hasSamplerOverrides = params.topK != 40 || params.topP != 0.95 || params.temperature != 1.0
        val hasSystemInstruction = params.systemInstruction != null
        val hasConfig = hasSamplerOverrides || hasSystemInstruction

        val conv = if (hasConfig) {
            val samplerConfig = if (hasSamplerOverrides) SamplerConfig(
                params.topK,
                params.topP,
                params.temperature
            ) else null

            val systemInstruction = if (hasSystemInstruction)
                Contents.of(params.systemInstruction!!)
            else null

            val config = ConversationConfig(
                systemInstruction = systemInstruction,
                samplerConfig = samplerConfig,
            )
            engine!!.createConversation(config)
        } else {
            engine!!.createConversation()
        }
        currentConversation = conv
        conv
    }

    private fun loadEngine() {
        Log.i(TAG, "Loading model: $modelPath (backend=$backend, gpuFailed=$gpuFailed)")

        when (backend) {
            BackendType.CPU -> loadWithBackend(Backend.CPU(), "CPU")
            BackendType.GPU -> loadWithBackend(Backend.GPU(), "GPU")
            BackendType.NPU -> {
                val dir = nativeLibraryDir
                    ?: throw IllegalStateException("nativeLibraryDir required for NPU backend")
                try {
                    loadWithBackend(Backend.NPU(dir), "NPU")
                } catch (e: Exception) {
                    Log.w(TAG, "NPU backend failed, falling back to CPU: ${e.message}", e)
                    loadWithBackend(Backend.CPU(), "CPU (NPU fallback)")
                }
            }
            BackendType.GPU_WITH_CPU_FALLBACK -> {
                if (!gpuFailed) {
                    try {
                        loadWithBackend(Backend.GPU(), "GPU")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "GPU backend failed: ${e::class.simpleName}: ${e.message}", e)
                        gpuFailed = true
                    }
                }
                Log.i(TAG, "Loading with CPU backend...")
                loadWithBackend(Backend.CPU(), "CPU (GPU fallback)")
            }
        }
    }

    private fun loadWithBackend(backendInstance: Backend, label: String) {
        val eng = Engine(EngineConfig(
            modelPath = modelPath,
            backend = backendInstance,
            maxNumTokens = contextSize
        ))
        eng.initialize()
        engine = eng
        Log.i(TAG, "Engine ready ($label)")
    }

    private fun shouldFallbackToCpu(e: Exception): Boolean =
        backend == BackendType.GPU_WITH_CPU_FALLBACK &&
        !gpuFailed &&
        e.message?.contains("OpenCL", ignoreCase = true) == true

    private suspend fun reloadWithCpu() = mutex.withLock {
        gpuFailed = true
        runCatching { currentConversation?.close() }
        currentConversation = null
        runCatching { engine?.close() }
        engine = null
        Log.i(TAG, "Reloading model with CPU backend: $modelPath")
        loadWithBackend(Backend.CPU(), "CPU (runtime fallback)")
    }

    private fun cancelIdleTimer() {
        idleFuture?.cancel(false)
        idleFuture = null
    }

    private fun resetIdleTimer() {
        cancelIdleTimer()
        idleFuture = scheduler.schedule({
            Log.i(TAG, "Idle timeout — unloading engine")
            runCatching { currentConversation?.close() }
            currentConversation = null
            runCatching { engine?.close() }
            engine = null
        }, idleTimeoutMs, TimeUnit.MILLISECONDS)
    }
}

// ── Content builders ─────────────────────────────────────────────────────────

private fun buildContents(parts: List<ContentPart>): Contents {
    val contentList = mutableListOf<Content>()
    for (part in parts) {
        if (part.text != null) {
            contentList.add(Content.Text(part.text))
        }
        if (part.imageBytes != null) {
            contentList.add(Content.ImageBytes(part.imageBytes))
        }
    }
    // If only text, use the simple factory
    if (contentList.size == 1 && contentList[0] is Content.Text) {
        return Contents.of((contentList[0] as Content.Text).text)
    }
    return Contents.of(contentList)
}

/**
 * Extracts concatenated text from a [Message]'s [Content.Text] items.
 */
private fun Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString("") { it.text }

/**
 * Extracts thinking text from a [Message]'s channels.
 */
private fun Message.thinkingContent(): String? =
    channels.get("thought")?.takeIf { it.isNotEmpty() }
