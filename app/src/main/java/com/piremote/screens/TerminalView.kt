package com.piremote.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.theme.*

/** Parsed ANSI SGR color/style */
data class AnsiStyle(
    val fgR: Int = -1, val fgG: Int = -1, val fgB: Int = -1,
    val bgR: Int = -1, val bgG: Int = -1, val bgB: Int = -1,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

private val ansi8Colors = arrayOf(
    intArrayOf(0, 0, 0),       // 30 black
    intArrayOf(197, 15, 31),   // 31 red
    intArrayOf(6, 96, 17),     // 32 green
    intArrayOf(193, 156, 0),   // 33 yellow
    intArrayOf(21, 44, 227),   // 34 blue
    intArrayOf(203, 0, 117),   // 35 magenta
    intArrayOf(0, 129, 129),   // 36 cyan
    intArrayOf(113, 113, 113)  // 37 white
)

private val ansi8BrightColors = arrayOf(
    intArrayOf(127, 127, 127), // 90 bright black
    intArrayOf(204, 0, 0),     // 91 bright red
    intArrayOf(72, 209, 6),    // 92 bright green
    intArrayOf(255, 255, 85),  // 93 bright yellow
    intArrayOf(6, 122, 255),   // 94 bright blue
    intArrayOf(187, 0, 255),   // 95 bright magenta
    intArrayOf(0, 211, 255),   // 96 bright cyan
    intArrayOf(255, 255, 255)  // 97 bright white
)

/** xterm 256-color palette lookup (0-15 base, 16-231 6x6x6 cube, 232-255 grayscale) */
private fun xtermColor(idx: Int): IntArray? {
    if (idx < 0 || idx > 255) return null
    return when {
        idx < 8 -> ansi8Colors[idx]
        idx < 16 -> ansi8BrightColors[idx - 8]
        idx < 232 -> {
            val n = idx - 16
            val r = n / 36
            val g = (n / 6) % 6
            val b = n % 6
            fun lvl(v: Int) = if (v == 0) 0 else 55 + v * 40
            intArrayOf(lvl(r), lvl(g), lvl(b))
        }
        else -> {
            val v = 8 + (idx - 232) * 10
            intArrayOf(v, v, v)
        }
    }
}

/** Parse a single ANSI line into styled text segments */
fun parseAnsiLine(line: String): List<Pair<String, AnsiStyle>> {
    val segments = ArrayList<Pair<String, AnsiStyle>>()
    var style = AnsiStyle()
    var textBuf = StringBuilder()
    var i = 0

    while (i < line.length) {
        if (line[i] == '\u001b' && i + 1 < line.length && line[i + 1] == '[') {
            var j = i + 2
            while (j < line.length && line[j] != 'm') j++
            if (j < line.length && line[j] == 'm') {
                val sgrStr = line.substring(i + 2, j)
                val codes = sgrStr.split(";")
                if (textBuf.isNotEmpty()) {
                    segments.add(Pair(textBuf.toString(), style.copy()))
                    textBuf = StringBuilder()
                }
                style = parseSgrCodes(codes, style)
                i = j + 1
            } else {
                i++
            }
        } else {
            textBuf.append(line[i])
            i++
        }
    }
    if (textBuf.isNotEmpty()) segments.add(Pair(textBuf.toString(), style.copy()))
    return segments
}

/** Parse SGR codes and return updated style */
private fun parseSgrCodes(codes: List<String>, style: AnsiStyle): AnsiStyle {
    // Empty SGR ([m) is equivalent to reset ([0m)
    if (codes.size == 1 && codes[0].isEmpty()) return AnsiStyle()
    var s = style
    var ci = 0
    while (ci < codes.size) {
        val code = codes[ci].toIntOrNull()
        if (code == null) { ci++; continue }
        if (code == 38 && ci + 1 < codes.size) {
            when (codes[ci + 1].toIntOrNull()) {
                2 -> if (ci + 4 < codes.size) {
                    s = s.copy(fgR = codes[ci+2].toIntOrNull() ?: -1, fgG = codes[ci+3].toIntOrNull() ?: -1, fgB = codes[ci+4].toIntOrNull() ?: -1)
                    ci += 5; continue
                }
                5 -> if (ci + 2 < codes.size) {
                    val rgb = codes[ci+2].toIntOrNull()?.let { xtermColor(it) }
                    if (rgb != null) s = s.copy(fgR = rgb[0], fgG = rgb[1], fgB = rgb[2])
                    ci += 3; continue
                }
            }
            ci++; continue
        }
        if (code == 48 && ci + 1 < codes.size) {
            when (codes[ci + 1].toIntOrNull()) {
                2 -> if (ci + 4 < codes.size) {
                    s = s.copy(bgR = codes[ci+2].toIntOrNull() ?: -1, bgG = codes[ci+3].toIntOrNull() ?: -1, bgB = codes[ci+4].toIntOrNull() ?: -1)
                    ci += 5; continue
                }
                5 -> if (ci + 2 < codes.size) {
                    val rgb = codes[ci+2].toIntOrNull()?.let { xtermColor(it) }
                    if (rgb != null) s = s.copy(bgR = rgb[0], bgG = rgb[1], bgB = rgb[2])
                    ci += 3; continue
                }
            }
            ci++; continue
        }
        when (code) {
            0 -> s = AnsiStyle()
            1 -> s = s.copy(bold = true)
            2 -> s = s.copy(dim = true)
            3 -> s = s.copy(italic = true)
            4 -> s = s.copy(underline = true)
            in 30..37 -> { val c = ansi8Colors[code - 30]; s = s.copy(fgR = c[0], fgG = c[1], fgB = c[2]) }
            39 -> s = s.copy(fgR = -1, fgG = -1, fgB = -1)
            in 40..47 -> { val c = ansi8Colors[code - 40]; s = s.copy(bgR = c[0], bgG = c[1], bgB = c[2]) }
            49 -> s = s.copy(bgR = -1, bgG = -1, bgB = -1)
            in 90..97 -> { val c = ansi8BrightColors[code - 90]; s = s.copy(fgR = c[0], fgG = c[1], fgB = c[2]) }
            in 100..107 -> { val c = ansi8BrightColors[code - 100]; s = s.copy(bgR = c[0], bgG = c[1], bgB = c[2]) }
        }
        ci++
    }
    return s
}

/** Build a Compose AnnotatedString from ANSI segments. [defaultColor] is used
 *  for text that carries no explicit ANSI foreground, so callers (e.g. the muted
 *  status bar) keep their own base colour while still honouring embedded colours. */
fun buildAnsiText(
    segments: List<Pair<String, AnsiStyle>>,
    defaultColor: Color = com.piremote.theme.textPrimary,
): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    segments.forEach { (text, style) ->
        val fgColor = if (style.fgR >= 0) {
            val base = Color(style.fgR / 255f, style.fgG / 255f, style.fgB / 255f)
            if (style.dim) base.copy(alpha = 0.5f) else base
        } else if (style.dim) com.piremote.theme.textMuted else defaultColor
        
        val bgColor = if (style.bgR >= 0) Color(style.bgR / 255f, style.bgG / 255f, style.bgB / 255f) else Color.Transparent

        builder.withStyle(
            style = androidx.compose.ui.text.SpanStyle(
                color = fgColor,
                background = bgColor,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (style.underline) TextDecoration.Underline else null
            )
        ) {
            append(text)
        }
    }
    return builder.toAnnotatedString()
}

