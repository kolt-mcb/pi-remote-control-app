package com.piremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import com.piremote.theme.CodeUtils

class PiWebSocket : WebSocketListener() {
    private var sock: WebSocket? = null
    private var pendingUrl: String = ""
    private var retryCount = 0
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── Per-agent state ────────────────────────────────────────────────
    // Each connected pi agent (host or peer) gets its own AgentState bag so
    // that two pis streaming in parallel don't smash each other's text
    // accumulators. The selected agent's flows are surfaced as the back-compat
    // messageFlow / busyFlow / etc. below, so the UI subscribes the same way
    // it did before — it just now follows whichever tab is active.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    class AgentState(val id: String) {
        // Display name + kind cache — populated from session_list. Optional;
        // events that arrive before session_list still get a working state.
        var name: String = ""
        var isSelf: Boolean = false

        val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        val assistingText = MutableStateFlow("")
        val thinking = MutableStateFlow("")
        val busy = MutableStateFlow(false)
        val turnSummary = MutableStateFlow<TurnSummary?>(null)

        // Streaming bookkeeping — was global before; now per-agent so two
        // simultaneous text streams don't interfere.
        var stxt = ""
        val tbufs = mutableMapOf<String, StringBuilder>()
        val turnToolCalls = mutableListOf<String>()
        var agentStartedAt = 0L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val agentMap = mutableMapOf<String, AgentState>()
    private val _agents = MutableStateFlow<List<AgentState>>(emptyList())
    val agentsFlow: StateFlow<List<AgentState>> get() = _agents

    // Which agent's chat the UI is currently showing. Null until the first
    // event arrives. Survives across reconnects so the user's tab choice sticks.
    private val _selectedAgentId = MutableStateFlow<String?>(null)
    val selectedAgentIdFlow: StateFlow<String?> get() = _selectedAgentId

    fun selectAgent(id: String) { if (agentMap.containsKey(id)) _selectedAgentId.value = id }

    /** Get or create the per-agent state for [id]. Newly-created agents auto-
     *  publish to agentsFlow and become the default selection if none yet. */
    private fun ensureAgent(id: String): AgentState =
        agentMap.getOrPut(id) {
            val s = AgentState(id)
            _agents.value = agentMap.values.toList()
            if (_selectedAgentId.value == null) _selectedAgentId.value = id
            s
        }

