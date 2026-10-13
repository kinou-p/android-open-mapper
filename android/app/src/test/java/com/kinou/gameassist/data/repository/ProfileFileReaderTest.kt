package com.kinou.gameassist.data.repository

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileFileReaderTest {

    private fun read(bytes: ByteArray, max: Int = ProfileFileReader.MAX_BYTES) =
        ProfileFileReader.readText(ByteArrayInputStream(bytes), max)

    @Test
    fun readsUtf8Text() {
        assertEquals("{\"name\":\"Café\"}", read("{\"name\":\"Café\"}".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun stripsAUtf8ByteOrderMark() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("{}", read(bom + "{}".toByteArray()))
    }

    @Test
    fun blankFileIsRejected() {
        assertNull(read(ByteArray(0)))
        assertNull(read("  \n\t ".toByteArray()))
    }

    @Test
    fun fileLargerThanTheLimitIsRejected() {
        assertNull(read(ByteArray(11) { 'a'.code.toByte() }, max = 10))
    }

    @Test
    fun fileExactlyAtTheLimitIsAccepted() {
        assertEquals("a".repeat(10), read(ByteArray(10) { 'a'.code.toByte() }, max = 10))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("{}", read("\n  {}  \n".toByteArray()))
    }
}
