package com.piremote.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.piremote.ChatImage
import com.piremote.ChatMessage
import com.piremote.MessageToolType
import com.piremote.theme.*
import com.piremote.tty.AnsiStyle
import com.piremote.tty.CellSpec
import com.piremote.tty.TtyBlock

/** Measured monospace grid metrics for the scrollback viewport. */
data class TtyMetrics(
    val cols: Int,
    val cellWidth: Dp,
    val cellHeight: Dp,
    val fontSize: TextUnit,
)

/**
 * Measure the real advance of the mono font instead of guessing 0.6em — the
 * same column count feeds vm.reportViewport() and local wrapping, so host
 * rendering and phone rendering agree on width.
 */
@Composable
fun rememberTtyMetrics(maxWidth: Dp, fontSize: TextUnit = 12.sp): TtyMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(maxWidth, fontSize, density) {
        val result = measurer.measure(
            AnnotatedString("0".repeat(100)),
            TextStyle(fontFamily = piMono, fontSize = fontSize)
        )
        with(density) {
            val cw = (result.size.width / 100f).toDp()
            val ch = result.size.height.toDp()
            // 12.dp = the LazyColumn's 6.dp horizontal contentPadding x2.
            val cols = if (cw > 0.dp) ((maxWidth - 12.dp) / cw).toInt().coerceIn(20, 200) else 60
            TtyMetrics(cols, cw, ch, fontSize)
        }
    }
}

/**
 * The unified TTY scrollback: one continuous terminal document. Every message
 * renders to ANSI+OSC, parses to blocks, and lands here as monospace text and
 * inline images — no bubbles, no cards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TtyScrollback(
    messages: List<ChatMessage>,
    assist: String,
    busy: Boolean,
    showStartupHeader: Boolean,
    metrics: TtyMetrics,
    listState: LazyListState,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme by ThemeManager.flow.collectAsState()
    val renderer = remember { ScrollbackRenderer() }
    var expandedIds by remember { mutableStateOf(setOf<String>()) } // reset on config change is acceptable
    var viewer by remember { mutableStateOf<TtyBlock.Image?>(null) }
    val clipboard = LocalClipboardManager.current

    // The trailing Streaming message shows live text already; the `assist`
    // preview is only for the pure-thinking window before text_start fires.
    val tailHasLiveText = messages.lastOrNull()?.let {
        it.type == MessageToolType.Streaming && it.content.isNotBlank()
    } == true

    val entries = remember(messages, metrics.cols, expandedIds, theme) {
        messages.flatMap { renderer.entries(it, metrics.cols, it.id in expandedIds, theme) }
    }
    val liveEntries = remember(assist, tailHasLiveText, metrics.cols, theme) {
        if (assist.isNotBlank() && !tailHasLiveText) {
            renderer.entries(
                ChatMessage(id = "live-assist", type = MessageToolType.Streaming, content = assist),
                metrics.cols, expanded = false, theme = theme
            )
        } else emptyList()
    }

    fun toggle(msgId: String) {
        expandedIds = if (msgId in expandedIds) expandedIds - msgId else expandedIds + msgId
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
    ) {
        if (showStartupHeader) {
            item(key = "startup") { PiStartupHeader() }
        }
        items(entries + liveEntries, key = { it.key }) { entry ->
            when (val block = entry.block) {
                is TtyBlock.Text -> TtyTextItem(
                    entry = entry,
                    block = block,
                    metrics = metrics,
                    onOpenLink = onOpenLink,
                    onToggle = ::toggle,
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                )
                is TtyBlock.Image -> TtyImageItem(
                    block = block,
                    metrics = metrics,
                    onOpen = { viewer = block },
                )
            }
        }
        if (busy) {
            item(key = "caret") {
                Row { PiBlinkBlock(color = accent) }
            }
        }
        item(key = "spacer") { Spacer(Modifier.height(6.dp)) }
    }

    viewer?.let { img ->
        ImageViewerDialog(image = img, onDismiss = { viewer = null })
    }
}

// ── text blocks ────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TtyTextItem(
    entry: ScrollbackEntry,
    block: TtyBlock.Text,
    metrics: TtyMetrics,
    onOpenLink: (String) -> Unit,
    onToggle: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val openLink by rememberUpdatedState(onOpenLink)
    val defaultColor = textPrimary
    val dimColor = textMuted
    val annotated = remember(block, defaultColor, dimColor) {
        buildTtyText(block.lines, defaultColor, dimColor) { url -> openLink(url) }
    }

    val base = Modifier.fillMaxWidth()
    val tapped = when (val tap = entry.tap) {
        is TapAction.ToggleExpand -> base.combinedClickable(
            onClick = { onToggle(tap.msgId) },
            onLongClick = entry.copyText?.let { text -> { onCopy(text) } },
        )
        else -> base
    }

    Text(
        annotated,
        fontFamily = piMono,
        fontSize = metrics.fontSize,
        softWrap = false,
        modifier = tapped,
    )
}

/**
 * AnnotatedString builder for parsed TTY lines. Like buildAnsiText, plus
 * OSC 8 links become LinkAnnotation.Clickable for sub-line tap targets.
 */
