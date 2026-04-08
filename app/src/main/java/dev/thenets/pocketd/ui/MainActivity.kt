package dev.thenets.pocketd.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Key
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
import dev.thenets.pocketd.service.LlmServerService
import dev.thenets.pocketd.ui.theme.PocketdTheme
import dev.thenets.pocketd.util.NetworkAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

// ── HF Token state ────────────────────────────────────────────────────────────

enum class HfTokenState { Empty, Entered, Invalid }

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    // ── Service binding ────────────────────────────────────────────────────

    private var boundService: LlmServerService? = null
    private var serverState by mutableStateOf<LlmServerService.ServerState>(
        LlmServerService.ServerState.Stopped
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boundService = (binder as LlmServerService.LocalBinder).getService()
            lifecycleScope.launch {
                boundService!!.serverState.collect { state ->
                    serverState = state
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            boundService = null
            serverState = LlmServerService.ServerState.Stopped
        }
    }

    // ── UI state ───────────────────────────────────────────────────────────

    private var port    by mutableStateOf(LlmServerService.DEFAULT_PORT.toString())
    private var token   by mutableStateOf("")
    private var hfToken by mutableStateOf("")
    private var selectedContextSize by mutableStateOf(CONTEXT_SIZE_OPTIONS[2]) // 2048 default

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
                        serverState         = serverState,
                        downloadState       = downloadState,
                        port                = port,
                        token               = token,
                        hfToken             = hfToken,
                        selectedContextSize = selectedContextSize,
                        onPortChange        = { port = it },
                        onTokenChange       = { token = it },
                        onHfTokenChange     = {
                            hfToken = it
                            prefs.edit().putString("hf_token", it).apply()
                        },
                        onContextSizeChange = { selectedContextSize = it },
                        onStartClick        = { startServer() },
                        onStopClick         = { stopServer() },
                        onDownloadClick     = { startDownload() },
                        onCancelDownload    = { modelDownloader.cancel() }
                    )
                }
            }
        }
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
        val portInt = port.toIntOrNull() ?: LlmServerService.DEFAULT_PORT
        ContextCompat.startForegroundService(
            this,
            LlmServerService.startIntent(
                context     = this,
                modelPath   = ModelDownloader.MODEL_PATH,
                port        = portInt,
                bearerToken = token.ifBlank { null },
                contextSize = selectedContextSize.tokens
            )
        )
    }

    private fun stopServer() {
        stopService(LlmServerService.stopIntent(this))
    }

    private fun startDownload() {
        val job = lifecycleScope.launch {
            modelDownloader.download(hfToken = hfToken.ifBlank { null })
        }
        modelDownloader.downloadJob = job
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
    port: String,
    token: String,
    hfToken: String,
    selectedContextSize: ContextSizeOption,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onHfTokenChange: (String) -> Unit,
    onContextSizeChange: (ContextSizeOption) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelDownload: () -> Unit
) {
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
            StatusCard(serverState)

            AnimatedVisibility(
                visible = serverState is LlmServerService.ServerState.Running,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (serverState is LlmServerService.ServerState.Running) {
                    NetworkAddressesCard(serverState.addresses, serverState.port)
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
                isRunning = isRunning,
                modelReady = modelReady,
                onPortChange = onPortChange,
                onTokenChange = onTokenChange,
                onContextSizeChange = onContextSizeChange,
                onStartClick = onStartClick,
                onStopClick = onStopClick
            )

            Spacer(Modifier.height(16.dp))
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
                        StatItem(Modifier.weight(1f), "Context", "${serverState.contextSize} tokens")
                    }

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

// ── Configuration Card ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationCard(
    port: String,
    token: String,
    selectedContextSize: ContextSizeOption,
    isRunning: Boolean,
    modelReady: Boolean,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onContextSizeChange: (ContextSizeOption) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Configuration", style = MaterialTheme.typography.titleMedium)

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (!isRunning) expanded = it }
            ) {
                OutlinedTextField(
                    value = "${selectedContextSize.label} (+${selectedContextSize.estimatedRamMb} MB KV cache)",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isRunning,
                    label = { Text("Context size") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                            onClick = { onContextSizeChange(option); expanded = false }
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
