package com.piremote.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectAsStateLifecycle
import com.piremote.*
import com.piremote.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(vm: ChatViewModel, url: String, input: String, messages: List<ChatMessage>, assist: String, status: ConnectionStatus, urlHistory: Set<String>) {
    var t by remember { mutableStateOf(url) }
    var showMenu by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }

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

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Link, null, tint = accent, modifier = Modifier.size(56.dp))
            Text("Pi Remote", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            Text("Control your Pi agent from your phone", color = textSecondary, fontSize = 16.sp)

            OutlinedTextField(
                value = t, onValueChange = { t = it }, label = { Text("WebSocket URL") },
                placeholder = { Text("ws://192.168.1.100:8765") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = textPrimary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                    val u = t.ifEmpty { "ws://192.168.1.100:8765" }
                    vm.setServerUrl(u); vm.connect()
                }),
                trailingIcon = { IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Options", tint = textSecondary) } }
            )

            // Auto-connect if we have a recent URL and no connection
            if (urlHistory.isNotEmpty() && status is ConnectionStatus.Disconnected) {
                val lastUrl = urlHistory.last()
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(500)
                    vm.setServerUrl(lastUrl); vm.connect()
                }
            }

            Button(onClick = {
                val u = t.ifEmpty { "ws://192.168.1.100:8765" }
                vm.setServerUrl(u); vm.connect()
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                Icon(Icons.Default.Link, "Connect", tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("⊡  Scan QR", fontSize = 15.sp)
            }

            Divider(color = border.copy(alpha = 0.3f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

            if (status is ConnectionStatus.Error) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status.message, color = errorColor, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Icon(Icons.Default.Refresh, "Retry", tint = errorColor, modifier = Modifier.size(18.dp))
                }
            }
            if (status is ConnectionStatus.Connecting) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                Text("Connecting...", color = textSecondary)
            }

            if (urlHistory.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Recent", color = textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        urlHistory.take(5).forEach { histUrl ->
                            ShortChip(histUrl) { t = histUrl }
                        }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = bgSecondary)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Quick Start", fontWeight = FontWeight.SemiBold, color = textPrimary, modifier = Modifier.padding(bottom = 6.dp))
                    Text("1.  Run:  pi -e ~/pi-remote-control/extension.ts", color = textSecondary, fontSize = 13.sp)
                    Text("2.  In pi:  /remote-control", color = textSecondary, fontSize = 13.sp)
                    Text("3.  Enter the ws:// URL shown", color = textSecondary, fontSize = 13.sp)
                }
            }
        }
    }

    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
        DropdownMenuItem(text = { Text("Clear history") }, onClick = { showMenu = false })
    }
}

@Composable
fun ShortChip(label: String, onClick: () -> Unit) {
    Surface(modifier = Modifier.clickable(onClick = onClick), tonalElevation = 2.dp) {
        Text(label.take(25) + (if (label.length > 25) "…" else ""), color = accent, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel, url: String, input: String, messages: List<ChatMessage>, assist: String, status: ConnectionStatus, busy: Boolean) {
    val ls = rememberLazyListState()
    val cnt = messages.size + if (assist.isNotBlank()) 2 else 0
    LaunchedEffect(cnt) {
        if (cnt > 0 && ls.layoutInfo.totalItemsCount > 0) {
            ls.animateScrollToItem(cnt.coerceAtMost(ls.layoutInfo.totalItemsCount - 1))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Column(modifier = Modifier.fillMaxWidth().background(bgSecondary)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (status is ConnectionStatus.Connected) {
                        val dotColor = if (busy) thinkingColor else toolBorder
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                    }
                    Text("Pi Remote", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = textPrimary)
                    if (busy) Text("thinking...", color = thinkingColor, fontSize = 12.sp, fontStyle = FontStyle.Italic)
                }
                TextButton(onClick = { vm.disconnect() }) { Text("Disconnect", color = errorColor, fontSize = 14.sp) }
            }
        }
        Divider(color = border)

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), state = ls, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages, key = { it.id }) { MessageBubble(it) }
            if (assist.isNotBlank()) {
                item(key = "streaming") {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MessageBubble(ChatMessage(type = MessageToolType.Streaming, content = assist))
                        BlinkDot()
                    }
                }
            }
            item(key = "spacer") { Spacer(Modifier.height(12.dp)) }
        }

        InputArea(vm, input, busy, messages.isNotEmpty())
    }
}

