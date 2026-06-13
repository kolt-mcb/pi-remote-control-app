package com.piremote.tty

/**
 * Typed render blocks produced by [TtyStreamParser] from an ANSI+OSC stream.
 * The Compose layer walks these; it never sees raw escape sequences.
 */
sealed interface TtyBlock {
    /** Pre-split styled lines. Each line is a list of (text, style) segments. */
    data class Text(val lines: List<List<Pair<String, AnsiStyle>>>) : TtyBlock

    /**
     * Inline image. The base64 payload is NOT decoded here — the render layer
     * decodes via its bitmap cache, so off-screen images cost nothing.
     */
    data class Image(
        val base64: String,
        val mimeType: String = "image/png",
        val widthSpec: CellSpec = CellSpec.Auto,
        val heightSpec: CellSpec = CellSpec.Auto,
        val preserveAspect: Boolean = true,
    ) : TtyBlock
}

/** Image dimension spec, as in OSC 1337: cells, pixels, percent of viewport, or auto. */
sealed interface CellSpec {
    data object Auto : CellSpec
    data class Cells(val n: Int) : CellSpec
    data class Pixels(val n: Int) : CellSpec
    data class Percent(val n: Int) : CellSpec

    companion object {
        /** Parse an OSC 1337 dimension value: "N" (cells), "Npx", "N%", "auto". */
        fun parse(value: String?): CellSpec {
            if (value.isNullOrBlank() || value == "auto") return Auto
            // Clamp to sane ranges, returning Auto on out-of-range: an unbounded
            // height= (tens of millions of px) overflows Compose Constraints and
            // throws during layout — a remote crash from one stream byte.
            return when {
                value.endsWith("px") -> value.dropLast(2).toIntOrNull()?.takeIf { it in 1..100_000 }?.let { Pixels(it) } ?: Auto
                value.endsWith("%") -> value.dropLast(1).toIntOrNull()?.takeIf { it in 1..100 }?.let { Percent(it) } ?: Auto
                else -> value.toIntOrNull()?.takeIf { it in 1..10_000 }?.let { Cells(it) } ?: Auto
            }
        }
    }
}
