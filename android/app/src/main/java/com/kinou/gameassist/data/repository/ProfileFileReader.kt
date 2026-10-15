package com.kinou.gameassist.data.repository

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Reads an exported profile (.json) picked with the system file picker. */
object ProfileFileReader {

    /** A profile is a few KB; this only protects against picking a huge file by mistake. */
    const val MAX_BYTES = 1_000_000

    /**
     * UTF-8 text of [input], trimmed and without a byte order mark, or null when the file is blank
     * or larger than [maxBytes], or not valid UTF-8. Reads at most [maxBytes] + 1 bytes.
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
        // Strict decoding: a malformed byte must reject the file, not become a silent U+FFFD
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return text.removePrefix("\uFEFF").trim().ifEmpty { null }
    }
}
