package com.piremote.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.test.TestState
import com.piremote.*
import com.piremote.theme.*

/**
 * Two-pane tablet layout: session sidebar on the left, chat on the right.
 * Shown when the window width is medium or expanded.
 */
@Composable
fun TabletLayout(
    vm: ChatViewModel,
    st: ChatUIState,
    url: String,
    input: String,
    messages: List<ChatMessage>,
    assist: String,
    status: ConnectionStatus,
    busy: Boolean,
    sessions: List<RemoteSession>,
    selectedSession: String,
    commands: List<RemoteCommand>,
    statuses: Map<String, String>,
    widgets: Map<String, List<String>>,
    compacting: Boolean,
    notifyBanners: List<BannerMessage>,
    uiTitle: String?,
    clientCount: Int,
    turnSummary: PiWebSocket.TurnSummary?,
    savedSessions: List<SavedSession>,
    renderFrame: RenderFrame?,
) {
    // Dialog requests (same as phone)
    val uiRequests = st.uiRequests.collectAsState()
    val selReq = uiRequests.value.firstOrNull { it.method in listOf("select", "confirm") }
    val inpReq = uiRequests.value.firstOrNull { it.method in listOf("input", "editor") }

    fun uiRespond(id: String, value: String) = TestState.ws.sendUIResponse(id, value = value)
    fun uiCancelled(id: String) = TestState.ws.sendUIResponse(id, cancelled = true)
    fun renderInput(id: String, value: String) = TestState.ws.sendInput(id, value)

    // Collect remaining flows
    val inp by st.inputText.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Left: Session Sidebar ────────────────────────────────
        TabletSidebar(
            sessions = sessions,
            selectedSession = selectedSession,
            compacting = compacting,
            clientCount = clientCount,
            savedSessions = savedSessions,
            onSessionSelect = { id ->
                vm.setSelectedSession(id)
                vm.showChatScreen()
            },
            onNewSession = { vm.spawnPeer() },
            onDisconnect = { vm.disconnect() },
            onCloseSession = vm::closeSession,
            onSavedSessionTap = { path -> vm.spawnPeerWithSession(path) },
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(bgSecondary)
                .border(0.5.dp, border, RoundedCornerShape(0.dp))
        )

        // Divider between sidebar and chat
        Box(modifier = Modifier.fillMaxHeight().width(0.5.dp).background(border))

        // ── Right: Chat Area ─────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            ChatScreen(
                vm = vm,
                url = url,
                input = input,
                messages = messages,
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
                showSessionSelector = false,
            )

            // Overlay dialogs (same as phone)
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

/**
 * Tablet session sidebar. Combines active sessions, saved sessions, and
 * connection controls into a scrollable vertical panel.
 */
