package com.piremote.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.*
import com.piremote.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

// ── Pi Terminal Font Family ────────────────────────────────────────────

val piMono = FontFamily.Monospace

// ── Pi Terminal Border Helpers ─────────────────────────────────────────

/** Draw a single-line terminal-style top border: ┌─ title ──────┐ */
@Composable
fun PiTopBorder(label: String? = null, color: Color = border) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("┌", color = color, fontFamily = piMono, fontSize = 11.sp)
        if (!label.isNullOrEmpty()) {
            Text("─ $label ──", color = color, fontFamily = piMono, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("┐", color = color, fontFamily = piMono, fontSize = 11.sp)
    }
}

/** Draw a single-line terminal-style bottom border: └──────────┘ */
@Composable
fun PiBottomBorder(color: Color = border) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("└", color = color, fontFamily = piMono, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("┘", color = color, fontFamily = piMono, fontSize = 11.sp)
    }
}

/** Left gutter with sidebar: │ content */
@Composable
fun PiGutter(
    color: Color = border,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("│", color = color, fontFamily = piMono, fontSize = 11.sp)
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

/** Full bordered box: ┌─ header ─┐ / │ body │ / └──────┘ */
@Composable
fun PiBox(
    header: String? = null,
    borderColor: Color = border,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        if (!header.isNullOrEmpty() || borderColor != borderMuted) {
            PiTopBorder(header, borderColor)
        }
        PiGutter(borderColor, content)
        if (!header.isNullOrEmpty() || borderColor != borderMuted) {
            PiBottomBorder(borderColor)
        }
    }
}

// ── Rounded box variant (Claude Code style: ╭─╮│╰─╯) ───────────────────

@Composable
fun PiRoundedTopBorder(label: String? = null, color: Color = border) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("╭", color = color, fontFamily = piMono, fontSize = 11.sp)
        if (!label.isNullOrEmpty()) {
            Text("─ $label ──", color = color, fontFamily = piMono, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("╮", color = color, fontFamily = piMono, fontSize = 11.sp)
    }
}

@Composable
fun PiRoundedBottomBorder(color: Color = border) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("╰", color = color, fontFamily = piMono, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("╯", color = color, fontFamily = piMono, fontSize = 11.sp)
    }
}

/** Rounded-corner bordered box: ╭─ header ─╮ / │ body │ / ╰──────╯ */
@Composable
fun PiRoundedBox(
    header: String? = null,
    borderColor: Color = border,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        PiRoundedTopBorder(header, borderColor)
        PiGutter(borderColor, content)
        PiRoundedBottomBorder(borderColor)
    }
}

// ── Pi-Style Connect Screen (Menu) ─────────────────────────────────────

