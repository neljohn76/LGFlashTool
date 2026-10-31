package com.neljohn76.lgflash.edl

import com.neljohn76.lgflash.fw.ByteSource
import com.neljohn76.lgflash.usb.BulkTransport

/**
 * Qualcomm Firehose protocol (per bkerler/edl, GPLv3): XML command envelope
 * `<?xml version="1.0" encoding="UTF-8" ?><data>...</data>`, with
 * `<response value="ACK" .../>` replies. Raw program mode sends the image
 * as a plain byte stream after the program command is ACKed.
 *
 * The program/erase XML attribute set matches edl firehose.py
 * (cmd_program / cmd_erase): SECTOR_SIZE_IN_BYTES, num_partition_sectors,
 * physical_partition_number and start_sector. Note that start_sector is the
 * TARGET location; file_sector_offset is an offset inside the sent file and
 * must not be used to address the target.
 */
class FirehoseClient(
    private val io: BulkTransport,
    private val log: (String) -> Unit
) {

    /** Must match MaxPayloadSizeToTargetInBytes sent in configure(). */
    private val maxPayload = 16384

    /** Best-effort storage geometry (present only if the programmer supports getstorageinfo). */
    data class StorageInfo(val sectorSize: Long?, val numSectors: Long?)

    companion object {
        /** Keep every non-final raw data packet sector-aligned. */
        fun alignedPayloadSize(maxPayload: Int, sectorSize: Long): Int {
            require(maxPayload > 0) { "maxPayload must be positive" }
            require(sectorSize > 0 && sectorSize <= maxPayload) {
                "sector size $sectorSize does not fit in a $maxPayload-byte payload"
            }
            val aligned = maxPayload - (maxPayload % sectorSize.toInt())
            require(aligned > 0) { "sector size $sectorSize leaves no usable payload" }
            return aligned
        }
    }

    /** Accumulates bytes until an ACK/NAK response arrives. */
    fun readResponse(timeoutMs: Int = 60000): Pair<String, Boolean> {
        val sb = StringBuilder()
        while (true) {
            val chunk = io.read(4096, timeoutMs)
            sb.append(String(chunk, Charsets.UTF_8))
            val text = sb.toString()
            if (text.contains("value=\"ACK\"")) return Pair(text, true)
            if (text.contains("value=\"NAK\"")) {
                log("Firehose NAK: $text")
                return Pair(text, false)
            }
        }
    }

    fun cmd(xml: String, timeoutMs: Int = 60000): Pair<String, Boolean> {
        io.write(xml.toByteArray(Charsets.UTF_8))
        return readResponse(timeoutMs)
    }

    private fun envelope(inner: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" ?><data>$inner</data>"

    /**
     * Configures the target storage; tries UFS first, then eMMC.
     * Returns which memory type ACKed ("ufs" / "emmc"), or null on failure.
     */
    fun configure(): String? {
        val common =
            "Verbose=\"0\" AlwaysValidate=\"0\" ZLPAwareHost=\"1\" SkipStorageInit=\"0\" " +
                "MaxPayloadSizeToTargetInBytes=\"$maxPayload\""
        val (ufsText, ufsAck) = cmd(envelope("<configure MemoryName=\"ufs\" $common />"))
        if (ufsAck) {
            log("Firehose configured for UFS")
            return "ufs"
        }
        log("UFS configure failed (${ufsText.take(200)}); trying eMMC...")
        val (emmcText, emmcAck) = cmd(envelope("<configure MemoryName=\"emmc\" $common />"))
        if (emmcAck) {
            log("Firehose configured for eMMC")
            return "emmc"
        }
        log("eMMC configure failed too: ${emmcText.take(200)}")
        return null
    }

    /**
     * Best-effort geometry query. Not every programmer supports it; returns
     * null or a StorageInfo with null fields in that case. When the
     * programmer reports a sector size, it OVERRIDES the assumed default.
     */
    fun getStorageInfo(lun: Int): StorageInfo? {
        return try {
            val (text, ack) = cmd(envelope("<getstorageinfo physical_partition_number=\"$lun\" />"))
            if (!ack) {
                log("getstorageinfo not supported by this programmer")
                null
            } else {
                val ss = Regex("sector_size=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toLongOrNull()
                val ns = Regex("num_sectors=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toLongOrNull()
                log("getstorageinfo: sector_size=$ss num_sectors=$ns")
                StorageInfo(ss, ns)
            }
        } catch (e: Exception) {
            log("getstorageinfo failed (${e.message}); using assumed sector size")
            null
        }
    }

    /**
     * Programs a raw image to a LUN starting at startSector.
     * The final partial sector is zero-padded, matching edl cmd_program.
     */
    fun programRaw(
        source: ByteSource,
        lun: Int,
        startSector: Long,
        sectorSize: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        if (lun < 0) throw EdlException("Invalid LUN $lun")
        if (startSector < 0) throw EdlException("Invalid start sector $startSector")
        if (sectorSize <= 0) throw EdlException("Invalid sector size $sectorSize")
        val total = source.size()
        if (total <= 0) throw EdlException("Image is empty")
        val alignedPayload = try {
            alignedPayloadSize(maxPayload, sectorSize)
        } catch (e: IllegalArgumentException) {
            throw EdlException(e.message ?: "Invalid sector geometry")
        }
        val numSectors = ((total - 1) / sectorSize) + 1
        if (numSectors > Long.MAX_VALUE - startSector) {
            throw EdlException("start sector + image size overflows the address range")
        }
        val xml = envelope(
            "<program SECTOR_SIZE_IN_BYTES=\"$sectorSize\"" +
                " num_partition_sectors=\"$numSectors\"" +
                " physical_partition_number=\"$lun\"" +
                " start_sector=\"$startSector\" />"
        )
        val (text, ack) = cmd(xml, 60000)
        if (!ack || !text.contains("rawmode=\"true\"")) {
            throw EdlException("program command was not ACKed: ${text.take(300)}")
        }
        // Raw data stream, then the final ACK.
        var done = 0L
        while (done < total) {
            // Padding a non-final packet would shift every following byte on
            // the target. Only the final packet may be partial and padded.
            val n = minOf(alignedPayload.toLong(), total - done).toInt()
            var piece = source.readAt(done, n)
            if (piece.size % sectorSize != 0L) {
                val padded = (piece.size / sectorSize + 1) * sectorSize
                piece = piece.copyOf(padded.toInt())
            }
            io.write(piece)
            done += n
            onProgress(done, total)
        }
        val (finalText, finalAck) = readResponse()
        if (!finalAck) {
            throw EdlException("Raw data was not ACKed: ${finalText.take(300)}")
        }
        log("program: wrote $numSectors sectors to LUN $lun starting at $startSector " +
            "(${sectorSize}-byte sectors)")
    }

    fun erase(lun: Int, startSector: Long, numSectors: Long, sectorSize: Long): Boolean {
        val (text, ack) = cmd(
            envelope(
                "<erase SECTOR_SIZE_IN_BYTES=\"$sectorSize\"" +
                    " physical_partition_number=\"$lun\"" +
                    " start_sector=\"$startSector\"" +
                    " num_partition_sectors=\"$numSectors\" />"
            )
        )
        if (!ack) log("erase NAK: ${text.take(200)}")
        return ack
    }

    /** Resets the target after flashing. */
    fun powerReset(): Boolean {
        val (text, ack) = cmd(envelope("<power Action=\"reset\" />"))
        if (!ack) log("power reset NAK: ${text.take(200)}")
        return ack
    }
}