@Composable
fun InputArea(vm: ChatViewModel, input: String, busy: Boolean, hasMessages: Boolean) {
    var steerMode by remember { mutableStateOf(false) }
    var followUpMode by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.background(bgSecondary)) {
        Divider(color = border)
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (steerMode) {
                AssumpChip(onClick = { steerMode = false }, label = { Text("🎯 Steering Pi...", color = thinkingColor, fontSize = 12.sp) })
                Spacer(modifier = Modifier.height(6.dp))
            } else if (followUpMode) {
                AssumpChip(onClick = { followUpMode = false }, label = { Text("↩ Follow-up mode", color = accent, fontSize = 12.sp) })
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { t: String -> vm.setInputText(t) },
                    placeholder = {
                        val h = when { steerMode -> "Guide Pi..." ; followUpMode -> "Follow-up..." ; else -> "Message Pi..." }
                        Text(h, color = textMuted)
                    },
                    modifier = Modifier.weight(1f), maxLines = 4, singleLine = false,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                        if (steerMode) { vm.sendSteer(); steerMode = false }
                        else if (followUpMode) { vm.sendFollowUp(); followUpMode = false }
                        else vm.sendPrompt()
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = bg,
                        unfocusedContainerColor = bg,
                        focusedBorderColor = accent,
                        cursorColor = accent
                    ),
                    textStyle = LocalTextStyle.current.copy(color = textPrimary, fontSize = 15.sp),
                )
                if (steerMode) {
                    FilledTonalIconButton(onClick = { vm.sendSteer(); steerMode = false }, enabled = input.isNotBlank()) {
                        Icon(Icons.Default.Tune, "Steer", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                } else if (followUpMode) {
                    FilledTonalIconButton(onClick = { vm.sendFollowUp(); followUpMode = false }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Default.Send, "FollowUp", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                } else {
                    if (busy) {
                        FilledTonalIconButton(onClick = { steerMode = true }, enabled = input.isNotBlank()) {
                            Icon(Icons.Default.Tune, "Steer", tint = thinkingColor, modifier = Modifier.size(16.dp))
                        }
                    }
                    FilledTonalIconButton(onClick = { vm.sendPrompt() }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Default.Send, "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (busy) {
                    FilterChip(selected = steerMode, onClick = { steerMode = !steerMode },
                        label = { Text("Steer", fontSize = 12.sp, color = thinkingColor) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = thinkingColor.copy(alpha = 0.15f)))
                }
                if (!busy && hasMessages) {
                    FilterChip(selected = followUpMode, onClick = { followUpMode = !followUpMode },
                        label = { Text("Follow-up", fontSize = 12.sp, color = accent) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = 0.15f)))
                }
            }
        }
    }
}

@Composable
fun AssumpChip(onClick: () -> Unit, label: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable { onClick() }) { label() }
}


val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
val timeFormatFull = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
fun tsNow(): String = timeFormat.format(Date())
fun formatTs(ts: Long): String = timeFormat.format(Date(ts))
fun formatTsFull(ts: Long): String = timeFormatFull.format(Date(ts))

@Composable
fun MessageBubble(msg: ChatMessage) {
    when (msg.type) {
        MessageToolType.User -> UserBubble(msg)
        MessageToolType.Assistant -> AssistantBubble(msg)
        MessageToolType.ToolResult -> ToolBubble(msg)
        MessageToolType.Streaming -> if (msg.content.isNotBlank()) AssistantBubble(msg) else Unit
        MessageToolType.Thinking -> ThinkingBubble(msg)
    }
}

