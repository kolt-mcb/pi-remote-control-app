package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** OSC sequences (esp. OSC 8 hyperlinks) must not render as literal "]8;;". */
class ParseAnsiLineOscTest {
    private val esc = ESC.toString()
    private val bel = BEL.toString()
    private val st = esc + "\\"

    private fun text(segs: List<Pair<String, AnsiStyle>>) = segs.joinToString("") { it.first }

    @Test fun osc8_hyperlink_st_terminated_keeps_only_link_text() {
        // ESC]8;;URL ST  text  ESC]8;; ST
        val line = "${esc}]8;;https://pi.dev${st}pi.dev${esc}]8;;${st}"
        val out = text(parseAnsiLine(line))
        assertEquals("pi.dev", out)
        assertFalse(out.contains("]8;;"))
    }

    @Test fun osc8_bel_terminated_keeps_only_link_text() {
        val line = "${esc}]8;;file:///x${bel}open${esc}]8;;${bel}"
        val out = text(parseAnsiLine(line))
        assertEquals("open", out)
    }

    @Test fun osc_mixed_with_sgr_color_survives() {
        val line = "${esc}[1mBold ${esc}]8;;u${st}link${esc}]8;;${st} done${esc}[0m"
        val out = text(parseAnsiLine(line))
        assertEquals("Bold link done", out)
    }

    @Test fun unterminated_osc_does_not_leak_litter() {
        val line = "before${esc}]8;;no-terminator-here"
        val out = text(parseAnsiLine(line))
        assertEquals("before", out)
        assertFalse(out.contains("]8;;"))
    }

    @Test fun plain_text_unchanged() {
        assertEquals("hello world", text(parseAnsiLine("hello world")))
    }
}
