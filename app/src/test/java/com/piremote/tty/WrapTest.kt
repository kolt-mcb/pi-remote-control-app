package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrapTest {

    private val plain = AnsiStyle()
    private val red = AnsiStyle(fgR = 255, fgG = 0, fgB = 0)
    private val gutterStyle = AnsiStyle(fgR = 33, fgG = 38, fgB = 45)

    private fun line(vararg segs: Pair<String, AnsiStyle>) = segs.toList()
    private fun flat(l: List<Pair<String, AnsiStyle>>) = l.joinToString("") { it.first }

    @Test
    fun shortLinesUntouched() {
        val lines = listOf(line("hello" to plain))
        assertEquals(lines, wrapStyledLines(lines, 40))
    }

    @Test
    fun hardBreakAtColsWithoutSpaces() {
        val lines = listOf(line("a".repeat(25) to plain))
        val wrapped = wrapStyledLines(lines, 10)
        assertEquals(listOf(10, 10, 5), wrapped.map { flat(it).length })
    }

    @Test
    fun wordAwareBreakSwallowsSpace() {
        val lines = listOf(line("hello world again" to plain))
        val wrapped = wrapStyledLines(lines, 12)
        assertEquals(listOf("hello world", "again"), wrapped.map { flat(it) })
    }

    @Test
    fun stylePreservedAcrossSplit() {
        val lines = listOf(line("aaaa" to plain, "b".repeat(10) to red))
        val wrapped = wrapStyledLines(lines, 8)
        // First line: 4 plain + 4 red; second line: 6 red.
        assertEquals("aaaabbbb", flat(wrapped[0]))
        assertEquals(red, wrapped[0].last().second)
        assertEquals("bbbbbb", flat(wrapped[1]))
        assertEquals(red, wrapped[1].single().second)
    }

    @Test
    fun gutterRepeatsOnContinuationLines() {
        val lines = listOf(line("│" to gutterStyle, " " to plain, "x".repeat(30) to plain))
        val wrapped = wrapStyledLines(lines, 12)
        assertTrue(wrapped.size > 1)
        wrapped.forEach { l ->
            assertEquals('│', flat(l).first())
            assertEquals(gutterStyle, l.first().second)
            assertTrue(flat(l).length <= 12)
        }
        // No content lost: total x count is 30.
        assertEquals(30, wrapped.sumOf { l -> flat(l).count { it == 'x' } })
    }

    @Test
    fun emptyLinePreserved() {
        val lines = listOf(emptyList<Pair<String, AnsiStyle>>(), line("ok" to plain))
        val wrapped = wrapStyledLines(lines, 10)
        assertEquals(2, wrapped.size)
        assertEquals("", flat(wrapped[0]))
    }

    @Test
    fun linkStyleSurvivesWrap() {
        val linked = AnsiStyle(link = "https://example.com")
        val lines = listOf(line(("w".repeat(20)) to linked))
        val wrapped = wrapStyledLines(lines, 8)
        wrapped.forEach { l ->
            assertEquals("https://example.com", l.single().second.link)
        }
    }

    @Test
    fun exactWidthLineNotWrapped() {
        val lines = listOf(line("x".repeat(10) to plain))
        assertEquals(1, wrapStyledLines(lines, 10).size)
    }
}
