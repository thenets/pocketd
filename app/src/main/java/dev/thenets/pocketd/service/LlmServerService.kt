package dev.thenets.pocketd.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.thenets.pocketd.llm.LlmEngine
import dev.thenets.pocketd.model.ApiLogEntry
import dev.thenets.pocketd.server.HttpServer
import dev.thenets.pocketd.util.NetworkAddress
import dev.thenets.pocketd.util.NetworkUtils
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
        const val EXTRA_CONTEXT_SIZE  = "context_size"
        const val ACTION_STOP         = "dev.thenets.pocketd.STOP_SERVER"

        const val DEFAULT_MODEL_PATH   = "/sdcard/Download/gemma-4-E2B-it.litertlm"
        const val DEFAULT_PORT         = 8080
        const val DEFAULT_IDLE_TIMEOUT = 5 * 60 * 1000L
        const val DEFAULT_CONTEXT_SIZE = 2048

        fun startIntent(
            context: Context,
            modelPath: String    = DEFAULT_MODEL_PATH,
            port: Int            = DEFAULT_PORT,
            bearerToken: String? = null,
            idleTimeoutMs: Long  = DEFAULT_IDLE_TIMEOUT,
            contextSize: Int     = DEFAULT_CONTEXT_SIZE
        ): Intent = Intent(context, LlmServerService::class.java).apply {
            putExtra(EXTRA_MODEL_PATH,   modelPath)
            putExtra(EXTRA_PORT,         port)
            putExtra(EXTRA_BEARER_TOKEN, bearerToken)
            putExtra(EXTRA_IDLE_TIMEOUT, idleTimeoutMs)
            putExtra(EXTRA_CONTEXT_SIZE, contextSize)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, LlmServerService::class.java).apply {
                action = ACTION_STOP
            }
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
        data class Running(
            val port: Int,
            val modelPath: String,
            val addresses: List<NetworkAddress> = emptyList(),
            val contextSize: Int = DEFAULT_CONTEXT_SIZE,
            val modelFileSizeMb: Long = 0L
        ) : ServerState()
        data class Error(val message: String) : ServerState()
    }

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _apiLog = MutableStateFlow<List<ApiLogEntry>>(emptyList())
    val apiLog: StateFlow<List<ApiLogEntry>> = _apiLog.asStateFlow()

    private fun onRequestLogged(entry: ApiLogEntry) {
        _apiLog.value = (_apiLog.value + entry).takeLast(50)
    }

    // ── Engine & server ────────────────────────────────────────────────────

    private var llmEngine:  LlmEngine?  = null
    private var httpServer: HttpServer? = null

    // ── Wake locks ─────────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action from notification button
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested via notification")
            stopSelf()
            return START_NOT_STICKY
        }

        // intent may be null if system restarts a START_STICKY service
        val modelPath   = intent?.getStringExtra(EXTRA_MODEL_PATH)            ?: DEFAULT_MODEL_PATH
        val port        = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT)       ?: DEFAULT_PORT
        val bearerToken = intent?.getStringExtra(EXTRA_BEARER_TOKEN)
        val idleTimeout = intent?.getLongExtra(EXTRA_IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT)
                          ?: DEFAULT_IDLE_TIMEOUT
        val contextSize = intent?.getIntExtra(EXTRA_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE)
                          ?: DEFAULT_CONTEXT_SIZE

        // Promote to foreground BEFORE doing any work (required by Android 12+)
        promoteToForeground(port)

        _serverState.value = ServerState.Starting

        try {
            tearDown()

            // Acquire locks to keep CPU and WiFi alive
            acquireWakeLock()
            acquireWifiLock()

            llmEngine  = LlmEngine(modelPath = modelPath, idleTimeoutMs = idleTimeout, contextSize = contextSize)
            httpServer = HttpServer(
                llmEngine       = llmEngine!!,
                port            = port,
                bearerToken     = bearerToken,
                onRequestLogged = ::onRequestLogged
            )
            httpServer!!.start()

            val addresses = NetworkUtils.getServerAddresses(port)
            val modelFileSizeMb = runCatching {
                java.io.File(modelPath).length() / (1024L * 1024L)
            }.getOrDefault(0L)
            _serverState.value = ServerState.Running(
                port = port,
                modelPath = modelPath,
                addresses = addresses,
                contextSize = contextSize,
                modelFileSizeMb = modelFileSizeMb
            )
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
        releaseWakeLock()
        releaseWifiLock()
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

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "pocketd::ServerWakeLock"
            ).apply {
                acquire()
            }
            Log.i(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "pocketd::ServerWifiLock"
            ).apply {
                acquire()
            }
            Log.i(TAG, "WifiLock acquired")
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "WifiLock released")
        }
        wifiLock = null
    }

    private fun tearDown() {
        runCatching { httpServer?.stop() }
        runCatching { llmEngine?.close() }
        httpServer = null
        llmEngine  = null
        _apiLog.value = emptyList()
    }
}
