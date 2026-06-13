package com.piremote.tty

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Max bytes an inflated mirror payload may produce. Guards against a
 *  decompression bomb — a tiny deflated frame that expands to gigabytes.
 *  Real mirror frames are well under this (the protocol caps images at 8 MiB). */
const val MAX_INFLATED_BYTES = 16 * 1024 * 1024

/**
 * Inflate a zlib-deflated byte array (as produced by Node's `zlib.deflateSync`)
 * into a UTF-8 string. Throws [DataFormatException] if the data is malformed or
 * the output would exceed [maxBytes]. Truncated input yields whatever inflated
 * so far (the caller's JSON parse will then reject it).
 */
fun inflateZlib(data: ByteArray, maxBytes: Int = MAX_INFLATED_BYTES): String {
    val inflater = Inflater()
    inflater.setInput(data)
    val out = ByteArrayOutputStream(maxOf(64, minOf(maxBytes, data.size * 4)))
    val buf = ByteArray(16384)
    try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break // all input consumed (done or truncated) — stop
            out.write(buf, 0, n)
            if (out.size() > maxBytes) throw DataFormatException("inflated payload exceeds $maxBytes bytes")
        }
    } finally {
        inflater.end()
    }
    return out.toString("UTF-8")
}
