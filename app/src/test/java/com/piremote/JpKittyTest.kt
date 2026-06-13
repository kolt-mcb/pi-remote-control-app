package com.piremote

import com.piremote.tty.MirrorItem
import com.piremote.tty.TtyBlock
import com.piremote.tty.parseMirrorLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror's kitty image line is dense with ESC () and backslash (the
 * `ESC \` chunk terminators) — exactly the escapes the hand-rolled JSON parser is
 * most likely to mishandle. This exercises the full app-side transport: the host's
 * JSON encoding of a mirror_frame carrying a chunked kitty image → JP.p → the line
 * → parseMirrorLine → a decoded image. If the line is corrupted in transit, the
 * decode fails and (pre-fix) the raw bytes would OOM the text measurer.
 */
class JpKittyTest {
    private val ESC = 27.toChar()

    /** Mimic JSON.stringify's escaping of a string (what the host does). */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    /** pi-tui encodeKitty: chunk at 4096, i only on the first chunk, joined. */
    private fun pituiKitty(b64: String): String {
        val sb = StringBuilder()
        var off = 0
        var first = true
        while (off < b64.length) {
            val chunk = b64.substring(off, minOf(off + 4096, b64.length))
            val last = off + 4096 >= b64.length
            sb.append(ESC).append("_G")
            when {
                first -> { sb.append("a=T,f=100,q=2,C=1,c=20,r=10,i=42,m=1"); first = false }
                last -> sb.append("m=0")
                else -> sb.append("m=1")
            }
            sb.append(';').append(chunk).append(ESC).append('\\')
            off += 4096
        }
        return sb.toString()
    }

    @Test fun kitty_line_survives_json_transport_and_decodes() {
        val b64 = "ABCD".repeat(2500) // 10000 chars → 3 chunks
        val kitty = pituiKitty(b64)
        val json = "{\"type\":\"mirror_frame\",\"seq\":1,\"width\":40,\"height\":24," +
            "\"lines\":[\"${jsonEscape(kitty)}\"]}"

        val parsed = JP.p(json)
        assertNotNull("JP.p returned null for the kitty frame", parsed)

        val lines = parsed!!["lines"] as? List<*>
        val line = lines?.firstOrNull() as? String
        assertEquals("kitty line corrupted by JSON round-trip", kitty, line)

        val item = parseMirrorLine(line!!)
        assertTrue("decoded item was not an image: $item", item is MirrorItem.Img)
        assertEquals(b64, (item as MirrorItem.Img).image.base64)
    }
}
