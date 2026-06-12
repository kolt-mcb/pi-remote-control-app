package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtyStreamParserTest {

    private val esc = ESC.toString()
    private val bel = 7.toChar().toString()
    private val st = TtyEscapes.ST

    // A tiny valid-looking base64 payload (content doesn't matter to the parser).
    private val png64 = "iVBORw0KGgoAAAANSUhEUg=="

    private fun textOf(blocks: List<TtyBlock>): TtyBlock.Text =
        blocks.filterIsInstance<TtyBlock.Text>().single()

    private fun plain(block: TtyBlock.Text): List<String> =
        block.lines.map { line -> line.joinToString("") { it.first } }

    // ── SGR ────────────────────────────────────────────────────────────

    @Test
    fun plainTextSingleLine() {
        val blocks = TtyStreamParser.parse("hello world")
        val text = textOf(blocks)
        assertEquals(listOf("hello world"), plain(text))
        assertEquals(AnsiStyle(), text.lines[0][0].second)
    }

    @Test
    fun truecolorForeground() {
        val blocks = TtyStreamParser.parse("${esc}[38;2;10;20;30mcolored${esc}[0mplain")
        val segs = textOf(blocks).lines[0]
        assertEquals("colored", segs[0].first)
        assertEquals(10, segs[0].second.fgR)
        assertEquals(20, segs[0].second.fgG)
        assertEquals(30, segs[0].second.fgB)
        assertEquals("plain", segs[1].first)
        assertEquals(-1, segs[1].second.fgR)
    }

    @Test
    fun basic16AndBoldUnderline() {
        val blocks = TtyStreamParser.parse("${esc}[1;4;31mx")
        val style = textOf(blocks).lines[0][0].second
        assertTrue(style.bold)
        assertTrue(style.underline)
        assertEquals(197, style.fgR)
    }

    @Test
    fun xterm256Color() {
        val blocks = TtyStreamParser.parse("${esc}[38;5;196mred")
        val style = textOf(blocks).lines[0][0].second
        // 196 = cube(5,0,0) → r = 55 + 5*40 = 255
        assertEquals(255, style.fgR)
        assertEquals(0, style.fgG)
    }

    @Test
    fun multiLineSplit() {
        val blocks = TtyStreamParser.parse("a\nb\nc")
        assertEquals(listOf("a", "b", "c"), plain(textOf(blocks)))
    }

    @Test
    fun blankLinePreserved() {
        val blocks = TtyStreamParser.parse("a\n\nb")
        assertEquals(listOf("a", "", "b"), plain(textOf(blocks)))
    }

    @Test
    fun tabExpandsToNextStop() {
        val blocks = TtyStreamParser.parse("ab\tc")
        assertEquals(listOf("ab      c"), plain(textOf(blocks)))
    }

    // ── OSC 8 hyperlinks ───────────────────────────────────────────────

    @Test
    fun osc8LinkWithStTerminator() {
        val stream = "${esc}]8;;https://example.com${st}label${esc}]8;;${st}after"
        val segs = textOf(TtyStreamParser.parse(stream)).lines[0]
        assertEquals("label", segs[0].first)
        assertEquals("https://example.com", segs[0].second.link)
        assertEquals("after", segs[1].first)
        assertNull(segs[1].second.link)
    }

    @Test
    fun osc8LinkWithBelTerminator() {
        val stream = "${esc}]8;;https://x.io${bel}go${esc}]8;;${bel}"
        val segs = textOf(TtyStreamParser.parse(stream)).lines[0]
        assertEquals("https://x.io", segs[0].second.link)
    }

    @Test
    fun linkSurvivesSgrReset() {
        val stream = "${esc}]8;;https://x.io${st}${esc}[1mbold${esc}[0mplain${esc}]8;;${st}"
        val segs = textOf(TtyStreamParser.parse(stream)).lines[0]
        assertEquals("https://x.io", segs[0].second.link)
        assertTrue(segs[0].second.bold)
        assertEquals("https://x.io", segs[1].second.link)
        assertFalse(segs[1].second.bold)
    }

    // ── OSC 1337 images ────────────────────────────────────────────────

    @Test
    fun osc1337InlineImage() {
        val stream = "before\n${TtyEscapes.osc1337Image(png64, "image/png", CellSpec.Cells(40))}after"
        val blocks = TtyStreamParser.parse(stream)
        assertEquals(3, blocks.size)
        assertEquals(listOf("before"), plain(blocks[0] as TtyBlock.Text))
        val img = blocks[1] as TtyBlock.Image
        assertEquals(png64, img.base64)
        assertEquals("image/png", img.mimeType)
        assertEquals(CellSpec.Cells(40), img.widthSpec)
        assertEquals(listOf("after"), plain(blocks[2] as TtyBlock.Text))
    }

    @Test
    fun osc1337WidthSpecs() {
        fun parseWidth(w: String): CellSpec {
            val stream = "${esc}]1337;File=inline=1;width=$w:$png64$st"
            return (TtyStreamParser.parse(stream).single() as TtyBlock.Image).widthSpec
        }
        assertEquals(CellSpec.Cells(12), parseWidth("12"))
        assertEquals(CellSpec.Pixels(200), parseWidth("200px"))
        assertEquals(CellSpec.Percent(50), parseWidth("50%"))
        assertEquals(CellSpec.Auto, parseWidth("auto"))
    }

    @Test
    fun osc1337WithoutInlineIsDropped() {
        val stream = "${esc}]1337;File=size=24:$png64${st}text"
        val blocks = TtyStreamParser.parse(stream)
        assertTrue(blocks.single() is TtyBlock.Text)
    }

    @Test
    fun osc1337MimeSniffing() {
        val jpeg = "/9j/4AAQSkZJRg=="
        val stream = "${esc}]1337;File=inline=1:$jpeg$st"
        val img = TtyStreamParser.parse(stream).single() as TtyBlock.Image
        assertEquals("image/jpeg", img.mimeType)
    }

    @Test
    fun osc1337RoundTripThroughBuilder() {
        val seq = TtyEscapes.osc1337Image(png64, "image/webp", CellSpec.Percent(80), CellSpec.Auto, false)
        val img = TtyStreamParser.parse(seq).single() as TtyBlock.Image
        assertEquals(png64, img.base64)
        assertEquals("image/webp", img.mimeType)
        assertEquals(CellSpec.Percent(80), img.widthSpec)
        assertFalse(img.preserveAspect)
    }

    // ── kitty graphics ─────────────────────────────────────────────────

    @Test
    fun kittySingleChunk() {
        val stream = TtyEscapes.kittyChunks(png64, id = 7, cols = 30).joinToString("")
        val img = TtyStreamParser.parse(stream).single() as TtyBlock.Image
        assertEquals(png64, img.base64)
        assertEquals(CellSpec.Cells(30), img.widthSpec)
    }

    @Test
    fun kittyThreeChunkReassembly() {
        val chunks = TtyEscapes.kittyChunks(png64, id = 3, cols = null, chunkSize = 8)
        assertTrue(chunks.size >= 3)
        val img = TtyStreamParser.parse(chunks.joinToString("")).single() as TtyBlock.Image
        assertEquals(png64, img.base64)
    }

    @Test
    fun kittyInterleavedWithText() {
        val stream = "pre" + TtyEscapes.kittyChunks(png64, id = 1).joinToString("") + "post"
        val blocks = TtyStreamParser.parse(stream)
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is TtyBlock.Image)
    }

    @Test
    fun kittyCompressedIsDropped() {
        val stream = "${esc}_Ga=T,f=100,o=z;$png64${st}text"
        val blocks = TtyStreamParser.parse(stream)
        assertTrue(blocks.single() is TtyBlock.Text)
    }

    @Test
    fun kittyUnterminatedChunkTrainDropped() {
        val stream = "${esc}_Ga=T,f=100,i=9,m=1;$png64${st}tail"
        val blocks = TtyStreamParser.parse(stream)
        // No m=0 final chunk → no image emitted; surrounding text preserved.
        assertEquals(listOf("tail"), plain(textOf(blocks)))
    }

    // ── malformed input recovery ───────────────────────────────────────

    @Test
    fun unterminatedOscDropsToEnd() {
        val blocks = TtyStreamParser.parse("ok${esc}]1337;File=inline=1:AAAA")
        assertEquals(listOf("ok"), plain(textOf(blocks)))
    }

    @Test
    fun bareEscAtEndOfInput() {
        val blocks = TtyStreamParser.parse("text$esc")
        assertEquals(listOf("text"), plain(textOf(blocks)))
    }

    @Test
    fun unknownCsiFinalSkipped() {
        // Cursor-up CSI A must vanish without rendering garbage.
        val blocks = TtyStreamParser.parse("a${esc}[2Ab")
        assertEquals(listOf("ab"), plain(textOf(blocks)))
    }

    @Test
    fun invalidBase64ImageDropped() {
        val stream = "${esc}]1337;File=inline=1:not!!base64${st}x"
        val blocks = TtyStreamParser.parse(stream)
        assertEquals(listOf("x"), plain(textOf(blocks)))
    }

    @Test
    fun otherOscCodesConsumedSilently() {
        val blocks = TtyStreamParser.parse("a${esc}]0;window title${bel}b")
        assertEquals(listOf("ab"), plain(textOf(blocks)))
    }

    @Test
    fun crIsIgnored() {
        val blocks = TtyStreamParser.parse("a\r\nb")
        assertEquals(listOf("a", "b"), plain(textOf(blocks)))
    }

    @Test
    fun emptyStreamYieldsNoBlocks() {
        assertTrue(TtyStreamParser.parse("").isEmpty())
    }
}
