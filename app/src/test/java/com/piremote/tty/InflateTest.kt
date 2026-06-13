package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.DataFormatException
import java.util.zip.Deflater

/** inflateZlib must round-trip zlib-deflated data (same format Node's
 *  zlib.deflateSync emits) and refuse to expand a decompression bomb. */
class InflateTest {
    private fun deflate(s: String): ByteArray {
        val d = Deflater()
        d.setInput(s.toByteArray(Charsets.UTF_8))
        d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return out.toByteArray()
    }

    @Test fun roundTripsJson() {
        val json = """{"type":"mirror_frame","seq":7,"lines":["hello","world"]}"""
        assertEquals(json, inflateZlib(deflate(json)))
    }

    @Test fun roundTripsUtf8AndAnsiEscapes() {
        val s = "[31mred[0m émoji 🎉 done"
        assertEquals(s, inflateZlib(deflate(s)))
    }

    @Test fun roundTripsLargePayload() {
        val s = "line ".repeat(200_000) + "[1mEND"
        assertEquals(s, inflateZlib(deflate(s)))
    }

    @Test(expected = DataFormatException::class)
    fun rejectsMalformedData() {
        inflateZlib(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    @Test(expected = DataFormatException::class)
    fun capsDecompressionBomb() {
        // ~4 MB of zeros deflates to a few KB; inflating with a 512 KB cap must throw.
        val bomb = deflate("0".repeat(4 * 1024 * 1024))
        inflateZlib(bomb, maxBytes = 512 * 1024)
    }
}
