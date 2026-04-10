package dev.thenets.pocketd.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.thenets.pocketd.download.DownloadState
import dev.thenets.pocketd.download.ModelDownloader
import dev.thenets.pocketd.llm.BackendType
import dev.thenets.pocketd.model.ApiLogEntry
import dev.thenets.pocketd.service.LlmServerService
import dev.thenets.pocketd.ui.theme.PocketdTheme
import dev.thenets.pocketd.util.NetworkAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Screen navigation ─────────────────────────────────────────────────────────

private sealed class AppScreen {
    object Main : AppScreen()
    object ActivityLog : AppScreen()
    data class RequestDetail(val entryId: Long) : AppScreen()
}

// ── Context size options ──────────────────────────────────────────────────────

data class ContextSizeOption(
    val tokens: Int,
    val label: String,
    val estimatedRamMb: Int,
    val description: String
)

val CONTEXT_SIZE_OPTIONS = listOf(
    ContextSizeOption(512,    "512 tokens",    10,   "Minimal — short replies"),
    ContextSizeOption(1024,   "1,024 tokens",  20,   "Basic — single-turn Q&A"),
    ContextSizeOption(2048,   "2,048 tokens",  40,   "Standard — multi-turn"),
    ContextSizeOption(4096,   "4,096 tokens",  80,   "Extended — longer context"),
    ContextSizeOption(8192,   "8,192 tokens",  160,  "Large — 4+ GB device RAM"),
    ContextSizeOption(16384,  "16,384 tokens", 320,  "Very large — 8+ GB device RAM"),
    ContextSizeOption(32768,  "32,768 tokens", 640,  "Huge — 12+ GB device RAM"),
    ContextSizeOption(65536,  "65,536 tokens", 1280, "Maximum — 24+ GB device RAM"),
)
// Note: RAM estimates are for KV cache only (on top of ~2 GB base model).
// Actual max context depends on what the model was compiled to support.

fun bestContextSize(totalRamMb: Long): ContextSizeOption = when {
    totalRamMb >= 24 * 1024 -> CONTEXT_SIZE_OPTIONS[7]  // 65 536 — 24+ GB
    totalRamMb >= 12 * 1024 -> CONTEXT_SIZE_OPTIONS[6]  // 32 768 — 12+ GB
    totalRamMb >= 8  * 1024 -> CONTEXT_SIZE_OPTIONS[5]  // 16 384 —  8+ GB
    totalRamMb >= 4  * 1024 -> CONTEXT_SIZE_OPTIONS[4]  //  8 192 —  4+ GB
    totalRamMb >= 3  * 1024 -> CONTEXT_SIZE_OPTIONS[3]  //  4 096 —  3+ GB
    totalRamMb >= 2560      -> CONTEXT_SIZE_OPTIONS[2]  //  2 048 — 2.5+ GB
    totalRamMb >= 2200      -> CONTEXT_SIZE_OPTIONS[1]  //  1 024 — 2.2+ GB
    else                    -> CONTEXT_SIZE_OPTIONS[0]  //    512 — fallback
}

// ── Memory stats ──────────────────────────────────────────────────────────────

data class MemoryStats(
    val totalMb: Long,
    val availMb: Long,
    val appPssMb: Long,
    val modelFileMb: Long,
    val kvCacheMb: Int,
    val modelLoaded: Boolean
) {
    val otherAppMb: Long  get() = maxOf(0L, appPssMb - modelFileMb - kvCacheMb)
    val systemUsedMb: Long get() = maxOf(0L, totalMb - availMb - appPssMb)
    val freeMb: Long       get() = availMb
}

@Composable
private fun rememberMemoryStats(
    context: android.content.Context,
    modelFileMb: Long,
    kvCacheMb: Int
): MemoryStats {
    var stats by remember {
        mutableStateOf(
            MemoryStats(0L, 0L, 0L, modelFileMb, kvCacheMb, false)
        )
    }
    LaunchedEffect(modelFileMb, kvCacheMb) {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        while (true) {
            am.getMemoryInfo(memInfo)
            val totalMb  = memInfo.totalMem  / (1024L * 1024L)
            val availMb  = memInfo.availMem  / (1024L * 1024L)
            val appPssMb = Debug.getPss()    / 1024L
            val nativeAllocMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
            // Model is considered resident once native allocation exceeds 50% of file size
            val modelLoaded = modelFileMb > 0L && nativeAllocMb > (modelFileMb / 2L)
            stats = MemoryStats(
                totalMb    = totalMb,
                availMb    = availMb,
                appPssMb   = appPssMb,
                modelFileMb = modelFileMb,
                kvCacheMb  = kvCacheMb,
                modelLoaded = modelLoaded
            )
            delay(2000)
        }
    }
    return stats
}

