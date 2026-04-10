package dev.thenets.pocketd.download

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloader"

// ── Model catalog ────────────────────────────────────────────────────────────

data class ModelInfo(
    val name: String,
    val filename: String,
    val huggingFaceId: String,
    val commitHash: String,
    val sizeBytes: Long,
    val maxContext: Int,
    val minRamGb: Int,
    val defaultTopK: Int = 64,
    val defaultTopP: Double = 0.95,
    val defaultTemperature: Double = 1.0,
    val features: Set<String> = emptySet(),
) {
    val url: String
        get() = "https://huggingface.co/$huggingFaceId/resolve/$commitHash/$filename"
    val sizeMb: Int
        get() = (sizeBytes / (1024L * 1024L)).toInt()
    val dir: String
        get() = "$MODEL_DIR/$name"
    val path: String
        get() = "$dir/$filename"
}

const val MODEL_DIR = "/sdcard/Download/pocketd"

val AVAILABLE_MODELS = listOf(
    ModelInfo(
        name = "Gemma-4-E2B-it",
        filename = "gemma-4-E2B-it.litertlm",
        huggingFaceId = "litert-community/gemma-4-E2B-it-litert-lm",
        commitHash = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
        sizeBytes = 2_583_085_056L,
        maxContext = 32768,
        minRamGb = 8,
        features = setOf("image", "audio", "thinking"),
    ),
    ModelInfo(
        name = "Gemma-4-E4B-it",
        filename = "gemma-4-E4B-it.litertlm",
        huggingFaceId = "litert-community/gemma-4-E4B-it-litert-lm",
        commitHash = "9695417f248178c63a9f318c6e0c56cb917cb837",
        sizeBytes = 3_654_467_584L,
        maxContext = 32768,
        minRamGb = 12,
        features = setOf("image", "audio", "thinking"),
    ),
    ModelInfo(
        name = "Gemma-3n-E2B-it",
        filename = "gemma-3n-E2B-it-int4.litertlm",
        huggingFaceId = "google/gemma-3n-E2B-it-litert-lm",
        commitHash = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
        sizeBytes = 3_655_827_456L,
        maxContext = 4096,
        minRamGb = 8,
        features = setOf("image", "audio"),
    ),
    ModelInfo(
        name = "Gemma-3n-E4B-it",
        filename = "gemma-3n-E4B-it-int4.litertlm",
        huggingFaceId = "google/gemma-3n-E4B-it-litert-lm",
        commitHash = "297ed75955702dec3503e00c2c2ecbbf475300bc",
        sizeBytes = 4_919_541_760L,
        maxContext = 4096,
        minRamGb = 12,
        features = setOf("image", "audio"),
    ),
    ModelInfo(
        name = "Gemma3-1B-IT",
        filename = "gemma3-1b-it-int4.litertlm",
        huggingFaceId = "litert-community/Gemma3-1B-IT",
        commitHash = "42d538a932e8d5b12e6b3b455f5572560bd60b2c",
        sizeBytes = 584_417_280L,
        maxContext = 1024,
        minRamGb = 6,
    ),
    ModelInfo(
        name = "Qwen2.5-1.5B-Instruct",
        filename = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        huggingFaceId = "litert-community/Qwen2.5-1.5B-Instruct",
        commitHash = "19edb84c69a0212f29a6ef17ba0d6f278b6a1614",
        sizeBytes = 1_597_931_520L,
        maxContext = 4096,
        minRamGb = 6,
        defaultTopK = 20,
        defaultTopP = 0.8,
        defaultTemperature = 0.7,
    ),
    ModelInfo(
        name = "DeepSeek-R1-Distill-Qwen-1.5B",
        filename = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
        huggingFaceId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        commitHash = "e34bb88632342d1f9640bad579a45134eb1cf988",
        sizeBytes = 1_833_451_520L,
        maxContext = 4096,
        minRamGb = 6,
    ),
    ModelInfo(
        name = "TinyGarden-270M",
        filename = "tiny_garden_q8_ekv1024.litertlm",
        huggingFaceId = "litert-community/functiongemma-270m-ft-tiny-garden",
        commitHash = "c205853ff82da86141a1105faa2344a8b176dfe7",
        sizeBytes = 288_964_608L,
        maxContext = 1024,
        minRamGb = 6,
        defaultTemperature = 0.0,
        features = setOf("function-calling"),
    ),
    ModelInfo(
        name = "MobileActions-270M",
        filename = "mobile_actions_q8_ekv1024.litertlm",
        huggingFaceId = "litert-community/functiongemma-270m-ft-mobile-actions",
        commitHash = "38942192c9b723af836d489074823ff33d4a3e7a",
        sizeBytes = 288_964_608L,
        maxContext = 1024,
        minRamGb = 6,
        defaultTemperature = 0.0,
        features = setOf("function-calling"),
    ),
)

val DEFAULT_MODEL = AVAILABLE_MODELS[0] // Gemma-4-E2B-it