/**
 * Full-screen ANSI Terminal view — renders raw ANSI text from ANY Pi extension TUI component.
 * Works with DOOM, text editors, selectors, settings lists, custom tools — everything pi renders.
 */
@Composable
fun TerminalRenderView(frame: com.piremote.RenderFrame, onInput: (String) -> Unit = {}) {
    Box(modifier = Modifier.fillMaxSize().background(com.piremote.theme.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Title bar
            if (frame.title.isNotBlank()) {
                Column(modifier = Modifier.fillMaxWidth().background(com.piremote.theme.bgSecondary)) {
                    Text(
                        frame.title,
                        color = com.piremote.theme.accent,
                        fontFamily = com.piremote.screens.piMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    HorizontalDivider(color = com.piremote.theme.borderMuted)
                }
            }

            // Rendered ANSI lines. Lines with a non-empty tapValues[i] entry
            // get a clickable modifier; tapping sends the entry back via
            // sendInput, so a render-frame menu (e.g. /resume's session list)
            // is touch-driven with no per-extension UI on the phone.
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp)) {
                frame.ansiLines.forEachIndexed { i, line ->
                    val segments = parseAnsiLine(line)
                    if (segments.all { it.first.isEmpty() }) {
                        Spacer(Modifier.height(12.dp))
                    } else {
                        val tap = frame.tapValues.getOrNull(i).orEmpty()
                        val text = @Composable {
                            Text(
                                buildAnsiText(segments),
                                fontFamily = com.piremote.screens.piMono,
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                        if (tap.isNotEmpty()) {
                            Box(modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInput(tap) }
                            ) { text() }
                        } else {
                            text()
                        }
                    }
                }
            }

            // Input area based on mode
            when (frame.inputMode) {
                "text" -> {
                    var input by remember { mutableStateOf("") }
                    Column(modifier = Modifier.fillMaxWidth().background(com.piremote.theme.bgSecondary)) {
                        HorizontalDivider(color = com.piremote.theme.accent)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Text(">", color = com.piremote.theme.accent, fontFamily = com.piremote.screens.piMono, fontSize = 12.sp)
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                textStyle = TextStyle(color = com.piremote.theme.textPrimary, fontFamily = com.piremote.screens.piMono, fontSize = 12.sp),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (input.isEmpty()) Text("Type here...", color = com.piremote.theme.textMuted, fontFamily = com.piremote.screens.piMono, fontSize = 12.sp)
                                    else innerTextField()
                                }
                            )
                            Text("⏎", color = com.piremote.theme.textMuted, fontFamily = com.piremote.screens.piMono, fontSize = 14.sp,
                                modifier = Modifier.clickable { onInput(input); input = "" })
                        }
                    }
                }
            }
        }
    }
}
