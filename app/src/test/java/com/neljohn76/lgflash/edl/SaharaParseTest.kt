package com.neljohn76.lgflash.edl

import com.neljohn76.lgflash.util.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Sahara READ_DATA / READ_DATA_64 request parsing, verified against
 * edlclient/Library/sahara_defs.py:
 *   READ_DATA      = [u32 image_id][u32 data_offset][u32 data_len]
 *   READ_DATA_64   = [u64 image_id][u64 data_offset][u64 data_len]
 * (payload = packet bytes after the 8-byte cmd/len header)
 */
class SaharaParseTest {

    private fun payload(vararg longs: Long): ByteArray {
        val b = ByteArray(longs.size * 8)
        longs.forEachIndexed { i, v ->
            val off = i * 8
            for (j in 0 until 8) {
                b[off + j] = ((v shr (8 * j)) and 0xFF).toByte()
            }
        }
        return b
    }

    @Test
    fun parsesReadData32() {
        // image_id=7, offset=0x1000, length=0x200
        val p = ByteArray(12)
        Bytes.p32(p, 0, 7)
        Bytes.p32(p, 4, 0x1000)
        Bytes.p32(p, 8, 0x200)
        val (id, off, len) = SaharaClient.parseReadRequest(SaharaClient.READ_DATA, p)
        assertEquals(7L, id)
        assertEquals(0x1000L, off)
        assertEquals(0x200L, len)
    }

    @Test
    fun parsesReadData64_with64BitOffset() {
        // Regression test for the off-by-8 parse bug: image_id=1,
        // offset=4 GiB, length=64 KiB.
        val p = payload(1L, 0x1_0000_0000L, 0x10000L)
        val (id, off, len) = SaharaClient.parseReadRequest(SaharaClient.READ_DATA_64, p)
        assertEquals(1L, id)
        assertEquals(0x1_0000_0000L, off)
        assertEquals(0x10000L, len)
    }

    @Test
    fun parsesReadData64_highImageId() {
        val highImageId = -2401053089206501375L
        val p = payload(highImageId, 0L, 4096L)
        val (id, off, len) = SaharaClient.parseReadRequest(SaharaClient.READ_DATA_64, p)
        assertEquals(highImageId, id)
        assertEquals(0L, off)
        assertEquals(4096L, len)
    }

    @Test
    fun rejectsShortPayloads() {
        assertThrows(EdlException::class.java) {
            SaharaClient.parseReadRequest(SaharaClient.READ_DATA, ByteArray(8))
        }
        assertThrows(EdlException::class.java) {
            SaharaClient.parseReadRequest(SaharaClient.READ_DATA_64, ByteArray(16))
        }
    }

    @Test
    fun rejectsUnknownCommand() {
        assertThrows(EdlException::class.java) {
            SaharaClient.parseReadRequest(0x99L, ByteArray(32))
        }
    }
}
