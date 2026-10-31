package com.neljohn76.lgflash.laf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * LAF framing tests. CRC16 vectors were produced with an independent
 * implementation of the lglaf CRC-16 variant (LSB-first poly 0x8408,
 * init 0xFFFF, final XOR 0xFFFF).
 */
class LafTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun crc16_knownVectors() {
        assertEquals(0x906e, Laf.crc16(bytes("123456789")))
        val heloHeader = ByteArray(32) { 0x41 }
        heloHeader[0] = 'H'.code.toByte()
        heloHeader[1] = 'E'.code.toByte()
        heloHeader[2] = 'L'.code.toByte()
        heloHeader[3] = 'O'.code.toByte()
        assertEquals(0xb54d, Laf.crc16(heloHeader))
        assertEquals(0xd397, Laf.crc16(ByteArray(512)))
    }

    @Test
    fun build_then_verify_roundtrip() {
        val pkt = Laf.build(Laf.CMD_HELO, arg1 = 0x01000001L)
        assertEquals(32, pkt.size)
        val h = Laf.parse(pkt.copyOfRange(0, 32))
        assertEquals("HELO", h.cmd)
        assertEquals(0x01000001L, h.args[0])
        assertEquals(0, h.bodyLen)
        assertEquals(Laf.invertedCmd(Laf.CMD_HELO), h.cmdInv)
        Laf.verify(pkt.copyOfRange(0, 32), ByteArray(0))
    }

    @Test
    fun build_withBody_and_verify() {
        val body = ByteArray(1000) { (it % 251).toByte() }
        val pkt = Laf.build(Laf.CMD_WRTE, arg1 = 3, arg2 = 42, arg3 = body.size.toLong(), body = body)
        assertEquals(32 + 1000, pkt.size)
        val h = Laf.parse(pkt)
        assertEquals(1000, h.bodyLen)
        assertEquals(3L, h.args[0])
        assertEquals(42L, h.args[1])
        Laf.verify(pkt.copyOfRange(0, 32), pkt.copyOfRange(32, pkt.size))
    }

    @Test
    fun verify_rejectsCorruptedBody() {
        val body = ByteArray(512) { (it % 7).toByte() }
        val pkt = Laf.build(Laf.CMD_WRTE, body = body)
        val header = pkt.copyOfRange(0, 32)
        val corrupted = body.copyOf()
        corrupted[10] = (corrupted[10] + 1).toByte()
        assertThrows(LafException::class.java) {
            Laf.verify(header, corrupted)
        }
    }

    @Test
    fun verify_rejectsBadCommandInversion() {
        val header = ByteArray(32)
        "READ".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        // wrong inverted command
        com.neljohn76.lgflash.util.Bytes.p32(header, 0x1C, 0)
        assertThrows(LafException::class.java) {
            Laf.verify(header, ByteArray(0))
        }
    }

    @Test
    fun maxIo_isBlockAligned() {
        assertEquals(0, Laf.MAX_IO % 512)
    }
}
