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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
    // Row height MUST equal the rendered line height (lineHeight = fontSize below),
    // not the measurer's natural height (~1.16em). Using the natural height skewed
    // every tapped row by ~16%, compounding over a tall scrollback so SGR clicks
    // landed tens of rows away from the tapped widget.
    val cellHeight = remember(fontSize, density) { with(density) { fontSize.toPx() } }

    val vScroll = rememberScrollState()
    // Follow the live bottom of the buffer, but never fight the user's finger:
    // stop following the moment they scroll, resume when they release at the
    // bottom. Without this, every frame yanks the view down mid-drag.
    var followBottom by remember { mutableStateOf(true) }
    LaunchedEffect(vScroll.isScrollInProgress) {
        if (vScroll.isScrollInProgress) {
            followBottom = false
        } else if (vScroll.value >= vScroll.maxValue - 16) {
            followBottom = true
        }
    }
    // Drive the auto-scroll off maxValue itself: it only grows AFTER the new
    // frame is measured/laid out, so scrolling on frame.seq used the previous
    // frame's (too-small) maxValue and rested one line short of the bottom.
    LaunchedEffect(Unit) {
        snapshotFlow { vScroll.maxValue }.collect { max ->
            if (followBottom && !vScroll.isScrollInProgress) vScroll.scrollTo(max)
        }
    }

    // Normal-app gestures: long-press + drag selects text (SelectionContainer),
    // a quick tap clicks the terminal (SGR mouse). They coexist because the tap
    // detector below only fires on a release BEFORE the long-press timeout and
    // never consumes the event, so a held press falls through to selection.
    // No horizontal scroll: the host renders at the phone's width, so lines fit.
    Box(modifier = modifier.fillMaxSize().verticalScroll(vScroll)) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth().pointerInput(frame.width, frame.height, cellWidth, cellHeight) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        }
                        if (up != null) {
                            // Quick tap → SGR click. Coords are 1-based and
                            // viewport-relative (bottom `height` lines = screen).
                            val lineIdx = (down.position.y / cellHeight).toInt()
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
                // Per-line memoization: frames mostly repeat (a spinner animates
                // one line); parsing only runs for lines whose raw text changed,
                // and unchanged nodes skip recompose. A line carrying an inline
                // image (kitty/OSC 1337) renders as an image; the rest is text.
                frame.lines.forEachIndexed { idx, raw ->
                    key(idx) {
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
