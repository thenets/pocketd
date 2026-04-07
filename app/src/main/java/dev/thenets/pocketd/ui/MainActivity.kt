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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.thenets.pocketd.service.LlmServerService
import dev.thenets.pocketd.ui.theme.PocketdTheme
import kotlinx.coroutines.launch

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

    private var modelPath by mutableStateOf(LlmServerService.DEFAULT_MODEL_PATH)
    private var port      by mutableStateOf(LlmServerService.DEFAULT_PORT.toString())
    private var token     by mutableStateOf("")

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

        setContent {
            PocketdTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    ServerControlScreen(
                        serverState       = serverState,
                        modelPath         = modelPath,
                        port              = port,
                        token             = token,
                        onModelPathChange = { modelPath = it },
                        onPortChange      = { port = it },
                        onTokenChange     = { token = it },
                        onStartClick      = { startServer() },
                        onStopClick       = { stopServer() }
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

    // ── Service control ────────────────────────────────────────────────────

    private fun startServer() {
        val portInt = port.toIntOrNull() ?: LlmServerService.DEFAULT_PORT
        ContextCompat.startForegroundService(
            this,
            LlmServerService.startIntent(
                context     = this,
                modelPath   = modelPath,
                port        = portInt,
                bearerToken = token.ifBlank { null }
            )
        )
    }

    private fun stopServer() {
        stopService(LlmServerService.stopIntent(this))
    }
}

// ── Composable UI ──────────────────────────────────────────────────────────────

@Composable
private fun ServerControlScreen(
    serverState: LlmServerService.ServerState,
    modelPath: String,
    port: String,
    token: String,
    onModelPathChange: (String) -> Unit,
    onPortChange:      (String) -> Unit,
    onTokenChange:     (String) -> Unit,
    onStartClick: () -> Unit,
    onStopClick:  () -> Unit
) {
    val isRunning = serverState is LlmServerService.ServerState.Running

    Column(
        modifier              = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement   = Arrangement.spacedBy(12.dp),
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Text(
            text  = "pocketd",
            style = MaterialTheme.typography.headlineLarge
        )

        // Status card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val (label, colour) = when (serverState) {
                    is LlmServerService.ServerState.Running  ->
                        "Running on port ${serverState.port}" to MaterialTheme.colorScheme.primary
                    is LlmServerService.ServerState.Starting ->
                        "Starting…" to MaterialTheme.colorScheme.secondary
                    is LlmServerService.ServerState.Error    ->
                        "Error: ${serverState.message}" to MaterialTheme.colorScheme.error
                    LlmServerService.ServerState.Stopped     ->
                        "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(text = label, color = colour, style = MaterialTheme.typography.bodyLarge)
                if (serverState is LlmServerService.ServerState.Running) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = serverState.modelPath,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Config fields — editable only when the server is stopped
        OutlinedTextField(
            value         = modelPath,
            onValueChange = onModelPathChange,
            label         = { Text("Model path") },
            enabled       = !isRunning,
            modifier      = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value         = port,
            onValueChange = onPortChange,
            label         = { Text("Port") },
            enabled       = !isRunning,
            modifier      = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value         = token,
            onValueChange = onTokenChange,
            label         = { Text("Bearer token (optional)") },
            enabled       = !isRunning,
            modifier      = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartClick,
                enabled = !isRunning
            ) { Text("Start Server") }

            OutlinedButton(
                onClick = onStopClick,
                enabled = isRunning
            ) { Text("Stop Server") }
        }
    }
}
