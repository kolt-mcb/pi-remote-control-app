package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SGR / CSI robustness: a malicious host — or any raw tool output — must never
 * produce a style the renderer can't draw, and non-SGR CSI must not eat text.
 * (PROTOCOL.md: "the parser never throws", surrounding text preserved.)
 */
class SgrRobustnessTest {
    private val esc = ESC.toString()
    private fun text(segs: List<Pair<String, AnsiStyle>>) = segs.joinToString("") { it.first }
    private fun styleOf(line: String) = parseAnsiLine(line).last().second

    // ── Truecolor clamping (the crash) ──────────────────────────────────

    @Test fun truecolor_components_clamped_to_0_255() {
        val s = styleOf("${esc}[38;2;300;0;0mX")
        assertTrue(s.fgR in 0..255 && s.fgG in 0..255 && s.fgB in 0..255)
        assertEquals(255, s.fgR)
    }

    @Test fun truecolor_nonnumeric_component_resets_whole_channel() {
        // Middle component is garbage: must not leave a mixed (10,-1,20) sentinel
        // that passes the renderer's fgR>=0 guard and then throws in Color().
        val s = styleOf("${esc}[38;2;10;x;20mX")
        assertEquals(-1, s.fgR)
        assertEquals(-1, s.fgG)
        assertEquals(-1, s.fgB)
    }

    @Test fun truecolor_background_clamped() {
        val s = styleOf("${esc}[48;2;0;999;0mX")
        assertTrue(s.bgG in 0..255)
    }

    @Test fun every_truecolor_component_stays_in_compose_color_range() {
        // Exhaustive-ish sweep of hostile inputs — none may produce out-of-[0,255]
        // values, which is what Color(r/255f, ...) requires.
        for (v in listOf("-1", "256", "9999", "-99999", "abc", "")) {
            val s = styleOf("${esc}[38;2;$v;$v;${v}mX")
            assertTrue("v=$v -> ${s.fgR}", s.fgR == -1 || s.fgR in 0..255)
        }
    }

    // ── Non-SGR CSI must be dropped, not eat following text ──────────────

    @Test fun erase_line_csi_does_not_eat_text() {
        // ESC[2K is erase-line. Old code scanned for a literal 'm' and swallowed
        // "ake build" (everything up to the 'm' in "make").
        assertEquals("\$ make build", text(parseAnsiLine("${esc}[2K\$ make build")))
    }

    @Test fun cursor_hide_csi_dropped() {
        assertEquals("hello", text(parseAnsiLine("${esc}[?25lhello")))
    }

    @Test fun non_sgr_csi_with_no_following_m_is_dropped_cleanly() {
        assertEquals("done", text(parseAnsiLine("${esc}[Kdone")))
    }

    @Test fun sgr_still_applies_after_a_non_sgr_csi() {
        val segs = parseAnsiLine("${esc}[2K${esc}[1mbold")
        assertEquals("bold", text(segs))
        assertTrue(segs.last().second.bold)
    }

    @Test fun unterminated_csi_drops_introducer() {
        // ESC[ with no final byte: drop it, don't render "[".
        assertEquals("before", text(parseAnsiLine("before${esc}[")))
    }
}