@Composable
fun TabletSidebar(
    sessions: List<RemoteSession>,
    selectedSession: String,
    compacting: Boolean,
    clientCount: Int,
    savedSessions: List<SavedSession>,
    onSessionSelect: (String) -> Unit,
    onNewSession: () -> Unit,
    onDisconnect: () -> Unit,
    onCloseSession: (String) -> Unit,
    onSavedSessionTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf("all") }
    val filteredSessions = if (selectedCategory == "all") savedSessions else savedSessions.filter { it.status == selectedCategory }
    val counts = savedSessions.groupingBy { it.status }.eachCount()
    val totalCount = savedSessions.size

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ── Connection header ────────────────────────────────
        item {
            PiBox(header = "Pi Remote", borderColor = accent) {
                Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Terminal Mode",
                            color = accent,
                            fontFamily = piMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .border(1.dp, error, RoundedCornerShape(0.dp))
                                .clickable { onDisconnect() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("[Disconnect]", color = error, fontFamily = piMono, fontSize = 9.sp)
                        }
                    }
                    if (sessions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${sessions.size} agent${if (sessions.size != 1) "s" else ""} connected  •  ${clientCount} viewer${if (clientCount != 1) "s" else ""}",
                            color = textMuted, fontFamily = piMono, fontSize = 9.sp,
                        )
                    }
                }
            }
        }

        // ── Active sessions header + [+ New] ─────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Active sessions", color = textSecondary, fontFamily = piMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier
                        .border(0.5.dp, accent, RoundedCornerShape(0.dp))
                        .clickable { onNewSession() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("[+ New]", color = accent, fontFamily = piMono, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // ── Active session items ─────────────────────────────
        if (sessions.isEmpty()) {
            item {
                PiBox(borderColor = borderMuted) {
                    Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
                        Text("No sessions", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        Text("will appear when agents connect", color = textMuted.copy(alpha = 0.6f), fontFamily = piMono, fontSize = 9.sp)
                    }
                }
            }
        } else {
            items(sessions, key = { it.id }) { session ->
                SidebarSessionItem(
                    session = session,
                    isSelected = session.id == selectedSession,
                    isBusy = session.status == "busy",
                    onSelect = { onSessionSelect(session.id) },
                    onClose = { onCloseSession(session.id) },
                )
            }
        }

        // Divider
        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = borderMuted)
            Spacer(Modifier.height(4.dp))
        }

        // ── Saved sessions header ────────────────────────────
        item {
            Text("Saved sessions", color = textSecondary, fontFamily = piMono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }

        // ── Category filter tabs ─────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf("all" to "All", "working" to "Working", "completed" to "Done", "archived" to "Archived").forEach { (key, label) ->
                    val isActive = key == selectedCategory
                    val count = if (key == "all") totalCount else (counts[key] ?: 0)
                    val color = if (isActive) accent else textMuted
                    val bgC = if (isActive) selectedBg else Color.Transparent
                    Box(
                        modifier = Modifier
                            .background(bgC)
                            .border(1.dp, if (isActive) accent else borderMuted, RoundedCornerShape(0.dp))
                            .clickable { selectedCategory = key }
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("$label $count", color = color, fontFamily = piMono, fontSize = 8.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }

        // ── Saved session items ──────────────────────────────
        if (filteredSessions.isEmpty()) {
            item {
                Text(
                    if (totalCount == 0) "No saved sessions yet." else "No $selectedCategory sessions.",
                    color = textMuted, fontFamily = piMono, fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        } else {
            items(filteredSessions, key = { it.path }) { saved ->
                SidebarSavedSessionItem(s = saved, onTap = { onSavedSessionTap(saved.path) })
            }
        }

        // Spacer to push content up
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Compact session item for the sidebar. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SidebarSessionItem(
    session: RemoteSession,
    isSelected: Boolean,
    isBusy: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val borderColor = when {
        isSelected -> accent
        isBusy -> thinkingBorder.copy(alpha = 0.7f)
        else -> borderMuted
    }

    PiBox(
        header = sessionLabel(session.name, 20),
        borderColor = borderColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onSelect() },
                    onLongClick = { if (session.kind != "self" && !isBusy) onClose() }
                )
                .padding(vertical = 3.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(modifier = Modifier.size(5.dp).background(
                when {
                    isBusy -> thinkingBorder
                    isSelected -> accent
                    else -> success
                },
                CircleShape,
            ))
            // Status text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        isBusy -> "busy"
                        isSelected -> "active"
                        else -> "idle"
                    },
                    color = when {
                        isBusy -> thinkingBorder
                        isSelected -> accent
                        else -> success
                    },
                    fontFamily = piMono, fontSize = 9.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    "${session.messageCount} msgs · ${sessionTime(session.lastActivity)}",
                    color = textMuted, fontFamily = piMono, fontSize = 8.sp,
                )
            }
            // Kind badge
            Text(
                if (session.kind == "self") "(host)" else "(peer)",
                color = textMuted, fontFamily = piMono, fontSize = 8.sp,
            )
        }
    }
}

/** Compact saved session item for the sidebar. */
@Composable
fun SidebarSavedSessionItem(s: SavedSession, onTap: () -> Unit) {
    val label = s.name.ifBlank { s.firstMessage }.ifBlank { s.path.substringAfterLast('/') }
    val trimmed = sessionLabel(label, 25)
    val statusColor = when (s.status) {
        "working" -> thinkingBorder
        "archived" -> textMuted
        else -> success
    }
    val statusIcon = when (s.status) {
        "working" -> "◉"
        "archived" -> "⊘"
        else -> "✓"
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▸ ", color = accent, fontFamily = piMono, fontSize = 9.sp)
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(trimmed, color = textPrimary, fontFamily = piMono, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (s.status != "completed") {
                    Text("$statusIcon", color = statusColor, fontFamily = piMono, fontSize = 8.sp)
                }
            }
            Text("${s.messageCount} msgs · ${sessionTime(s.modified)}",
                color = textMuted, fontFamily = piMono, fontSize = 8.sp)
        }
    }
}
