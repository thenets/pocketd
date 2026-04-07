package dev.thenets.pocketd.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.thenets.pocketd.llm.LlmEngine
import dev.thenets.pocketd.server.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LlmServerService"

class LlmServerService : Service() {

    // ── Intent extras & defaults ───────────────────────────────────────────

    companion object {
        const val EXTRA_MODEL_PATH    = "model_path"
        const val EXTRA_PORT          = "port"
        const val EXTRA_BEARER_TOKEN  = "bearer_token"
        const val EXTRA_IDLE_TIMEOUT  = "idle_timeout_ms"

        const val DEFAULT_MODEL_PATH   = "/sdcard/Download/gemma-4-E2B-it.litertlm"
        const val DEFAULT_PORT         = 8080
        const val DEFAULT_IDLE_TIMEOUT = 5 * 60 * 1000L

        fun startIntent(
            context: Context,
            modelPath: String    = DEFAULT_MODEL_PATH,
            port: Int            = DEFAULT_PORT,
            bearerToken: String? = null,
            idleTimeoutMs: Long  = DEFAULT_IDLE_TIMEOUT
        ): Intent = Intent(context, LlmServerService::class.java).apply {
            putExtra(EXTRA_MODEL_PATH,   modelPath)
            putExtra(EXTRA_PORT,         port)
            putExtra(EXTRA_BEARER_TOKEN, bearerToken)
            putExtra(EXTRA_IDLE_TIMEOUT, idleTimeoutMs)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, LlmServerService::class.java)
    }

    // ── Binder (for MainActivity to observe state) ─────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): LlmServerService = this@LlmServerService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // ── Observable state ───────────────────────────────────────────────────

    sealed class ServerState {
        object Stopped  : ServerState()
        object Starting : ServerState()
        data class Running(val port: Int, val modelPath: String) : ServerState()
        data class Error(val message: String) : ServerState()
    }

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    // ── Engine & server ────────────────────────────────────────────────────

    private var llmEngine:  LlmEngine?  = null
    private var httpServer: HttpServer? = null

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent may be null if system restarts a START_STICKY service
        val modelPath   = intent?.getStringExtra(EXTRA_MODEL_PATH)            ?: DEFAULT_MODEL_PATH
        val port        = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT)       ?: DEFAULT_PORT
        val bearerToken = intent?.getStringExtra(EXTRA_BEARER_TOKEN)
        val idleTimeout = intent?.getLongExtra(EXTRA_IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT)
                          ?: DEFAULT_IDLE_TIMEOUT

        // Promote to foreground BEFORE doing any work (required by Android 12+)
        promoteToForeground(port)

        _serverState.value = ServerState.Starting

        try {
            tearDown()

            llmEngine  = LlmEngine(modelPath = modelPath, idleTimeoutMs = idleTimeout)
            httpServer = HttpServer(
                llmEngine   = llmEngine!!,
                port        = port,
                bearerToken = bearerToken
            )
            httpServer!!.start()

            _serverState.value = ServerState.Running(port = port, modelPath = modelPath)
            Log.i(TAG, "Server running — model=$modelPath port=$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            _serverState.value = ServerState.Error(e.message ?: "Unknown error")
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        tearDown()
        _serverState.value = ServerState.Stopped
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun promoteToForeground(port: Int) {
        val notification = NotificationHelper.buildNotification(this, port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun tearDown() {
        runCatching { httpServer?.stop() }
        runCatching { llmEngine?.close() }
        httpServer = null
        llmEngine  = null
    }
}
