package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Test

/** Row-level mirror diff application must reconstruct exactly what the host had,
 *  for every shape of change (same size, grow, shrink, no-op) — a desync here
 *  silently corrupts the mirrored screen. */
class MirrorDiffTest {

    private fun buf(vararg s: String) = s.toMutableList()
    private fun rows(vararg p: Pair<Int, String>) = p.toList()

    @Test fun overwrites_changed_rows_same_size() {
        val b = buf("a", "b", "c")
        applyMirrorDiff(b, 3, rows(1 to "B"))
        assertEquals(listOf("a", "B", "c"), b)
    }

    @Test fun grows_buffer_with_new_trailing_rows() {
        val b = buf("a", "b")
        applyMirrorDiff(b, 4, rows(2 to "c", 3 to "d"))
        assertEquals(listOf("a", "b", "c", "d"), b)
    }

    @Test fun shrinks_buffer_by_line_count() {
        val b = buf("a", "b", "c", "d")
        applyMirrorDiff(b, 2, rows())
        assertEquals(listOf("a", "b"), b)
    }

    @Test fun shrink_then_overwrite_remaining() {
        val b = buf("a", "b", "c", "d")
        applyMirrorDiff(b, 2, rows(0 to "A"))
        assertEquals(listOf("A", "b"), b)
    }

    @Test fun empty_diff_is_a_noop() {
        val b = buf("x", "y", "z")
        applyMirrorDiff(b, 3, rows())
        assertEquals(listOf("x", "y", "z"), b)
    }

    @Test fun out_of_range_index_ignored() {
        val b = buf("a", "b")
        applyMirrorDiff(b, 2, rows(5 to "nope", 0 to "A"))
        assertEquals(listOf("A", "b"), b)
    }

    @Test fun reconstructs_against_host_diff_semantics() {
        // Simulate the host: old vs new, rows = changed indices, lineCount = new size.
        val old = listOf("line0", "line1", "line2", "line3")
        val new = listOf("line0", "CHANGED", "line2")     // line1 edited, line3 removed
        val hostRows = new.indices.filter { old.getOrNull(it) != new[it] }.map { it to new[it] }
        val b = old.toMutableList()
        applyMirrorDiff(b, new.size, hostRows)
        assertEquals(new, b)
    }

    @Test fun absurd_line_count_is_capped_not_ooming() {
        val b = buf("a")
        applyMirrorDiff(b, Int.MAX_VALUE, rows())
        assertEquals(MIRROR_MAX_LINES, b.size)
    }
}
