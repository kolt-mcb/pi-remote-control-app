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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piremote.screens.ConnectScreen
import com.piremote.screens.ChatScreen
import com.piremote.screens.SessionsScreen
import com.piremote.screens.SelectDialog
import com.piremote.screens.InputDialog
import com.piremote.screens.TabletLayout
import com.piremote.screens.TabletConnectScreen
import com.piremote.screens.TerminalRenderView
import com.piremote.screens.clearImageCaches
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
                val st = vm.st
                // Collect flows from the single state object — the Activity
                // never reaches past the ViewModel into PiWebSocket directly.
                val url by st.serverUrl.collectAsState()
                val inp by st.inputText.collectAsState()
                val ms by st.messages.collectAsState()
                val assist by st.streamingText.collectAsState()
                val status by st.status.collectAsState()
                val busy by st.busy.collectAsState()
                val urlHistory by st.urlHistory.collectAsState()
                val sessions by st.sessions.collectAsState()
                val selectedSession by st.selectedSession.collectAsState()
                val commands by st.commands.collectAsState()
                val uiRequests by st.uiRequests.collectAsState()
                val statuses by st.statuses.collectAsState()
                val widgets by st.widgets.collectAsState()
                val compacting by st.compacting.collectAsState()
                val notifyBanners by st.notifyBanners.collectAsState()
                val uiTitle by st.uiTitle.collectAsState()
                val clientCount by st.clientCount.collectAsState()
                val turnSummary by st.turnSummary.collectAsState()
                val savedSessions by st.savedSessions.collectAsState()
                val renderFrame by st.renderFrame.collectAsState()

                // Mirror the host Pi's active theme into the app palette.
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

                // Detect tablet: width ≥ 600dp (matches WindowSizeClass.MEDIUM threshold).
                val isTablet = LocalConfiguration.current.screenWidthDp >= 600

                // Back-press handling differs by layout:
                // Phone: Chat → Sessions → Disconnect
                // Tablet: sidebar is always visible, so back just disconnects
                val currentScreen by vm.connectedScreen.collectAsState()
                if (status == ConnectionStatus.Connected) {
                    BackHandler {
                        if (isTablet) {
                            vm.disconnect()
                        } else {
                            when (currentScreen) {
                                ConnectedScreen.Chat -> vm.showSessionsScreen()
                                ConnectedScreen.Sessions -> vm.disconnect()
                            }
                        }
                    }
                }

                // Reconnect on resume if we have a URL but lost the connection.
                // Uses DisposableEffect so the observer is removed when the
                // composable leaves — prevents stacking stale observers and
                // firing with outdated `status`/`url` snapshots.
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                if (status == ConnectionStatus.Disconnected && url.isNotEmpty()) {
                                    vm.connect()
                                }
                            }
                            // DO NOT disconnect on ON_STOP — foreground service owns the connection.
                            else -> {}
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                // -- Extension UI helpers
                fun uiRespond(id: String, value: String) = ws.sendUIResponse(id, value = value)
                fun uiCancelled(id: String) = ws.sendUIResponse(id, cancelled = true)
                fun renderInput(id: String, value: String) = ws.sendInput(id, value)

                // Dialog requests
                val selReq = uiRequests.firstOrNull { it.method in listOf("select", "confirm") }
                val inpReq = uiRequests.firstOrNull { it.method in listOf("input", "editor") }

                when {
                    status is ConnectionStatus.Connected && isTablet ->
                        TabletLayout(
                            vm = vm,
                            st = st,
                            url = url,
                            input = inp,
                            messages = ms,
                            assist = assist,
                            status = status,
                            busy = busy,
                            sessions = sessions,
                            selectedSession = selectedSession,
                            commands = commands,
                            statuses = statuses,
                            widgets = widgets,
                            compacting = compacting,
                            notifyBanners = notifyBanners,
                            uiTitle = uiTitle,
                            clientCount = clientCount,
                            turnSummary = turnSummary,
                            savedSessions = savedSessions,
                            renderFrame = renderFrame,
                        )
                    isTablet ->
                        TabletConnectScreen(
                            vm = vm,
                            url = url,
                            input = inp,
                            messages = ms,
                            assist = assist,
                            status = status,
                            urlHistory = urlHistory,
                            sessions = sessions,
                        )
                    // ── Phone layout (unchanged) ─────────────────
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

                // Overlay dialogs (phone only — tablet owns its own in TabletLayout)
                if (!isTablet) {
                    selReq?.let { req -> SelectDialog(req, ::uiRespond, ::uiCancelled) }
                    inpReq?.let { req -> InputDialog(req, ::uiRespond, ::uiCancelled) }

                    // Full-screen Terminal render overlay
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

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT disconnect here — the WS singleton survives config changes
        // (orientation) and the foreground service keeps it alive in the
        // background. Let the ViewModel or explicit user action own disconnect.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) clearImageCaches()
    }
}