@Composable
fun ConnectScreen(
    vm: ChatViewModel, url: String, input: String, messages: List<ChatMessage>,
    assist: String, status: ConnectionStatus, urlHistory: Set<String>,
    sessions: List<com.piremote.RemoteSession> = emptyList()
) {
    var t by remember { mutableStateOf(url) }
    var showScanner by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf(false) }

    if (showScanner) {
        com.piremote.scan.QrScanner(
            initialUrl = t,
            onConnected = { scanned: String ->
                val wsUrl = if (scanned.startsWith("piremote://")) scanned.replaceFirst("piremote://", "ws://") else scanned
                t = wsUrl
                vm.setServerUrl(wsUrl); vm.connect(); showScanner = false
            },
            onClose = { showScanner = false }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Header bar
            PiBox(header = "Pi Remote", borderColor = accent) {
                Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) {
                    Text(
                        "Pi Remote Control — Terminal Mode",
                        color = accent, fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Control your Pi agent from your phone",
                        color = textMuted, fontFamily = piMono, fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // Menu
            PiBox(header = "Options") {
                Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
                    PiMenuItem(label = "1", title = "Connect to Pi server", action = { inputMode = true })
                    PiMenuItem(label = "2", title = "Scan QR code", action = { showScanner = true })
                    PiMenuItem(label = "3", title = "Recent connections", action = { if (urlHistory.isNotEmpty()) inputMode = true })
                    
                    // URL input area
                    if (inputMode) {
                        Spacer(Modifier.height(8.dp))
                        PiBox(header = "URL", borderColor = accent) {
                            Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
                                TextField(
                                    value = t,
                                    onValueChange = { t = it },
                                    placeholder = { Text("ws://192.168.1.100:8765", color = textMuted, fontFamily = piMono) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = bg,
                                        unfocusedContainerColor = bg,
                                        focusedIndicatorColor = accent,
                                        unfocusedIndicatorColor = border,
                                        focusedTextColor = textPrimary,
                                        unfocusedTextColor = textPrimary,
                                        cursorColor = accent
                                    ),
                                    textStyle = LocalTextStyle.current.copy(color = textPrimary, fontFamily = piMono, fontSize = 12.sp),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                                        val u = t.ifEmpty { "ws://192.168.1.100:8765" }
                                        vm.setServerUrl(u); vm.connect()
                                    })
                                )
                                
                                // Recent connections
                                if (urlHistory.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("Recent:", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                                    Spacer(Modifier.height(3.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                        urlHistory.take(5).forEach { histUrl ->
                                            PiTerminalChip(histUrl, onClick = { t = histUrl })
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        
                        // Connect button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, accent, RoundedCornerShape(0.dp))
                                .clickable {
                                    val u = t.ifEmpty { "ws://192.168.1.100:8765" }
                                    vm.setServerUrl(u); vm.connect()
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text("Connect", color = accent, fontFamily = piMono, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                    
                    // Connection status
                    Spacer(Modifier.height(10.dp))
                    when {
                        status is ConnectionStatus.Connecting -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = accent, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Text("Connecting...", color = accent, fontFamily = piMono, fontSize = 11.sp)
                            }
                        }
                        status is ConnectionStatus.Error -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("✕ ${status.message}", color = error, fontFamily = piMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, error, RoundedCornerShape(0.dp))
                                        .clickable { vm.connect() }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Retry", color = error, fontFamily = piMono, fontSize = 10.sp)
                                }
                            }
                        }
                        status is ConnectionStatus.Connected -> {
                            Text("● Connected", color = success, fontFamily = piMono, fontSize = 11.sp)
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Quick Start Guide
            PiBox(header = "Quick Start", borderColor = borderMuted) {
                Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
                    Text("1  Run:  pi -e ~/pi-remote-control/extension.ts  ", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
                    Text("2  In pi:  /remote-control                         ", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
                    Text("3  Enter the ws:// URL shown above                ", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
                }
            }
            
            Spacer(Modifier.weight(1f))
        }
        
        // Version in bottom-right
        Text("v0.1.0", color = textMuted.copy(alpha = 0.3f), fontFamily = piMono, fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

/** Terminal-style menu item */
@Composable
fun PiMenuItem(label: String, title: String, action: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { action() }
            .padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("  ", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
        Text("[${label}]", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
        Text(title, color = textSecondary, fontFamily = piMono, fontSize = 12.sp)
    }
}

/** Terminal-style chip for URLs */
@Composable
fun PiTerminalChip(label: String, onClick: () -> Unit) {
    val short = label.take(22) + if (label.length > 22) "…" else ""
    Text(
        short,
        color = accent, fontFamily = piMono, fontSize = 10.sp,
        modifier = Modifier
            .clickable { onClick() }
            .border(0.5.dp, borderAccent, RoundedCornerShape(0.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

// ── Pi Terminal Sessions Screen ────────────────────────────────────────

@Composable
fun SessionsScreen(
    vm: ChatViewModel,
    sessions: List<com.piremote.RemoteSession>,
    selectedSession: String,
    compacting: Boolean = false,
    retryStatus: String? = null,
    clientCount: Int = 0
) {
    val activeSession = sessions.find { it.id == selectedSession }
    
    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            PiBox(header = "Sessions", borderColor = accent) {
                Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) {
                    Text(
                        "Active Sessions — Tap to switch", color = accent,
                        fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${sessions.size} agent${if (sessions.size != 1) "s" else ""} connected  •  ${clientCount} viewer${if (clientCount != 1) "s" else ""}",
                        color = textMuted, fontFamily = piMono, fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // Session list
            if (sessions.isEmpty()) {
                PiBox(borderColor = borderMuted) {
                    Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp)) {
                        Text("No sessions available", color = textMuted, fontFamily = piMono, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Sessions will appear when agents connect", color = textMuted.copy(alpha = 0.6f), fontFamily = piMono, fontSize = 10.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        val isSelected = session.id == selectedSession
                        val isBusy = session.status == "busy"
                        SessionCard(session, isSelected, isBusy) { vm.setSelectedSession(session.id); vm.showChatScreen() }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Nav hint
            PiBox(borderColor = borderMuted) {
                Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 10.dp)) {
                    Text("▸ Back: disconnect", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

/** Card for a single session in the sessions list */
@Composable
fun SessionCard(session: com.piremote.RemoteSession, isSelected: Boolean, isBusy: Boolean, onClick: () -> Unit) {
    PiBox(
        header = session.name.take(16),
        borderColor = if (isSelected) accent else if (isBusy) thinkingBorder.copy(alpha = 0.7f) else borderMuted
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 6.dp, horizontal = 8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Status dot
                Box(modifier = Modifier.size(7.dp).background(
                    when {
                        isBusy -> thinkingBorder
                        isSelected -> accent
                        else -> success
                    },
                    CircleShape
                ))

                // Name and kind
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            isBusy -> Text("busy", color = thinkingBorder, fontFamily = piMono, fontSize = 10.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold)
                            isSelected -> Text("active", color = accent, fontFamily = piMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            else -> Text("idle", color = success, fontFamily = piMono, fontSize = 10.sp)
                        }
                        if (session.kind == "self") {
                            Text("(host)", color = textMuted, fontFamily = piMono, fontSize = 9.sp)
                        } else {
                            Text("(peer)", color = textMuted, fontFamily = piMono, fontSize = 9.sp)
                        }
                    }
                }

                // Status label
                when {
                    isBusy -> Text("working", color = thinkingMedium, fontFamily = piMono, fontSize = 9.sp)
                    isSelected -> Text("✓", color = accent, fontFamily = piMono, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Stats row
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${session.messageCount} msgs", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                Text("turn ${session.turnIndex}", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(sessionTime(session.lastActivity), color = textMuted.copy(alpha = 0.6f), fontFamily = piMono, fontSize = 9.sp)
            }

            if (isBusy) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(8.dp), color = thinkingBorder, strokeWidth = 1.5.dp)
                    Text("agent is processing...", color = thinkingBorder, fontFamily = piMono, fontSize = 9.sp, fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}

// ── Pi Terminal Header ─────────────────────────────────────────────────

@Composable
fun PiHeader(status: ConnectionStatus, busy: Boolean, disconnect: () -> Unit, title: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().background(bgSecondary)) {
        androidx.compose.material3.HorizontalDivider(color = border, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(7.dp).background(
                    when (status) {
                        is ConnectionStatus.Connected -> if (busy) thinkingBorder else success
                        is ConnectionStatus.Connecting -> accent
                        is ConnectionStatus.Error -> error
                        else -> textMuted
                    },
                    CircleShape
                ))
                Text(
                    title?.take(30) ?: "Pi Remote",
                    color = accent, fontFamily = piMono, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                if (busy) Text("(thinking)", color = thinkingBorder, fontFamily = piMono, fontSize = 10.sp, fontStyle = FontStyle.Italic)
            }
            Box(
                modifier = Modifier
                    .border(1.dp, error, RoundedCornerShape(0.dp))
                    .clickable { disconnect() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("[Disconnect]", color = error, fontFamily = piMono, fontSize = 10.sp)
            }
        }
    }
}

// ── Pi Terminal Footer ─────────────────────────────────────────────────

@Composable
fun PiFooter(
    sessions: List<com.piremote.RemoteSession>,
    selectedSession: String,
    messageCount: Int,
    compacting: Boolean = false,
    retryStatus: String? = null,
    clientCount: Int = 0
) {
    Column(modifier = Modifier.fillMaxWidth().background(footerBg)) {
        androidx.compose.material3.HorizontalDivider(color = border, thickness = 1.dp)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val active = sessions.find { it.id == selectedSession }
            Text(
                "session: ${active?.name ?: "none"}",
                color = if (active?.status == "busy") thinkingBorder else accent,
                fontFamily = piMono, fontSize = 10.sp
            )
            Text("agents: ${sessions.size}", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
            if (clientCount > 0) Text("connected: $clientCount", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("messages: $messageCount", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
        }
        if (compacting) {
            androidx.compose.material3.HorizontalDivider(color = borderMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(10.dp), color = thinkingBorder, strokeWidth = 1.5.dp)
                Text("compacting...", color = thinkingBorder, fontFamily = piMono, fontSize = 10.sp, fontStyle = FontStyle.Italic)
            }
        }
    }
}

// ── Pi Terminal Session Selector ───────────────────────────────────────

@Composable
fun PiSessionSelector(sessions: List<com.piremote.RemoteSession>, selected: String, onSelect: (String) -> Unit) {
    if (sessions.size < 2) {
        sessions.firstOrNull()?.let { sess ->
            val isActive = sess.status == "busy"
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).background(bgSecondary),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(5.dp).background(if (isActive) thinkingBorder else success, CircleShape))
                Text(sess.name, color = textPrimary, fontFamily = piMono, fontSize = 11.sp)
                Text(if (isActive) "busy" else "idle",
                    color = if (isActive) thinkingBorder else textMuted, fontFamily = piMono, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text("${sess.messageCount} msgs • ${sessionTime(sess.lastActivity)}",
                    color = textMuted, fontFamily = piMono, fontSize = 10.sp)
            }
        }
        return
    }
    
    PiBox(header = "Sessions", borderColor = borderMuted) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sessions.forEachIndexed { i, sess ->
                val isSelected = sess.id == selected
                val isActive = sess.status == "busy"
                
                Box(
                    modifier = Modifier
                        .clickable { onSelect(sess.id) }
                        .background(if (isSelected) selectedBg else Color.Transparent)
                        .border(1.dp, if (isSelected) accent else borderMuted, RoundedCornerShape(0.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(5.dp).background(if (isActive) thinkingBorder else success, CircleShape))
                        Text(
                            sess.name.take(10),
                            color = if (isSelected) textPrimary else textSecondary,
                            fontFamily = piMono, fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

// ── Pi Terminal Chat Screen ────────────────────────────────────────────

@Composable
fun ChatScreen(
    vm: ChatViewModel, url: String, input: String, messages: List<ChatMessage>,
    assist: String, status: ConnectionStatus, busy: Boolean,
    sessions: List<com.piremote.RemoteSession> = emptyList(),
    selectedSession: String = "",
    commands: List<com.piremote.RemoteCommand> = emptyList(),
    statuses: Map<String, String> = emptyMap(),
    widgets: Map<String, List<String>> = emptyMap(),
    compacting: Boolean = false,
    retryStatus: String? = null,
    notifyBanners: List<com.piremote.BannerMessage> = emptyList(),
    uiTitle: String? = null,
    clientCount: Int = 0,
    attachedImages: List<Uri> = emptyList(),
    onPickImages: () -> Unit = {},
    onRemoveImage: (Uri) -> Unit = {}
) {
    val ls = rememberLazyListState()
    val cnt = messages.size + if (assist.isNotBlank()) 2 else 0
    LaunchedEffect(cnt) {
        if (cnt > 0 && ls.layoutInfo.totalItemsCount > 0) {
            ls.animateScrollToItem(cnt.coerceAtMost(ls.layoutInfo.totalItemsCount - 1))
        }
    }

    // Mode state lifted up so PiWorkingStatus can flip it on tap-to-interrupt.
    var steerMode by remember { mutableStateOf(false) }
    var followUpMode by remember { mutableStateOf(false) }
    // Reset modes when busy changes — busy→idle clears steer; idle→busy clears follow-up.
    LaunchedEffect(busy) {
        if (!busy) steerMode = false
        else followUpMode = false
    }
    // Track when busy started so the working status line can show elapsed seconds.
    var busyStartedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(busy) {
        if (busy) busyStartedAt = System.currentTimeMillis()
    }

    Column(modifier = Modifier.fillMaxSize().background(bg).imePadding()) {
        PiHeader(status, busy, { vm.disconnect() }, uiTitle?.take(30))

        if (sessions.isNotEmpty()) {
            PiSessionSelector(
                sessions,
                if (selectedSession.isNotBlank()) selectedSession else sessions.firstOrNull()?.id ?: "",
                { vm.setSelectedSession(it) }
            )
        }

        StatusBarLine(statuses)
        widgets.forEach { (key, lines) -> WidgetPanel(key, lines) }
        notifyBanners.forEach { NotifyBanner(it.content, it.type) }

        // Chat Messages Area — flat scrollback, no card padding.
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = ls,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(messages, key = { idx, msg -> "${idx}_${msg.id}_${msg.timestamp}" }) { _, msg ->
                    PiMessageBubble(msg)
                }
                if (assist.isNotBlank()) {
                    item(key = "streaming") {
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 12.dp, top = 2.dp, end = 4.dp, bottom = 2.dp)) {
                            // Stream-time markdown rendering matches the final render
                            // so users don't see a flash when streaming completes.
                            PiMarkdown(
                                text = assist,
                                baseColor = assistantText,
                                baseSize = 13.sp,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            PiBlinkBlock(color = accent)
                        }
                    }
                }
                item(key = "spacer") { Spacer(Modifier.height(6.dp)) }
            }
        }

        // Animated working status line — Claude Code "✻ Pondering…" feel.
        if (busy) {
            PiWorkingStatus(
                busyStartedAt = busyStartedAt,
                onInterrupt = { steerMode = true }
            )
        }

        PiInputEditor(
            vm = vm,
            input = input,
            busy = busy,
            hasMessages = messages.isNotEmpty(),
            commands = commands,
            steerMode = steerMode,
            setSteerMode = { steerMode = it },
            followUpMode = followUpMode,
            setFollowUpMode = { followUpMode = it },
            attachedImages = attachedImages,
            onPickImages = onPickImages,
            onRemoveImage = onRemoveImage
        )
        PiFooter(sessions, selectedSession, messages.size, compacting, retryStatus, clientCount)
    }
}

// ── Animated working-status line (matches pi's loader) ────────────────

// Pi uses the standard cli-spinners "dots" set, advanced every 80ms.
// Mirrored here verbatim so the app reads like pi's own footer.
private val piSpinnerFrames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

/**
 * `⠋ Working... (5s · tap to interrupt)` floating just above the input.
 *
 * Verbatim copy of pi's working indicator: braille-dot spinner + literal
 * "Working..." text, no cycling verbs. The (esc to interrupt) hint
 * becomes (tap to interrupt) because we have no esc on touch — tap arms
 * steer mode so the user's next message lands as a course-correction.
 * Elapsed seconds are appended because they're useful on a remote.
 */
@Composable
fun PiWorkingStatus(busyStartedAt: Long, onInterrupt: () -> Unit) {
    // 80ms tick for the spinner — exact match for pi's DEFAULT_INTERVAL_MS.
    var frameIdx by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(80)
            frameIdx = (frameIdx + 1) % piSpinnerFrames.size
        }
    }
    // 1s tick for elapsed-seconds counter.
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(busyStartedAt) {
        while (true) {
            elapsed = ((System.currentTimeMillis() - busyStartedAt) / 1000).coerceAtLeast(0)
            kotlinx.coroutines.delay(500)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(bg)
            .clickable { onInterrupt() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(piSpinnerFrames[frameIdx], color = accent, fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("Working...", color = textMuted, fontFamily = piMono, fontSize = 12.sp)
        Text("(${elapsed}s · tap to interrupt)", color = textMuted.copy(alpha = 0.7f), fontFamily = piMono, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
    }
}

/** Blinking block cursor for streaming text. */
@Composable
fun PiBlinkBlock(color: Color = accent) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); on = !on } }
    Text(
        if (on) "▌" else " ",
        color = color, fontFamily = piMono, fontSize = 13.sp
    )
}

// ── Pi Terminal Input Editor ───────────────────────────────────────────

/**
 * Compact accessory row of hard-to-reach keys, sized to live just above the
 * IME so users don't have to dig through the symbol layer for `/ @ ! ` ` tab`.
 * Horizontally scrollable — add more keys without reflowing the layout.
 *
 * Esc collapses steer/follow-up mode if active, otherwise clears the input.
 * Tab inserts a literal "\t" (pi's editor renders it as four spaces).
 */
@Composable
fun PiHotkeyBar(
    input: String,
    inSpecialMode: Boolean,
    onInsert: (String) -> Unit,
    onEsc: () -> Unit
) {
    val keys = listOf("/", "@", "!", "`", "~", "\$", "|", "↹")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Esc — special action button, styled distinctly.
        val escEnabled = inSpecialMode || input.isNotEmpty()
        Box(
            modifier = Modifier
                .border(1.dp, if (escEnabled) accent else borderMuted, RoundedCornerShape(0.dp))
                .background(bgSecondary)
                .clickable(enabled = escEnabled, onClick = onEsc)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                "Esc",
                color = if (escEnabled) accent else textMuted,
                fontFamily = piMono,
                fontSize = 12.sp
            )
        }
        // Vertical separator.
        Box(modifier = Modifier.width(1.dp).height(20.dp).background(borderMuted))
        // Char inserters.
        for (k in keys) {
            Box(
                modifier = Modifier
                    .border(1.dp, borderMuted, RoundedCornerShape(0.dp))
                    .background(bgSecondary)
                    .clickable { onInsert(if (k == "↹") "\t" else k) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(k, color = textPrimary, fontFamily = piMono, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun PiInputEditor(
    vm: ChatViewModel, input: String, busy: Boolean, hasMessages: Boolean,
    commands: List<com.piremote.RemoteCommand>,
    steerMode: Boolean,
    setSteerMode: (Boolean) -> Unit,
    followUpMode: Boolean,
    setFollowUpMode: (Boolean) -> Unit,
    attachedImages: List<Uri> = emptyList(),
    onPickImages: () -> Unit = {},
    onRemoveImage: (Uri) -> Unit = {}
) {
    val editorBorderColor = when {
        steerMode -> thinkingMedium
        followUpMode -> accent
        busy -> thinkingBorder
        else -> borderMuted
    }

    val editorLabel = when {
        steerMode -> "STEER"
        followUpMode -> "FOLLOW-UP"
        else -> ""
    }

    Column(modifier = Modifier.background(bgSecondary)) {
        // Slash-command suggestions
        if (input.startsWith("/") && commands.isNotEmpty()) {
            val query = input.removePrefix("/").substringBefore(' ').lowercase()
            val filtered = commands.filter { it.name.lowercase().startsWith(query) }.take(8)
            if (filtered.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().background(bg)) {
                    Column {
                        androidx.compose.material3.HorizontalDivider(color = border, thickness = 1.dp)
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
                            filtered.forEach { cmd ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { vm.setInputText("/${cmd.name} ") }
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("/${cmd.name}", color = accent, fontFamily = piMono, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    if (cmd.description.isNotBlank()) Text(cmd.description, color = textMuted, fontFamily = piMono, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Editor border — rounded ╭─╮ to match Claude Code's input box.
        PiRoundedBox(header = editorLabel.ifEmpty { null }, borderColor = editorBorderColor) {
            Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
                // Mode indicator
                if (steerMode) {
                    Text("▸ Steering agent mid-flight…", color = thinkingMedium, fontFamily = piMono, fontSize = 10.sp,
                        modifier = Modifier.clickable { setSteerMode(false) }.padding(bottom = 4.dp))
                } else if (followUpMode) {
                    Text("▸ Follow-up to previous turn…", color = accent, fontFamily = piMono, fontSize = 10.sp,
                        modifier = Modifier.clickable { setFollowUpMode(false) }.padding(bottom = 4.dp))
                }

                // Attached image previews
                if (attachedImages.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(attachedImages.toList(), key = { it.toString() }) { uri ->
                            PiImagePreviewChip(uri, onRemove = { onRemoveImage(uri) })
                        }
                        item { Spacer(Modifier.width(4.dp)) }
                    }
                    Spacer(Modifier.height(2.dp))
                }

                // Input row: blinking `>` sigil + text field.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                    PiBlinkSigil(
                        sigil = ">",
                        color = when {
                            steerMode -> thinkingMedium
                            followUpMode -> accent
                            else -> accent
                        },
                        // Sigil only blinks when input is empty (mirrors a terminal at the prompt).
                        active = input.isEmpty()
                    )
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.TextField(
                        value = input,
                        onValueChange = { vm.setInputText(it) },
                        placeholder = {
                            Text(when {
                                steerMode -> "Guide Pi…"
                                followUpMode -> "Follow-up…"
                                busy -> "(agent busy)"
                                else -> "Message Pi…"
                            }, color = textMuted, fontFamily = piMono)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4, singleLine = false,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = bg, unfocusedContainerColor = bg,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = textPrimary, unfocusedTextColor = textPrimary,
                            focusedPlaceholderColor = textMuted, unfocusedPlaceholderColor = textMuted,
                            cursorColor = accent
                        ),
                        textStyle = LocalTextStyle.current.copy(color = textPrimary, fontFamily = piMono, fontSize = 13.sp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                            when {
                                steerMode -> { vm.sendSteer(); setSteerMode(false) }
                                followUpMode -> { vm.sendFollowUp(); setFollowUpMode(false) }
                                // Catch slash commands: /command [args]
                                input.trim().startsWith("/") -> {
                                    val trimmed = input.trim()
                                    val spaceIdx = trimmed.indexOf(' ')
                                    val cmd = if (spaceIdx > 1) trimmed.substring(1, spaceIdx) else trimmed.substring(1)
                                    val args = if (spaceIdx > 1) trimmed.substring(spaceIdx + 1).trim() else ""
                                    vm.sendSlashCommand(cmd, args)
                                }
                                else -> vm.sendPrompt()
                            }
                        })
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Bottom controls
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // [Attach] button — paperclip icon
                    Box(
                        modifier = Modifier
                            .border(1.dp, borderMuted, RoundedCornerShape(0.dp))
                            .clickable { onPickImages() }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            Icons.Default.AddAPhoto,
                            contentDescription = "Attach image",
                            tint = textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // [Send] button
                    val submitText = input.trim()
                    val hasImages = attachedImages.isNotEmpty()
                    val isSlashCmd = submitText.startsWith("/")
                    Box(
                        modifier = Modifier
                            .border(1.dp, if (input.isNotBlank() || hasImages) accent else borderMuted, RoundedCornerShape(0.dp))
                            .clickable(enabled = input.isNotBlank() || hasImages) {
                                when {
                                    steerMode -> { vm.sendSteer(); setSteerMode(false) }
                                    followUpMode -> { vm.sendFollowUp(); setFollowUpMode(false) }
                                    isSlashCmd -> {
                                        val spaceIdx = submitText.indexOf(' ')
                                        val cmd = if (spaceIdx > 1) submitText.substring(1, spaceIdx) else submitText.substring(1)
                                        val args = if (spaceIdx > 1) submitText.substring(spaceIdx + 1).trim() else ""
                                        vm.sendSlashCommand(cmd, args)
                                    }
                                    else -> vm.sendPrompt()
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            when {
                                steerMode -> "Steer"
                                followUpMode -> "Follow"
                                else -> "Send"
                            },
                            color = if (input.isNotBlank() || hasImages) textPrimary else textMuted,
                            fontFamily = piMono, fontSize = 10.sp
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // steer chip
                    if (busy) {
                        Box(
                            modifier = Modifier
                                .border(0.5.dp, if (steerMode) thinkingMedium else borderMuted, RoundedCornerShape(0.dp))
                                .clickable { setSteerMode(!steerMode) }
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("steer", color = if (steerMode) thinkingMedium else textMuted, fontFamily = piMono, fontSize = 9.sp)
                        }
                    }

                    // follow-up chip
                    if (!busy && hasMessages) {
                        Box(
                            modifier = Modifier
                                .border(0.5.dp, if (followUpMode) accent else borderMuted, RoundedCornerShape(0.dp))
                                .clickable { setFollowUpMode(!followUpMode) }
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("follow-up", color = if (followUpMode) accent else textMuted, fontFamily = piMono, fontSize = 9.sp)
                        }
                    }
                }
            }
        }

        // Accessory row above the IME with shortcuts for slash, file refs,
        // bash prefix, backticks, etc. Esc collapses mode or clears input.
        PiHotkeyBar(
            input = input,
            inSpecialMode = steerMode || followUpMode,
            onInsert = { vm.setInputText(input + it) },
            onEsc = {
                if (steerMode) setSteerMode(false)
                else if (followUpMode) setFollowUpMode(false)
                else if (input.isNotEmpty()) vm.setInputText("")
            }
        )
    }
}

/** Image preview chip — small thumbnail with × to remove */
@Composable
fun PiImagePreviewChip(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }
    Box(modifier = Modifier.size(48.dp)) {
        bitmap?.let { bmp ->
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Attached image",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, borderMuted, RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
            // Dimension label
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("IMG", color = Color.White, fontFamily = piMono, fontSize = 7.sp)
            }
        } ?: run {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, borderMuted, RoundedCornerShape(4.dp))
                    .background(bgSecondary),
                contentAlignment = Alignment.Center
            ) {
                Text("IMG", color = textMuted, fontFamily = piMono, fontSize = 9.sp)
            }
        }
        // Remove button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove image",
                tint = Color.White,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

/** Blinking `>` prompt sigil. Stops blinking once the user has typed. */
@Composable
fun PiBlinkSigil(sigil: String, color: Color, active: Boolean) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (active) while (true) { kotlinx.coroutines.delay(550); on = !on }
        else on = true
    }
    Text(
        sigil,
        color = if (on) color else color.copy(alpha = 0.25f),
        fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.Bold
    )
}

// ── Pi Terminal Message Bubbles ────────────────────────────────────────

@Composable
fun PiMessageBubble(msg: ChatMessage) {
    when (msg.type) {
        MessageToolType.User -> PiUserMessage(msg)
        MessageToolType.Assistant -> PiAssistantMessage(msg)
        MessageToolType.ToolResult -> PiToolMessage(msg)
        MessageToolType.Streaming -> if (msg.content.isNotBlank()) PiAssistantMessage(msg) else Unit
        MessageToolType.Thinking -> PiThinkingMessage(msg)
    }
}

/**
 * User message — flat scrollback echo, no border, prefixed with `> `.
 * Long-press the row to surface the timestamp (kept off by default for density).
 */
@Composable
fun PiUserMessage(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("> ", color = accent, fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(msg.content, color = userBubbleText, fontFamily = piMono, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * Assistant prose — flat, two-space indent, no labels, no border, no timestamp.
 * Markdown is rendered with pi-themed styling (headers, code, bold, etc.)
 * so output reads like pi's own terminal output instead of raw `**...**`.
 */
@Composable
fun PiAssistantMessage(msg: ChatMessage) {
    PiMarkdown(
        text = msg.content,
        baseColor = assistantText,
        baseSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, end = 4.dp, bottom = 2.dp)
    )
}

/**
 * Thinking — `✻ Thinking…` header, dim italic body, tap-to-expand.
 * Collapsed by default to keep the scrollback dense.
 */
@Composable
fun PiThinkingMessage(msg: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp, horizontal = 4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✻", color = thinkingBorder, fontFamily = piMono, fontSize = 11.sp)
            Text(
                if (expanded) "Thinking…" else "Thinking… (tap)",
                color = thinkingBorder, fontFamily = piMono, fontSize = 11.sp,
                fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold
            )
        }
        if (expanded) {
            Text(
                "  " + msg.content,
                color = thinkingBorder.copy(alpha = 0.7f),
                fontFamily = piMono, fontSize = 11.sp, fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Tool call — Claude Code-style one-liner.
 *
 *   ● Tool(arg)
 *   ⎿  first line of result…
 *
 * Tap row to expand to full content; long-press to copy. No box, no chrome —
 * matches the flat scrollback feel. Tap target padded to ~40dp for touch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PiToolMessage(msg: ChatMessage) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    var copied by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val isCode = CodeUtils.isCodeContent(msg.content)
    val lineCount = CodeUtils.countLines(msg.content)

    val dotColor = when {
        msg.isError -> error
        else -> toolBorder
    }
    val argText = if (msg.toolArgs.isNotBlank()) parseToolArgs(msg.toolArgs) else ""
    val firstLine = msg.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }

    Column(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    clipboard?.setPrimaryClip(ClipData.newPlainText("tool", msg.content))
                    copied = true
                }
            )
            .padding(vertical = 3.dp, horizontal = 4.dp)
    ) {
        // ● Tool(args)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = dotColor, fontFamily = piMono, fontSize = 13.sp)
            Text(msg.toolName.ifBlank { "Tool" }, color = textPrimary, fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (argText.isNotBlank()) {
                val shortArgs = argText.take(40) + if (argText.length > 40) "…" else ""
                Text("($shortArgs)", color = textMuted, fontFamily = piMono, fontSize = 12.sp, maxLines = 1)
            }
            if (msg.isError) Text("error", color = error, fontFamily = piMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (copied) Text("copied", color = toolBorder, fontFamily = piMono, fontSize = 10.sp)
        }

        // ⎿ first-line excerpt (collapsed) OR full body (expanded)
        if (msg.content.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 1.dp)) {
                Text("  ⎿", color = textMuted, fontFamily = piMono, fontSize = 12.sp)
                if (expanded) {
                    if (isCode && lineCount > 1) {
                        PiCodeBlock(msg.content.take(3000))
                    } else {
                        Text(msg.content, color = textSecondary, fontFamily = piMono, fontSize = 12.sp)
                    }
                } else {
                    val preview = firstLine.take(80) + if (firstLine.length > 80 || lineCount > 1) "…" else ""
                    Text(preview, color = textMuted, fontFamily = piMono, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Code block with line numbers */
@Composable
fun PiCodeBlock(content: String) {
    val lines = remember(content) { content.split("\n").mapIndexed { idx, line -> idx + 1 to line } }
    val displayLines = lines.take(50)
    val moreCount = lines.size - displayLines.size

    Column {
        for ((num, line) in displayLines) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(num.toString(), color = textMuted.copy(alpha = 0.3f), fontFamily = piMono, fontSize = 9.sp,
                    modifier = Modifier.width(18.dp), textAlign = TextAlign.End)
                PiAnnotatedLine(line)
            }
        }
        if (moreCount > 0) Text("  ... $moreCount more lines", color = textMuted, fontFamily = piMono, fontSize = 9.sp, fontStyle = FontStyle.Italic)
    }
}

@Composable
fun PiAnnotatedLine(line: String) {
    val isComment = line.startsWith("//") || line.startsWith("/*") || line.startsWith(" *") || line.startsWith("*/")
    val isBlank = line.isBlank()
    val isKeyword = try {
        val trimmed = line.trimStart()
        setOf("class", "interface", "object", "enum", "sealed", "data", "abstract", "val", "var", "fun", "package", "import", "override", "constructor", "return", "if", "else", "when", "for", "while", "do", "is", "in", "as", "by", "companion", "suspend", "where", "public", "private", "protected", "static", "fn", "let", "mut", "pub", "use", "const").any { kw ->
            trimmed.startsWith(kw) && (trimmed.length == kw.length || !Character.isLetterOrDigit(trimmed[kw.length]))
        }
    } catch (_: Exception) { false }
    val isNumber = line.trimStart().matches(Regex("[0-9]+\\.?[0-9]*"))

    val color = when {
        isComment -> codeComment
        isBlank -> textMuted
        isKeyword -> codeKeyword
        isNumber -> codeNumber
        line.contains('"') -> codeString
        else -> textSecondary
    }

    Text(line, color = color, fontFamily = piMono, fontSize = 11.sp)
}

// ── Helpers ────────────────────────────────────────────────────────────

fun parseToolArgs(json: String): String {
    return try {
        val j = com.piremote.JP.p(json)
        j?.let { m ->
            val path = com.piremote.Js.gets(m, "path") ?: com.piremote.Js.gets(m, "file") ?: ""
            val cmd = com.piremote.Js.gets(m, "cmd") ?: com.piremote.Js.gets(m, "command") ?: ""
            val name = com.piremote.Js.gets(m, "name") ?: ""
            if (path.isNotBlank()) path
            else if (cmd.isNotBlank()) cmd
            else if (name.isNotBlank()) name
            else json.take(60)
        } ?: json.take(60)
    } catch (_: Exception) {
        json.take(60)
    }
}

val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
val timeFormatFull = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
fun tsNow(): String = timeFormat.format(Date())
fun formatTs(ts: Long): String = timeFormat.format(Date(ts))
fun formatTsFull(ts: Long): String = timeFormatFull.format(Date(ts))

/** Blinking dot for streaming */
@Composable
fun PiBlinkDot(color: Color = accent) {
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); blink = !blink } }
    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(if (blink) color else Color.Transparent))
}

/** Pi-style session time formatting */
val fullTimeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
fun sessionTime(ts: Long): String {
    val now = java.lang.System.currentTimeMillis()
    val diff = now - ts
    if (diff < 60_000) return "just now"
    if (diff < 3_600_000) return "${diff / 60_000}m ago"
    if (diff < 86_400_000) {
        val h = diff / 3_600_000
        val m = (diff % 3_600_000) / 60_000
        return "${h}h ${m}m ago"
    }
    return fullTimeFormat.format(java.util.Date(ts))
}
