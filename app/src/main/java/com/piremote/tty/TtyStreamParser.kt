package com.piremote.tty

/**
 * Single-pass parser turning an ANSI+OSC stream into typed [TtyBlock]s.
 *
 * Supported:
 *  - SGR styling (CSI ... m) via [parseSgrCodes]; all other CSI finals are
 *    silently skipped (cursor/erase are non-goals in an append-only scrollback)
 *  - OSC 8 hyperlinks  (ESC ] 8 ; params ; URI  BEL|ST)
 *  - OSC 1337 inline images  (ESC ] 1337 ; File=k=v;... : base64  BEL|ST)
 *  - kitty graphics APC  (ESC _ G controls ; payload  ESC \), PNG (f=100),
 *    chunked m=1.../m=0 transmission keyed by image id
 *
 * Never throws: malformed, unterminated, or oversized sequences are dropped
 * whole and the surrounding text is preserved.
 */
object TtyStreamParser {
    /** Cap on a single image payload, in base64 chars (~6 MB decoded). */
    const val MAX_IMAGE_PAYLOAD = 8 * 1024 * 1024

    /** Cap on non-image OSC payloads. */
    const val MAX_MISC_OSC = 4 * 1024

    private val BEL: Char = 7.toChar()
    private const val TAB_STOP = 8

    fun parse(stream: String): List<TtyBlock> = Builder().parse(stream)

    private class KittyAccum(
        val format: Int,
        val cols: Int?,
        val rows: Int?,
        val data: StringBuilder = StringBuilder(),
    )

    private class Builder {
        private val blocks = mutableListOf<TtyBlock>()
        private val lines = mutableListOf<List<Pair<String, AnsiStyle>>>()
        private var segments = mutableListOf<Pair<String, AnsiStyle>>()
        private val textBuf = StringBuilder()
        private var style = AnsiStyle()
        private var col = 0
        private var swallowNewline = false
        private val kitty = HashMap<Int, KittyAccum>()

        fun parse(stream: String): List<TtyBlock> {
            val n = stream.length
            var i = 0
            while (i < n) {
                val ch = stream[i]
                if (ch != ESC) {
                    i++
                    when {
                        // An image sequence already breaks the flow; the newline
                        // conventionally following it must not add a blank line.
                        ch == '\n' && swallowNewline -> swallowNewline = false
                        ch == '\n' -> flushLine()
                        ch == '\r' -> {}
                        ch == '\t' -> {
                            swallowNewline = false
                            val spaces = TAB_STOP - (col % TAB_STOP)
                            repeat(spaces) { textBuf.append(' ') }
                            col += spaces
                        }
                        ch.code < 0x20 -> {} // drop other C0 controls
                        else -> { swallowNewline = false; textBuf.append(ch); col++ }
                    }
                    continue
                }
                if (i + 1 >= n) break // bare ESC at end of input
                i = when (stream[i + 1]) {
                    '[' -> consumeCsi(stream, i)
                    ']' -> consumeOsc(stream, i)
                    '_' -> consumeApc(stream, i)
                    else -> i + 2 // unknown two-char escape: drop it
                }
            }
            flushTextBlock()
            return blocks
        }

        // ── flush helpers ──────────────────────────────────────────────

        private fun flushSegment() {
            if (textBuf.isNotEmpty()) {
                segments.add(textBuf.toString() to style)
                textBuf.clear()
            }
        }

        private fun flushLine() {
            flushSegment()
            lines.add(segments)
            segments = mutableListOf()
            col = 0
        }

        /** Flush any pending text (including a partial line) into a Text block. */
        private fun flushTextBlock() {
            flushSegment()
            if (segments.isNotEmpty()) {
                lines.add(segments)
                segments = mutableListOf()
            }
            if (lines.isNotEmpty()) {
                blocks.add(TtyBlock.Text(lines.toList()))
                lines.clear()
            }
            col = 0
        }

        private fun emitImage(image: TtyBlock.Image) {
            flushTextBlock()
            blocks.add(image)
            swallowNewline = true
        }

        // ── CSI ────────────────────────────────────────────────────────

        /** ESC [ params/intermediates final. Only final 'm' (SGR) is interpreted. */
        private fun consumeCsi(s: String, start: Int): Int {
            var j = start + 2
            while (j < s.length && s[j].code in 0x20..0x3F) j++
            if (j >= s.length) return s.length // unterminated: drop to end
            if (s[j].code !in 0x40..0x7E) return j // malformed: resume scan here
            if (s[j] == 'm') {
                flushSegment()
                style = parseSgrCodes(s.substring(start + 2, j).split(";"), style)
            }
            return j + 1
        }

        // ── OSC ────────────────────────────────────────────────────────

