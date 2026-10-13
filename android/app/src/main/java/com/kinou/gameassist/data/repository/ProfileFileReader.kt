package com.kinou.gameassist.data.repository

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads an exported profile (.json) picked with the system file picker. */
object ProfileFileReader {

    /** A profile is a few KB; this only protects against picking a huge file by mistake. */
    const val MAX_BYTES = 1_000_000

    /**
     * UTF-8 text of [input], trimmed and without a byte order mark, or null when the file is blank
     * or larger than [maxBytes]. Reads at most [maxBytes] + 1 bytes.
     */
    fun readText(input: InputStream, maxBytes: Int = MAX_BYTES): String? {
        // Not readNBytes: it needs API 33 and the app supports API 26+
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        if (out.size() > maxBytes) return null
        return out.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF").trim().ifEmpty { null }
    }
}