fun buildTtyText(
    lines: List<List<Pair<String, AnsiStyle>>>,
    defaultColor: Color,
    dimColor: Color,
    onOpenLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { idx, line ->
        if (idx > 0) append('\n')
        line.forEach { (text, style) ->
            val span = style.toSpanStyle(defaultColor, dimColor)
            val link = style.link
            if (link != null) {
                withLink(LinkAnnotation.Clickable(link) { onOpenLink(link) }) {
                    withStyle(span) { append(text) }
                }
            } else {
                withStyle(span) { append(text) }
            }
        }
    }
}

private fun AnsiStyle.toSpanStyle(defaultColor: Color, dimColor: Color): SpanStyle {
    val fgColor = if (fgR >= 0) {
        val base = Color(fgR / 255f, fgG / 255f, fgB / 255f)
        if (dim) base.copy(alpha = 0.5f) else base
    } else if (dim) dimColor else defaultColor
    val bgColor = if (bgR >= 0) Color(bgR / 255f, bgG / 255f, bgB / 255f) else Color.Transparent
    return SpanStyle(
        color = fgColor,
        background = bgColor,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

// ── image blocks ───────────────────────────────────────────────────────

@Composable
private fun TtyImageItem(
    block: TtyBlock.Image,
    metrics: TtyMetrics,
    onOpen: () -> Unit,
) {
    val bitmap = remember(block.base64) { decodeBase64Image(block.base64) }
    if (bitmap == null) {
        TtyBrailleFallback(block, metrics)
        return
    }

    val density = LocalDensity.current
    val viewport = metrics.cellWidth * (metrics.cols - 2)
    val naturalWidth = with(density) { bitmap.width.toDp() }

    val width = when (val spec = block.widthSpec) {
        is CellSpec.Cells -> metrics.cellWidth * spec.n
        is CellSpec.Pixels -> with(density) { spec.n.toDp() }
        is CellSpec.Percent -> viewport * (spec.n.coerceIn(1, 100) / 100f)
        is CellSpec.Auto -> minOf(naturalWidth, viewport)
    }.coerceAtMost(viewport)

    var modifier = Modifier
        .padding(vertical = 2.dp)
        .width(width)
        .clickable { onOpen() }
    when (val spec = block.heightSpec) {
        is CellSpec.Cells -> modifier = modifier.height(metrics.cellHeight * spec.n)
        is CellSpec.Pixels -> modifier = modifier.height(with(density) { spec.n.toDp() })
        is CellSpec.Percent, is CellSpec.Auto -> {
            // Height follows the bitmap's aspect ratio at the resolved width.
            val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
            modifier = modifier.height(width * aspect)
        }
    }

    Image(
        bitmap.asImageBitmap(),
        contentDescription = "inline image",
        contentScale = if (block.preserveAspect) ContentScale.Fit else ContentScale.FillBounds,
        modifier = modifier,
    )
}

/** Decode failure → braille charset art; final fallback "[image]". */
@Composable
private fun TtyBrailleFallback(block: TtyBlock.Image, metrics: TtyMetrics) {
    val cols = metrics.cols.coerceAtMost(36)
    val lines = remember(block.base64, cols) {
        val cells = renderImageToBraille(ChatImage(block.base64, block.mimeType), cols)
        if (cells.isEmpty()) null else brailleToAnsiLines(cells, cols)
    }
    if (lines == null) {
        Text("[image]", color = textMuted, fontFamily = piMono, fontSize = metrics.fontSize)
        return
    }
    val annotated = remember(lines) {
        buildTtyText(lines, textPrimary, textMuted) {}
    }
    Text(annotated, fontFamily = piMono, fontSize = metrics.fontSize, softWrap = false)
}

// ── fullscreen viewer ──────────────────────────────────────────────────

@Composable
fun ImageViewerDialog(image: TtyBlock.Image, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 8f)
            offset += pan
        }
        val bitmap: Bitmap? = remember(image.base64) { decodeBase64Image(image.base64) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable { onDismiss() }
                .transformable(transform),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "image viewer",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } ?: Text("[image failed to decode]", color = Color.White, fontFamily = piMono)
        }
    }
}
