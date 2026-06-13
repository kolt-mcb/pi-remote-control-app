package com.piremote.tty

/** One rendered item of a mirror frame: either a styled text line or an inline image. */
sealed interface MirrorItem {
    data class Line(val segments: List<Pair<String, AnsiStyle>>) : MirrorItem
    data class Img(val image: TtyBlock.Image) : MirrorItem
}

private val KITTY_INTRO = "${ESC}_G"        // ESC _ G    (kitty graphics APC)
private val OSC1337_INTRO = "$ESC]1337"     // ESC ] 1337 (iTerm2 / OSC 1337)
// A real terminal row is at most a few hundred columns. A line far longer than
// this is escape/binary junk (e.g. raw base64 image data) — never feed it to the
// Compose text measurer, which OOMs on multi-hundred-KB strings.
private const val MAX_MIRROR_LINE = 4096

/**
 * Parse a single mirror buffer line into a render item. pi-tui emits an inline
 * image as one line carrying the whole (possibly multi-chunk) kitty/OSC-1337
 * sequence, with the reserved height as following blank lines — so per-line
 * parsing is enough, and it preserves the mirror's per-line memoization.
 *
 * A line carrying an image sequence is parsed through [TtyStreamParser] (which
 * handles kitty chunk reassembly and OSC 1337) and rendered as an image; the rest
 * is styled text. Critically, a line that LOOKS like an image but doesn't decode
 * (oversized payload, or a sequence split across rows) is shown as "[image]" — NOT
 * as its raw base64, which would OOM the text measurer.
 */
fun parseMirrorLine(raw: String): MirrorItem {
    if (raw.contains(KITTY_INTRO) || raw.contains(OSC1337_INTRO)) {
        val img = TtyStreamParser.parse(raw).firstNotNullOfOrNull { it as? TtyBlock.Image }
        return if (img != null) MirrorItem.Img(img)
               else MirrorItem.Line(listOf("[image]" to AnsiStyle()))
    }
    val safe = if (raw.length > MAX_MIRROR_LINE) raw.substring(0, MAX_MIRROR_LINE) else raw
    return MirrorItem.Line(parseAnsiLine(safe))
}
