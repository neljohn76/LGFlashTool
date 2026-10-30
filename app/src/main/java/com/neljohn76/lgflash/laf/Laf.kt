package com.neljohn76.lgflash.laf

import com.neljohn76.lgflash.util.Bytes

class LafException(message: String) : Exception(message)

/** Known LAF error codes (from lglaf). */
object LafErrorCodes {
    val names = mapOf(
        0x80000001L to "Unaligned access",
        0x80000002L to "Invalid fd",
        0x80000003L to "Bad argument",
        0x80000004L to "Unsupported command",
        0x80000005L to "IO error",
        0x80000006L to "Out of memory",
        0x80000007L to "Permission denied"
    )
    fun name(code: Long): String = names[code] ?: "code 0x" + java.lang.Long.toHexString(code)
}

/** One parsed LAF response header. */
data class LafHeader(
    val cmd: String,
    val args: LongArray,
    val bodyLen: Int,
    val crc: Int,
    val cmdInv: Long,
    var body: ByteArray = ByteArray(0)
)

/**
 * LG's LAF (download mode) protocol, ported from Lekensteyn/lglaf (MIT).
 *
 * Every command is a 32-byte header followed by an optional body:
 *   cmd[4] @0x00, args[4] @0x04..0x13 (LE u32), bodyLen @0x14,
 *   CRC16 @0x18 (over header+body with CRC field zeroed), ~cmd @0x1C.
 */
object Laf {

    const val HEADER_SIZE = 32

    /** Max body per command: 31 * 512 bytes (target uses 16 KiB buffers). */
    const val MAX_IO = 31 * 512

    val CMD_HELO = "HELO".toByteArray(Charsets.US_ASCII)
    val CMD_KILO = "KILO".toByteArray(Charsets.US_ASCII)
    val CMD_OPEN = "OPEN".toByteArray(Charsets.US_ASCII)
    val CMD_CLOS = "CLOS".toByteArray(Charsets.US_ASCII)
    val CMD_READ = "READ".toByteArray(Charsets.US_ASCII)
    val CMD_WRTE = "WRTE".toByteArray(Charsets.US_ASCII)
    val CMD_ERSE = "ERSE".toByteArray(Charsets.US_ASCII)
    val CMD_CTRL = "CTRL".toByteArray(Charsets.US_ASCII)

    /** CRC-16-CCITT, LSB-first (poly 0x8408), init 0xFFFF, final xor 0xFFFF. */
    fun crc16(data: ByteArray, off: Int = 0, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in off until off + len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if (crc and 1 != 0) (crc shr 1) xor 0x8408 else crc shr 1
            }
        }
        return crc xor 0xFFFF
    }

    /** ~cmd as LE u32 (used for validation on both ends). */
    fun invertedCmd(cmd: ByteArray): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (cmd[i].toLong() and 0xFF)
        return v.inv() and 0xFFFFFFFFL
    }

    /** Builds header + body with the CRC filled in. */
    fun build(
        cmd: ByteArray,
        arg1: Long = 0, arg2: Long = 0, arg3: Long = 0, arg4: Long = 0,
        body: ByteArray? = null
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE + (body?.size ?: 0))
        cmd.copyInto(out, 0)
        Bytes.p32(out, 4, arg1)
        Bytes.p32(out, 8, arg2)
        Bytes.p32(out, 12, arg3)
        Bytes.p32(out, 16, arg4)
        Bytes.p32(out, 0x14, (body?.size ?: 0).toLong())
        Bytes.p32(out, 0x1C, invertedCmd(cmd))
        body?.copyInto(out, HEADER_SIZE)
        Bytes.p32(out, 0x18, crc16(out).toLong())
        return out
    }

    fun parse(h: ByteArray): LafHeader {
        require(h.size >= HEADER_SIZE)
        val cmd = String(h, 0, 4, Charsets.US_ASCII)
        val args = longArrayOf(
            Bytes.u32(h, 4), Bytes.u32(h, 8), Bytes.u32(h, 12), Bytes.u32(h, 16)
        )
        val bodyLen = Bytes.u32(h, 0x14).toInt()
        val crc = (Bytes.u32(h, 0x18) and 0xFFFFL).toInt()
        val cmdInv = Bytes.u32(h, 0x1C)
        return LafHeader(cmd, args, bodyLen, crc, cmdInv)
    }

    /** Verifies a response header (command inversion + CRC over header + body). */
    fun verify(hdrBytes: ByteArray, body: ByteArray) {
        val cmd = hdrBytes.copyOfRange(0, 4)
        if (Bytes.u32(hdrBytes, 0x1C) != invertedCmd(cmd)) {
            throw LafException("Bad command inversion in response")
        }
        val copy = hdrBytes.copyOf(HEADER_SIZE)
        Bytes.p32(copy, 0x18, 0)
        val expected = crc16(copy + body)
        if ((Bytes.u32(hdrBytes, 0x18) and 0xFFFFL).toInt() != expected) {
            throw LafException("CRC mismatch in response")
        }
    }
}
