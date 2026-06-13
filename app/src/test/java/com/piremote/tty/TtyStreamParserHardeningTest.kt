package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hardening of the stream parser against hostile / truncated sequences. */
class TtyStreamParserHardeningTest {
    private val esc = ESC.toString()
    private val st = TtyEscapes.ST
    private val png64 = "iVBORw0KGgoAAAANSUhEUg=="

    private fun textOf(blocks: List<TtyBlock>): TtyBlock.Text =
        blocks.filterIsInstance<TtyBlock.Text>().single()
    private fun plain(b: TtyBlock.Text) = b.lines.joinToString("\n") { l -> l.joinToString("") { it.first } }

    @Test fun truncated_misc_osc_does_not_swallow_following_text() {
        // An unterminated OSC 0 (title) must drop only ~4 KiB, not the whole stream.
        val tail = "VISIBLE_AFTER"
        // No terminator on the title, then 5 KiB of filler, then real text.
        val stream = "${esc}]0;" + "x".repeat(5000) + tail
        val out = plain(textOf(TtyStreamParser.parse(stream)))
        assertTrue("expected tail to survive misc-OSC cap", out.contains(tail))
    }

    @Test fun image_osc_still_gets_large_window() {
        val stream = "${esc}]1337;File=inline=1:${png64}${st}done"
        val blocks = TtyStreamParser.parse(stream)
        assertTrue(blocks.any { it is TtyBlock.Image })
    }

    @Test fun dcs_payload_dropped_not_rendered() {
        val stream = "before${esc}Pq#0;2;0;0;0sixeldata${st}after"
        val out = plain(textOf(TtyStreamParser.parse(stream)))
        assertFalse(out.contains("sixeldata"))
        assertTrue(out.contains("before"))
        assertTrue(out.contains("after"))
    }

    @Test fun osc8_non_web_scheme_is_not_clickable() {
        val stream = "${esc}]8;;javascript:alert(1)${st}click${esc}]8;;${st}"
        val segs = textOf(TtyStreamParser.parse(stream)).lines[0]
        assertEquals("click", segs[0].first)
        assertNull("javascript: must not become a tappable link", segs[0].second.link)
    }

    @Test fun osc8_http_scheme_still_clickable() {
        val stream = "${esc}]8;;http://x.io${st}go${esc}]8;;${st}"
        val segs = textOf(TtyStreamParser.parse(stream)).lines[0]
        assertEquals("http://x.io", segs[0].second.link)
    }

    @Test fun dropped_kitty_train_does_not_resurrect_as_image() {
        // Opening chunk uses unsupported compression o=z (dropped); the following
        // a-less continuation chunks must NOT start a phantom transmission.
        val open = "${esc}_Gi=1,a=T,o=z,m=1;$png64${st}"
        val mid = "${esc}_Gi=1,m=1;$png64${st}"
        val end = "${esc}_Gi=1,m=0;$png64${st}"
        val blocks = TtyStreamParser.parse(open + mid + end)
        assertFalse("a dropped kitty train must not emit an image",
            blocks.any { it is TtyBlock.Image })
    }
}
