package com.piremote.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.*
import com.piremote.R
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.asImageBitmap
import com.piremote.theme.*
import com.piremote.tty.parseAnsiLine
import com.piremote.tty.parseEdits
import com.piremote.tty.parseToolArgs
import com.piremote.tty.parseWriteContent
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Pi Terminal Font Family ────────────────────────────────────────────

// DejaVu Sans Mono: a real terminal font whose box-drawing glyphs (─ │ ┌ ┐ etc.)
// span the full cell, so horizontal rules render solid instead of dashed — the
// default Android FontFamily.Monospace leaves side gaps that break long lines.
val piMono = FontFamily(
    Font(R.font.dejavu_sans_mono, FontWeight.Normal),
    Font(R.font.dejavu_sans_mono_bold, FontWeight.Bold),
)

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
    val showBorder = !header.isNullOrEmpty() || borderColor != borderMuted
    Column {
        if (showBorder) {
            PiTopBorder(header, borderColor)
        }
        PiGutter(borderColor, content)
        if (showBorder) {
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
    sessions: List<RemoteSession> = emptyList()
) {
    // Key on url so the field populates once DataStore loads the saved URL
    // (which arrives asynchronously, after this screen first composes). Typing
    // only mutates `t`, never `url`, so this never clobbers user input.
    var t by remember(url) { mutableStateOf(url) }
    var showScanner by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf(false) }
    val connect = {
        val u = t.ifEmpty { "" }
        vm.setServerUrl(u); vm.connect()
    }

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
                                    placeholder = { Text("ws://<IP>:<port>", color = textMuted, fontFamily = piMono) },
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
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { connect() })
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
                                .clickable { connect() }
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
                    Text("1  pi install git:github.com/kolt-mcb/pi-remote-control", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
                    Text("2  Run:  pi   (extension auto-loads; QR + URL print on startup)", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
                    Text("3  Scan the QR or paste the ws://…?token=…  URL above", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.weight(1f))
        }

        // Tappable version display in bottom-right doubles as the update
        // entry point. Tap → fetches GitHub Releases → install prompt if a
        // newer build exists. The component owns its own dialog state.
        UpdateAffordance(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

/**
 * Bottom-corner version chip + update flow. Shows "vN · sha", tap to check
 * GitHub Releases for a newer build. On hit, prompts to install; on download
 * tap, fires the OS package installer with the new APK.
 */
@Composable
fun UpdateAffordance(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val updater = remember { UpdateChecker(ctx.applicationContext) }
    val currentCode = remember { updater.currentVersionCode() }
    val currentName = remember { updater.currentVersionName() }
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<LatestRelease?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadPct by remember { mutableStateOf(0) }

    // Brief "up to date" / error message auto-clears after a couple seconds.
    LaunchedEffect(status) {
        if (status != null) {
            kotlinx.coroutines.delay(2500); status = null
        }
    }

    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        Text(
            text = "v$currentCode · $currentName",
            color = textMuted.copy(alpha = 0.5f),
            fontFamily = piMono, fontSize = 9.sp,
            modifier = Modifier.clickable(enabled = !checking && !downloading) {
                checking = true
                scope.launch {
                    val r = updater.fetchLatest()
                    checking = false
                    if (r == null) {
                        status = "couldn't reach updates"
                    } else if (r.versionCode <= currentCode) {
                        status = "up to date"
                    } else {
                        latest = r
                    }
                }
            }
        )
        if (checking) {
            Text("checking…", color = textMuted.copy(alpha = 0.7f), fontFamily = piMono, fontSize = 9.sp)
        } else if (status != null) {
            Text(status!!, color = textMuted.copy(alpha = 0.7f), fontFamily = piMono, fontSize = 9.sp)
        }
    }

    // "Update available" dialog. Confirms before downloading 40MB.
    latest?.let { r ->
        if (!downloading) {
            AlertDialog(
                onDismissRequest = { latest = null },
                title = { Text("Update available", fontFamily = piMono, fontSize = 14.sp) },
                text = {
                    Column {
                        Text("v$currentCode → v${r.versionCode}", fontFamily = piMono, fontSize = 12.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(r.versionName, color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        if (r.publishedAt.isNotBlank()) {
                            Text("published ${r.publishedAt.take(10)}", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        downloading = true
                        downloadPct = 0
                        scope.launch {
                            val file = updater.downloadApk(r) { pct -> downloadPct = pct }
                            downloading = false
                            latest = null
                            if (file != null) {
                                updater.launchInstaller(file)
                            } else {
                                status = "download failed"
                            }
                        }
                    }) { Text("Install") }
                },
                dismissButton = {
                    TextButton(onClick = { latest = null }) {
                        Text("Later")
                    }
                }
            )
        }
    }

    // Download progress dialog.
    if (downloading) {
        AlertDialog(
            onDismissRequest = { /* not dismissible mid-download */ },
            title = { Text("Downloading", fontFamily = piMono, fontSize = 14.sp) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { downloadPct / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("$downloadPct%", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
                }
            },
            confirmButton = {},
        )
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
    sessions: List<RemoteSession>,
    selectedSession: String,
    compacting: Boolean = false,
    clientCount: Int = 0,
    savedSessions: List<SavedSession> = emptyList()
) {
    val activeSession = sessions.find { it.id == selectedSession }
    var selectedCategory by remember { mutableStateOf("all") }

    // Refresh the saved-session list once when this screen is opened. The
    // response repopulates savedSessionsFlow → savedSessions param.
    // Uses key = sessions.size so it fires on first entry AND when the
    // session list changes (meaning a new peer may have joined/left).
    LaunchedEffect(sessions.size) {
        vm.refreshSavedSessions()
    }

    // Filter saved sessions by selected category
    val filteredSessions = if (selectedCategory == "all") {
        savedSessions
    } else {
        savedSessions.filter { it.status == selectedCategory }
    }

    // Count per category for tab badges
    val counts = savedSessions.groupingBy { it.status }.eachCount()
    val totalCount = savedSessions.size
    
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
            Spacer(Modifier.height(8.dp))

            // [+ New session] — spawns a separate pi process on the host
            // (peer mode) instead of calling /new on the current pi. The old
            // /new path went through ctx.newSession(), which permanently
            // invalidates the extension's runtime — every subsequent slash
            // command then failed until pi was restarted. Spawning a new
            // process sidesteps that entirely: each pi has its own
            // extension lifecycle, and the new one joins this host as a
            // peer agent → shows up as a separate tab.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .border(0.5.dp, accent, RoundedCornerShape(0.dp))
                        .clickable(enabled = sessions.isNotEmpty()) {
                            vm.spawnPeer()
                            // Don't navigate to chat yet — the peer takes a
                            // moment to boot and join. The notify banner
                            // ("Launching a new pi peer…") will appear; the
                            // user can stay on the Sessions screen to watch
                            // the new tab appear in the pill row.
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "[+ New session]",
                        color = if (sessions.isNotEmpty()) accent else textMuted,
                        fontFamily = piMono, fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        val isSelected = session.id == selectedSession
                        val isBusy = session.status == "busy"
                        SessionCard(vm, session, isSelected, isBusy) { vm.setSelectedSession(session.id); vm.showChatScreen() }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Saved sessions ────────────────────────────────────────
            // Tap any row to spawn `pi --session <path>` as a peer. The
            // resumed pi joins as a new agent, surfaces as a new tab.
            // (Direct ctx.switchSession can't be used from an extension —
            // see PR #20 / commit message.)
            PiBox(header = "Saved sessions", borderColor = borderMuted) {
                Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
                    // ── Category filter tabs ─────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val categories = listOf(
                            "all" to "All",
                            "working" to "Working",
                            "completed" to "Completed",
                            "archived" to "Archived"
                        )
                        categories.forEach { (key, label) ->
                            val isActive = key == selectedCategory
                            val count = if (key == "all") totalCount else (counts[key] ?: 0)
                            val color = if (isActive) accent else textMuted
                            val bgC = if (isActive) selectedBg else Color.Transparent
                            Box(
                                modifier = Modifier
                                    .background(bgC)
                                    .border(1.dp, if (isActive) accent else borderMuted, RoundedCornerShape(0.dp))
                                    .clickable { selectedCategory = key }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, color = color, fontFamily = piMono, fontSize = 10.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                                    Text("($count)", color = if (isActive) color else textMuted.copy(alpha = 0.6f),
                                        fontFamily = piMono, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    // ── Filtered session list ────────────────────────
                    if (filteredSessions.isEmpty()) {
                        Text(
                            if (totalCount == 0) "No saved sessions yet."
                            else "No $selectedCategory sessions.",
                            color = textMuted, fontFamily = piMono, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                        )
                    } else {
                        Text(
                            "${filteredSessions.size} session${if (filteredSessions.size != 1) "s" else ""} · tap to resume as a new tab",
                            color = textMuted, fontFamily = piMono, fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            items(filteredSessions, key = { it.path }) { saved ->
                                SavedSessionRow(saved) {
                                    vm.spawnPeerWithSession(saved.path)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Nav hint
            PiBox(borderColor = borderMuted) {
                Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 10.dp)) {
                    Text("▸ Back: disconnect", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                    Text("▸ Long-press peer: close session", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

/** Row in the saved-sessions list. Tap to spawn `pi --session <path>` as a peer. */
@Composable
fun SavedSessionRow(s: SavedSession, onTap: () -> Unit) {
    val label = s.name.ifBlank { s.firstMessage }.ifBlank { s.path.substringAfterLast('/') }
    val trimmed = sessionLabel(label)
    val whenStr = sessionTime(s.modified)

    // Status badge color + icon
    val statusColor = when (s.status) {
        "working" -> thinkingBorder
        "archived" -> textMuted
        else -> success
    }
    val statusLabel = when (s.status) {
        "working" -> "working"
        "archived" -> "archived"
        else -> "completed"
    }
    val statusIcon = when (s.status) {
        "working" -> "◉"
        "archived" -> "⊘"
        else -> "✓"
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("▸ ", color = accent, fontFamily = piMono, fontSize = 11.sp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(trimmed, color = textPrimary, fontFamily = piMono, fontSize = 12.sp, maxLines = 1)
                // Status badge — only show non-default (non-completed) status
                if (s.status != "completed") {
                    Box(
                        modifier = Modifier
                            .border(0.5.dp, statusColor, RoundedCornerShape(0.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "$statusIcon $statusLabel",
                            color = statusColor, fontFamily = piMono, fontSize = 9.sp
                        )
                    }
                }
            }
            // Model's last reply preview — shown when available so you can see
            // what the agent is waiting on without opening the session.
            if (s.lastAssistantMessage.isNotBlank()) {
                val preview = s.lastAssistantMessage.trim().take(140) +
                    if (s.lastAssistantMessage.length > 140) "…" else ""
                Text(
                    "π ${preview}",
                    color = thinkingLow,
                    fontFamily = piMono, fontSize = 10.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2
                )
            }
            Text("${s.messageCount} msg${if (s.messageCount != 1) "s" else ""} · $whenStr",
                color = textMuted, fontFamily = piMono, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** Card for a single session in the sessions list */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    vm: ChatViewModel,
    session: RemoteSession,
    isSelected: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    var showCloseConfirm by remember { mutableStateOf(false) }

    PiBox(
        header = sessionLabel(session.name, 16),
        borderColor = if (isSelected) accent else if (isBusy) thinkingBorder.copy(alpha = 0.7f) else borderMuted
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onClick() },
                    onLongClick = {
                        // Don't allow closing the host (self) session
                        if (session.kind != "self" && !isBusy) {
                            showCloseConfirm = true
                        }
                    }
                )
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

    // Close confirmation dialog
    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close session", fontFamily = piMono, fontSize = 14.sp) },
            text = {
                Text(
                    "This will quit peer agent:\n${sessionLabel(session.name, 30)}\n\nThis cannot be undone.",
                    fontFamily = piMono, fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.closeSession(session.id); showCloseConfirm = false }
                ) {
                    Text("Close", color = error, fontFamily = piMono)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) {
                    Text("Cancel", color = textMuted, fontFamily = piMono)
                }
            }
        )
    }
}

// ── Pi Terminal Header ─────────────────────────────────────────────────

@Composable
fun PiHeader(status: ConnectionStatus, busy: Boolean, disconnect: () -> Unit, title: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().background(bgSecondary)) {
        HorizontalDivider(color = border, thickness = 1.dp)
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
    sessions: List<RemoteSession>,
    selectedSession: String,
    messageCount: Int,
    compacting: Boolean = false,
    clientCount: Int = 0
) {
    Column(modifier = Modifier.fillMaxWidth().background(footerBg)) {
        HorizontalDivider(color = border, thickness = 1.dp)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val active = sessions.find { it.id == selectedSession }
            // Single ellipsized line: session names derive from the first user
            // message on the host, which can be arbitrarily long pasted text.
            Text(
                "session: ${active?.name ?: "none"}",
                color = if (active?.status == "busy") thinkingBorder else accent,
                fontFamily = piMono, fontSize = 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f, fill = false)
            )
            Text("agents: ${sessions.size}", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
            if (clientCount > 0) Text("connected: $clientCount", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("messages: $messageCount", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
        }
        if (compacting) {
            HorizontalDivider(color = borderMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(10.dp), color = thinkingBorder, strokeWidth = 1.5.dp)
                Text("compacting...", color = thinkingBorder, fontFamily = piMono, fontSize = 10.sp, fontStyle = FontStyle.Italic)
            }
        }
    }
}

// ── Compact Turn Summary ──────────────────────────────────────────────

/**
 * Compact summary bar shown after agent finishes a turn.
 * 
 *   ┌─ Last turn (5 calls) ─┐
 *   │ bash(2) read(3) web(1) │
 *   └────────────────────────┘
 * 
 * Terminal-styled chips showing tool usage counts.
 */
@Composable
fun TurnSummaryPanel(summary: PiWebSocket.TurnSummary) {
    PiRoundedBox(
        header = "Last turn (${summary.totalCalls} calls)",
        borderColor = borderMuted,
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                summary.toolsUsed.forEach { tool ->
                    PiTurnToolChip(tool.name, tool.count)
                }
            }
        }
    )
}

/** Single tool chip: `toolName(N)` with icon */
@Composable
fun PiTurnToolChip(name: String, count: Int) {
    val icon = when {
        name == "bash" -> "⌘"
        name == "read" -> "📖"
        name == "write" -> "✏"
        name == "edit" -> "🔧"
        name == "ToolSearch" -> "🔍"
        name == "WebFetch" -> "🌐"
        else -> "●"
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontFamily = piMono, fontSize = 10.sp)
        Text(name, color = textPrimary, fontFamily = piMono, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text("($count)", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
    }
}

// ── Pi Terminal Session Selector ───────────────────────────────────────

@Composable
fun PiSessionSelector(sessions: List<RemoteSession>, selected: String, onSelect: (String) -> Unit) {
    if (sessions.size < 2) {
        sessions.firstOrNull()?.let { sess ->
            val isActive = sess.status == "busy"
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).background(bgSecondary),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(5.dp).background(if (isActive) thinkingBorder else success, CircleShape))
                // Session names derive from the first user message on the host —
                // can be a multi-line paste. One ellipsized line, newlines stripped.
                Text(
                    sessionLabel(sess.name, 60),
                    color = textPrimary, fontFamily = piMono, fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(3f, fill = false)
                )
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
                            sessionLabel(sess.name, 10),
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel, url: String, input: String, messages: List<ChatMessage>,
    assist: String, status: ConnectionStatus, busy: Boolean,
    sessions: List<RemoteSession> = emptyList(),
    selectedSession: String = "",
    commands: List<RemoteCommand> = emptyList(),
    statuses: Map<String, String> = emptyMap(),
    widgets: Map<String, List<String>> = emptyMap(),
    compacting: Boolean = false,
    notifyBanners: List<BannerMessage> = emptyList(),
    uiTitle: String? = null,
    clientCount: Int = 0,
    turnSummary: PiWebSocket.TurnSummary? = null,
    showSessionSelector: Boolean = true,
) {
    val ls = rememberLazyListState()
    // Pin to bottom when a message arrives OR the streaming tail grows —
    // block count inside the tail message changes with content length.
    val scrollKey = Triple(messages.size, messages.lastOrNull()?.content?.length ?: 0, assist.length)
    LaunchedEffect(scrollKey) {
        val last = ls.layoutInfo.totalItemsCount - 1
        if (last > 0) ls.animateScrollToItem(last)
    }


        // Mirror state computed up front: when a live frame is showing, the
        // mirror already renders pi's FULL screen (its loader, status line,
        // widgets, footer), so we suppress the app's duplicate chrome below to
        // avoid a second "Working..." spinner and stray separators.
        val mirrorFrame by vm.mirrorFrame.collectAsState()
        val selfId = sessions.firstOrNull { it.isSelf }?.id
        val chosen = sessions.firstOrNull { it.id == selectedSession }
            ?: sessions.firstOrNull { it.isSelf }
            ?: sessions.firstOrNull()
        // The host's OWN session subscribes with a blank agentId so it always
        // takes the self/clamp path; peers subscribe by their id.
        val mirrorTarget = if (chosen == null || chosen.isSelf) "" else chosen.id
        // Subscribe to the mirror only while this screen is in the FOREGROUND.
        // When the app is backgrounded the host keeps pumping full-screen frames
        // over the (metered, slow) link to a view nobody's looking at — so unsubscribe
        // on ON_STOP and re-subscribe on ON_START (the host sends an immediate
        // snapshot on subscribe, so the view refreshes at once on return).
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(mirrorTarget, lifecycleOwner) {
            // Subscribe immediately on (re)compose — we're in the foreground here.
            // Relying on the observer's ON_START alone failed to fire on the initial
            // composition, so the mirror never subscribed until a tab switch.
            vm.setMirror(true, mirrorTarget)
            // Then track foreground/background to pause the stream while backgrounded.
            var started = true   // we just subscribed above; skip the add-time ON_START
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> if (!started) { vm.setMirror(true, mirrorTarget); started = true }
                    Lifecycle.Event.ON_STOP -> { vm.setMirror(false, mirrorTarget); started = false }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                vm.setMirror(false, mirrorTarget)
            }
        }
        val liveFrame = mirrorFrame?.takeIf {
            if (mirrorTarget.isBlank()) (selfId == null || it.agentId == selfId) else it.agentId == mirrorTarget
        }

        Column(modifier = Modifier.fillMaxSize().background(bg).imePadding()) {
        PiHeader(status, busy, { vm.disconnect() }, uiTitle?.take(30))

        // Host notify() messages and dropped-send warnings. Previously the
        // banner list was plumbed all the way here but never drawn.
        if (notifyBanners.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                notifyBanners.takeLast(3).forEach { b -> NotifyBanner(b.content, b.type) }
            }
        }

        if (showSessionSelector && sessions.isNotEmpty()) {
            PiSessionSelector(
                sessions,
                if (selectedSession.isNotBlank()) selectedSession else sessions.firstOrNull()?.id ?: "",
                { vm.setSelectedSession(it) }
            )
        }

        // Shared keystroke-capture focus: tapping the mirror raises the soft
        // keyboard by focusing the (invisible) capture field in the bar below.
        val ttyFocus = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val openKeyboard: () -> Unit = {
            // requestFocus() shows the IME when unfocused; show() re-raises it if
            // the field is already focused but the keyboard was dismissed.
            try { ttyFocus.requestFocus() } catch (_: Exception) {}
            keyboardController?.show()
        }

        // The mirror IS the session view — it renders pi's whole screen (status,
        // widgets, loader, footer). No legacy scrollback or duplicate chrome; a
        // neutral placeholder shows until the first frame arrives (no UI flash).
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            // Measured monospace metrics feed the host so it renders at our width.
            val metrics = rememberTtyMetrics(maxWidth)
            LaunchedEffect(metrics.cols) { vm.reportViewport(metrics.cols) }
            if (liveFrame != null) {
                MirrorSurface(
                    frame = liveFrame,
                    modifier = Modifier.fillMaxSize(),
                    onInput = { vm.sendMirrorInput(it, mirrorTarget) },
                    onRequestKeyboard = openKeyboard,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "connecting to session…",
                        color = textMuted,
                        fontFamily = piMono,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // Type straight into pi's terminal: a special-key row (esc/ctrl/alt/arrows)
        // above the keyboard plus hidden keystroke capture, both forwarding raw
        // bytes to the mirror's PTY. No separate chat box — pi echoes what you type.
        if (status is ConnectionStatus.Connected) {
            TtyKeyboardBar(vm = vm, agentId = mirrorTarget, focusRequester = ttyFocus)
        }
        // No app footer: pi's own footer (cwd, model, remote status) is in the mirror.
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
    val frameIdx by produceState(initialValue = 0) {
        var idx = 0
        while (true) {
            kotlinx.coroutines.delay(80)
            value = idx
            idx = (idx + 1) % piSpinnerFrames.size
        }
    }
    // Elapsed-seconds counter — restarts when busyStartedAt changes.
    val elapsed by produceState(initialValue = 0L, key1 = busyStartedAt) {
        while (true) {
            kotlinx.coroutines.delay(500)
            value = ((System.currentTimeMillis() - busyStartedAt) / 1000).coerceAtLeast(0)
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
    val on by produceState(initialValue = true) {
        var state = true
        while (true) {
            kotlinx.coroutines.delay(500)
            state = !state
            value = state
        }
    }
    Text(
        if (on) "▌" else " ",
        color = color, fontFamily = piMono, fontSize = 13.sp
    )
}

/**
 * pi's startup header, reproduced for the phone so a fresh connection greets
 * you the way pi's terminal does. pi prints (interactive-mode): an accent logo,
 * compact keybinding hints, then an onboarding line. We mirror that structure
 * and the synced theme colors. The keyboard hints collapse to `/` — the only
 * one with a touch equivalent (^C/^L/^O don't apply here); copy stays pi's.
 */
@Composable
fun PiStartupHeader() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp)) {
        Text("pi", color = accent, fontWeight = FontWeight.Bold, fontFamily = piMono, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        // Compact hint line — pi draws the key dim, the label muted.
        Row {
            Text("/", color = textMuted, fontFamily = piMono, fontSize = 12.sp)
            Text(" commands", color = textSecondary, fontFamily = piMono, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        // Onboarding line — verbatim from pi.
        Text(
            "Pi can explain its own features and look up its docs. Ask it how to use or extend Pi.",
            color = textMuted, fontFamily = piMono, fontSize = 12.sp, lineHeight = 17.sp
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────

/**
 * Sanitize a host-derived session name for one-line display. Names come from
 * the first user message, which can be a multi-line paste of arbitrary text.
 */
fun sessionLabel(name: String, max: Int = 40): String {
    val flat = name.replace('\n', ' ').replace('\r', ' ').trim()
    return if (flat.length > max) flat.take(max - 1) + "…" else flat
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

