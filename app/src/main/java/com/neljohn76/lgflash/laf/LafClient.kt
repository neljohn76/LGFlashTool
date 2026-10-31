package com.neljohn76.lgflash.laf

import com.neljohn76.lgflash.usb.BulkTransport
import com.neljohn76.lgflash.util.Bytes
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * High-level LAF operations: KILO authentication, HELO handshake,
 * disk open/read/write/erase, reboot / power-off. Ported from
 * Lekensteyn/lglaf (MIT).
 *
 * Addressing semantics (verified against lglaf partitions.py):
 *  - READ/WRTE arg2 is a 512-byte BLOCK offset; the transfer length
 *    (arg3 / body) is in BYTES. A partial final write at a block-aligned
 *    offset is accepted by the device (lglaf relies on this).
 *  - ERSE takes a start sector and a sector count (512-byte sectors).
 */
class LafClient(
    private val io: BulkTransport,
    private val log: (String) -> Unit
) {

    fun call(
        cmd: ByteArray,
        arg1: Long = 0, arg2: Long = 0, arg3: Long = 0, arg4: Long = 0,
        body: ByteArray? = null
    ): LafHeader {
        io.write(Laf.build(cmd, arg1, arg2, arg3, arg4, body))
        val hdrBytes = io.readExact(Laf.HEADER_SIZE)
        val h = Laf.parse(hdrBytes)
        val respBody = if (h.bodyLen > 0) io.readExact(h.bodyLen) else ByteArray(0)
        val expected = String(cmd, Charsets.US_ASCII)
        if (h.cmd != expected) {
            throw LafException(
                "Unexpected LAF response '${h.cmd}' (expected '$expected')" +
                    if (h.args[1] != 0L) ", error: ${LafErrorCodes.name(h.args[1])}" else ""
            )
        }
        Laf.verify(hdrBytes, respBody)
        h.body = respBody
        return h
    }

    fun tryHello(): Boolean {
        val h = call(Laf.CMD_HELO, arg1 = 0x01000001L)
        log("LAF HELO ok: protocol 0x" + java.lang.Long.toHexString(h.args[0]))
        return true
    }

    /**
     * KILO challenge/response authentication, exactly as in
     * lglaf challenge_response(): CENT is sent as header arg1, the device
     * returns a 4-byte challenge in the response header (bytes 8..11), and
     * METR (arg1) with the mode (arg3) carries the 16-byte encrypted reply
     * in the body.
     */
    fun kilo(): Boolean {
        val cent = call(Laf.CMD_KILO, arg1 = asciiDword("CENT"))
        val challenge = ByteArray(4)
        Bytes.p32(challenge, 0, cent.args[2])
        val response = kiloEncrypt(KILO_KEY, challenge)
        call(
            Laf.CMD_KILO,
            arg1 = asciiDword("METR"),
            arg3 = KILO_MODE,
            body = response
        )
        log("LAF KILO authentication ok")
        return true
    }

    /** Opens the whole disk, returns the fd used by read/write/erase. */
    fun openDisk(): Int {
        val h = call(Laf.CMD_OPEN, body = byteArrayOf(0))
        val fd = h.args[0].toInt()
        if (fd <= 0) throw LafException("openDisk failed (fd=$fd)")
        return fd
    }

    fun closeFd(fd: Int) {
        call(Laf.CMD_CLOS, arg1 = fd.toLong(), body = byteArrayOf(0))
    }

    /** Reads size bytes starting at blockOffset (512-byte blocks). */
    fun read(fd: Int, blockOffset: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = minOf(Laf.MAX_IO, size - done)
            val at = blockOffset + done / 512
            val h = call(
                Laf.CMD_READ,
                arg1 = fd.toLong(),
                arg2 = at,
                arg3 = n.toLong()
            )
            // lglaf asserts the response echoes fd / block offset / length.
            if (h.args[0] != fd.toLong() || h.args[1] != at || h.args[2] != n.toLong()) {
                throw LafException(
                    "READ response does not echo the request " +
                        "(fd=${h.args[0]}/${fd}, block=${h.args[1]}/${at}, len=${h.args[2]}/$n)"
                )
            }
            if (h.body.size != n) {
                throw LafException("READ returned ${h.body.size} bytes, expected $n")
            }
            h.body.copyInto(out, done)
            done += n
        }
        return out
    }

    /**
     * Writes data starting at blockOffset (512-byte blocks), MAX_IO at a
     * time. Callers must start each call at a block boundary (data of an
     * arbitrary length is fine; only the START offset is block-granular).
     */
    fun write(fd: Int, blockOffset: Long, data: ByteArray) {
        var done = 0
        while (done < data.size) {
            val n = minOf(Laf.MAX_IO, data.size - done)
            val at = blockOffset + done / 512
            val h = call(
                Laf.CMD_WRTE,
                arg1 = fd.toLong(),
                arg2 = at,
                arg3 = n.toLong(),
                body = data.copyOfRange(done, done + n)
            )
            // lglaf asserts the response reports the byte offset back.
            val expectedByteOffset = (at * 512) and 0xFFFFFFFFL
            if (h.args[0] != fd.toLong() || h.args[1] != expectedByteOffset) {
                throw LafException(
                    "WRTE response reports fd=${h.args[0]}, byte offset ${h.args[1]} " +
                        "(expected fd=$fd, byte offset $expectedByteOffset) - data may have " +
                        "landed at the wrong address; aborting."
                )
            }
            done += n
        }
    }

    /** Erases count sectors starting at startSector (512-byte sectors). */
    fun erase(fd: Int, startSector: Long, count: Long) {
        val h = call(Laf.CMD_ERSE, arg1 = fd.toLong(), arg2 = startSector, arg3 = count, arg4 = 0)
        if (h.args[0] != fd.toLong() || h.args[1] != startSector || h.args[2] != count) {
            throw LafException(
                "ERSE response does not echo the request " +
                    "(fd=${h.args[0]}/${fd}, start=${h.args[1]}/${startSector}, " +
                    "count=${h.args[2]}/${count})"
            )
        }
    }

    fun reboot() {
        call(Laf.CMD_CTRL, arg1 = asciiDword("RSET"))
    }

    fun powerOff() {
        call(Laf.CMD_CTRL, arg1 = asciiDword("POFF"))
    }

    /** Reads the first 34 LBAs (protective MBR + GPT + partition entries). */
    fun readGptTable(fd: Int): ByteArray = read(fd, 0, 34 * 512)

    /** 4 ASCII bytes as a little-endian dword (e.g. "CENT" for header args). */
    private fun asciiDword(name: String): Long {
        val b = name.toByteArray(Charsets.US_ASCII)
        require(b.size == 4)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    companion object {
        private val KILO_KEY =
            "qndiakxxuiemdklseqid~a~niq,zjuxl".toByteArray(Charsets.US_ASCII)

        /** lglaf uses mode 2 for the METR reply. */
        const val KILO_MODE = 2L

        /**
         * KILO key transform, byte-for-byte as lglaf laf_crypto.key_transform:
         * for x = 32 downTo 1, byte (key[x-1] - (x % 12)) is APPENDED, so the
         * result is the reversed key. (Verified against lglaf tests:
         * "qndiakxxuiemdklseqid~a~niq,zjuxl" -> "dqoev)ohnsWu\bk`oiicmZ_lpqe\ealp")
         */
        fun kiloKeyTransform(key0: ByteArray): ByteArray {
            require(key0.size == 32) { "KILO key must be 32 bytes" }
            val out = ByteArray(32)
            for (x in 32 downTo 1) {
                out[32 - x] = (((key0[x - 1].toInt() and 0xFF) - (x % 12)) and 0xFF).toByte()
            }
            return out
        }

        /**
         * Group-wise XOR of the transformed key with the 32-bit
         * big-endian challenge, as lglaf laf_crypto.xor_key.
         */
        fun kiloXorKey(transformedKey: ByteArray, challenge4: ByteArray): ByteArray {
            require(transformedKey.size == 32) { "Transformed key must be 32 bytes" }
            require(challenge4.size == 4) { "Challenge must be 4 bytes" }
            var challenge = 0L
            for (b in challenge4) challenge = (challenge shl 8) or (b.toLong() and 0xFF)
            val out = ByteArray(32)
            for (g in 0 until 8) {
                val k = Bytes.u32(transformedKey, g * 4)
                Bytes.p32(out, g * 4, k xor challenge)
            }
            return out
        }

        /**
         * Full KILO response: transform the static key, XOR it with the
         * 4-byte big-endian challenge, AES-256-ECB-encrypt 0x00..0x0F.
         * (Verified against lglaf tests: challenge f2 9a e1 30 ->
         * 2f47ca81ebeee6f414263c0542c8d132)
         */
        fun kiloEncrypt(key0: ByteArray, challenge4: ByteArray): ByteArray {
            val xoredKey = kiloXorKey(kiloKeyTransform(key0), challenge4)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(xoredKey, "AES"))
            return cipher.doFinal(ByteArray(16) { i -> i.toByte() })
        }
    }
}
