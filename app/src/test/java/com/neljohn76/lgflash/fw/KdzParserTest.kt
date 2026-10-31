package com.neljohn76.lgflash.fw

import com.neljohn76.lgflash.util.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * KDZ parser tests over synthetic fixtures (terminator logic verified
 * against kdztools unkdz.py: 0x03 = one more entry follows, 0x00 = end).
 */
class KdzParserTest {

    private fun entry(name: String, length: Long, offset: Long): ByteArray {
        val e = ByteArray(272)
        name.toByteArray(Charsets.US_ASCII).copyInto(e, 0)
        Bytes.p32(e, 256, length and 0xFFFFFFFFL)
        Bytes.p32(e, 260, (length shr 32) and 0xFFFFFFFFL)
        Bytes.p32(e, 264, offset and 0xFFFFFFFFL)
        Bytes.p32(e, 268, (offset shr 32) and 0xFFFFFFFFL)
        return e
    }

    private fun buildKdz(vararg entries: Pair<String, Pair<Long, Long>>): ByteArray {
        val out = ByteArrayOutputStream()
        val magic = byteArrayOf(0x28, 0x05, 0x00, 0x00, 0x34, 0x31, 0x25, 0x80.toByte())
        out.write(magic)
        // write each entry, separated by 0x03, final terminator 0x00
        for (i in entries.indices) {
            val (name, (len, off)) = entries[i]
            out.write(entry(name, len, off))
            out.write(if (i < entries.size - 1) 0x03 else 0x00)
        }
        // payload region
        val payloadStart = out.size()
        out.write(ByteArray(100) { it.toByte() })
        return fixupOffsets(out.toByteArray(), payloadStart)
    }

    private fun fixupOffsets(b: ByteArray, payloadStart: Int): ByteArray {
        // point the first entry at the payload region
        Bytes.p32(b, 8 + 264, payloadStart.toLong())
        Bytes.p32(b, 8 + 256, 100L)
        return b
    }

    @Test
    fun parsesTwoEntries() {
        val b = buildKdz(
            "rooted.dz" to Pair(100L, 0L),
            "totinfo.tot" to Pair(50L, 0L)
        )
        val src = ArraySource(b)
        assertTrue(KdzParser.isKdz(src))
        val entries = KdzParser.parse(src)
        assertEquals(2, entries.size)
        assertEquals("rooted.dz", entries[0].name)
        assertEquals(100L, entries[0].length)
        assertEquals(b.size - 100, entries[0].offset)
        assertEquals("totinfo.tot", entries[1].name)
    }

    @Test
    fun rejectsNonKdz() {
        assertFalse(KdzParser.isKdz(ArraySource(ByteArray(1024))))
        // parse() on garbage: 0xFF-filled input has an entry whose
        // offset/length fields point far outside the file
        val garbage = ByteArray(2048) { 0xFF.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            KdzParser.parse(ArraySource(garbage))
        }
    }

    @Test
    fun rejectsEntryBeyondFileSize() {
        val b = buildKdz("rooted.dz" to Pair(100L, 0L))
        // point the entry far past the end of the file
        Bytes.p32(b, 8 + 264, b.size + 5000)
        assertThrows(IllegalArgumentException::class.java) {
            KdzParser.parse(ArraySource(b))
        }
    }

    @Test
    fun rejectsEntryLengthOverflow() {
        val b = buildKdz("rooted.dz" to Pair(100L, 0L))
        // huge length: offset + length must not fit in the file
        Bytes.p32(b, 8 + 256, 0x0000FFFFL)
        Bytes.p32(b, 8 + 260, 0x0000FFF0L) // length ~ 2^44
        assertThrows(IllegalArgumentException::class.java) {
            KdzParser.parse(ArraySource(b))
        }
    }
}
