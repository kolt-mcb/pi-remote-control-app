package com.piremote.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.theme.*

// Per-tool inline rendering for Edit / MultiEdit / Write — pulls structured
// data out of the tool's args JSON and renders a unified-style diff (or new-
// content preview) instead of pi's "Successfully replaced N block(s)" status
// string, which carries none of the actual change.

/**
 * One file's edits, rendered as a unified-style diff.
 *
 *   path/to/file.kt
 *   - val foo = 1
 *   + val foo = 2
 *
 * `oldText` / `newText` are pi's `edits[].oldText` and `edits[].newText`.
 * No LCS — we just show the old block then the new block, which reads cleanly
 * for the typical sub-block replacements pi emits. Empty `oldText` (an
 * insertion) renders only the `+` half; empty `newText` (a deletion) renders
 * only the `-` half.
 */
@Composable
fun PiEditHunk(oldText: String, newText: String) {
    val oldLines = oldText.split("\n")
    val newLines = newText.split("\n")
    val removed = diffRowBg(error)
    val added   = diffRowBg(success)
    Column(modifier = Modifier.fillMaxWidth()) {
        if (oldText.isNotEmpty()) {
            for (line in oldLines) {
                DiffLine(prefix = "-", text = line, fg = error, bg = removed)
            }
        }
        if (newText.isNotEmpty()) {
            for (line in newLines) {
                DiffLine(prefix = "+", text = line, fg = success, bg = added)
            }
        }
    }
}

/**
 * Write-tool rendering: file path + the (possibly truncated) new content as
 * an all-additions block. No diff because there's no prior text.
 */
@Composable
fun PiWriteContent(content: String, maxLines: Int = 30) {
    val lines = content.split("\n")
    val shown = lines.take(maxLines)
    val truncated = lines.size - shown.size
    val added = diffRowBg(success)
    Column(modifier = Modifier.fillMaxWidth()) {
        for (line in shown) {
            DiffLine(prefix = "+", text = line, fg = success, bg = added)
        }
        if (truncated > 0) {
            Text(
                "  … $truncated more line${if (truncated == 1) "" else "s"}",
                color = textMuted, fontFamily = piMono, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DiffLine(prefix: String, text: String, fg: Color, bg: Color) {
    Row(modifier = Modifier.fillMaxWidth().background(bg)) {
        Text(prefix, color = fg, fontFamily = piMono, fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp))
        Text(text, color = fg.copy(alpha = 0.9f), fontFamily = piMono, fontSize = 11.sp)
    }
}

private fun diffRowBg(accent: Color): Color = accent.copy(alpha = 0.08f)
