package com.piremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
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
)

class ChatViewModel(private val _ws: PiWebSocket, private val _ctx: Context) : ViewModel() {
    private val _dao = ChatDatabase.getInstance(_ctx).chatDao()
    val _url = MutableStateFlow("")
    val _inp = MutableStateFlow("")
    val _urlHistory = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedSession = MutableStateFlow("")

    val st = ChatUIState(
        _url, _ws.messageFlow, _ws.assistingTextFlow, _ws.statusFlow, _ws.busyFlow, _inp, _urlHistory, _selectedSession
    )

    fun setSelectedSession(id: String) { _selectedSession.value = id }

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
        // Load saved messages from DB
        viewModelScope.launch {
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
        viewModelScope.launch {
            _ws.messageFlow.collect { msgs ->
                val existingIds = _dao.getAllByServerUrl(u).map { it.msgId }.toSet()
                for (m in msgs) {
                    if (m.id !in existingIds) {
                        _dao.insert(ChatMessageEntity(
                            msgId = m.id, url = u, seq = msgs.indexOf(m),
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
        viewModelScope.launch {
            _ws.sessionListFlow.collect { sessions ->
                val current = _selectedSession.value
                val stillPresent = sessions.any { it.id == current }
                if (current.isBlank() || !stillPresent) {
                    val self = sessions.firstOrNull { it.isSelf } ?: sessions.firstOrNull()
                    _selectedSession.value = self?.id ?: ""
                }
            }
        }
        // Foreground service: start on connect, update on busy/message changes, stop on disconnect
        viewModelScope.launch {
            val host = extractHost(u)
            _ws.statusFlow.collect { st ->
                when (st) {
                    is ConnectionStatus.Connected -> {
                        try { PiService.start(_ctx, host) } catch (_: Exception) {}
                        // Update notification as busy state / message count changes
                        viewModelScope.launch {
                            combine(_ws.busyFlow, _ws.messageFlow) { busy, msgs -> busy to msgs.size }
                                .collect { (busy, count) ->
                                    try { PiService.start(_ctx, host, busy, count) } catch (_: Exception) {}
                                }
                        }
                    }
                    is ConnectionStatus.Disconnected, is ConnectionStatus.Error -> {
                        try { PiService.stop(_ctx) } catch (_: Exception) {}
                    }
                    else -> {}
                }
            }
        }
        _ws.connect(u)
    }

    fun disconnect() {
        viewModelScope.launch { _dao.clearByServerUrl(_url.value) }
        _ws.disconnect()
    }

    fun sendPrompt() {
        val t = _inp.value.trim()
        if (t.isNotEmpty()) { _ws.sendPrompt(t, _selectedSession.value); _inp.value = "" }
    }
    fun sendSteer() {
        val t = _inp.value.trim()
        if (t.isNotEmpty()) { _ws.sendSteer(t, _selectedSession.value); _inp.value = "" }
    }
    fun sendFollowUp() {
        val t = _inp.value.trim()
        if (t.isNotEmpty()) { _ws.sendFollowUp(t, _selectedSession.value); _inp.value = "" }
    }
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
