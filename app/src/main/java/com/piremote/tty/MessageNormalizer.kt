package com.piremote.tty

import androidx.compose.ui.graphics.Color
import com.piremote.ChatImage
import com.piremote.ChatMessage
import com.piremote.MessageToolType
import com.piremote.theme.*

/**
 * The thin adapter layer: turns a [ChatMessage] into a single ANSI+OSC stream
 * that [TtyStreamParser] decodes into render blocks.
 *
 * When the host sends host-rendered [ChatMessage.ansiLines], those pass
 * straight through for every message type. Client-side fallbacks exist only
 * for older hosts that don't yet render to ANSI.
 */
object MessageNormalizer {

    /** Max columns an inline image block requests by default. */
    private const val IMAGE_MAX_COLS = 40

    /** Render [msg] to a full ANSI+OSC stream wrapped/decorated for [cols]. */
    fun toStream(msg: ChatMessage, cols: Int, expanded: Boolean): String {
        // Host-rendered stream: the complete presentation, shown verbatim (the
        // host owns layout — no gutter, no client decoration). Images the host
        // couldn't embed as OSC sequences (oversize) are appended after it.
        val hostStream = (if (expanded) msg.streamExpanded ?: msg.stream else msg.stream)
        if (hostStream != null) {
            val out = StringBuilder(hostStream)
            if (!hostStream.endsWith("\n")) out.append('\n')
            msg.images.forEach { out.append(imageStream(it, cols)) }
            return out.toString()
        }

        // Host-rendered ANSI lines pass through for ANY message type.
        msg.ansiLines?.takeIf { it.isNotEmpty() }?.let { lines ->
            val out = StringBuilder()
            lines.forEach { out.appendLine(gutter(it)) }
            // Images previously rode a separate structured array; keep showing
            // them when an older host sends both ansiLines and images.
            msg.images.forEach { out.append(imageStream(it, cols)) }
            return out.toString()
        }

        // Fallback for older hosts that don't send a rendered stream.
        return when (msg.type) {
            MessageToolType.User -> userFallback(msg, cols)
            MessageToolType.Assistant -> assistantFallback(msg, cols)
            MessageToolType.ToolResult -> toolFallback(msg, cols, expanded)
            MessageToolType.Thinking -> thinkingFallback(msg, cols, expanded)
            MessageToolType.Streaming -> streamingFallback(msg, cols)
            MessageToolType.Custom -> ""
        }
    }

    // ── fallback renderers (older hosts) ──────────────────────────────

    private fun userFallback(msg: ChatMessage, cols: Int): String {
        val out = StringBuilder()
        val innerCols = (cols * 0.85).toInt().coerceIn(20, (cols - 4).coerceAtLeast(20))
        if (msg.content.isNotBlank()) {
            wrapText(escapeContent(msg.content), innerCols).forEach { line ->
                out.appendLine(gutter("${fg(accent)}>${TtyEscapes.RESET} $line"))
            }
        }
        msg.images.forEach { out.append(imageStream(it, cols)) }
        return out.toString()
    }

    private fun assistantFallback(msg: ChatMessage, cols: Int): String {
        val out = StringBuilder()
        if (msg.content.isNotBlank()) {
            wrapText(msg.content, cols - 2).forEach { line ->
                out.appendLine(gutter(line))
            }
        }
        return out.toString()
    }