        /**
         * Find a BEL or ST terminator starting at [from], scanning at most [cap]
         * chars. Returns (contentEnd, nextIndex) or null.
         */
        private fun findStringTerminator(s: String, from: Int, cap: Int): Pair<Int, Int>? {
            var j = from
            val limit = minOf(s.length, from + cap)
            while (j < limit) {
                when {
                    s[j] == BEL -> return j to j + 1
                    s[j] == ESC && j + 1 < s.length && s[j + 1] == '\\' -> return j to j + 2
                }
                j++
            }
            return null
        }

        private fun consumeOsc(s: String, start: Int): Int {
            val from = start + 2
            val found = findStringTerminator(s, from, MAX_IMAGE_PAYLOAD + 1024)
                ?: return minOf(s.length, from + MAX_IMAGE_PAYLOAD + 1024) // drop; resume past cap
            val (contentEnd, next) = found
            val content = s.substring(from, contentEnd)

            when {
                content.startsWith("8;") -> {
                    // OSC 8 ; params ; URI — link state rides on AnsiStyle.
                    if (content.length <= MAX_MISC_OSC) {
                        val uri = content.substringAfter(';').substringAfter(';', "")
                        flushSegment()
                        style = style.copy(link = uri.ifBlank { null })
                    }
                }
                content.startsWith("1337;File=") -> {
                    parse1337Image(content.removePrefix("1337;File="))?.let { emitImage(it) }
                }
                // other OSC codes (title, clipboard, ...) are consumed and dropped
            }
            return next
        }

        private fun parse1337Image(body: String): TtyBlock.Image? {
            val colon = body.indexOf(':')
            if (colon < 0) return null
            val payload = body.substring(colon + 1).filterNot { it == '\n' || it == '\r' || it == ' ' }
            if (payload.isEmpty() || payload.length > MAX_IMAGE_PAYLOAD) return null
            if (!isBase64(payload)) return null

            val args = body.substring(0, colon).split(';').mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq < 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
            }.toMap()
            if (args["inline"] != "1") return null

            return TtyBlock.Image(
                base64 = payload,
                mimeType = args["mime"] ?: sniffMime(payload),
                widthSpec = CellSpec.parse(args["width"]),
                heightSpec = CellSpec.parse(args["height"]),
                preserveAspect = args["preserveAspectRatio"] != "0",
            )
        }

        // ── APC (kitty graphics) ───────────────────────────────────────

        private fun consumeApc(s: String, start: Int): Int {
            val from = start + 2
            val found = findStringTerminator(s, from, MAX_IMAGE_PAYLOAD + 1024)
                ?: return minOf(s.length, from + MAX_IMAGE_PAYLOAD + 1024)
            val (contentEnd, next) = found
            val content = s.substring(from, contentEnd)
            if (!content.startsWith("G")) return next // not kitty graphics: drop

            val semi = content.indexOf(';')
            val controlStr = if (semi < 0) content.substring(1) else content.substring(1, semi)
            val payload = if (semi < 0) "" else content.substring(semi + 1)
            val controls = controlStr.split(',').mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq < 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
            }.toMap()

            val id = controls["i"]?.toIntOrNull() ?: 0
            val action = controls["a"] ?: "T"
            val more = controls["m"] == "1"

            // Only transmit+display of PNG data is supported; anything else
            // (placements, deletion, animation, compression, raw formats) drops.
            if (controls["o"] == "z") { kitty.remove(id); return next }
            val isContinuation = kitty.containsKey(id) && controls["a"] == null
            if (!isContinuation) {
                if (action != "T" && action != "t") return next
                val format = controls["f"]?.toIntOrNull() ?: 100
                if (format != 100) return next
                kitty[id] = KittyAccum(
                    format = format,
                    cols = controls["c"]?.toIntOrNull(),
                    rows = controls["r"]?.toIntOrNull(),
                )
            }

            val accum = kitty[id] ?: return next
            accum.data.append(payload.filterNot { it == '\n' || it == '\r' || it == ' ' })
            if (accum.data.length > MAX_IMAGE_PAYLOAD) { kitty.remove(id); return next }

            if (!more) {
                kitty.remove(id)
                val base64 = accum.data.toString()
                if (base64.isNotEmpty() && isBase64(base64)) {
                    emitImage(TtyBlock.Image(
                        base64 = base64,
                        mimeType = "image/png",
                        widthSpec = accum.cols?.let { CellSpec.Cells(it) } ?: CellSpec.Auto,
                        heightSpec = accum.rows?.let { CellSpec.Cells(it) } ?: CellSpec.Auto,
                    ))
                }
            }
            return next
        }

        // ── misc ───────────────────────────────────────────────────────

        private fun isBase64(s: String): Boolean = s.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
        }

        private fun sniffMime(base64: String): String = when {
            base64.startsWith("iVBOR") -> "image/png"
            base64.startsWith("/9j/") -> "image/jpeg"
            base64.startsWith("R0lGOD") -> "image/gif"
            base64.startsWith("UklGR") -> "image/webp"
            else -> "image/png"
        }
    }
}
