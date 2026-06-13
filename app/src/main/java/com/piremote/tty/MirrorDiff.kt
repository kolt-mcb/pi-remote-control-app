package com.piremote.tty

/** Upper bound on a mirror buffer's line count, so a hostile/garbled `lineCount`
 *  can't make us pad billions of empty lines (OOM). A real terminal scrollback
 *  is nowhere near this. */
const val MIRROR_MAX_LINES = 100_000

/**
 * Apply a row-level mirror diff to [buf] in place: resize the buffer to
 * [lineCount] (truncating removed trailing rows, padding new ones with ""),
 * then overwrite each changed row from [rows] (index → new text). This mirrors
 * the host's diffRows/keyframe protocol: rows carries exactly the indices whose
 * text changed (including new trailing rows); the removed tail is conveyed by a
 * smaller lineCount. Out-of-range indices are ignored.
 */
fun applyMirrorDiff(buf: MutableList<String>, lineCount: Int, rows: List<Pair<Int, String>>) {
    val n = lineCount.coerceIn(0, MIRROR_MAX_LINES)
    while (buf.size > n) buf.removeAt(buf.size - 1)
    while (buf.size < n) buf.add("")
    for ((i, t) in rows) if (i in 0 until buf.size) buf[i] = t
}
