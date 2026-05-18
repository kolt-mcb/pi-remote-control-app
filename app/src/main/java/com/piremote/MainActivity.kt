package com.piremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

                // Load DataStore on startup
                LaunchedEffect(Unit) {
                    vm.loadUrlHistory()
                    val prefs = dataStore.data.first()
                    val lastUrl = prefs[KEY_URL] ?: ""
                    if (lastUrl.isNotBlank()) vm.setServerUrl(lastUrl)
                }

                lifecycle.addObserver(LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            if (status == ConnectionStatus.Disconnected && url.isNotEmpty()) { vm.connect() }
                        }
                        Lifecycle.Event.ON_STOP -> ws.disconnect()
                        else -> {}
                    }
                })

                if (status == ConnectionStatus.Connected) ChatScreen(vm, url, inp, ms, assist, status, busy)
                else ConnectScreen(vm, url, inp, ms, assist, status, urlHistory)
            }
        }
    }
}
