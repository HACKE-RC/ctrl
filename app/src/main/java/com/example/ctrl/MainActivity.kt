package com.example.ctrl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ctrl.capture.ScreenCaptureService
import com.example.ctrl.security.AllowlistStore
import com.example.ctrl.security.AllowlistConfig
import com.example.ctrl.server.McpServerService
import com.example.ctrl.server.ServerState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CtrlApp()
        }
    }
}

@Composable
fun CtrlApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            RemoteControlScreen()
        }
    }
}

@Composable
private fun RemoteControlScreen() {
    val context = LocalContext.current
    val allowlistStore = remember { AllowlistStore(context.applicationContext) }
    val allowlist by allowlistStore.configFlow.collectAsState(initial = AllowlistConfig(enabled = true, entries = emptyList(), lastBlockedIp = null))
    val serverStatus by ServerState.status.collectAsState()
    val scope = rememberCoroutineScope()

    var newEntry by remember { mutableStateOf("") }

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            ScreenCaptureService.start(context.applicationContext, res.resultCode, res.data!!)
        }
    }

    var pendingAfterNotifications by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAfterNotifications
        pendingAfterNotifications = null
        if (granted) action?.invoke()
    }

    fun runWithNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            action()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
            return
        }
        pendingAfterNotifications = action
        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "CTRL Remote Control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(text = "MCP endpoint: http://<phone-ip>:${serverStatus.port}/mcp", modifier = Modifier.padding(top = 4.dp))

        Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                runWithNotificationPermission {
                    if (serverStatus.running) {
                        McpServerService.stop(context.applicationContext)
                    } else {
                        McpServerService.start(context.applicationContext, serverStatus.port)
                    }
                }
            }) {
                Text(if (serverStatus.running) "Stop Server" else "Start Server")
            }

            Text(
                text = if (serverStatus.running) "Running" else "Stopped",
                modifier = Modifier.padding(start = 12.dp),
                fontWeight = FontWeight.Medium,
            )
        }

        if (serverStatus.lastError != null) {
            Text(text = "Last error: ${serverStatus.lastError}", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
        }

        Text(text = "Allowlist", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(text = "Enabled")
            Switch(
                checked = allowlist.enabled,
                onCheckedChange = { enabled -> scope.launch { allowlistStore.setEnabled(enabled) } },
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        if (allowlist.lastBlockedIp != null) {
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Last blocked: ${allowlist.lastBlockedIp}")
                Button(
                    onClick = { scope.launch { allowlistStore.addEntry(allowlist.lastBlockedIp!!) } },
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text("Allow")
                }
            }
        }

        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newEntry,
                onValueChange = { newEntry = it },
                label = { Text("IPv4 or CIDR") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val value = newEntry
                    scope.launch {
                        runCatching { allowlistStore.addEntry(value) }
                        newEntry = ""
                    }
                },
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text("Add")
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            items(allowlist.entries) { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(text = entry, modifier = Modifier.weight(1f))
                    Button(onClick = { scope.launch { allowlistStore.removeEntry(entry) } }) {
                        Text("Remove")
                    }
                }
            }
        }

        Text(text = "Permissions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                runWithNotificationPermission {
                    val intent = mediaProjectionManager.createScreenCaptureIntent()
                    captureLauncher.launch(intent)
                }
            }) {
                Text("Enable Screen Capture")
            }
        }

        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val enabled = isAccessibilityEnabled(context)
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                Text(if (enabled) "Accessibility Enabled" else "Enable Accessibility")
            }
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = context.packageName + "/" + "com.example.ctrl.input.CtrlAccessibilityService"
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
}
