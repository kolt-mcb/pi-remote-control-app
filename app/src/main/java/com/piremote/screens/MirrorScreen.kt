package com.piremote.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.piremote.MirrorFrame
import com.piremote.theme.textMuted
import com.piremote.tty.MirrorItem
import com.piremote.tty.TtyBlock
import com.piremote.tty.parseMirrorLine
import kotlinx.coroutines.withTimeoutOrNull

private const val ESC = "\u001b"

/**
 * Live terminal render of a pi session (tty mirror). Drop-in content for the
 * session view: shows the host TUI's composed frames verbatim — widgets,
 * overlays, autocomplete, everything — and maps taps to SGR mouse events at
 * the tapped cell, so anything clickable on the desktop is tappable here.
 */
@Composable
fun MirrorSurface(frame: MirrorFrame, modifier: Modifier, onInput: (String) -> Unit) {
    val fontSize = 12.sp
    val density = LocalDensity.current
    // Measure the real glyph ADVANCE (width) so taps map to the host's columns.
    val measurer = rememberTextMeasurer()
    val cellWidth = remember(fontSize, density) {
        val r = measurer.measure(
            AnnotatedString("0".repeat(100)),
            TextStyle(fontFamily = piMono, fontSize = fontSize),
        )
        r.size.width / 100f
    }

    val listState = rememberLazyListState()
    // Follow the live bottom of the buffer, but never fight the user's finger:
    // stop following the moment they scroll, resume when they settle at the bottom.
    var followBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            followBottom = last == null || last.index >= frame.lines.size - 1
        }
    }
    // Stick to the bottom as new frames arrive: scrollToItem(last) clamps to the
    // end, leaving the latest line at the viewport bottom.
    LaunchedEffect(frame.seq) {
        if (followBottom && frame.lines.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(frame.lines.size - 1)
        }
    }

    // Virtualized: only on-screen rows are composed, so scrolling stays fast no
    // matter how long the conversation/scrollback grows (the host sends the full
    // composed buffer, which can be thousands of lines).
    // Gestures: a quick tap clicks the terminal (SGR mouse); a held press falls
    // through (non-consuming) to SelectionContainer for text selection.
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize().pointerInput(frame.width, frame.height) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (up != null) {
                        // Quick tap → SGR click. Find the visible row under the tap
                        // (robust to variable-height image rows), then map to the
                        // host's 1-based, viewport-relative coordinates.
                        val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            down.position.y >= it.offset && down.position.y < it.offset + it.size
                        }
                        val lineIdx = hit?.index ?: listState.firstVisibleItemIndex
                        val viewportTop = maxOf(0, frame.lines.size - frame.height)
                        val row = lineIdx - viewportTop + 1
                        val col = (down.position.x / cellWidth).toInt() + 1
                        if (row in 1..frame.height && col in 1..frame.width) {
                            onInput("$ESC[<0;$col;${row}M") // press
                            onInput("$ESC[<0;$col;${row}m") // release
                        }
                    }
                    // up == null → held past long-press: leave it for
                    // SelectionContainer to start a selection.
                }
            },
        ) {
            // Per-line memoization: parsing only runs for a row whose raw text
            // changed; a row carrying an inline image (kitty/OSC 1337) renders as
            // an image, the rest is text.
            items(frame.lines.size, key = { it }) { idx ->
                val raw = frame.lines[idx]
                val item = remember(raw) { parseMirrorLine(raw) }
                when (item) {
                    is MirrorItem.Line -> {
                        val styled = remember(raw) { buildAnsiText(item.segments) }
                        Text(
                            text = styled,
                            fontFamily = piMono,
                            fontSize = fontSize,
                            lineHeight = fontSize,
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    is MirrorItem.Img -> MirrorImageItem(item.image)
                }
            }
        }
    }
}

/** Render an inline image from the mirror (kitty graphics / OSC 1337), scaled to
 *  the mirror width preserving aspect ratio. Falls back to "[image]" if decode
 *  fails (e.g. an oversized or corrupt payload). */
@Composable
private fun MirrorImageItem(image: TtyBlock.Image) {
    val bitmap = remember(image.base64) { decodeBase64Image(image.base64) }
    if (bitmap == null) {
        Text("[image]", fontFamily = piMono, fontSize = 12.sp, color = textMuted)
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "inline image",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
    )
}
