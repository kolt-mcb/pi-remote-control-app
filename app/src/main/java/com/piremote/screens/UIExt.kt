package com.piremote.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.*
import com.piremote.theme.*

/** Pi Terminal Styled Dialog for select() / confirm() */
@Composable
fun SelectDialog(req: ExtensionUIRequest, onRespond: (id: String, value: String) -> Unit, onCancel: (id: String) -> Unit) {
    val isConfirm = req.method == "confirm"
    Dialog(
        onDismissRequest = { onCancel(req.id) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 600.dp)
                .background(bgSecondary)
                .border(BorderStroke(1.dp, accent), RoundedCornerShape(0.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header: title + close, single divider — no nested boxes.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        req.title,
                        color = accent,
                        fontFamily = piMono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "[✕]",
                        color = textMuted,
                        fontFamily = piMono,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onCancel(req.id) }.padding(4.dp)
                    )
                }
                HorizontalDivider(color = borderMuted)

                if (!req.message.isNullOrBlank()) {
                    Text(
                        req.message,
                        color = textSecondary,
                        fontFamily = piMono,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    HorizontalDivider(color = borderMuted)
                }

                if (isConfirm) {
                    // Confirm has two affordances; the tap is the choice, no separate confirm button.
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onRespond(req.id, "true") }
                                .padding(vertical = 14.dp)
                        ) {
                            Text(
                                "YES",
                                color = toolBorder,
                                fontFamily = piMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(borderMuted))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onRespond(req.id, "false") }
                                .padding(vertical = 14.dp)
                        ) {
                            Text(
                                "NO",
                                color = textSecondary,
                                fontFamily = piMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                } else {
                    // List of options. LazyColumn handles long lists without flattening Compose.
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(req.options) { opt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onRespond(req.id, opt) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("▸ ", color = accent, fontFamily = piMono, fontSize = 12.sp)
                                Text(opt, color = textPrimary, fontFamily = piMono, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = borderMuted.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

/** Pi Terminal Styled Dialog for input() / editor() */
@Composable
fun InputDialog(req: ExtensionUIRequest, onRespond: (id: String, value: String) -> Unit, onCancel: (id: String) -> Unit) {
    var text by remember { mutableStateOf(req.prefill ?: "") }
    val isEditor = req.method == "editor"
    AlertDialog(
        onDismissRequest = { onCancel(req.id) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(req.title, color = accent, fontFamily = piMono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (isEditor) Text("[editor]", color = thinkingColor, fontFamily = piMono, fontSize = 10.sp, fontStyle = FontStyle.Italic)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (req.message.isNullOrBlank().not()) {
                    Box(modifier = Modifier.border(BorderStroke(1.dp, borderMuted), RoundedCornerShape(0.dp)).padding(6.dp)) {
                        Text(req.message!!, color = textSecondary, fontFamily = piMono, fontSize = 12.sp)
                    }
                }
                Box(modifier = Modifier.border(BorderStroke(1.dp, accent), RoundedCornerShape(0.dp)).padding(8.dp)) {
                    TextField(value = text, onValueChange = { text = it },
                        placeholder = { Text(req.placeholder ?: "", color = textMuted, fontFamily = piMono) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = if (isEditor) 10 else 1,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = bg, unfocusedContainerColor = bg,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, cursorColor = accent
                        ),
                        textStyle = LocalTextStyle.current.copy(color = textPrimary, fontFamily = piMono, fontSize = 13.sp)
                    )
                }
            }
        },
        confirmButton = {
            Box(modifier = Modifier
                .border(BorderStroke(1.dp, accent), RoundedCornerShape(0.dp))
                .clickable(enabled = text.isNotBlank()) { onRespond(req.id, text) }
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("[OK]", color = accent, fontFamily = piMono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Box(modifier = Modifier
                .border(BorderStroke(1.dp, borderMuted), RoundedCornerShape(0.dp))
                .clickable { onCancel(req.id) }
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("[CANCEL]", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
            }
        },
        containerColor = bgSecondary,
        tonalElevation = 4.dp
    )
}

/** Pi Terminal Styled Banner for notify() */
@Composable
fun NotifyBanner(msg: String, type: String) {
    val icon = when (type) { "error" -> "✕" ; "warning" -> "⚠" ; else -> "i" }
    val color = when (type) { "error" -> error ; "warning" -> warning ; else -> accent }
    Surface(color = color.copy(alpha = 0.06f), tonalElevation = 0.dp,
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(icon, color = color, fontFamily = piMono, fontSize = 12.sp)
            Text(msg, color = color, fontFamily = piMono, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2)
        }
    }
}

/** Pi Terminal Styled Widget panel for setWidget() */
@Composable
fun PiWidgetPanel(key: String, lines: List<String>) {
    Column {
        // Top: ┌─ key ──
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp)) {
            Text("┌", color = borderMuted, fontFamily = piMono, fontSize = 10.sp)
            Text("─ $key", color = borderMuted, fontFamily = piMono, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("┐", color = borderMuted, fontFamily = piMono, fontSize = 10.sp)
        }
        // Body with left border
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Top) {
            Text("│", color = borderMuted, fontFamily = piMono, fontSize = 10.sp)
            Column(modifier = Modifier.weight(1f).padding(vertical = 3.dp, horizontal = 4.dp)) {
                lines.forEach { line ->
                    Text(line, color = textSecondary, fontFamily = piMono, fontSize = 11.sp)
                }
            }
        }
        // Bottom: └──────
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("└", color = borderMuted, fontFamily = piMono, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("┘", color = borderMuted, fontFamily = piMono, fontSize = 10.sp)
        }
    }
}

/** Pi Terminal Styled Status bar for setStatus() */
@Composable
fun PiStatusBarLine(statuses: Map<String, String>) {
    if (statuses.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(bgSecondary)
            .border(BorderStroke(0.5.dp, borderMuted), RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp)) {
        Text("├", color = borderMuted, fontFamily = piMono, fontSize = 10.sp)
        statuses.forEach { (_, text) ->
            if (text.isNotBlank()) Text(text, color = textMuted, fontFamily = piMono, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Pi Terminal Styled Compaction Banner */
@Composable
fun PiCompactingBanner() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .border(BorderStroke(0.5.dp, borderMuted), RoundedCornerShape(0.dp))
            .background(bgSecondary)
            .padding(horizontal = 10.dp, vertical = 4.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(10.dp), color = thinkingBorder, strokeWidth = 1.5.dp)
        Text("compacting context...", color = thinkingBorder, fontFamily = piMono, fontSize = 11.sp, fontStyle = FontStyle.Italic)
    }
}

/** Pi Terminal Styled Retry Banner */
@Composable
fun PiRetryBanner(status: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .border(BorderStroke(0.5.dp, error.copy(alpha = 0.3f)), RoundedCornerShape(0.dp))
            .background(error.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text("retry: $status", color = error, fontFamily = piMono, fontSize = 10.sp)
    }
}

// ── Backwards-compatible aliases ──────────────────────────────────────

val errorColor = error
val accentColor = accent

@Composable
fun WidgetPanel(key: String, lines: List<String>) = PiWidgetPanel(key, lines)

@Composable
fun StatusBarLine(statuses: Map<String, String>) = PiStatusBarLine(statuses)

@Composable
fun CompactingBanner() = PiCompactingBanner()

@Composable
fun RetryBanner(status: String) = PiRetryBanner(status)