@Composable
fun UserBubble(msg: ChatMessage) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Card(colors = CardDefaults.cardColors(containerColor = userBubble), shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), modifier = Modifier.fillMaxWidth(0.88f)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(msg.content, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
                Text(formatTs(msg.timestamp), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
fun AssistantBubble(msg: ChatMessage) {
    Column {
        Text(msg.content, color = assistantText, fontSize = 15.sp, lineHeight = 24.sp, modifier = Modifier.fillMaxWidth())
        Text(formatTs(msg.timestamp), color = textMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun ThinkingBubble(msg: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = bgSecondary.copy(alpha = 0.5f)),
         shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, thinkingColor.copy(alpha = 0.3f))) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text("🤔 Thought", color = thinkingColor, fontSize = 12.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium)
                Text(formatTs(msg.timestamp), fontSize = 9.sp, color = thinkingColor.copy(alpha = 0.5f))
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", color = thinkingColor.copy(alpha = 0.5f), fontSize = 10.sp)
            }
            if (expanded) {
                Divider(color = thinkingColor.copy(alpha = 0.2f))
                Text(msg.content, color = thinkingColor.copy(alpha = 0.85f), fontSize = 13.sp,
                     fontStyle = FontStyle.Italic, lineHeight = 20.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

@Composable
fun ToolBubble(msg: ChatMessage) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    var copied by remember { mutableStateOf(false) }
    val isCode = CodeUtils.isCodeContent(msg.content)
    val filePath = CodeUtils.extractFilePath(msg.content)
    val lineCount = CodeUtils.countLines(msg.content)
    var collapsed by remember { mutableStateOf(msg.content.length > 500) }

    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2000); copied = false } }

    Card(colors = CardDefaults.cardColors(containerColor = bgSecondary), shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (msg.isError) errorColor else if (isCode) accent.copy(alpha = 0.4f) else toolBorder.copy(alpha = 0.4f))) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(if (isCode) "📄" else "⚡", fontSize = 14.sp)
                Text(msg.toolName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (msg.isError) errorColor else toolBorder)
                if (filePath.isNotBlank()) {
                    Text(filePath, fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
                }
                if (isCode && lineCount > 1) {
                    Text("$lineCount lines", fontSize = 10.sp, color = textMuted)
                }
                if (msg.isError) Text("ERROR", color = errorColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(formatTs(msg.timestamp), fontSize = 9.sp, color = textMuted)
                if (msg.content.length > 500) {
                    Text(if (collapsed) "Show" else "Hide", fontSize = 11.sp, color = textSecondary,
                         modifier = Modifier.clickable { collapsed = !collapsed }.padding(horizontal = 4.dp).padding(vertical = 2.dp))
                }
                if (copied) Text("Copied!", color = toolBorder, fontSize = 11.sp)
                IconButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("tool", msg.content)); copied = true }) {
                    Icon(Icons.Default.ContentCopy, "Copy", tint = textSecondary, modifier = Modifier.size(14.dp))
                }
            }
            if (msg.content.isNotBlank()) {
                if (isCode && !collapsed && lineCount > 1) {
                    CodeBlock(msg.content.take(3000))
                } else {
                    val c = if (collapsed) msg.content.take(200) + "..." else msg.content
                    Text(c, color = textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                         modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp))
                }
            }
        }
    }
}

@Composable
fun CodeBlock(content: String) {
    val lines = remember(content) { content.split("\n").mapIndexed { idx, line -> idx + 1 to line } }
    val displayLines = lines.take(50)
    val moreCount = lines.size - displayLines.size

    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).padding(bottom = 8.dp)) {
        for ((num, line) in displayLines) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 0.5.dp)) {
                Text(num.toString(), color = textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                     modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
                AnnotatedDirectedLine(line)
            }
        }
        if (moreCount > 0) {
            Text("... $moreCount more lines", color = textMuted, fontSize = 10.sp, fontStyle = FontStyle.Italic,
                 modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun AnnotatedDirectedLine(line: String) {
    val isComment = line.startsWith("//") || line.startsWith("/*") || line.startsWith(" *") || line.startsWith("*/")
    val isBlank = line.isBlank()
    val hasString = line.contains('"')
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
        else -> textSecondary
    }

    Text(line, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
fun BlinkDot() {
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); blink = !blink } }
    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(if (blink) accent else Color.Transparent))
}
