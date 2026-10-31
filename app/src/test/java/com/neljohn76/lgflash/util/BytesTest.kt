package com.neljohn76.lgflash.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BytesTest {

    @Test
    fun u16_u32_u64_readLittleEndian() {
        val b = byteArrayOf(0x34, 0x12, 0x78, 0x56, 0xEF.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0x89.toByte())
        assertEquals(0x1234, Bytes.u16(b, 0))
        assertEquals(0x5678, Bytes.u16(b, 2))
        assertEquals(0x56781234L, Bytes.u32(b, 0))
        assertEquals(0x89AB_CDEF_5678_1234UL.toLong(), Bytes.u64(b, 0))
    }

    @Test
    fun p32_writesLittleEndian() {
        val b = ByteArray(8)
        Bytes.p32(b, 0, 0xDEADBEEFL)
        Bytes.p32(b, 4, 0x01020304L)
        assertEquals(0xEF.toByte(), b[0])
        assertEquals(0xBE.toByte(), b[1])
        assertEquals(0xAD.toByte(), b[2])
        assertEquals(0xDE.toByte(), b[3])
        assertEquals(0x04.toByte(), b[4])
        assertEquals(0xDEADBEEFL, Bytes.u32(b, 0))
    }

    @Test
    fun cstr_readsUntilNul() {
        val b = ByteArray(16)
        "boot\u0000xyz".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        assertEquals("boot", Bytes.cstr(b, 0, 16))
        // NUL not found within the window: stops at the window end
        val full = "ABCDEFGHIJKLMNOP".toByteArray(Charsets.US_ASCII)
        assertEquals("ABCDEFGHIJKLMNOP", Bytes.cstr(full, 0, 16))
    }

    @Test
    fun formatBytes() {
        assertEquals("512 B", Bytes.formatBytes(512))
        assertEquals("1.0 KB", Bytes.formatBytes(1024))
        assertEquals("1.5 MB", Bytes.formatBytes((1.5 * 1024 * 1024).toLong()))
    }
}