// ── HF Token state ────────────────────────────────────────────────────────────

enum class HfTokenState { Empty, Entered, Invalid }

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    // ── Service binding ────────────────────────────────────────────────────

    private var boundService: LlmServerService? = null
    private var serverState by mutableStateOf<LlmServerService.ServerState>(
        LlmServerService.ServerState.Stopped
    )
    private var apiLog by mutableStateOf<List<ApiLogEntry>>(emptyList())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boundService = (binder as LlmServerService.LocalBinder).getService()
            lifecycleScope.launch {
                boundService!!.serverState.collect { state ->
                    serverState = state
                }
            }
            lifecycleScope.launch {
                boundService!!.apiLog.collect { log ->
                    apiLog = log
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            boundService = null
            serverState = LlmServerService.ServerState.Stopped
            apiLog = emptyList()
        }
    }

    // ── UI state ───────────────────────────────────────────────────────────

    private var port    by mutableStateOf(LlmServerService.DEFAULT_PORT.toString())
    private var token   by mutableStateOf("")
    private var hfToken by mutableStateOf("")
    private var selectedContextSize by mutableStateOf(CONTEXT_SIZE_OPTIONS[2]) // overridden in onCreate
    private var selectedBackend by mutableStateOf(BackendType.GPU_WITH_CPU_FALLBACK)
    private var storagePermissionGranted by mutableStateOf(false)

    private fun checkStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    // ── Download state ─────────────────────────────────────────────────────

    private val modelDownloader = ModelDownloader()
    private var downloadState by mutableStateOf<DownloadState>(DownloadState.Idle)

    // ── Permissions ────────────────────────────────────────────────────────

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        storagePermissionGranted = checkStoragePermission()

        // Pick the best context size for this device's RAM
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        selectedContextSize = bestContextSize(memInfo.totalMem / (1024L * 1024L))

        // Restore persisted HF token
        val prefs = getSharedPreferences("pocketd_prefs", Context.MODE_PRIVATE)
        hfToken = prefs.getString("hf_token", "") ?: ""

        // Check if model already exists
        modelDownloader.checkModelExists()

        // Collect download state
        lifecycleScope.launch {
            modelDownloader.state.collect { downloadState = it }
        }

        setContent {
            PocketdTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    ServerControlScreen(
                        serverState              = serverState,
                        downloadState            = downloadState,
                        apiLog                   = apiLog,
                        port                     = port,
                        token                    = token,
                        hfToken                  = hfToken,
                        selectedContextSize      = selectedContextSize,
                        selectedBackend          = selectedBackend,
                        storagePermissionGranted = storagePermissionGranted,
                        onPortChange             = { port = it },
                        onTokenChange            = { token = it },
                        onHfTokenChange          = {
                            hfToken = it
                            prefs.edit().putString("hf_token", it).apply()
                        },
                        onContextSizeChange      = { selectedContextSize = it },
                        onBackendChange          = { selectedBackend = it },
                        onStartClick             = { startServer() },
                        onStopClick              = { stopServer() },
                        onDownloadClick          = { startDownload() },
                        onCancelDownload         = { modelDownloader.cancel() },
                        onGrantStoragePermission = { openAllFilesAccess() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check in case the user just returned from the All Files Access settings screen
        storagePermissionGranted = checkStoragePermission()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, LlmServerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        boundService = null
    }

    override fun onDestroy() {
        modelDownloader.cancel()
        super.onDestroy()
    }

    // ── Service control ────────────────────────────────────────────────────

    private fun startServer() {
        // Request battery optimization exemption so the server survives Doze
        requestBatteryOptimizationExemption()

        val portInt = port.toIntOrNull() ?: LlmServerService.DEFAULT_PORT
        ContextCompat.startForegroundService(
            this,
            LlmServerService.startIntent(
                context     = this,
                modelPath   = ModelDownloader.MODEL_PATH,
                port        = portInt,
                bearerToken = token.ifBlank { null },
                contextSize = selectedContextSize.tokens,
                backend     = selectedBackend
            )
        )
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun stopServer() {
        // Unbind first — BIND_AUTO_CREATE keeps the service alive while bound
        boundService = null
        runCatching { unbindService(connection) }
        stopService(Intent(this, LlmServerService::class.java))
        // Re-bind so we can observe state if the service is started again
        bindService(
            Intent(this, LlmServerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun startDownload() {
        val job = lifecycleScope.launch {
            modelDownloader.download(hfToken = hfToken.ifBlank { null })
        }
        modelDownloader.downloadJob = job
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L              -> "$bytes B"
    bytes < 1024L * 1024       -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else                        -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec < 1024L * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
    else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024))
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0   -> "${h}h ${m}m"
        m > 0   -> "${m}m ${s}s"
        else    -> "< 1m"
    }
}

// ── Main screen composable ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerControlScreen(
    serverState: LlmServerService.ServerState,
    downloadState: DownloadState,
    apiLog: List<ApiLogEntry>,
    port: String,
    token: String,
    hfToken: String,
    selectedContextSize: ContextSizeOption,
    selectedBackend: BackendType,
    storagePermissionGranted: Boolean,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onHfTokenChange: (String) -> Unit,
    onContextSizeChange: (ContextSizeOption) -> Unit,
    onBackendChange: (BackendType) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelDownload: () -> Unit,
    onGrantStoragePermission: () -> Unit
) {
    var appScreen by remember { mutableStateOf<AppScreen>(AppScreen.Main) }

    when (val screen = appScreen) {
        is AppScreen.ActivityLog -> {
            ApiActivityLogScreen(
                apiLog   = apiLog,
                onBack   = { appScreen = AppScreen.Main },
                onSelect = { appScreen = AppScreen.RequestDetail(it.id) }
            )
            return
        }
        is AppScreen.RequestDetail -> {
            val entry = apiLog.find { it.id == screen.entryId }
            if (entry != null) {
                ApiRequestDetailScreen(
                    entry  = entry,
                    onBack = { appScreen = AppScreen.ActivityLog }
                )
            } else {
                appScreen = AppScreen.ActivityLog
            }
            return
        }
        is AppScreen.Main -> { /* fall through to main content */ }
    }

    val isRunning = serverState is LlmServerService.ServerState.Running
    val modelReady = downloadState is DownloadState.Complete

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("pocketd") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!storagePermissionGranted) {
                StoragePermissionCard(onGrant = onGrantStoragePermission)
            }

            StatusCard(serverState)

            AnimatedVisibility(
                visible = serverState is LlmServerService.ServerState.Running,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (serverState is LlmServerService.ServerState.Running) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NetworkAddressesCard(serverState.addresses, serverState.port)
                        ApiActivityCard(
                            apiLog      = apiLog,
                            onViewAll   = { appScreen = AppScreen.ActivityLog },
                            onSelectEntry = { appScreen = AppScreen.RequestDetail(it.id) }
                        )
                    }
                }
            }

            ModelCard(
                downloadState = downloadState,
                hfToken = hfToken,
                onHfTokenChange = onHfTokenChange,
                onDownloadClick = onDownloadClick,
                onCancelDownload = onCancelDownload
            )

            ConfigurationCard(
                port = port,
                token = token,
                selectedContextSize = selectedContextSize,
                selectedBackend = selectedBackend,
                isRunning = isRunning,
                modelReady = modelReady,
                onPortChange = onPortChange,
                onTokenChange = onTokenChange,
                onContextSizeChange = onContextSizeChange,
                onBackendChange = onBackendChange,
                onStartClick = onStartClick,
                onStopClick = onStopClick
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Storage Permission Card ───────────────────────────────────────────────────

@Composable
private fun StoragePermissionCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Storage access required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "pocketd needs \"All files access\" to read the model file from /sdcard/Download/. " +
                "Without it every inference request will fail with a 500 error.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onGrant,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor   = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Grant All Files Access")
            }
        }
    }
}

