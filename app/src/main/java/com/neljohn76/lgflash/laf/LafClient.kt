package com.neljohn76.lgflash.laf

import com.neljohn76.lgflash.usb.BulkTransport
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * High-level LAF operations: HELO handshake, KILO authentication,
 * disk open/read/write/erase, reboot / power-off. Ported from
 * Lekensteyn/lglaf (MIT).
 *
 * NOTE (assumption to validate on hardware): READ/WRTE offsets are in
 * 512-byte blocks, matching the GPT/DZ addressing used everywhere else.
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

    fun kilo(): Boolean {
        val h1 = call(Laf.CMD_KILO, body = "CENT".toByteArray(Charsets.US_ASCII))
        if (h1.body.size < 16) {
            throw LafException("KILO challenge too short (${h1.body.size} bytes)")
        }
        val response = kiloEncrypt(KILO_KEY, h1.body.copyOf(16))
        call(Laf.CMD_KILO, body = "METR".toByteArray(Charsets.US_ASCII) + response)
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
            val h = call(
                Laf.CMD_READ,
                arg1 = fd.toLong(),
                arg2 = blockOffset + done / 512,
                arg3 = n.toLong()
            )
            if (h.body.size != n) {
                throw LafException("READ returned ${h.body.size} bytes, expected $n")
            }
            h.body.copyInto(out, done)
            done += n
        }
        return out
    }

    /** Writes data starting at blockOffset (512-byte blocks), MAX_IO at a time. */
    fun write(fd: Int, blockOffset: Long, data: ByteArray) {
        var done = 0
        while (done < data.size) {
            val n = minOf(Laf.MAX_IO, data.size - done)
            call(
                Laf.CMD_WRTE,
                arg1 = fd.toLong(),
                arg2 = blockOffset + done / 512,
                arg3 = n.toLong(),
                body = data.copyOfRange(done, done + n)
            )
            done += n
        }
    }

    /** Erases count sectors starting at startSector. */
    fun erase(fd: Int, startSector: Long, count: Long) {
        call(Laf.CMD_ERSE, arg1 = fd.toLong(), arg2 = startSector, arg3 = count, arg4 = 0)
    }

    fun reboot() {
        call(Laf.CMD_CTRL, arg1 = ctrlArg("RSET"))
    }

    fun powerOff() {
        call(Laf.CMD_CTRL, arg1 = ctrlArg("POFF"))
    }

    /** Reads the first 34 LBAs (protective MBR + GPT + partition entries). */
    fun readGptTable(fd: Int): ByteArray = read(fd, 0, 34 * 512)

    private fun ctrlArg(name: String): Long {
        val b = name.toByteArray(Charsets.US_ASCII)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    companion object {
        private val KILO_KEY =
            "qndiakxxuiemdklseqid~a~niq,zjuxl".toByteArray(Charsets.US_ASCII)

        /**
         * KILO challenge/response: derive the AES-256-ECB key from the static
         * secret, XOR it with the big-endian challenge, encrypt 0x00..0x0F.
         */
        fun kiloEncrypt(key0: ByteArray, challenge: ByteArray): ByteArray {
            require(key0.size == 32)
            val key = key0.copyOf()
            for (x in 32 downTo 1) {
                key[x - 1] = ((key[x - 1] - (x % 12).toByte().toInt()) and 0xFF).toByte()
            }
            for (i in 0 until 32) {
                key[i] = (key[i].toInt() xor challenge[i % 16].toInt()).toByte()
            }
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            return cipher.doFinal(ByteArray(16) { i -> i.toByte() })
        }
    }
}
