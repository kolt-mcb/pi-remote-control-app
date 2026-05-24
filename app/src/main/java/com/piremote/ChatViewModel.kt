package com.piremote

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import com.piremote.db.ChatDatabase
import com.piremote.db.ChatMessageEntity

val Context.dataStore by preferencesDataStore(name = "settings")
val KEY_URL = stringPreferencesKey("last_server_url")
val KEY_URL_HISTORY = stringSetPreferencesKey("url_history")

sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

/** Which sub-screen to show while connected */
enum class ConnectedScreen { Chat, Sessions }
enum class MessageToolType { User, Assistant, ToolResult, Streaming, Thinking }
object MsgId {
    private val counter = AtomicInteger(0)
    private val epoch = System.currentTimeMillis()
    fun next(): String = "$epoch-${counter.incrementAndGet()}"
}

data class ChatMessage(
    val id: String = MsgId.next(),
    val toolCallId: String = "",
    val type: MessageToolType = MessageToolType.User,
    val toolName: String = "",
    val toolArgs: String = "",
    val content: String = "",
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatUIState(
    val serverUrl: MutableStateFlow<String>,
    val messages: StateFlow<List<ChatMessage>>,
    val streamingText: StateFlow<String>,
    val status: StateFlow<ConnectionStatus>,
    val busy: StateFlow<Boolean>,
    val inputText: MutableStateFlow<String>,
    val urlHistory: StateFlow<Set<String>>,
    val selectedSession: StateFlow<String>,
    val notifyBanners: StateFlow<List<com.piremote.BannerMessage>>,
    val uiTitle: StateFlow<String?>,
    val clientCount: StateFlow<Int>,
    val turnSummary: StateFlow<com.piremote.PiWebSocket.TurnSummary?>,
)

class ChatViewModel(private val _ws: PiWebSocket, private val _ctx: Context) : ViewModel() {
    private val _dao = ChatDatabase.getInstance(_ctx).chatDao()
    val _url = MutableStateFlow("")
    val _inp = MutableStateFlow("")
    val _urlHistory = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedSession = MutableStateFlow("")
    private val _connectedScreen = MutableStateFlow(ConnectedScreen.Chat)

    // Scope for everything tied to the current pi connection — persistence
    // collector, session auto-select, FGS notification updater. connect()
    // cancels the previous one before opening a new one, so reconnects
    // don't stack observers; disconnect() cancels for good.
    private var connectionScope: CoroutineScope? = null

    // Attached images for the current message
    private val _attachedImages = MutableStateFlow<List<Uri>>(emptyList())
    val attachedImagesFlow: StateFlow<List<Uri>> get() = _attachedImages
    private val _attachedBase64 = MutableStateFlow<Map<Uri, String>>(emptyMap())

    fun addImage(uri: Uri) {
        val current = _attachedImages.value.toMutableList()
        if (!current.contains(uri)) {
            current.add(uri)
            _attachedImages.value = current
            viewModelScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val b64 = uriToBase64(uri)
                    _attachedBase64.value = _attachedBase64.value.toMutableMap().apply { put(uri, b64) }
                }
            }
        }
    }
    fun removeImage(uri: Uri) {
        _attachedImages.value = _attachedImages.value.filter { it != uri }
        _attachedBase64.value = _attachedBase64.value.toMutableMap().apply { remove(uri) }
    }
    fun clearImages() {
        _attachedImages.value = emptyList()
        _attachedBase64.value = emptyMap()
    }
    fun getBase64ForImage(uri: Uri): String = _attachedBase64.value[uri] ?: ""
    fun getMimeTypeForImage(uri: Uri): String {
        return try { _ctx.contentResolver.getType(uri) ?: "image/jpeg" } catch (_: Exception) { "image/jpeg" }
    }
    private fun uriToBase64(uri: Uri): String {
        val stream = _ctx.contentResolver.openInputStream(uri) ?: return ""
        val bytes = stream.use { it.readBytes() }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    val connectedScreen = _connectedScreen
    fun setConnectedScreen(s: ConnectedScreen) { _connectedScreen.value = s }
    fun showSessionsScreen() { _connectedScreen.value = ConnectedScreen.Sessions }
    fun showChatScreen() { _connectedScreen.value = ConnectedScreen.Chat }

    val st = ChatUIState(
        _url, _ws.messageFlow, _ws.assistingTextFlow, _ws.statusFlow, _ws.busyFlow, _inp, _urlHistory, _selectedSession,
        _ws.notifyBannerFlow, _ws.uiTitleFlow, _ws.clientCountFlow, _ws.turnSummaryFlow
    )

    fun setSelectedSession(id: String) {
        _selectedSession.value = id
        // Tell the WS which agent's flows the UI is showing. Drives messageFlow,
        // assistingTextFlow, busyFlow, turnSummaryFlow all at once.
        _ws.selectAgent(id)
    }

    fun setServerUrl(u: String) { _url.value = u }
    fun setInputText(t: String) { _inp.value = t }
    fun loadUrlHistory() {
        try {
            runBlocking {
                val prefs = _ctx.dataStore.data.first()
                _urlHistory.value = prefs[KEY_URL_HISTORY] ?: emptySet()
            }
        } catch (_: Throwable) {}
    }

    fun connect() {
        val u = _url.value.ifBlank { "ws://192.168.1.100:8765" }
        _url.value = u
        try {
            runBlocking {
                val prefs = _ctx.dataStore.data.first()
                val history = (prefs[KEY_URL_HISTORY] ?: emptySet()).toList().toMutableList()
                history.remove(u); history.add(0, u)
                while (history.size > 10) history.removeAt(history.lastIndex)
                val finalHistory = history.toSet()
                _ctx.dataStore.edit { it[KEY_URL] = u; it[KEY_URL_HISTORY] = finalHistory }
                _urlHistory.value = finalHistory
            }
        } catch (_: Throwable) {}

        // Cancel any observers from a prior connect(). Without this, every
        // reconnect stacked another set of persistence/session/FGS collectors
        // on top of the previous ones.
        connectionScope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob())
        connectionScope = scope

        // Load saved messages from DB
        scope.launch {
            val saved = _dao.getAllByServerUrl(u).distinctBy { it.msgId }
            val rebuilt = saved.map { e ->
                val t = when(e.type) {
                    "User" -> MessageToolType.User
                    "Assistant" -> MessageToolType.Assistant
                    "ToolResult" -> MessageToolType.ToolResult
                    "Thinking" -> MessageToolType.Thinking
                    else -> MessageToolType.Streaming
                }
                ChatMessage(id = e.msgId, toolCallId = e.toolCallId, type = t, toolName = e.toolName, content = e.content, isError = e.isError, timestamp = e.timestamp)
            }
            if (rebuilt.isNotEmpty()) _ws.repoMessages(rebuilt)
        }
        // Watch flow for persistence
        scope.launch {
            _ws.messageFlow.collect { msgs ->
                val existingIds = _dao.getAllByServerUrl(u).map { it.msgId }.toSet()
                for ((idx, m) in msgs.withIndex()) {
                    if (m.id !in existingIds) {
                        _dao.insert(ChatMessageEntity(
                            msgId = m.id, url = u, seq = idx,
                            type = m.type.name, toolCallId = m.toolCallId,
                            toolName = m.toolName, content = m.content, isError = m.isError,
                            timestamp = m.timestamp
                        ))
                    }
                }
            }
        }
        // Auto-select the host (self) session whenever the session list updates and
        // nothing is currently selected (or the selection vanished).
        scope.launch {
            _ws.sessionListFlow.collect { sessions ->
                val current = _selectedSession.value
                val stillPresent = sessions.any { it.id == current }
                if (current.isBlank() || !stillPresent) {
                    val self = sessions.firstOrNull { it.isSelf } ?: sessions.firstOrNull()
                    val id = self?.id ?: ""
                    _selectedSession.value = id
                    if (id.isNotBlank()) _ws.selectAgent(id)
                }
            }
        }
        // Foreground service: start on connect, update on busy/message changes, stop on disconnect.
        // collectLatest auto-cancels the inner combine() when status leaves Connected, so the
        // FGS-updater coroutine doesn't leak across status transitions.
        scope.launch {
            val host = extractHost(u)
            _ws.statusFlow.collectLatest { st ->
                when (st) {
                    is ConnectionStatus.Connected -> {
                        try { PiService.start(_ctx, host) } catch (_: Exception) {}
                        combine(_ws.busyFlow, _ws.messageFlow) { busy, msgs -> busy to msgs.size }
                            .collect { (busy, count) ->
                                try { PiService.start(_ctx, host, busy, count) } catch (_: Exception) {}
                            }
                    }
                    is ConnectionStatus.Disconnected, is ConnectionStatus.Error -> {
                        try { PiService.stop(_ctx) } catch (_: Exception) {}
                    }
                    else -> {}
                }
            }
        }
        // "Pi is ready" notification — only fires when the app is backgrounded
        // so the foreground chat doesn't ping over its own view. Foreground
        // state read off ProcessLifecycleOwner at emit time, not subscribed,
        // so we don't race the OS lifecycle event ordering.
        scope.launch {
            val host = extractHost(u)
            _ws.agentDoneFlow.collect { done ->
                val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                if (isForeground) return@collect
                val summary = done.summary?.let { s ->
                    "${s.totalCalls} tool${if (s.totalCalls != 1) "s" else ""} used"
                }
                try { PiService.notifyDone(_ctx, host, summary, done.durationMs) } catch (_: Exception) {}
            }
        }
        _connectedScreen.value = ConnectedScreen.Chat
        _ws.connect(u)
    }

    fun disconnect() {
        // Tear down the per-connection observers (persistence, session-select,
        // FGS updater) so reconnects don't stack new ones on top.
        connectionScope?.cancel()
        connectionScope = null
        // Keep the persisted chat — connect() rebuilds it via _ws.repoMessages
        // when the user reconnects to the same URL.
        _ws.disconnect()
    }

    fun sendPrompt() {
        val t = _inp.value.trim()
        val images = _attachedImages.value
        if (t.isNotEmpty() || images.isNotEmpty()) {
            _ws.sendPrompt(t, _selectedSession.value, images.mapNotNull { uri ->
                val b64 = getBase64ForImage(uri)
                val mime = getMimeTypeForImage(uri)
                if (b64.isNotEmpty()) "data:$mime;base64,$b64" else null
            })
            _inp.value = ""
            clearImages()
        }
    }
    fun sendSteer() {
        val t = _inp.value.trim()
        if (t.isNotEmpty()) { _ws.sendSteer(t, _selectedSession.value); _inp.value = "" }
    }
    fun sendFollowUp() {
        val t = _inp.value.trim()
        if (t.isNotEmpty()) { _ws.sendFollowUp(t, _selectedSession.value); _inp.value = "" }
    }
    /** Send a slash command. Handles local commands (like /disconnect) and forwards
     *  remote ones (/compact, /new, /reload, /quit, /resume) to the host extension. */
    fun sendSlashCommand(command: String, args: String = "") {
        // Local-only commands handled on the device
        if (command == "disconnect") {
            disconnect()
            return
        }
        _ws.sendSlashCommand(command, args, _selectedSession.value)
    }
    /** Spawn a new pi peer process on the host. Routes to PiWebSocket.sendSpawnPeer. */
    fun spawnPeer() { _ws.sendSpawnPeer() }

    /** Spawn a peer resuming a specific saved session — equivalent to
     *  `pi --session <path>` on the host. The new pi joins the host as a
     *  peer; the app shows it as a new tab pre-loaded with that history. */
    fun spawnPeerWithSession(path: String) { _ws.sendSpawnPeer(path) }

    /** Refresh the saved-session list. The browser screen calls this on
     *  entry; the response shows up in [PiWebSocket.savedSessionsFlow]. */
    fun refreshSavedSessions() { _ws.sendGetSavedSessions() }
    class Factory(private val ws: PiWebSocket, private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = ChatViewModel(ws, ctx) as T
    }

    private fun extractHost(url: String): String {
        return try {
            url.replace("ws://", "").replace("wss://", "").split("/")[0]
        } catch (_: Exception) { "Pi" }
    }
}