    private fun toolFallback(msg: ChatMessage, cols: Int, expanded: Boolean): String {
        val out = StringBuilder()
        val dotColor = if (msg.isError) error else toolBorder
        val tn = msg.toolName.lowercase()
        val isEdit = tn == "edit" || tn == "multiedit" || tn == "multi_edit"
        val isWrite = tn == "write"

        // Header: ● ToolName(args)
        val argPreview = parseToolArgs(msg.toolArgs).take(35)
        out.appendLine(gutter(buildString {
            append("${fg(dotColor)}●${TtyEscapes.RESET} ")
            append("${TtyEscapes.BOLD}${msg.toolName.ifBlank { "Tool" }}${TtyEscapes.RESET}")
            if (argPreview.isNotBlank()) append("${fg(textMuted)}($argPreview)${TtyEscapes.RESET}")
            if (msg.isError) append(" ${fg(error)}${TtyEscapes.BOLD}error${TtyEscapes.RESET}")
        }))

        msg.images.forEach { out.append(imageStream(it, cols)) }

        if (!expanded) {
            summaryLineFallback(msg, isEdit, isWrite, cols)?.let { line ->
                out.appendLine(gutter("${fg(textMuted)}$line${TtyEscapes.RESET}"))
            }
            return out.toString()
        }

        if (isEdit) {
            val hunks = parseEdits(msg.toolArgs)
            hunks.forEach { (oldText, newText) ->
                if (oldText.isNotEmpty()) oldText.split("\n").forEach { line ->
                    out.appendLine(gutter("${fg(error)}-${TtyEscapes.RESET} ${escapeContent(line)}"))
                }
                if (newText.isNotEmpty()) newText.split("\n").forEach { line ->
                    out.appendLine(gutter("${fg(success)}+${TtyEscapes.RESET} ${escapeContent(line)}"))
                }
            }
        }

        if (isWrite) {
            parseWriteContent(msg.toolArgs)?.let { content ->
                content.split("\n").take(25).forEach { line ->
                    out.appendLine(gutter("${fg(success)}+ ${escapeContent(line)}${TtyEscapes.RESET}"))
                }
            }
        }

        if (msg.content.isNotBlank() && !isEdit && !isWrite) {
            val innerCols = (cols * 0.85).toInt().coerceIn(20, (cols - 4).coerceAtLeast(20))
            wrapText(escapeContent(msg.content), innerCols - 2).forEach { line ->
                out.appendLine(gutter("${fg(textMuted)}$line${TtyEscapes.RESET}"))
            }
        }

        return out.toString()
    }

    private fun summaryLineFallback(msg: ChatMessage, isEdit: Boolean, isWrite: Boolean, cols: Int): String? {
        if (isEdit) {
            val n = parseEdits(msg.toolArgs).size
            return if (n > 0) "$n edit${if (n > 1) "s" else ""}" else null
        }
        if (isWrite) {
            val n = parseWriteContent(msg.toolArgs)?.let { simpleCountLines(it) } ?: return null
            return "$n line${if (n > 1) "s" else ""} written"
        }
        if (msg.content.isBlank()) return null
        return msg.content.split("\n").firstOrNull { it.isNotBlank() }?.take(cols - 8)
    }

    private fun thinkingFallback(msg: ChatMessage, cols: Int, expanded: Boolean): String {
        val out = StringBuilder()
        out.appendLine(gutter("${fg(thinkingBorder)}${TtyEscapes.ITALIC}Thinking…${TtyEscapes.RESET}"))
        if (expanded && msg.content.isNotBlank()) {
            wrapText(escapeContent(msg.content), (cols - 6).coerceAtLeast(20)).forEach { line ->
                out.appendLine(gutter("${fg(thinkingBorder)}${TtyEscapes.DIM}${TtyEscapes.ITALIC}$line${TtyEscapes.RESET}"))
            }
        }
        return out.toString()
    }

    private fun streamingFallback(msg: ChatMessage, cols: Int): String {
        if (msg.content.isBlank()) return ""
        val out = StringBuilder()
        wrapText(msg.content, cols - 2).forEach { line ->
            out.appendLine(gutter(line))
        }
        return out.toString()
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Desugar a structured ChatImage into the OSC 1337 escape on its own line. */
    private fun imageStream(img: ChatImage, cols: Int): String {
        val width = CellSpec.Cells((cols - 2).coerceAtMost(IMAGE_MAX_COLS).coerceAtLeast(4))
        return TtyEscapes.osc1337Image(img.data, img.mimeType, width = width) + "\n"
    }

    private fun fg(color: Color): String = TtyEscapes.fg(
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt(),
    )

    /** Gutter-prefixed line: "│ content" with the gutter in borderMuted. */
    private fun gutter(line: String): String =
        "${fg(borderMuted)}│${TtyEscapes.RESET} $line"

    /** Strip ESC from user-supplied content so it cannot inject sequences. */
    private fun escapeContent(text: String): String = text.filter { it.code != 0x1b }

    /** Simple word-wrap for plain text; preserves existing newlines. */
    internal fun wrapText(text: String, maxWidth: Int): List<String> {
        val out = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            var current = StringBuilder()
            paragraph.split(" ").forEach { word ->
                when {
                    current.isEmpty() -> current.append(word)
                    current.length + 1 + word.length <= maxWidth -> current.append(' ').append(word)
                    else -> { out.add(current.toString()); current = StringBuilder(word) }
                }
            }
            out.add(current.toString())
        }
        return out.ifEmpty { listOf("") }
    }

    /** Count lines without allocating a list. */
    private fun simpleCountLines(content: String): Int = content.count { it == '\n' } + 1
}
