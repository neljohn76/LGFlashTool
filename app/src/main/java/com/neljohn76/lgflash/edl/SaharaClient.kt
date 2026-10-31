package com.neljohn76.lgflash.edl

import com.neljohn76.lgflash.usb.BulkTransport
import com.neljohn76.lgflash.util.Bytes

class EdlException(message: String) : Exception(message)

/**
 * Qualcomm Sahara protocol (per bkerler/edl, GPLv3).
 *
 * Packets: a 4-byte command, a 4-byte length (little-endian, includes the
 * 8-byte header), then the payload.
 *
 * Wire formats verified against edlclient/Library/sahara_defs.py:
 *  - READ_DATA  (0x03): payload = [u32 image_id][u32 data_offset][u32 data_len]
 *  - READ_DATA_64 (0x12): payload = [u64 image_id][u64 data_offset][u64 data_len]
 */
class SaharaClient(
    private val io: BulkTransport,
    private val log: (String) -> Unit
) {

    companion object {
        const val HELLO_REQ = 0x01L
        const val HELLO_RSP = 0x02L
        const val READ_DATA = 0x03L
        const val END_TRANSFER = 0x04L
        const val DONE_REQ = 0x05L
        const val DONE_RSP = 0x06L
        const val RESET_REQ = 0x07L
        const val RESET_RSP = 0x08L
        const val READ_DATA_64 = 0x12L

        const val MODE_IMAGE_TX_PENDING = 0x00L
        const val MODE_COMMAND = 0x03L

        private val ERROR_NAMES = mapOf(
            0x01L to "INVALID_HOST",
            0x02L to "INVALID_PACKET_LENGTH",
            0x03L to "INVALID_IMAGE_ID",
            0x04L to "INVALID_TARGET_PROTOCOL",
            0x05L to "INVALID_HOST_PROTOCOL_VERSION",
            0x06L to "UNREGISTERED_MISMATCHED_IMAGE_TYPE",
            0x07L to "INVALID_HASH_TABLE_LENGTH",
            0x08L to "VALIDATION_FAILED",
            0x09L to "INITIAL_IMAGE_AUTHENTICATION_FAILED",
            0x0AL to "SWITCH_MODE_FAILED",
            0x0BL to "IMAGE_RECEIVED_MISMATCH_HASH",
            0x0CL to "FINAL_IMAGE_AUTHENTICATION_FAILED",
            0x0DL to "END_OF_IMAGE_TRANSFER"
        )

        fun errorName(status: Long): String = ERROR_NAMES[status] ?: "status $status"

        /**
         * Parses a device READ_DATA / READ_DATA_64 request payload (i.e. the
         * packet bytes after the 8-byte cmd/len header).
         * Returns (imageId, offset, length).
         */
        fun parseReadRequest(cmd: Long, payload: ByteArray): Triple<Long, Long, Long> {
            if (cmd == READ_DATA) {
                if (payload.size < 12) {
                    throw EdlException("READ_DATA payload too short (${payload.size} bytes)")
                }
                return Triple(Bytes.u32(payload, 0), Bytes.u32(payload, 4), Bytes.u32(payload, 8))
            }
            if (cmd == READ_DATA_64) {
                if (payload.size < 24) {
                    throw EdlException("READ_DATA_64 payload too short (${payload.size} bytes)")
                }
                return Triple(Bytes.u64(payload, 0), Bytes.u64(payload, 8), Bytes.u64(payload, 16))
            }
            throw EdlException("Not a read request: 0x" + java.lang.Long.toHexString(cmd))
        }
    }

    data class HelloInfo(
        val protocol: Long,
        val version: Long,
        val minVersion: Long,
        val maxPacketLength: Long,
        val mode: Long,
        val features: LongArray
    )

    private fun sendPacket(cmd: Long, payload: ByteArray) {
        val pkt = ByteArray(8 + payload.size)
        Bytes.p32(pkt, 0, cmd)
        Bytes.p32(pkt, 4, (8 + payload.size).toLong())
        payload.copyInto(pkt, 8)
        io.write(pkt)
    }

    private fun readPacket(timeoutMs: Int): Pair<Long, ByteArray> {
        val hdr = io.readExact(8, timeoutMs)
        val cmd = Bytes.u32(hdr, 0)
        val len = Bytes.u32(hdr, 4).toInt()
        if (len < 8) throw EdlException("Bad Sahara packet length $len")
        val payload = if (len > 8) io.readExact(len - 8, timeoutMs) else ByteArray(0)
        return Pair(cmd, payload)
    }

    /** Reads the device HELLO_REQ and answers with HELLO_RSP. */
    fun readHello(timeoutMs: Int = 30000): HelloInfo {
        val (cmd, payload) = readPacket(timeoutMs)
        if (cmd != HELLO_REQ) {
            throw EdlException("Expected Sahara HELLO_REQ (0x01), got 0x" + java.lang.Long.toHexString(cmd))
        }
        if (payload.size < 44) {
            throw EdlException("HELLO_REQ payload too short (${payload.size} bytes)")
        }
        val d = LongArray(11) { Bytes.u32(payload, it * 4) }
        val info = HelloInfo(
            protocol = d[0],
            version = d[2],
            minVersion = d[3],
            maxPacketLength = d[4],
            mode = d[5],
            features = d.copyOfRange(6, 11)
        )
        log("Sahara HELLO: protocol=${info.protocol} ver=${info.version} " +
            "min=${info.minVersion} maxpkt=${info.maxPacketLength} mode=${info.mode}")
        if (info.protocol != 2L) {
            log("Note: unusual Sahara protocol version ${info.protocol}")
        }
        helloRsp(MODE_IMAGE_TX_PENDING, info.maxPacketLength)
        return info
    }

    /** Replies to HELLO_REQ: 12 dwords. */
    fun helloRsp(mode: Long, maxPacketLength: Long) {
        val payload = ByteArray(48)
        Bytes.p32(payload, 0, 2)                  // protocol: image tx pending
        Bytes.p32(payload, 4, 0x30)
        Bytes.p32(payload, 8, 2)                   // host version
        Bytes.p32(payload, 12, 1)                  // min host version
        Bytes.p32(payload, 16, maxPacketLength)
        Bytes.p32(payload, 20, mode)
        for (i in 0 until 6) Bytes.p32(payload, 24 + i * 4, (i + 1).toLong())
        sendPacket(HELLO_RSP, payload)
    }

    /**
     * Uploads a firehose programmer image. Returns the resulting mode
     * ("firehose" on success).
     */
    fun uploadProgrammer(
        image: ByteArray,
        onProgress: (Long, Long) -> Unit
    ): String {
        val total = image.size.toLong()
        var done = 0L
        var lastLog = 0L
        while (true) {
            val (cmd, payload) = readPacket(60000)
            when (cmd) {
                READ_DATA, READ_DATA_64 -> {
                    val (imageId, offset, length) = parseReadRequest(cmd, payload)
                    // Overflow-safe range check against the programmer size.
                    if (offset < 0 || length < 0 || length > total || offset > total - length) {
                        throw EdlException(
                            "Device requested image range outside the programmer " +
                                "(id $imageId, $offset+$length of $total)"
                        )
                    }
                    io.write(image.sliceArray(offset.toInt() until (offset + length).toInt()))
                    done += length
                    if (done - lastLog >= 262144L) {
                        onProgress(done, total)
                        lastLog = done
                    }
                }
                END_TRANSFER -> {
                    val status = if (payload.size >= 8) Bytes.u32(payload, 4) else 0L
                    if (status != 0L) {
                        throw EdlException("Device ended transfer: ${errorName(status)}")
                    }
                    sendPacket(DONE_REQ, ByteArray(0))
                }
                DONE_RSP -> {
                    onProgress(total, total)
                    log("Sahara: programmer upload complete")
                    return "firehose"
                }
                RESET_REQ -> {
                    log("Sahara: device requested reset")
                    sendPacket(RESET_RSP, ByteArray(0))
                    return "reset"
                }
                else -> throw EdlException("Unexpected Sahara packet 0x" + java.lang.Long.toHexString(cmd))
            }
        }
    }

    fun reset(): Boolean {
        sendPacket(RESET_REQ, ByteArray(0))
        return true
    }
}