// ── Download state ───────────────────────────────────────────────────────────

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val elapsedMs: Long
    ) : DownloadState() {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else -1
        val progressFraction: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        val etaMs: Long
            get() = if (speedBytesPerSec > 0) ((totalBytes - bytesDownloaded) * 1000L) / speedBytesPerSec else -1L
    }
    object Complete : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

class ModelDownloader {

    companion object {
        // Legacy constants for backward compatibility
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        @Deprecated("Use ModelInfo.path instead")
        const val MODEL_DIR = "/sdcard/Download"
        @Deprecated("Use ModelInfo.path instead")
        const val MODEL_PATH = "/sdcard/Download/pocketd/Gemma-4-E2B-it/gemma-4-E2B-it.litertlm"
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }

    var selectedModel: ModelInfo = DEFAULT_MODEL

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    var downloadJob: Job? = null

    fun checkModelExists(): Boolean {
        // Check new path first, then legacy path for backward compat
        val model = selectedModel
        val file = File(model.path)
        val legacyFile = File("/sdcard/Download/${model.filename}")
        val exists = (file.exists() && file.length() > 0) ||
                     (legacyFile.exists() && legacyFile.length() > 0)
        _state.value = if (exists) DownloadState.Complete else DownloadState.Idle
        return exists
    }

    /** Returns the actual path where the model file exists (new or legacy location). */
    fun resolveModelPath(): String {
        val model = selectedModel
        val file = File(model.path)
        if (file.exists() && file.length() > 0) return model.path
        val legacyFile = File("/sdcard/Download/${model.filename}")
        if (legacyFile.exists() && legacyFile.length() > 0) return legacyFile.absolutePath
        return model.path
    }

    suspend fun download(hfToken: String? = null) = withContext(Dispatchers.IO) {
        cancelled = false
        val model = selectedModel
        val modelPath = model.path
        val tmpFile = File("$modelPath.tmp")
        var connection: HttpURLConnection? = null

        try {
            // Clean up any previous partial download
            tmpFile.delete()

            val url = URL(model.url)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "pocketd/1.0")
            if (!hfToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $hfToken")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val msg = when (responseCode) {
                    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                        "Authentication failed ($responseCode). Check your HuggingFace token."
                    HttpURLConnection.HTTP_NOT_FOUND ->
                        "Model not found at URL ($responseCode). The download link may have changed."
                    else -> "HTTP error $responseCode"
                }
                _state.value = DownloadState.Failed(msg)
                return@withContext
            }

            val totalBytes = connection.contentLengthLong
            val startTime = System.currentTimeMillis()
            var bytesDownloaded = 0L

            // Ring buffer for rolling speed calculation (last 5 samples)
            val speedSamples = LongArray(5)
            val timeSamples = LongArray(5)
            var sampleIndex = 0
            var sampleCount = 0

            _state.value = DownloadState.Downloading(0, totalBytes, 0, 0)

            File(model.dir).mkdirs()
            connection.inputStream.buffered().use { input ->
                FileOutputStream(tmpFile).buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var lastReportTime = startTime

                    while (true) {
                        if (cancelled) throw CancellationException("Download cancelled")

                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime >= 500) { // Update UI every 500ms
                            val elapsed = now - startTime

                            // Rolling speed
                            speedSamples[sampleIndex % 5] = bytesDownloaded
                            timeSamples[sampleIndex % 5] = now
                            sampleIndex++
                            sampleCount = minOf(sampleCount + 1, 5)

                            val oldestIdx = if (sampleCount < 5) 0 else sampleIndex % 5
                            val dt = now - timeSamples[oldestIdx]
                            val db = bytesDownloaded - speedSamples[oldestIdx]
                            val speed = if (dt > 0) (db * 1000) / dt else 0L

                            _state.value = DownloadState.Downloading(
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes,
                                speedBytesPerSec = speed,
                                elapsedMs = elapsed
                            )
                            lastReportTime = now
                        }
                    }
                }
            }

            // Verify download completeness
            if (totalBytes > 0 && tmpFile.length() != totalBytes) {
                tmpFile.delete()
                _state.value = DownloadState.Failed("Download incomplete: got ${tmpFile.length()} of $totalBytes bytes")
                return@withContext
            }

            // Atomic rename
            if (!tmpFile.renameTo(File(modelPath))) {
                tmpFile.delete()
                _state.value = DownloadState.Failed("Failed to save model file")
                return@withContext
            }

            _state.value = DownloadState.Complete
            Log.i(TAG, "Download complete: $modelPath (${bytesDownloaded} bytes)")

        } catch (e: CancellationException) {
            tmpFile.delete()
            _state.value = DownloadState.Idle
            Log.i(TAG, "Download cancelled")
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e(TAG, "Download failed", e)
            _state.value = DownloadState.Failed(e.message ?: "Unknown error")
        } finally {
            connection?.disconnect()
        }
    }

    fun cancel() {
        cancelled = true
        downloadJob?.cancel()
    }
}
