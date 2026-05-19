package com.piremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piremote.screens.ConnectScreen
import com.piremote.screens.ChatScreen
import com.piremote.test.TestState
import com.piremote.theme.PiRemoteTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    // Share PiWebSocket with test receiver so broadcasts work even via ADB
    private val ws = TestState.ws

    // Runtime permission launcher for POST_NOTIFICATIONS (API 33+)
    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notification permission needed for connection status", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationsPermission()
        setContent {
            PiRemoteTheme {
                val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(ws, this@MainActivity))
                val st: ChatUIState = vm.st
                val url by st.serverUrl.collectAsState()
                val inp: String by st.inputText.collectAsState()
                val ms: List<ChatMessage> by st.messages.collectAsState()
                val assist: String by st.streamingText.collectAsState()
                val status: ConnectionStatus by st.status.collectAsState()
                val busy: Boolean by st.busy.collectAsState()
                val urlHistory by st.urlHistory.collectAsState()
                val sessions by ws.sessionListFlow.collectAsState()
                val selectedSession by st.selectedSession.collectAsState()
                val commands by ws.commandListFlow.collectAsState()

                // Load DataStore on startup
                LaunchedEffect(Unit) {
                    vm.loadUrlHistory()
                    val prefs = dataStore.data.first()
                    val lastUrl = prefs[KEY_URL] ?: ""
                    if (lastUrl.isNotBlank()) vm.setServerUrl(lastUrl)
                }

                lifecycle.addObserver(LifecycleEventObserver { _, event ->
                    when (event) {
                        // When resuming, reconnect if we were disconnected AND have a URL.
                        // Foreground service keeps the WS alive in background, so if status is
                        // still Connected here, we just show the chat — no reconnect needed.
                        Lifecycle.Event.ON_RESUME -> {
                            if (status == ConnectionStatus.Disconnected && url.isNotEmpty()) { vm.connect() }
                        }
                        // DO NOT disconnect on ON_STOP — foreground service owns the connection.
                        // The WS thread (OkHttp dispatcher) survives app backgrounding.
                        // We only disconnect when user explicitly taps "Disconnect".
                        Lifecycle.Event.ON_STOP -> {
                            // Previously: ws.disconnect() — killed connection when backgrounded
                            // Now: let the connection live. OS + foreground service keeps process alive.
                        }
                        else -> {}
                    }
                })

                if (status == ConnectionStatus.Connected)
                    ChatScreen(vm, url, inp, ms, assist, status, busy, sessions, selectedSession, commands)
                else
                    ConnectScreen(vm, url, inp, ms, assist, status, urlHistory, sessions)
            }
        }
    }
}
