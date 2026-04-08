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
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_DIR = "/sdcard/Download"
        const val MODEL_PATH = "$MODEL_DIR/$MODEL_FILENAME"
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    var downloadJob: Job? = null

    fun checkModelExists(): Boolean {
        val file = File(MODEL_PATH)
        val exists = file.exists() && file.length() > 0
        _state.value = if (exists) DownloadState.Complete else DownloadState.Idle
        return exists
    }

    suspend fun download(hfToken: String? = null) = withContext(Dispatchers.IO) {
        cancelled = false
        val tmpFile = File("$MODEL_PATH.tmp")
        var connection: HttpURLConnection? = null

        try {
            // Clean up any previous partial download
            tmpFile.delete()

            val url = URL(MODEL_URL)
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

            File(MODEL_DIR).mkdirs()
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
            if (!tmpFile.renameTo(File(MODEL_PATH))) {
                tmpFile.delete()
                _state.value = DownloadState.Failed("Failed to save model file")
                return@withContext
            }

            _state.value = DownloadState.Complete
            Log.i(TAG, "Download complete: $MODEL_PATH (${bytesDownloaded} bytes)")

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