// ── Status Card ───────────────────────────────────────────────────────────────

@Composable
private fun StatusCard(serverState: LlmServerService.ServerState) {
    val containerColor = when (serverState) {
        is LlmServerService.ServerState.Running ->
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        is LlmServerService.ServerState.Error ->
            MaterialTheme.colorScheme.errorContainer
        else -> CardDefaults.cardColors().containerColor
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(16.dp)) {
            when (serverState) {
                is LlmServerService.ServerState.Stopped -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.width(8.dp))
                        Text("Server Stopped",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                is LlmServerService.ServerState.Starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Starting Server...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Loading model into memory...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                is LlmServerService.ServerState.Running -> {
                    var uptimeMs by remember { mutableLongStateOf(0L) }
                    val startTime = remember { System.currentTimeMillis() }
                    LaunchedEffect(Unit) {
                        while (true) {
                            uptimeMs = System.currentTimeMillis() - startTime
                            delay(1000)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(Color(0xFF4CAF50))
                        Spacer(Modifier.width(8.dp))
                        Text("Server Running",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text("UP ${formatDuration(uptimeMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth()) {
                        StatItem(Modifier.weight(1f), "Port", "${serverState.port}")
                        StatItem(Modifier.weight(1f), "Context", "${serverState.contextSize} tk")
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // ── Memory breakdown ──────────────────────────────────
                    val kvCacheMb = CONTEXT_SIZE_OPTIONS
                        .firstOrNull { it.tokens == serverState.contextSize }
                        ?.estimatedRamMb ?: 40
                    val mem = rememberMemoryStats(
                        context     = androidx.compose.ui.platform.LocalContext.current,
                        modelFileMb = serverState.modelFileSizeMb,
                        kvCacheMb   = kvCacheMb
                    )

                    Text("Memory",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))

                    MemoryBar(mem)
                    Spacer(Modifier.height(8.dp))

                    MemoryLegendRow(COLOR_MODEL, "Model weights", mem.modelFileMb)
                    MemoryLegendRow(COLOR_KV,  "KV cache (context)", mem.kvCacheMb.toLong())
                    if (mem.otherAppMb > 0)
                        MemoryLegendRow(COLOR_APP, "App overhead", mem.otherAppMb)
                    MemoryLegendRow(COLOR_SYS, "Other system use", mem.systemUsedMb)
                    MemoryLegendRow(COLOR_FREE, "Free", mem.freeMb)

                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (mem.totalMb > 0) "Total: ${mem.totalMb} MB device RAM" else "Measuring…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    Text("Model", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(serverState.modelPath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                is LlmServerService.ServerState.Error -> {
                    Text("Server Error",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(8.dp))
                    Text(serverState.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        drawCircle(color = color, center = Offset(size.width / 2, size.height / 2))
    }
}

// ── Memory bar ────────────────────────────────────────────────────────────────

private val COLOR_MODEL   = Color(0xFF7C4DFF)  // deep purple — model weights
private val COLOR_KV      = Color(0xFF00BCD4)  // teal        — KV cache
private val COLOR_APP     = Color(0xFF2196F3)  // blue        — other app
private val COLOR_SYS     = Color(0xFF546E7A)  // blue-grey   — other system
private val COLOR_FREE    = Color(0xFF1B2A30)  // near-black  — free

@Composable
private fun MemoryBar(stats: MemoryStats) {
    if (stats.totalMb == 0L) return

    data class Seg(val mb: Float, val color: Color)
    val segments = listOf(
        Seg(stats.modelFileMb.toFloat(),    COLOR_MODEL),
        Seg(stats.kvCacheMb.toFloat(),      COLOR_KV),
        Seg(stats.otherAppMb.toFloat(),     COLOR_APP),
        Seg(stats.systemUsedMb.toFloat(),   COLOR_SYS),
        Seg(stats.freeMb.toFloat(),         COLOR_FREE)
    )
    // Use sum of segments as denominator so the bar always fills completely,
    // even when model file size exceeds device RAM (memory-mapped models).
    val total = maxOf(1f, segments.sumOf { it.mb.toDouble() }.toFloat())

    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            var x = 0f
            segments.forEach { seg ->
                val segW = (seg.mb / total).coerceIn(0f, 1f) * w
                if (segW < 1f) { x += segW; return@forEach }
                drawRect(
                    color   = seg.color,
                    topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                    size    = androidx.compose.ui.geometry.Size(segW, h)
                )
                x += segW
            }
        }
    }
}

@Composable
private fun MemoryLegendRow(color: Color, label: String, valueMb: Long, dimmed: Boolean = false) {
    val alpha = if (dimmed) 0.5f else 1f
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(color.copy(alpha = alpha), center = Offset(size.width / 2, size.height / 2))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.weight(1f)
        )
        Text(
            "$valueMb MB",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
    }
}

@Composable
private fun StatItem(modifier: Modifier, label: String, value: String) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
    }
}

// ── Network Addresses Card ────────────────────────────────────────────────────

@Composable
private fun NetworkAddressesCard(addresses: List<NetworkAddress>, port: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Server Addresses", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val nonLocal = addresses.filter { it.interfaceName != "lo" }
            val localhost = addresses.filter { it.interfaceName == "lo" }

            for (addr in nonLocal) { NetworkAddressRow(addr) }
            if (nonLocal.isNotEmpty() && localhost.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            for (addr in localhost) { NetworkAddressRow(addr) }
        }
    }
}

