package com.piremote

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import com.piremote.theme.CodeUtils

private const val WS_TAG = "PiWs"
class PiWebSocket : WebSocketListener() {
    private var sock: WebSocket? = null
    private var pendingUrl: String = ""
    private var retryCount = 0
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _m = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messageFlow: StateFlow<List<ChatMessage>> get() = _m
    private val _sessions = MutableStateFlow<List<RemoteSession>>(emptyList())
    val sessionListFlow: StateFlow<List<RemoteSession>> get() = _sessions
    private val _commands = MutableStateFlow<List<RemoteCommand>>(emptyList())
    val commandListFlow: StateFlow<List<RemoteCommand>> get() = _commands
    private val _s = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val statusFlow: StateFlow<ConnectionStatus> get() = _s
    private val _busy = MutableStateFlow(false)
    val busyFlow: StateFlow<Boolean> get() = _busy
    private val _a = MutableStateFlow("")
    val assistingTextFlow: StateFlow<String> get() = _a
    private val _thinking = MutableStateFlow("")
    private var stxt = ""
    private val tbufs = mutableMapOf<String, StringBuilder>()

    fun connect(url: String) {
        Log.d(WS_TAG, "Connecting to: $url"); pendingUrl = url
        retryCount = 0
        doConnect(url)
    }
    private fun doConnect(url: String) {
        val u = url.trim()
        if (u.isNotBlank()) {
            try {
                Request.Builder().url(u).build()
            } catch (e: Exception) {
                _s.value = ConnectionStatus.Error("Invalid URL: " + e.message)
                return
            }
        } else {
            _s.value = ConnectionStatus.Error("URL is empty")
            return
        }
        if (_s.value == ConnectionStatus.Disconnected || _s.value is ConnectionStatus.Error) {
            _s.value = ConnectionStatus.Connecting
        }
        sock = http.newWebSocket(Request.Builder().url(u).build(), this)
    }
    private fun reconnect() {
        if (pendingUrl.isBlank()) return
        if (_s.value == ConnectionStatus.Connecting) return
        _s.value = ConnectionStatus.Connecting
        sock = http.newWebSocket(Request.Builder().url(pendingUrl).build(), this)
    }
    fun disconnect() {
        pendingUrl = ""
        retryCount = 0
        sock?.close(1000, null)
    }

    fun sendPrompt(txt: String, targetAgentId: String = "") {
        send("prompt", txt, targetAgentId)
    }
    fun sendSteer(txt: String, targetAgentId: String = "") {
        send("steer", txt, targetAgentId)
    }
    fun sendFollowUp(txt: String, targetAgentId: String = "") {
        send("follow_up", txt, targetAgentId)
    }
    // Re-inject saved DB messages into the flow (called on reconnect)
    fun repoMessages(saved: List<ChatMessage>) {
        _m.value = _m.value + saved
    }
    private fun send(type: String, txt: String, targetAgentId: String = "") {
        val target = if (targetAgentId.isNotBlank()) ",\"targetAgentId\":\"${Js.e(targetAgentId)}\"" else ""
        val json = "{\"type\":\"$type\",\"message\":\"${Js.e(txt)}\"$target}"
        sock?.send(json)
    }