    // ── Back-compat flows: follow the selected agent ──────────────────
    // Defined as derived flatMapLatest of (_selectedAgentId → agent.<flow>) so
    // existing UI code that collected messageFlow/busyFlow/etc. transparently
    // sees the active tab's stream.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messageFlow: StateFlow<List<ChatMessage>> = _selectedAgentId
        .flatMapLatest { id -> agentMap[id]?.messages ?: MutableStateFlow(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val assistingTextFlow: StateFlow<String> = _selectedAgentId
        .flatMapLatest { id -> agentMap[id]?.assistingText ?: MutableStateFlow("") }
        .stateIn(scope, SharingStarted.Eagerly, "")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val busyFlow: StateFlow<Boolean> = _selectedAgentId
        .flatMapLatest { id -> agentMap[id]?.busy ?: MutableStateFlow(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val turnSummaryFlow: StateFlow<TurnSummary?> = _selectedAgentId
        .flatMapLatest { id -> agentMap[id]?.turnSummary ?: MutableStateFlow<TurnSummary?>(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // ── Global state (not per-agent) ──────────────────────────────────
    private val _sessions = MutableStateFlow<List<RemoteSession>>(emptyList())
    val sessionListFlow: StateFlow<List<RemoteSession>> get() = _sessions
    private val _commands = MutableStateFlow<List<RemoteCommand>>(emptyList())
    val commandListFlow: StateFlow<List<RemoteCommand>> get() = _commands
    private val _savedSessions = MutableStateFlow<List<SavedSession>>(emptyList())
    val savedSessionsFlow: StateFlow<List<SavedSession>> get() = _savedSessions
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

    // _busy / _a / _thinking / _turnSummary / stxt / tbufs / turnToolCalls /
    // agentStartedAt were moved into AgentState above. Per-agent now.

    // One-shot signal emitted on agent_end so the VM can post a "pi is ready"
    // notification when the app is backgrounded. SharedFlow (not StateFlow)
    // because each turn-end is its own event, not a state we re-emit.
    private val _agentDone = MutableSharedFlow<AgentDoneEvent>(extraBufferCapacity = 4)
    val agentDoneFlow: SharedFlow<AgentDoneEvent> get() = _agentDone

    data class AgentDoneEvent(
        val summary: TurnSummary?,
        val durationMs: Long
    )

    data class TurnSummary(
        val toolsUsed: List<ToolCallSummary>,
        val totalCalls: Int
    )
    data class ToolCallSummary(val name: String, val count: Int)

    fun connect(url: String) {
        pendingUrl = url
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
    /** Spawn a second pi process on the host. The new pi auto-loads this
     *  same extension, finds the WS port busy, falls back to peer mode, and
     *  joins as a separate agent — which the app surfaces as a new tab.
     *
     *  This is the correct path to "new session in the app": pi's extension
     *  runtime can't survive ctx.newSession() (state.staleMessage is set and
     *  never cleared), so we sidestep it by getting a fresh process.
     *
     *  If [sessionPath] is non-blank, the new pi is invoked with
     *  `--session <path>` to resume that saved session — this is how the
     *  saved-session browser surfaces a "tap to resume" action. */
    fun sendSpawnPeer(sessionPath: String = "") {
        val sp = if (sessionPath.isNotBlank()) ",\"sessionPath\":\"${Js.e(sessionPath)}\"" else ""
        sock?.send("{\"type\":\"spawn_peer\"$sp}")
    }

    /** Ask the host for its list of saved pi sessions. Reply arrives as
     *  type=saved_sessions; surfaced via [savedSessionsFlow]. */
    fun sendGetSavedSessions() {
        sock?.send("{\"type\":\"get_saved_sessions\"}")
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

    /**
     * Re-inject saved DB messages on reconnect. The DB schema doesn't track
     * agentId, so all saved messages land on a single placeholder "self"
     * AgentState. When session_list arrives and identifies the real self
     * agent, handleSessions migrates these messages onto that agent.
     */
    fun repoMessages(saved: List<ChatMessage>) {
        if (saved.isEmpty()) return
        val placeholder = ensureAgent(REPO_PLACEHOLDER_ID)
        placeholder.name = "pi"
        placeholder.isSelf = true
        placeholder.messages.value = placeholder.messages.value + saved
    }
    companion object {
        // Sentinel ID used for saved-message restore before any session_list
        // arrives. handleSessions promotes this state onto the real self agent
        // once its id is known.
        const val REPO_PLACEHOLDER_ID = "__repo_placeholder__"
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
        val json = "{\"type\":\"game_touch\",\"key\":\"$key\",\"pressed\":$pressed}"
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
        _s.value = ConnectionStatus.Connected
        retryCount = 0
        // Request session list and command list on connect
        sock?.send("{\"type\":\"get_sessions\"}")
        sock?.send("{\"type\":\"get_commands\"}")
    }
    override fun onFailure(ws: WebSocket, t: Throwable, r: okhttp3.Response?) {
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
        try { dispatch(txt) } catch (_: Throwable) {}
    }
    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        _s.value = ConnectionStatus.Disconnected
        // Clear busy on every known agent — no global busy flag now.
        agentMap.values.forEach { it.busy.value = false }
    }

    private fun dispatch(raw: String) {
        val j = JP.p(raw) ?: return
        val tp = Js.gets(j, "type") ?: return

        // Per-agent events: route to the right AgentState by event.agentId.
        // Extension stamps agentId on every agent_*/message_*/tool_*/turn_*
        // event before broadcast (see extension.ts emitAgentEvent), so any
        // event in the perAgentTypes set will have one.
        val perAgentTypes = setOf(
            "agent_start", "agent_end",
            "message_start", "message_end", "message_update",
            "tool_start", "tool_update", "tool_end",
            "turn_start", "turn_end"
        )
        if (tp in perAgentTypes) {
            val agentId = Js.gets(j, "agentId")
            if (agentId.isNullOrBlank()) return  // can't route without it
            val state = ensureAgent(agentId)
            when (tp) {
                "agent_start" -> {
                    state.busy.value = true
                    state.stxt = ""
                    state.tbufs.clear()
                    state.thinking.value = ""
                    state.turnToolCalls.clear()
                    state.agentStartedAt = System.currentTimeMillis()
                }
                "agent_end" -> {
                    state.busy.value = false
                    es(state, null)
                    buildTurnSummary(state)
                    val dur = if (state.agentStartedAt > 0) System.currentTimeMillis() - state.agentStartedAt else 0L
                    _agentDone.tryEmit(AgentDoneEvent(summary = state.turnSummary.value, durationMs = dur))
                    state.agentStartedAt = 0L
                }
                "message_start" -> msgStart(state, j)
                "message_end"   -> { val mm = JP.nj(j, "message"); if (mm != null) msgEnd(state, mm) }
                "message_update" -> msgUpd(state, j)
                "tool_start"  -> toolStart(state, j)
                "tool_update" -> toolUpdate(state, j)
                "tool_end"    -> toolEnd(state, j)
                // turn_start/turn_end no-op for now — could track turn index per agent
            }
            return
        }

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
        if (tp == "saved_sessions") handleSavedSessions(j)
        if (tp == "command_list") handleCommands(j)
        // Missing handlers added:
        if (tp == "connected") {
            val count = (j["clients"] as? Number)?.toInt() ?: 0
            _clientCount.value = count
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

    private fun msgStart(state: AgentState, j: Map<*, *>) {
        val mm = JP.nj(j, "message") ?: return
        val role = Js.gets(mm, "role") ?: return
        // pi normalizes message.content into an array of typed blocks
        // ([{type:"text",text:"hi"}, {type:"image",...}, ...]); plain-string
        // content also appears for some legacy paths. extractText handles both.
        val content = extractText(mm)
        if (role == "user") state.messages.value = state.messages.value + ChatMessage(type = MessageToolType.User, content = content)
        if (role == "toolResult") {
            val tid = Js.gets(mm, "toolCallId") ?: ""
            // Skip if tool_end already created this ToolResult from streaming
            if (!state.messages.value.any { it.toolCallId == tid && it.type == MessageToolType.ToolResult }) {
                state.messages.value = state.messages.value + ChatMessage(
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

    // Extract a plain-text rendering of a pi Message's content field.
    // Pi's wire format is normally an array of typed content blocks:
    //   [{type:"text",text:"..."}, {type:"thinking",thinking:"..."}, {type:"image",...}, ...]
    // Plain strings still show up for legacy paths and tool results. This helper
    // accepts both shapes and returns the concatenated text portion (skipping
    // thinking blocks — those are surfaced separately via the streaming protocol).
    private fun extractText(message: Map<*, *>): String {
        val c = message["content"] ?: return ""
        if (c is String) return c
        if (c is List<*>) {
            return c.mapNotNull { block ->
                if (block !is Map<*, *>) return@mapNotNull null
                when (block["type"] as? String) {
                    "text" -> block["text"] as? String
                    else  -> null   // skip thinking, image, tool_use, …
                }
            }.joinToString("")
        }
        return ""
    }

    private fun msgEnd(state: AgentState, j: Map<*, *>) {
        val role = Js.gets(j, "role")
        if (role == "assistant") es(state, null)
    }

    private fun msgUpd(state: AgentState, j: Map<*, *>) {
        val ev = Js.gets(j, "eventType") ?: return
        if (ev == "text_start") { state.stxt = ""; addP(state) }
        if (ev == "text_delta") { val d = Js.gets(j, "delta") ?: return; state.stxt += d; pushSt(state, state.stxt); state.assistingText.value = state.stxt }
        if (ev == "text_end") es(state, state.stxt)
        if (ev == "thinking_start") state.thinking.value = ""
        if (ev == "thinking_delta") { val d = Js.gets(j, "delta") ?: return; state.thinking.value += d; state.assistingText.value = state.thinking.value }
        if (ev == "thinking_end") {
            // Convert thinking text to a Thinking message type instead of dropping it
            val thinkingText = state.thinking.value
            if (thinkingText.isNotBlank()) {
                state.messages.value = state.messages.value + ChatMessage(type = MessageToolType.Thinking, content = thinkingText)
            } else {
                // Remove blank streaming placeholders
                state.messages.value = state.messages.value.filterNot { it.type == MessageToolType.Streaming && it.content.isBlank() }
            }
            state.thinking.value = ""
            // Don't clear stxt here: pi can interleave thinking and text streams,
            // and thinking_end often arrives AFTER text_delta has populated stxt
            // (e.g. Qwen3.6: thinking_start → text_start → text_delta → thinking_end
            //  → text_end). Wiping stxt mid-stream made text_end see empty content,
            // drop the Streaming bubble, and never produce the Assistant message.
            // The text stream's own text_end → es(stxt) clears stxt at the right time.
        }
        if (ev == "done") es(state, null)
        if (ev == "error") { es(state, "Err: " + (Js.gets(j, "message") ?: "")) }
    }

    private fun toolStart(state: AgentState, j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val nm = Js.gets(j, "toolName") ?: "?"
        // Re-serialize the parsed args back to JSON. j["args"] is a Map from
        // JP, and the old `.toString()` produced Kotlin's `{k=v}` format that
        // can't be re-parsed downstream — every renderer that tried fell back
        // to a 60-char preview.
        val argsJson = Js.write(j["args"])
        state.tbufs[id] = StringBuilder()
        state.turnToolCalls.add(nm)
        state.messages.value = state.messages.value + ChatMessage(toolCallId = id, type = MessageToolType.Streaming, toolName = nm, toolArgs = argsJson)
    }

    private fun toolUpdate(state: AgentState, j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val content = Js.gets(j, "content") ?: ""
        val buf = state.tbufs[id]
        if (buf != null) {
            buf.append(content)
            val ml = state.messages.value.toMutableList()
            val idx = ml.indexOfLast { it.toolCallId == id && it.type == MessageToolType.Streaming }
            if (idx >= 0) {
                ml[idx] = ml[idx].copy(content = buf.toString())
                state.messages.value = ml
            }
        }
    }

    private fun toolEnd(state: AgentState, j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val nm = Js.gets(j, "toolName") ?: "?"
        val remoteContent = Js.gets(j, "content") ?: ""
        val isError = (j["isError"] as? Boolean) ?: false
        val bufferedContent = state.tbufs[id]?.toString().orEmpty()
        val content = if (remoteContent.isNotBlank()) remoteContent else bufferedContent

        val ml = state.messages.value.toMutableList()
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
            state.messages.value = ml
        } else {
            state.messages.value = ml + ChatMessage(
                toolCallId = id, type = MessageToolType.ToolResult,
                toolName = nm, content = content, isError = isError
            )
        }
        state.tbufs.remove(id)
    }

    // Build a compact summary of tools used during the last turn
    private fun buildTurnSummary(state: AgentState) {
        if (state.turnToolCalls.isEmpty()) {
            state.turnSummary.value = null
            return
        }
        val counts = state.turnToolCalls.groupingBy { it }.eachCount()
        val toolsUsed = counts.entries
            .sortedByDescending { it.value }
            .map { (name, count) -> ToolCallSummary(name, count) }
        state.turnSummary.value = TurnSummary(
            toolsUsed = toolsUsed,
            totalCalls = state.turnToolCalls.size
        )
    }

    private fun addP(state: AgentState) {
        if (!state.messages.value.any { it.type == MessageToolType.Streaming && it.content == "" })
            state.messages.value = state.messages.value + ChatMessage(type = MessageToolType.Streaming)
    }
    private fun pushSt(state: AgentState, t: String) {
        // Match the last Streaming bubble unconditionally — pushSt is called with
        // the *accumulated* delta text, and predicating on content=="" meant only
        // the first delta ever landed in the persisted message; subsequent deltas
        // silently no-op'd and the bubble froze at the first chunk's text.
        val ml = state.messages.value.toMutableList()
        val idx = ml.indexOfLast { it.type == MessageToolType.Streaming }
        if (idx >= 0) { ml[idx] = ml[idx].copy(content = t); state.messages.value = ml }
    }
    private fun es(state: AgentState, finalText: String?) {
        val sub = finalText ?: state.stxt
        if (sub.isNotBlank()) {
            val ml = state.messages.value.toMutableList()
            val idx = ml.indexOfLast { it.type == MessageToolType.Streaming }
            // Always transition the last Streaming bubble in-place; the old
            // isEmpty() guard caused a second Assistant message to be appended
            // when the bubble had already been populated by pushSt.
            if (idx >= 0) {
                ml[idx] = ml[idx].copy(type = MessageToolType.Assistant, content = sub)
                state.messages.value = ml
            } else { state.messages.value = ml + ChatMessage(type = MessageToolType.Assistant, content = sub) }
        } else {
            state.messages.value = state.messages.value.filterNot { it.type == MessageToolType.Streaming && it.content.isBlank() }
        }
        state.stxt = ""
        state.assistingText.value = ""
    }
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

        // Make sure every connected pi has an AgentState bucket, with name/
        // kind populated from the session list so the tab row can label them.
        for (s in list) {
            if (s.id.isBlank()) continue
            val a = ensureAgent(s.id)
            a.name = s.name
            a.isSelf = s.isSelf
        }
        // If the placeholder agent (from saved-DB restore) is still around,
        // promote it onto the real self agent now that we know its id.
        val placeholder = agentMap[REPO_PLACEHOLDER_ID]
        val self = list.firstOrNull { it.isSelf }
        if (placeholder != null && self != null && self.id != REPO_PLACEHOLDER_ID) {
            val selfState = ensureAgent(self.id)
            // Merge: prepend placeholder's saved history before any live
            // messages the self agent has already accumulated.
            selfState.messages.value = placeholder.messages.value + selfState.messages.value
            agentMap.remove(REPO_PLACEHOLDER_ID)
            _agents.value = agentMap.values.toList()
            if (_selectedAgentId.value == REPO_PLACEHOLDER_ID) _selectedAgentId.value = self.id
        }
        // Prefer the self agent as the default selection if nothing is selected
        // (or the selection points at a vanished agent).
        val curr = _selectedAgentId.value
        val stillPresent = curr != null && agentMap.containsKey(curr)
        if (!stillPresent) _selectedAgentId.value = self?.id ?: list.firstOrNull()?.id
    }

    private fun handleSavedSessions(j: Map<*, *>) {
        val arr = j["sessions"] as? List<*> ?: return
        _savedSessions.value = arr.mapNotNull { s ->
            if (s !is Map<*, *>) return@mapNotNull null
            SavedSession(
                path = (s["path"] as? String) ?: return@mapNotNull null,
                name = (s["name"] as? String) ?: "",
                firstMessage = (s["firstMessage"] as? String) ?: "",
                messageCount = ((s["messageCount"] as? Number) ?: 0).toInt(),
                modified = ((s["modified"] as? Number) ?: 0).toLong()
            )
        }
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
    /** Round-trip a value parsed by JP back into a JSON string. Counterpart to
     *  PS.pv() — handles the same primitive set the parser produces. */
    fun write(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> v.toString()
        is String -> "\"${e(v)}\""
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, vv) -> "\"${e(k.toString())}\":${write(vv)}" }
        is List<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> "\"${e(v.toString())}\""
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
    val timeout: Long? = null
)

// ── Session model ──

data class RemoteCommand(
    val name: String,
    val description: String
)

/** A saved pi session — name, first message preview, message count, last
 *  modified epoch ms. Tap-to-resume from the saved-session browser sends
 *  spawn_peer with sessionPath = [path], which the extension launches as
 *  `pi --session <path>` in a new process. */
data class SavedSession(
    val path: String,
    val name: String,
    val firstMessage: String,
    val messageCount: Int,
    val modified: Long
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