@Composable
private fun NetworkAddressRow(addr: NetworkAddress) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(80.dp)) {
            Text(addr.interfaceName,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold)
            Text(addr.interfaceType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(addr.url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Model Card ────────────────────────────────────────────────────────────────

@Composable
private fun ModelCard(
    downloadState: DownloadState,
    hfToken: String,
    onHfTokenChange: (String) -> Unit,
    onDownloadClick: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val hfTokenState = when {
        hfToken.isBlank() -> HfTokenState.Empty
        downloadState is DownloadState.Failed &&
            downloadState.message.contains("Authentication", ignoreCase = true) -> HfTokenState.Invalid
        else -> HfTokenState.Entered
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (downloadState) {
                is DownloadState.Idle -> {
                    Text("Model Not Found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(ModelDownloader.MODEL_PATH,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(ModelDownloader.MODEL_FILENAME,
                                style = MaterialTheme.typography.bodyMedium)
                            Text("LiteRT-LM format",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = onDownloadClick) {
                            Text("Download")
                        }
                    }

                    HfTokenSection(hfToken, onHfTokenChange, hfTokenState)
                }

                is DownloadState.Downloading -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading Model...",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancelDownload) { Text("Cancel") }
                    }

                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadState.progressFraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${downloadState.progressPercent}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                        Text(formatSpeed(downloadState.speedBytesPerSec),
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${formatBytes(downloadState.bytesDownloaded)} / ${formatBytes(downloadState.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val eta = downloadState.etaMs
                        Text(if (eta > 0) "ETA ${formatDuration(eta)}" else "--:--",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                is DownloadState.Complete -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(Color(0xFF4CAF50))
                        Spacer(Modifier.width(8.dp))
                        Text("Model Ready", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    InfoRow("File", ModelDownloader.MODEL_FILENAME)
                    InfoRow("Path", ModelDownloader.MODEL_PATH)
                }

                is DownloadState.Failed -> {
                    Text("Download Failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(downloadState.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = onDownloadClick) { Text("Retry") }

                    HfTokenSection(hfToken, onHfTokenChange, hfTokenState)
                }
            }
        }
    }
}

// ── HuggingFace Token Section ─────────────────────────────────────────────────

@Composable
private fun HfTokenSection(
    hfToken: String,
    onHfTokenChange: (String) -> Unit,
    tokenState: HfTokenState
) {
    var expanded by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    // Auto-expand when token is invalid
    LaunchedEffect(tokenState) {
        if (tokenState == HfTokenState.Invalid) expanded = true
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // Header row — tap to expand/collapse
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = when (tokenState) {
                    HfTokenState.Empty -> MaterialTheme.colorScheme.onSurfaceVariant
                    HfTokenState.Entered -> MaterialTheme.colorScheme.primary
                    HfTokenState.Invalid -> MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.width(8.dp))
            Text("HuggingFace Token",
                style = MaterialTheme.typography.labelLarge,
                color = when (tokenState) {
                    HfTokenState.Invalid -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            // Show masked preview when collapsed + token entered
            if (!expanded && tokenState == HfTokenState.Entered) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text("hf_****${hfToken.takeLast(4)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }

        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Expandable content
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("This model is gated on HuggingFace. You need an access token with read permissions to download it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = hfToken,
                onValueChange = onHfTokenChange,
                label = { Text("Access token") },
                placeholder = { Text("hf_...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = tokenState == HfTokenState.Invalid,
                supportingText = if (tokenState == HfTokenState.Invalid) {
                    { Text("Token was rejected. Check that it has read access.") }
                } else null,
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible)
                                Icons.Outlined.Visibility
                            else
                                Icons.Outlined.VisibilityOff,
                            contentDescription = if (passwordVisible) "Hide token" else "Show token"
                        )
                    }
                }
            )

            // "Get a token" helper link
            val uriHandler = LocalUriHandler.current
            Row(
                modifier = Modifier
                    .clickable { uriHandler.openUri("https://huggingface.co/settings/tokens") }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text("Get a token at huggingface.co/settings/tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp))
        Text(value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── API Activity Card ─────────────────────────────────────────────────────────

private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

private fun formatTimestamp(millis: Long): String = timeFormat.format(java.util.Date(millis))

private fun formatRequestDuration(ms: Long): String = when {
    ms < 1000  -> "${ms}ms"
    ms < 10000 -> "%.1fs".format(ms / 1000.0)
    else       -> "${ms / 1000}s"
}

@Composable
private fun ApiActivityCard(
    apiLog: List<ApiLogEntry>,
    onViewAll: () -> Unit,
    onSelectEntry: (ApiLogEntry) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("API Activity", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text("${apiLog.size} requests",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onViewAll, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("View All", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (apiLog.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("No requests yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Show last 5 entries as preview
                val preview = apiLog.takeLast(5)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    preview.forEach { entry ->
                        ApiLogRow(entry, onClick = { onSelectEntry(entry) })
                    }
                }
                if (apiLog.size > 5) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${apiLog.size - 5} older requests — tap View All",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiLogRow(entry: ApiLogEntry, onClick: (() -> Unit)? = null) {
    val statusColor = when (entry.statusCode) {
        in 200..299 -> Color(0xFF4CAF50)
        in 400..499 -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.error
    }

    val methodColor = when (entry.method) {
        "GET"  -> MaterialTheme.colorScheme.tertiary
        "POST" -> MaterialTheme.colorScheme.primary
        else   -> MaterialTheme.colorScheme.onSurface
    }

    val rowModifier = Modifier
        .fillMaxWidth()
        .background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            RoundedCornerShape(4.dp)
        )
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 8.dp, vertical = 4.dp)

    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Timestamp
        Text(formatTimestamp(entry.timestamp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Method badge
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = methodColor.copy(alpha = 0.15f)
        ) {
            Text(entry.method,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = methodColor,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
        }

        // Path (truncated)
        val shortPath = entry.path.removePrefix("/v1/")
        Text(shortPath,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)

        // Status code
        Text("${entry.statusCode}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = statusColor)

        // Duration
        Text(formatRequestDuration(entry.durationMs),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Token count
        if (entry.tokensGenerated != null && entry.tokensGenerated > 0) {
            Text("${entry.tokensGenerated}tk",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary)
        }

        // Streaming indicator
        if (entry.isStreaming) {
            Text("SSE",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

// ── Configuration Card ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationCard(
    port: String,
    token: String,
    selectedContextSize: ContextSizeOption,
    selectedBackend: BackendType,
    isRunning: Boolean,
    modelReady: Boolean,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onContextSizeChange: (ContextSizeOption) -> Unit,
    onBackendChange: (BackendType) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Configuration", style = MaterialTheme.typography.titleMedium)

            // Context size dropdown
            var contextExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = contextExpanded,
                onExpandedChange = { if (!isRunning) contextExpanded = it }
            ) {
                OutlinedTextField(
                    value = "${selectedContextSize.label} (+${selectedContextSize.estimatedRamMb} MB KV cache)",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isRunning,
                    label = { Text("Context size") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(contextExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = contextExpanded, onDismissRequest = { contextExpanded = false }) {
                    CONTEXT_SIZE_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("${option.label}  (+${option.estimatedRamMb} MB KV cache)",
                                        style = MaterialTheme.typography.bodyLarge)
                                    Text(option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { onContextSizeChange(option); contextExpanded = false }
                        )
                    }
                }
            }

            // Backend selector dropdown
            var backendExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = backendExpanded,
                onExpandedChange = { if (!isRunning) backendExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedBackend.displayLabel(),
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isRunning,
                    label = { Text("Inference backend") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(backendExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = backendExpanded, onDismissRequest = { backendExpanded = false }) {
                    BackendType.entries.forEach { backend ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(backend.displayLabel(),
                                        style = MaterialTheme.typography.bodyLarge)
                                    Text(backend.displayDescription(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { onBackendChange(backend); backendExpanded = false }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = port, onValueChange = onPortChange,
                label = { Text("Port") },
                enabled = !isRunning, modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = token, onValueChange = onTokenChange,
                label = { Text("Bearer token (optional)") },
                enabled = !isRunning, modifier = Modifier.fillMaxWidth()
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStartClick, enabled = !isRunning && modelReady,
                    modifier = Modifier.weight(1f)) { Text("Start Server") }
                OutlinedButton(onClick = onStopClick, enabled = isRunning,
                    modifier = Modifier.weight(1f)) { Text("Stop Server") }
            }

            if (!modelReady && !isRunning) {
                Text("Download the model file first",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun BackendType.displayLabel(): String = when (this) {
    BackendType.CPU -> "CPU"
    BackendType.GPU -> "GPU"
    BackendType.NPU -> "NPU"
    BackendType.GPU_WITH_CPU_FALLBACK -> "GPU with CPU fallback"
}

private fun BackendType.displayDescription(): String = when (this) {
    BackendType.CPU -> "Slower but most compatible"
    BackendType.GPU -> "Fast — requires OpenCL support"
    BackendType.NPU -> "Fastest — Tensor chip devices only"
    BackendType.GPU_WITH_CPU_FALLBACK -> "Try GPU first, fall back to CPU if unavailable"
}

// ── API Activity Log Screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiActivityLogScreen(
    apiLog: List<ApiLogEntry>,
    onBack: () -> Unit,
    onSelect: (ApiLogEntry) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Activity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Text("${apiLog.size} requests",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            )
        }
    ) { innerPadding ->
        if (apiLog.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No requests yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                items(apiLog.reversed(), key = { it.id }) { entry ->
                    ApiLogRow(entry, onClick = { onSelect(entry) })
                }
            }
        }
    }
}

// ── API Request Detail Screen ─────────────────────────────────────────────────

private fun prettyJson(raw: String?): String {
    if (raw == null) return "(none)"
    return try {
        if (raw.trimStart().startsWith("{")) {
            org.json.JSONObject(raw).toString(2)
        } else if (raw.trimStart().startsWith("[")) {
            org.json.JSONArray(raw).toString(2)
        } else {
            raw
        }
    } catch (_: Exception) {
        raw
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiRequestDetailScreen(
    entry: ApiLogEntry,
    onBack: () -> Unit
) {
    val statusColor = when (entry.statusCode) {
        in 200..299 -> Color(0xFF4CAF50)
        in 400..499 -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.error
    }
    val methodColor = when (entry.method) {
        "GET"  -> MaterialTheme.colorScheme.tertiary
        "POST" -> MaterialTheme.colorScheme.primary
        else   -> MaterialTheme.colorScheme.onSurface
    }
    val dateFormat = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(3.dp), color = methodColor.copy(alpha = 0.15f)) {
                            Text(entry.method,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = methodColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Text(entry.path,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Summary card ──────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Status", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${entry.statusCode}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = statusColor)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Time", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(dateFormat.format(java.util.Date(entry.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Duration", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatRequestDuration(entry.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                    if (entry.tokensGenerated != null && entry.tokensGenerated > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tokens", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${entry.tokensGenerated}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (entry.isStreaming) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Mode", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = RoundedCornerShape(3.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text("SSE streaming",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            // ── Request body ──────────────────────────────────────────────
            PayloadCard(title = "Request Body", content = prettyJson(entry.requestBody))

            // ── Response body ─────────────────────────────────────────────
            PayloadCard(title = "Response Body", content = prettyJson(entry.responseBody))

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PayloadCard(title: String, content: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(12.dp)
                )
            }
        }
    }
}

