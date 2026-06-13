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

    @Test fun kitty_reassembles_pitui_chunks_without_id_on_continuations() {
        // pi-tui's encodeKitty puts i=<id> only on the FIRST chunk; continuation
        // chunks carry only m=/data. They must reassemble onto the open transmission.
        val s = "${esc}_Ga=T,f=100,i=5,m=1;iVBOR$st" +
                "${esc}_Gm=1;w0KGg$st" +
                "${esc}_Gm=0;oAAAANSUhEUg==$st"
        val img = TtyStreamParser.parse(s).filterIsInstance<TtyBlock.Image>().single()
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", img.base64)
    }

    @Test fun parseMirrorLine_extracts_kitty_image() {
        // A mirror line carrying a (chunked) kitty sequence → an image render item.
        val line = "${esc}_Ga=T,f=100,i=9,m=1;iVBOR$st${esc}_Gm=0;w0KGgoAAAANSUhEUg==$st"
        val item = parseMirrorLine(line)
        assertTrue(item is MirrorItem.Img)
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", (item as MirrorItem.Img).image.base64)
    }

    @Test fun parseMirrorLine_plain_text_is_a_line() {
        val item = parseMirrorLine("${esc}[31mjust text${esc}[0m")
        assertTrue(item is MirrorItem.Line)
    }

    /** Faithful reproduction of pi-tui's encodeKitty output for a >4 KiB image
     *  (full param set incl. q=2/C=1/c/r, chunked at 4096, i only on the first
     *  chunk, all chunks back-to-back on ONE line) — the exact shape the host
     *  mirror now emits. Must decode to a single image with the full base64. */
    @Test fun parseMirrorLine_decodes_full_pitui_kitty_image() {
        val b64 = "ABCD".repeat(2500) // 10000 valid base64 chars → 3 chunks (4096/4096/1808)
        val sb = StringBuilder()
        var off = 0
        var first = true
        while (off < b64.length) {
            val chunk = b64.substring(off, minOf(off + 4096, b64.length))
            val last = off + 4096 >= b64.length
            sb.append(esc).append("_G")
            when {
                first -> { sb.append("a=T,f=100,q=2,C=1,c=20,r=10,i=42,m=1"); first = false }
                last -> sb.append("m=0")
                else -> sb.append("m=1")
            }
            sb.append(';').append(chunk).append(st)
            off += 4096
        }
        val item = parseMirrorLine(sb.toString())
        assertTrue("expected an image item, got $item", item is MirrorItem.Img)
        assertEquals(b64, (item as MirrorItem.Img).image.base64)
    }
}
