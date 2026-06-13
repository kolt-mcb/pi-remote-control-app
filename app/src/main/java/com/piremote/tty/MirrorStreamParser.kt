package com.piremote.tty

/** One rendered item of a mirror frame: either a styled text line or an inline image. */
sealed interface MirrorItem {
    data class Line(val segments: List<Pair<String, AnsiStyle>>) : MirrorItem
    data class Img(val image: TtyBlock.Image) : MirrorItem
}

private const val KITTY_INTRO = "_G"     // ESC _ G  (kitty graphics APC)
private const val OSC1337_INTRO = "]1337" // ESC ] 1337  (iTerm2 / OSC 1337)

/**
 * Parse a single mirror buffer line into a render item. pi-tui emits an inline
 * image as one line carrying the whole (possibly multi-chunk) kitty/OSC-1337
 * sequence, with the reserved height as following blank lines — so per-line
 * parsing is enough, and it preserves the mirror's per-line memoization.
 *
 * A line that carries an image sequence is parsed through [TtyStreamParser]
 * (which handles kitty chunk reassembly and OSC 1337) and rendered as an image;
 * everything else is styled text via [parseAnsiLine].
 */
fun parseMirrorLine(raw: String): MirrorItem {
    if (raw.contains(KITTY_INTRO) || raw.contains(OSC1337_INTRO)) {
        val img = TtyStreamParser.parse(raw).firstNotNullOfOrNull { it as? TtyBlock.Image }
        if (img != null) return MirrorItem.Img(img)
    }
    return MirrorItem.Line(parseAnsiLine(raw))
}
