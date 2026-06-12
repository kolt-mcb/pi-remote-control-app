package com.piremote.tty

import com.piremote.ChatImage
import com.piremote.ChatMessage
import com.piremote.MessageToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNormalizerTest {

    private val png64 = "iVBORw0KGgoAAAANSUhEUg=="

    private fun render(msg: ChatMessage, cols: Int = 60, expanded: Boolean = false): List<TtyBlock> =
        TtyStreamParser.parse(MessageNormalizer.toStream(msg, cols, expanded))

    private fun plainText(blocks: List<TtyBlock>): String =
        blocks.filterIsInstance<TtyBlock.Text>()
            .flatMap { it.lines }
            .joinToString("\n") { line -> line.joinToString("") { it.first } }

    // ── stream passthrough (host-rendered, PROTOCOL.md single stream) ──

    @Test
    fun streamPassesThroughVerbatimWithoutGutter() {
        val esc = ESC.toString()
        val msg = ChatMessage(
            type = MessageToolType.Assistant, content = "ignored",
            stream = "${esc}[1mHeading${esc}[0m\nplain second line",
        )
        val text = render(msg).single() as TtyBlock.Text
        // No client decoration: first segment is the host's own content.
        val firstSeg = text.lines.first().first()
        assertEquals("Heading", firstSeg.first)
        assertTrue(firstSeg.second.bold)
        assertEquals("plain second line", text.lines[1].joinToString("") { it.first })
    }

    @Test
    fun streamTakesPrecedenceOverAnsiLines() {
        val msg = ChatMessage(
            type = MessageToolType.Custom,
            stream = "from stream",
            ansiLines = listOf("from ansiLines"),
        )
        val flat = plainText(render(msg))
        assertTrue(flat.contains("from stream"))
        assertTrue(!flat.contains("from ansiLines"))
    }

    @Test
    fun streamExpandedSelectedWhenExpanded() {
        val msg = ChatMessage(
            type = MessageToolType.ToolResult, toolName = "edit",
            stream = "collapsed view",
            streamExpanded = "expanded view",
        )
        assertTrue(plainText(render(msg, expanded = false)).contains("collapsed view"))
        assertTrue(plainText(render(msg, expanded = true)).contains("expanded view"))
    }

    @Test
    fun streamWithoutExpandedVariantUsedForBothStates() {
        val msg = ChatMessage(type = MessageToolType.ToolResult, toolName = "bash", stream = "only view")
        assertTrue(plainText(render(msg, expanded = true)).contains("only view"))
        assertTrue(plainText(render(msg, expanded = false)).contains("only view"))
    }

    @Test
    fun overflowImagesAppendedAfterStream() {
        val msg = ChatMessage(
            type = MessageToolType.ToolResult, toolName = "read",
            stream = "tool header",
            images = listOf(ChatImage(data = png64, mimeType = "image/png")),
        )
        val blocks = render(msg)
        assertTrue(blocks.first() is TtyBlock.Text)
        assertTrue(blocks.any { it is TtyBlock.Image })
    }

    @Test
    fun imagesAppendedAfterAnsiLinesToo() {
        val msg = ChatMessage(
            type = MessageToolType.ToolResult, toolName = "read",
            ansiLines = listOf("result line"),
            images = listOf(ChatImage(data = png64, mimeType = "image/png")),
        )
        val blocks = render(msg)
        assertTrue(plainText(blocks).contains("result line"))
        assertTrue(blocks.any { it is TtyBlock.Image })
    }

    @Test
    fun osc133ZoneMarkersInStreamAreDropped() {
        // pi's components wrap output in OSC 133 prompt-zone markers; the
        // parser must consume them without leaking artifacts into the text.
        val esc = ESC.toString()
        val bel = 7.toChar()
        val msg = ChatMessage(
            type = MessageToolType.Assistant,
            stream = "${esc}]133;A${bel}first${esc}]133;B${bel}${esc}]133;C${bel} last",
        )
        val flat = plainText(render(msg))
        assertEquals("first last", flat)
    }

    @Test
    fun streamWithEmbeddedOsc1337ImageRenders() {
        val esc = ESC.toString()
        val osc = "${esc}]1337;File=inline=1;size=16;mime=image/png:${png64}${esc}\\"
        val msg = ChatMessage(
            type = MessageToolType.User,
            stream = "see image:\n$osc",
        )
        val blocks = render(msg)
        assertTrue(plainText(blocks).contains("see image:"))
        assertTrue(blocks.any { it is TtyBlock.Image })
    }

    // ── ansiLines passthrough (host-rendered) ─────────────────────────

    @Test
    fun ansiLinesOnAssistantPassesThrough() {
        val esc = ESC.toString()
        val hostLine = "${esc}[38;2;1;2;3mstyled text${esc}[0m"
        val msg = ChatMessage(type = MessageToolType.Assistant, content = "ignored", ansiLines = listOf(hostLine))
        val segs = (render(msg).single() as TtyBlock.Text).lines.flatten()
        val styled = segs.first { it.first == "styled text" }
        assertEquals(1, styled.second.fgR)
        assertEquals(2, styled.second.fgG)
        assertEquals(3, styled.second.fgB)
    }

    @Test
    fun ansiLinesOnUserPassesThrough() {
        val esc = ESC.toString()
        val hostLine = "${esc}[1mbold user line${esc}[0m"
        val msg = ChatMessage(type = MessageToolType.User, content = "ignored", ansiLines = listOf(hostLine))
        val segs = (render(msg).single() as TtyBlock.Text).lines.flatten()
        val bold = segs.first { it.first == "bold user line" }
        assertTrue(bold.second.bold)
    }

    @Test
    fun ansiLinesOnThinkingPassesThrough() {
        val esc = ESC.toString()
        val hostLine = "${esc}[3mhost thinking${esc}[0m"
        val msg = ChatMessage(type = MessageToolType.Thinking, content = "ignored", ansiLines = listOf(hostLine))
        val segs = (render(msg).single() as TtyBlock.Text).lines.flatten()
        val italic = segs.first { it.first == "host thinking" }
        assertTrue(italic.second.italic)
    }

    @Test
    fun ansiLinesOnToolResultPassesThrough() {
        val esc = ESC.toString()
        val hostLine = "${esc}[38;2;1;2;3mhost styled${esc}[0m"
        val msg = ChatMessage(type = MessageToolType.ToolResult, toolName = "demo", ansiLines = listOf(hostLine))
        val segs = (render(msg).single() as TtyBlock.Text).lines.flatten()
        val styled = segs.first { it.first == "host styled" }
        assertEquals(1, styled.second.fgR)
    }

    @Test
    fun missingAnsiLinesFallsBackToPlainText() {
        val msg = ChatMessage(type = MessageToolType.Assistant, content = "hello world")
        val flat = plainText(render(msg))
        assertTrue(flat.contains("hello world"))
    }

    // ── images ────────────────────────────────────────────────────────

    @Test
    fun userMessageWithTwoImages() {
        val msg = ChatMessage(
            type = MessageToolType.User,
            content = "look at these",
            images = listOf(ChatImage(png64, "image/png"), ChatImage(png64, "image/jpeg")),
        )
        val blocks = render(msg)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is TtyBlock.Text)
        assertTrue(blocks[1] is TtyBlock.Image)
        assertTrue(blocks[2] is TtyBlock.Image)
        assertEquals("image/jpeg", (blocks[2] as TtyBlock.Image).mimeType)
        assertTrue(plainText(blocks).contains("look at these"))
    }

    // ── host passthrough (tool results) ──────────────────────────────

    @Test
    fun hostAnsiLinesPassThrough() {
        val esc = ESC.toString()
        val hostLine = "${esc}[38;2;1;2;3mhost styled${esc}[0m"
        val msg = ChatMessage(type = MessageToolType.ToolResult, toolName = "demo", ansiLines = listOf(hostLine))
        val segs = (render(msg).single() as TtyBlock.Text).lines.flatten()
        val styled = segs.first { it.first == "host styled" }
        assertEquals(1, styled.second.fgR)
        assertEquals(2, styled.second.fgG)
        assertEquals(3, styled.second.fgB)
    }

    @Test
    fun hostOscImageInsideAnsiLinesPassesThrough() {
        val seq = TtyEscapes.osc1337Image(png64, "image/png")
        val msg = ChatMessage(type = MessageToolType.ToolResult, toolName = "imgcat", ansiLines = listOf(seq))
        val blocks = render(msg)
        assertTrue(blocks.any { it is TtyBlock.Image })
    }

    // ── fallback tool rendering (old hosts) ──────────────────────────

    @Test
    fun editToolFallbackExpandedShowsDiffColors() {
        val args = """{"path":"a.py","edits":[{"oldText":"old line","newText":"new line"}]}"""
        val msg = ChatMessage(type = MessageToolType.ToolResult, toolName = "edit", toolArgs = args)
        val flat = plainText(render(msg, expanded = true))
        assertTrue(flat.contains("- old line"))
        assertTrue(flat.contains("+ new line"))
    }

    @Test
    fun editToolFallbackCollapsedIsSummaryOnly() {
        val args = """{"path":"a.py","edits":[{"oldText":"old","newText":"new"}]}"""
        val msg = ChatMessage(type = MessageToolType.ToolResult, toolName = "edit", toolArgs = args)
        val flat = plainText(render(msg, expanded = false))
        assertTrue(flat.contains("1 edit"))
        assertTrue(!flat.contains("+ new"))
    }

    @Test
    fun thinkingFallbackCollapsedIsSingleLine() {
        val msg = ChatMessage(type = MessageToolType.Thinking, content = "pondering deeply about things")
        val text = render(msg, expanded = false).single() as TtyBlock.Text
        assertEquals(1, text.lines.size)
        assertTrue(plainText(listOf(text)).contains("Thinking"))
    }

    @Test
    fun thinkingFallbackExpandedShowsBody() {
        val msg = ChatMessage(type = MessageToolType.Thinking, content = "pondering deeply")
        val flat = plainText(render(msg, expanded = true))
        assertTrue(flat.contains("pondering deeply"))
    }

    @Test
    fun toolErrorBadgeInFallbackHeader() {
        val msg = ChatMessage(type = MessageToolType.ToolResult, toolName = "bash", isError = true, content = "boom")
        assertTrue(plainText(render(msg)).contains("error"))
    }

    // ── security ─────────────────────────────────────────────────────

    @Test
    fun userContentCannotInjectEscapes() {
        val esc = ESC.toString()
        val msg = ChatMessage(type = MessageToolType.User, content = "evil${esc}]1337;File=inline=1:AAAA${7.toChar()}text")
        val blocks = render(msg)
        assertTrue(blocks.none { it is TtyBlock.Image })
    }

    // ── wrapping ──────────────────────────────────────────────────────

    @Test
    fun wrapTextRespectsWidthAndNewlines() {
        val wrapped = MessageNormalizer.wrapText("aaa bbb ccc\nddd", 7)
        assertEquals(listOf("aaa bbb", "ccc", "ddd"), wrapped)
    }
}