    override fun onOpen(ws: WebSocket, r: okhttp3.Response) {
        Log.d(WS_TAG, "OPEN - Connected"); _s.value = ConnectionStatus.Connected
        retryCount = 0
        _thinking.value = ""
        stxt = ""
        // Request session list and command list on connect
        sock?.send("{\"type\":\"get_sessions\"}")
        sock?.send("{\"type\":\"get_commands\"}")
    }
    override fun onFailure(ws: WebSocket, t: Throwable, r: okhttp3.Response?) {
        Log.e(WS_TAG, "FAILURE: " + t.message)
        val msg = t.message ?: "Connection failed"
        // Auto-reconnect with increasing backoff (handled via a small delay)
        if (retryCount < 10 && pendingUrl.isNotBlank()) {
            retryCount++
            // Schedule reconnect on main thread via a simple delay
            kotlin.concurrent.thread {
                Thread.sleep(2000L shl minOf(retryCount - 1, 4)) // 2s, 4s, 8s, 16s, 32s, 64s... cap at 32s
                reconnect()
            }
        } else {
            _s.value = ConnectionStatus.Error(msg)
        }
    }
    override fun onMessage(ws: WebSocket, txt: String) {
        Log.d(WS_TAG, "MSG IN: " + txt.take(80))
        try { dispatch(txt) } catch (_: Throwable) {}
    }
    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        Log.d(WS_TAG, "CLOSED code=$code reason=$reason")
        _s.value = ConnectionStatus.Disconnected
        _busy.value = false
    }

    private fun dispatch(raw: String) {
        val j = JP.p(raw) ?: return
        val tp = Js.gets(j, "type") ?: return
        if (tp == "agent_start") { _busy.value = true; stxt = ""; tbufs.clear(); _thinking.value = "" }
        if (tp == "agent_end") { _busy.value = false; es(null) }
        if (tp == "message_start") msgStart(j)
        if (tp == "message_end") { val mm = JP.nj(j, "message"); if (mm != null) msgEnd(mm) }
        if (tp == "message_update") msgUpd(j)
        if (tp == "tool_start") toolStart(j)
        if (tp == "session_list") handleSessions(j)
        if (tp == "command_list") handleCommands(j)
    }

    private fun msgStart(j: Map<*, *>) {
        val mm = JP.nj(j, "message") ?: return
        val role = Js.gets(mm, "role") ?: return
        val content = Js.gets(mm, "content") ?: ""
        if (role == "user") _m.value += ChatMessage(type = MessageToolType.User, content = content)
        if (role == "toolResult") {
            _m.value += ChatMessage(
                type = MessageToolType.ToolResult,
                toolCallId = Js.gets(mm, "toolCallId") ?: "",
                toolName = Js.gets(mm, "toolName") ?: "?",
                content = content,
                isError = Js.getBool(mm, "isError") ?: false
            )
        }
        if (role == "assistant") _m.value += ChatMessage(type = MessageToolType.Assistant, content = content)
    }

    private fun msgEnd(j: Map<*, *>) {
        val role = Js.gets(j, "role")
        if (role == "assistant") es(null)
    }

    private fun msgUpd(j: Map<*, *>) {
        val ev = Js.gets(j, "eventType") ?: return
        if (ev == "text_start") { stxt = ""; addP() }
        if (ev == "text_delta") { val d = Js.gets(j, "delta") ?: return; stxt += d; pushSt(stxt); _a.value = stxt }
        if (ev == "text_end") es(stxt)
        if (ev == "thinking_start") _thinking.value = ""
        if (ev == "thinking_delta") { val d = Js.gets(j, "delta") ?: return; _thinking.value += d; _a.value = _thinking.value }
        if (ev == "thinking_end") {
            // Convert thinking text to a Thinking message type instead of dropping it
            val thinkingText = _thinking.value
            if (thinkingText.isNotBlank()) {
                _m.value += ChatMessage(type = MessageToolType.Thinking, content = thinkingText)
            } else {
                // Remove blank streaming placeholders
                _m.value = _m.value.filterNot { it.type == MessageToolType.Streaming && it.content.isBlank() }
            }
            _thinking.value = ""
            stxt = ""
        }
        if (ev == "done") es(null)
        if (ev == "error") { ss("Err: " + (Js.gets(j, "message") ?: "")) }
    }

    private fun toolStart(j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val nm = Js.gets(j, "toolName") ?: "?"
        tbufs[id] = StringBuilder()
        _m.value += ChatMessage(toolCallId = id, type = MessageToolType.Streaming, toolName = nm)
    }

    private fun addP() {
        if (!_m.value.any { it.type == MessageToolType.Streaming && it.content == "" })
            _m.value += ChatMessage(type = MessageToolType.Streaming)
    }
    private fun pushSt(t: String) {
        val ml = _m.value.toMutableList()
        val idx = ml.indexOfLast { it.type == MessageToolType.Streaming && it.content == "" }
        if (idx >= 0) { ml[idx] = ml[idx].copy(content = t); _m.value = ml }
    }
    private fun es(finalText: String?) {
        val sub = finalText ?: stxt
        if (sub.isNotBlank()) {
            val ml = _m.value.toMutableList()
            val idx = ml.indexOfLast { it.type == MessageToolType.Streaming }
            if (idx >= 0 && ml[idx].content.isEmpty()) {
                ml[idx] = ml[idx].copy(type = MessageToolType.Assistant, content = sub)
                _m.value = ml
            } else { _m.value = ml + ChatMessage(type = MessageToolType.Assistant, content = sub) }
        } else {
            _m.value = _m.value.filterNot { it.type == MessageToolType.Streaming && it.content.isBlank() }
        }
        stxt = ""
        _a.value = ""
    }
    private fun ss(sub: String) { es(sub) }
    private fun handleCommands(j: Map<*, *>) {
        val arr = j["commands"] ?: return
        if (arr !is List<*>) return
        val list = arr.mapNotNull { c ->
            if (c !is Map<*, *>) return@mapNotNull null
            val name = (c["name"] as? String) ?: return@mapNotNull null
            RemoteCommand(name = name, description = (c["description"] as? String) ?: "")
        }
        _commands.value = list
    }
    private fun handleSessions(j: Map<*, *>) {
        val sessionsArr = j["sessions"] ?: return
        if (sessionsArr !is List<*>) return
        val list = sessionsArr.mapNotNull { s ->
            if (s !is Map<*, *>) return@mapNotNull null
            RemoteSession(
                id = (s["id"] as? String) ?: "",
                name = (s["name"] as? String) ?: "",
                kind = (s["kind"] as? String) ?: "peer",
                status = (s["status"] as? String) ?: "idle",
                connectedAt = ((s["connectedAt"] as? Number) ?: 0).toLong(),
                lastActivity = ((s["lastActivity"] as? Number) ?: 0).toLong(),
                messageCount = ((s["messageCount"] as? Number) ?: 0).toInt(),
                turnIndex = ((s["turnIndex"] as? Number) ?: 0).toInt()
            )
        }
        _sessions.value = list
    }
}

