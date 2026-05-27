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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piremote.screens.ConnectScreen
import com.piremote.screens.ChatScreen
import com.piremote.screens.SessionsScreen
import com.piremote.screens.SelectDialog
import com.piremote.screens.InputDialog
import com.piremote.screens.TerminalRenderView
import com.piremote.test.TestState
import com.piremote.theme.PiRemoteTheme
import com.piremote.theme.ThemeManager
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
                Box(modifier = Modifier.fillMaxSize().safeContentPadding()) {
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
                val uiRequests by ws.uiRequestFlow.collectAsState()
                val statuses by ws.statusesFlow.collectAsState()
                val widgets by ws.widgetsFlow.collectAsState()
                val compacting by ws.compactingFlow.collectAsState()
                val notifyBanners by ws.notifyBannerFlow.collectAsState()
                val uiTitle by ws.uiTitleFlow.collectAsState()
                val clientCount by ws.clientCountFlow.collectAsState()
                val turnSummary by ws.turnSummaryFlow.collectAsState()
                val savedSessions by ws.savedSessionsFlow.collectAsState()
                // Generic TUI render frames from any Pi extension
                val renderFrame by ws.renderFrameFlow.collectAsState()

                // Mirror the host Pi's active theme into the app palette. The host
                // broadcasts theme_info on connect and whenever its theme changes;
                // applying it here recolors the whole UI via ThemeManager.flow.
                LaunchedEffect(Unit) {
                    ws.remoteThemeFlow.collect { remote ->
                        remote?.let { ThemeManager.applyRemote(it) }
                    }
                }

                // Load DataStore on startup
                LaunchedEffect(Unit) {
                    vm.loadUrlHistory()
                    val prefs = dataStore.data.first()
                    val lastUrl = prefs[KEY_URL] ?: ""
                    if (lastUrl.isNotBlank()) vm.setServerUrl(lastUrl)
                }

                // Back-press handling: when connected and on SessionsScreen, back = disconnect
                // When connected and on ChatScreen, back = show sessions screen
                val currentScreen by vm.connectedScreen.collectAsState()
                if (status == ConnectionStatus.Connected) {
                    BackHandler {
                        when (currentScreen) {
                            ConnectedScreen.Chat -> vm.showSessionsScreen()
                            ConnectedScreen.Sessions -> vm.disconnect()
                        }
                    }
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

                // -- Extension UI: respond to pending dialogs
                fun uiRespond(id: String, value: String) = ws.sendUIResponse(id, value = value)
                fun uiCancelled(id: String) = ws.sendUIResponse(id, cancelled = true)
                // Generic TUI render input back to extension
                fun renderInput(id: String, value: String) = ws.sendInput(id, value)

                // Show dialogs for extension_ui_request
                val selReq = uiRequests.firstOrNull { it.method in listOf("select", "confirm") }
                val inpReq = uiRequests.firstOrNull { it.method in listOf("input", "editor") }

                when {
                    status == ConnectionStatus.Connected && currentScreen == ConnectedScreen.Chat ->
                        ChatScreen(vm, url, inp, ms, assist, status, busy, sessions, selectedSession,
                            commands = commands,
                            statuses = statuses,
                            widgets = widgets,
                            compacting = compacting,
                            notifyBanners = notifyBanners,
                            uiTitle = uiTitle,
                            clientCount = clientCount,
                            turnSummary = turnSummary
                        )
                    status == ConnectionStatus.Connected ->
                        SessionsScreen(vm, sessions, selectedSession, compacting, clientCount, savedSessions)
                    else ->
                        ConnectScreen(vm, url, inp, ms, assist, status, urlHistory, sessions)
                }

                // Overlay dialogs
                selReq?.let { req -> SelectDialog(req, ::uiRespond, ::uiCancelled) }
                inpReq?.let { req -> InputDialog(req, ::uiRespond, ::uiCancelled) }

                // Full-screen Terminal render overlay (streams ANY Pi extension TUI)
                renderFrame?.let { frame ->
                    TerminalRenderView(frame) { value ->
                        renderInput(frame.id, value)
                    }
                }
                }
            }
        }
    }
}
