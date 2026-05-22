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
    // Extension UI: pending dialog requests awaiting response
    private val _uiRequests = MutableStateFlow<List<ExtensionUIRequest>>(emptyList())
    val uiRequestFlow: StateFlow<List<ExtensionUIRequest>> get() = _uiRequests
    // Extension UI: fire-and-forget status indicators
    private val _statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val statusesFlow: StateFlow<Map<String, String>> get() = _statuses
    // Extension UI: widgets (above/below editor)
    private val _widgets = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val widgetsFlow: StateFlow<Map<String, List<String>>> get() = _widgets
    // Compaction / retry status
    private val _compacting = MutableStateFlow(false)
    val compactingFlow: StateFlow<Boolean> get() = _compacting
    private val _retryStatus = MutableStateFlow<String?>(null)
    val retryStatusFlow: StateFlow<String?> get() = _retryStatus
    // Notify banners (extension_ui notify)
    private val _notifyBanners = MutableStateFlow<List<BannerMessage>>(emptyList())
    val notifyBannerFlow: StateFlow<List<BannerMessage>> get() = _notifyBanners
    // Extension UI title
    private val _uiTitle = MutableStateFlow<String?>(null)
    val uiTitleFlow: StateFlow<String?> get() = _uiTitle
    // Connected client count
    private val _clientCount = MutableStateFlow(0)
    val clientCountFlow: StateFlow<Int> get() = _clientCount
    // Generic TUI render frames (any Pi extension component)
    private val _renderFrame = MutableStateFlow<RenderFrame?>(null)
    val renderFrameFlow: StateFlow<RenderFrame?> get() = _renderFrame

    private val _s = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val statusFlow: StateFlow<ConnectionStatus> get() = _s
    private val _busy = MutableStateFlow(false)
    val busyFlow: StateFlow<Boolean> get() = _busy
    private val _a = MutableStateFlow("")
    val assistingTextFlow: StateFlow<String> get() = _a
    private val _thinking = MutableStateFlow("")
    private var stxt = ""
    private val tbufs = mutableMapOf<String, StringBuilder>()

    // Turn summary: tracks tool calls during the current agent turn
    private val _turnSummary = MutableStateFlow<TurnSummary?>(null)
    val turnSummaryFlow: StateFlow<TurnSummary?> get() = _turnSummary
    private val turnToolCalls = mutableListOf<String>()

    data class TurnSummary(
        val toolsUsed: List<ToolCallSummary>,
        val totalCalls: Int
    )
    data class ToolCallSummary(val name: String, val count: Int)

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

    fun sendPrompt(txt: String, targetAgentId: String = "", images: List<String> = emptyList()) {
        send("prompt", txt, targetAgentId, images)
    }
    fun sendSteer(txt: String, targetAgentId: String = "") {
        send("steer", txt, targetAgentId)
    }
    fun sendFollowUp(txt: String, targetAgentId: String = "") {
        send("follow_up", txt, targetAgentId)
    }
    // Send a slash command to the host for remote execution.
    // The extension intercepts supported commands (/compact, /new, /reload, /quit)
    // via withCommandContext. Unsupported commands get a notify banner.
    fun sendSlashCommand(name: String, args: String = "", targetAgentId: String = "") {
        val target = if (targetAgentId.isNotBlank()) ",\"targetAgentId\":\"${Js.e(targetAgentId)}\"" else ""
        val a = if (args.isNotBlank()) ",\"args\":\"${Js.e(args)}\"" else ""
        val json = "{\"type\":\"slash_command\",\"command\":\"${Js.e(name)}\"$a$target}"
        sock?.send(json)
    }
    // UI protocol: send response for extension_ui_request dialogs
    fun sendUIResponse(id: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        val escId = Js.e(id)
        var json = "{\"type\":\"extension_ui_response\",\"id\":\"" + escId + "\""
        if (cancelled) json += ",\"cancelled\":true"
        else if (confirmed != null) json += ",\"confirmed\":$confirmed"
        else if (value != null) json += ",\"value\":\"" + Js.e(value) + "\""
        json += "}"
        sock?.send(json)
        _uiRequests.value = _uiRequests.value.filter { it.id != id }
    }

    // Re-inject saved DB messages into the flow (called on reconnect)
    fun repoMessages(saved: List<ChatMessage>) {
        _m.value = _m.value + saved
    }
    private fun send(type: String, txt: String, targetAgentId: String = "", images: List<String> = emptyList()) {
        val target = if (targetAgentId.isNotBlank()) ",\"targetAgentId\":\"${Js.e(targetAgentId)}\"" else ""
        val imgs = if (images.isNotEmpty()) {
            val imgParts = images.map { img -> "\"${Js.e(img)}\"" }
            ",\"images\":[${imgParts.joinToString()}]"
        } else ""
        val json = "{\"type\":\"$type\",\"message\":\"${Js.e(txt)}\"$target$imgs}"
        sock?.send(json)
    }

    // ── Game Protocol ──
    // Send game-touch events (DOOM keys via touch) to the extension
    fun sendGameTouch(key: String, pressed: Boolean) {
        val json = "{\"type\":\"game_touch\",\"key\":\"$key\",\"pressed\"$pressed}"
        sock?.send(json)
    }

    // ── Render Protocol ──
    // Send input from rendered TUI components back to the extension
    fun sendRenderInput(renderId: String, value: String) {
        val json = "{\"type\":\"input\",\"id\":\"${Js.e(renderId)}\",\"value\":\"${Js.e(value)}\"}"
        sock?.send(json)
    }
    // Alias
    fun sendInput(renderId: String, value: String) = sendRenderInput(renderId, value)

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
        if (tp == "agent_start") { _busy.value = true; stxt = ""; tbufs.clear(); _thinking.value = ""; turnToolCalls.clear() }
        if (tp == "agent_end") { _busy.value = false; es(null); buildTurnSummary() }
        if (tp == "message_start") msgStart(j)
        if (tp == "message_end") { val mm = JP.nj(j, "message"); if (mm != null) msgEnd(mm) }
        if (tp == "message_update") msgUpd(j)
        if (tp == "tool_start") toolStart(j)
        if (tp == "tool_update") toolUpdate(j)
        if (tp == "tool_end") toolEnd(j)
        if (tp == "compaction_start") _compacting.value = true
        if (tp == "compaction_end") { _compacting.value = false; _retryStatus.value = null }
        if (tp == "auto_retry_start") {
            val attempt = (j["attempt"] as? Number)?.toInt() ?: 0
            val max = (j["maxAttempts"] as? Number)?.toInt() ?: 0
            _retryStatus.value = "Retry $attempt/$max"
        }
        if (tp == "auto_retry_end") _retryStatus.value = null
        if (tp == "extension_ui_request") handleExtensionUI(j)
        if (tp == "extension_ui_dismiss") {
            val id = Js.gets(j, "id") ?: return
            _uiRequests.value = _uiRequests.value.filter { it.id != id }
        }
        if (tp == "session_list") handleSessions(j)
        if (tp == "command_list") handleCommands(j)
        // Missing handlers added:
        if (tp == "connected") {
            val count = (j["clients"] as? Number)?.toInt() ?: 0
            _clientCount.value = count
        }
        if (tp == "turn_start") {
            // No-op for now — could track turn index per session
        }
        if (tp == "turn_end") {
            // No-op for now
        }
        // TUI input delivery confirmation (from DOOM/remote-control)
        // Ack is enough; tui_input broadcast just means the key was relayed
        if (tp == "tui_input") {
            // Key input was delivered to DOOM — no action needed on Android side
        }
        // Generic TUI render frames (any Pi extension component)
        if (tp == "render") {
            val id = Js.gets(j, "id") ?: ""
            val ansiLinesRaw = j["lines"]
            val ansiLines = if (ansiLinesRaw is List<*>) ansiLinesRaw else emptyList<Any>()
            val inputMode = Js.gets(j, "inputMode") ?: "none"
            val title = Js.gets(j, "title") ?: ""
            val dismiss = j["dismiss"] as? Boolean == true
            // Empty lines or explicit dismiss → clear the render frame
            val resolved = ansiLines.filterIsInstance<String>()
            if (dismiss || resolved.isEmpty()) {
                _renderFrame.value = null
            } else {
                _renderFrame.value = RenderFrame(
                    id = id,
                    ansiLines = resolved,
                    inputMode = inputMode,
                    title = title
                )
            }
        }
    }

    private fun msgStart(j: Map<*, *>) {
        val mm = JP.nj(j, "message") ?: return
        val role = Js.gets(mm, "role") ?: return
        val content = Js.gets(mm, "content") ?: ""
        if (role == "user") _m.value += ChatMessage(type = MessageToolType.User, content = content)
        if (role == "toolResult") {
            val tid = Js.gets(mm, "toolCallId") ?: ""
            // Skip if tool_end already created this ToolResult from streaming
            if (!_m.value.any { it.toolCallId == tid && it.type == MessageToolType.ToolResult }) {
                _m.value += ChatMessage(
                    type = MessageToolType.ToolResult,
                    toolCallId = tid,
                    toolName = Js.gets(mm, "toolName") ?: "?",
                    content = content,
                    isError = Js.getBool(mm, "isError") ?: false
                )
            }
        }
        // assistant prose is handled via streaming (text_delta → text_end → es()),
        // so skip creating an (empty) Assistant here on msgStart.
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
        val argsJson = j["args"]?.toString().orEmpty()
        tbufs[id] = StringBuilder()
        turnToolCalls.add(nm)
        _m.value += ChatMessage(toolCallId = id, type = MessageToolType.Streaming, toolName = nm, toolArgs = argsJson)
    }

    private fun toolUpdate(j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val content = Js.gets(j, "content") ?: ""
        val buf = tbufs[id]
        if (buf != null) {
            buf.append(content)
            val ml = _m.value.toMutableList()
            val idx = ml.indexOfLast { it.toolCallId == id && it.type == MessageToolType.Streaming }
            if (idx >= 0) {
                ml[idx] = ml[idx].copy(content = buf.toString())
                _m.value = ml
            }
        }
    }

    private fun toolEnd(j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val nm = Js.gets(j, "toolName") ?: "?"
        val remoteContent = Js.gets(j, "content") ?: ""
        val isError = (j["isError"] as? Boolean) ?: false
        val bufferedContent = tbufs[id]?.toString().orEmpty()
        val content = if (remoteContent.isNotBlank()) remoteContent else bufferedContent

        val ml = _m.value.toMutableList()
        val idx = ml.indexOfLast { it.toolCallId == id && it.type == MessageToolType.Streaming }
        if (idx >= 0) {
            ml[idx] = ChatMessage(
                id = ml[idx].id, toolCallId = id,
                type = MessageToolType.ToolResult,
                toolName = nm, toolArgs = ml[idx].toolArgs,
                content = content,
                isError = isError,
                timestamp = ml[idx].timestamp
            )
            _m.value = ml
        } else {
            _m.value = ml + ChatMessage(
                toolCallId = id, type = MessageToolType.ToolResult,
                toolName = nm, content = content, isError = isError
            )
        }
        tbufs.remove(id)
    }

    // Build a compact summary of tools used during the last turn
    private fun buildTurnSummary() {
        if (turnToolCalls.isEmpty()) {
            _turnSummary.value = null
            return
        }
        val counts = turnToolCalls.groupingBy { it }.eachCount()
        val toolsUsed = counts.entries
            .sortedByDescending { it.value }
            .map { (name, count) -> ToolCallSummary(name, count) }
        _turnSummary.value = TurnSummary(
            toolsUsed = toolsUsed,
            totalCalls = turnToolCalls.size
        )
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

    private fun handleExtensionUI(j: Map<*, *>) {
        val method = Js.gets(j, "method") ?: return
        val id = Js.gets(j, "id") ?: return

        // Fire-and-forget methods
        if (method == "notify") {
            val msg = Js.gets(j, "message") ?: Js.gets(j, "content") ?: "Notification"
            val nt = Js.gets(j, "notifyType") ?: Js.gets(j, "type") ?: "info"
            val banner = BannerMessage(msg, nt, System.currentTimeMillis())
            val list = _notifyBanners.value.toMutableList()
            list.add(banner)
            // Keep max 5 banners
            while (list.size > 5) list.removeAt(0)
            _notifyBanners.value = list
            return
        }
        if (method == "setTitle") {
            val title = Js.gets(j, "title")
            _uiTitle.value = title
            return
        }
        if (method == "setStatus") {
            val key = Js.gets(j, "statusKey").takeUnless { it.isNullOrEmpty() } ?: j["key"]?.toString() ?: return
            val text = Js.gets(j, "statusText").takeUnless { it.isNullOrEmpty() } ?: j["text"]?.toString()
            val current = _statuses.value.toMutableMap()
            if (text != null && text != "null") current[key] = text else current.remove(key)
            _statuses.value = current
            return
        }
        if (method == "setWidget") {
            val key = Js.gets(j, "widgetKey") ?: return
            val lines = j["widgetLines"] as? List<*>?
            val current = _widgets.value.toMutableMap()
            if (lines != null) current[key] = lines.map { it?.toString() ?: "" } else current.remove(key)
            _widgets.value = current
            return
        }
        if (method == "set_editor_text") {
            // TODO: apply to input field — requires callback to ViewModel
            return
        }

        // Dialog methods: add to pending requests
        val rawOpts = j["options"] as? List<*>
        val options = rawOpts?.map { it?.toString() ?: "" } ?: emptyList()

        val req = ExtensionUIRequest(
            id = id,
            method = method,
            title = Js.gets(j, "title") ?: "",
            message = Js.gets(j, "message"),
            options = options,
            placeholder = Js.gets(j, "placeholder"),
            prefill = Js.gets(j, "prefill"),
            timeout = (j["timeout"] as? Number)?.toLong()
        )

        _uiRequests.value = _uiRequests.value.toMutableList().apply { add(req) }
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
            else if (s[pp] == '[') pa()
            else if (s.startsWith("null", pp)) { pp += 4; null }
            else if (s.startsWith("true", pp)) { pp += 4; true }
            else if (s.startsWith("false", pp)) { pp += 5; false }
            else pn()
        }
    }
    private fun pa(): List<Any?> {
        ws(); we('['); ws()
        val list = mutableListOf<Any?>()
        if (pp < n && s[pp] == ']') { pp++; return list }
        while (true) {
            ws(); list.add(pv()); ws()
            if (pp < n && s[pp] == ',') { pp++; continue }
            we(']'); return list
        }
    }
    fun ps(): String {
        ws(); we('"'); val sb = StringBuilder()
        while (pp < n) {
            val c = s[pp]
            if (c == '\\') {
                pp++; val ec = s[pp]
                if (ec == 'u' && pp + 4 < n) {
                    val code = s.substring(pp + 1, pp + 5).toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); pp += 4 }
                    else sb.append(ec)
                } else {
                    sb.append(when(ec) { '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'b' -> '\b'; 'f' -> ''; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> ec })
                }
            } else if (c == '"') { pp++; return sb.toString() }
            else sb.append(c)
            pp++
        }
        return sb.toString()
    }
    private fun pn(): Any {
        val st = pp
        while (pp < n && (s[pp].isDigit() || s[pp] == '-' || s[pp] == '.' || s[pp] == 'e' || s[pp] == 'E' || s[pp] == '+')) pp++
        val raw = s.substring(st, pp)
        return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
            raw.toDoubleOrNull() ?: raw
        } else {
            raw.toLongOrNull() ?: raw
        }
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

// ── Extension UI ──

data class ExtensionUIRequest(
    val id: String,
    val method: String,       // "select" | "confirm" | "input" | "editor"
    val title: String = "",
    val message: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    val prefill: String? = null,
    val widgetKey: String? = null,
    val widgetLines: List<String>? = null,
    val widgetPlacement: String? = null,
    val statusKey: String? = null,
    val statusText: String? = null,
    val notifyType: String? = null,
    val text: String? = null,   // for set_editor_text
    val timeout: Long? = null
)

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

/** Banner/toast from extension_ui notify() */
data class BannerMessage(
    val content: String,
    val type: String,        // "info" | "warning" | "error"
    val timestamp: Long
)

/** Generic TUI render frame — any Pi extension UI component */
data class RenderFrame(
    val id: String,              // component ID for response matching
    val ansiLines: List<String>, // rendered ANSI-colored text lines
    val inputMode: String,       // "none" | "text" | "keys"
    val title: String = ""
)