// ── JSON helpers (hand-rolled parser) ──

object JP {
    fun p(s: String): Map<String, Any?>? = try { val p = PS(s.trim()); p.po() } catch (_: Throwable) { null }
    fun nj(p: Map<*, *>, k: String): Map<*, *>? = if (p[k] is Map<*, *>) p[k] as? Map<*, *> else null
}

class PS(val s: String) {
    var pp = 0; private val n = s.length
    private fun ws() { while (pp < n && s[pp].isWhitespace()) pp++ }
    private fun we(c: Char) { if (pp >= n || s[pp] != c) error("need $c"); pp++ }
    fun po(): Map<String, Any?> {
        ws(); we('{'); ws(); val m = mutableMapOf<String, Any?>()
        if (pp < n && s[pp] == '}') { pp++; return m }
        while (true) {
            ws(); val k = ps(); ws(); we(':'); ws()
            m[k] = pv()
            ws()
            if (pp < n && s[pp] == ',') { pp++; continue }
            we('}'); return m
        }
    }
    private fun pv(): Any? {
        ws()
        return if (pp >= n) "" else {
            if (s[pp] == '"') ps()
            else if (s[pp] == '{') po()
            else if (s[pp] == '[') { pa(); emptyList<Any>() }
            else if (s.startsWith("null", pp)) { pp += 4; null }
            else if (s.startsWith("true", pp)) { pp += 4; true }
            else if (s.startsWith("false", pp)) { pp += 5; false }
            else pn()
        }
    }
    private fun pa() {
        pp++; var dd = 1
        while (pp < n) {
            if (s[pp] == '[') dd++; if (s[pp] == ']') { dd--; if (dd == 0) { pp++; return } }
            if (s[pp] == '\\') pp++; pp++
        }
    }
    fun ps(): String {
        ws(); we('"'); val sb = StringBuilder()
        while (pp < n) {
            val c = s[pp]
            if (c == '\\') { pp++; val ec = s[pp]
                sb.append(when(ec) { '"' -> '"'; '\\' -> '\\'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> ec })
            } else if (c == '"') { pp++; return sb.toString() }
            else if (c == '/') { pp++; if (pp < n && s[pp] == '<') { /* skip entity */ }
                sb.append(c); pp++; continue
            }
            else sb.append(c)
            pp++
        }
        return sb.toString()
    }
    private fun pn(): Any {
        val st = pp
        while (pp < n && (s[pp].isDigit() || s[pp] == '-' || s[pp] == '.' || s[pp] == 'e' || s[pp] == 'E' || s[pp] == '+')) pp++
        return s.substring(st, pp)
    }
}

// JSON utility helpers
object Js {
    fun gets(m: Map<*, *>, k: String): String? {
        val v = m[k] ?: return null
        return if (v is String) v else if (v is Number) v.toString() else if (v is Boolean) v.toString() else null
    }
    fun getBool(m: Map<*, *>, k: String): Boolean? = m[k] as? Boolean
    fun e(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }
}

// ── Session model ──

data class RemoteCommand(
    val name: String,
    val description: String
)

data class RemoteSession(
    val id: String,
    val name: String,
    val kind: String = "peer",
    val status: String,
    val connectedAt: Long,
    val lastActivity: Long,
    val messageCount: Int,
    val turnIndex: Int
) {
    val isActive: Boolean = status == "busy"
    val isSelf: Boolean = kind == "self"
}
