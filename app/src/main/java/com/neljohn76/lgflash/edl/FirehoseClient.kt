package com.neljohn76.lgflash.edl

import com.neljohn76.lgflash.fw.ByteSource
import com.neljohn76.lgflash.usb.BulkTransport

/**
 * Qualcomm Firehose protocol (per bkerler/edl, GPLv3): XML command envelope
 * `<?xml version="1.0" encoding="UTF-8" ?><data>...</data>`, with
 * `<response value="ACK" .../>` replies. Raw program mode sends the image
 * as a plain byte stream after the program command is ACKed.
 */
class FirehoseClient(
    private val io: BulkTransport,
    private val log: (String) -> Unit
) {

    /** Must match MaxPayloadSizeToTargetInBytes sent in configure(). */
    private val maxPayload = 16384

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

    /** Configures the target storage; tries UFS first, then eMMC. */
    fun configure(): Boolean {
        val common =
            "Verbose=\"0\" AlwaysValidate=\"0\" ZLPAwareHost=\"1\" SkipStorageInit=\"0\" " +
                "MaxPayloadSizeToTargetInBytes=\"$maxPayload\""
        val (ufsText, ufsAck) = cmd(envelope("<configure MemoryName=\"ufs\" $common />"))
        if (ufsAck) {
            log("Firehose configured for UFS")
            return true
        }
        log("UFS configure failed (${ufsText.take(200)}); trying eMMC...")
        val (emmcText, emmcAck) = cmd(envelope("<configure MemoryName=\"emmc\" $common />"))
        if (emmcAck) {
            log("Firehose configured for eMMC")
            return true
        }
        log("eMMC configure failed too: ${emmcText.take(200)}")
        return false
    }

    /**
     * Programs a raw image to a LUN starting at startSector.
     * sectorSize is 4096 for UFS LUNs (set by the caller).
     */
    fun programRaw(
        source: ByteSource,
        lun: Int,
        startSector: Long,
        sectorSize: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val total = source.size()
        if (total % sectorSize != 0L) {
            throw EdlException(
                "Image size ${total} is not a multiple of the $sectorSize-byte sector size"
            )
        }
        val numSectors = total / sectorSize
        val xml = envelope(
            "<program SECTOR_SIZE_IN_BYTES=\"$sectorSize\" skip_pwrite=\"false\" " +
                "file_sector_offset=\"$startSector\" num_of_sectors=\"$numSectors\" " +
                "physical_partition_number=\"$lun\" start_byte_sparse=\"0\" " +
                "sparse_sector_size=\"$sectorSize\" />"
        )
        val (text, ack) = cmd(xml, 60000)
        if (!ack || !text.contains("rawmode=\"true\"")) {
            throw EdlException("program command was not ACKed: ${text.take(300)}")
        }
        // Raw data stream, then the final ACK.
        var done = 0L
        while (done < total) {
            val n = minOf(maxPayload.toLong(), total - done).toInt()
            io.write(source.readAt(done, n))
            done += n
            onProgress(done, total)
        }
        val (finalText, finalAck) = readResponse()
        if (!finalAck) {
            throw EdlException("Raw data was not ACKed: ${finalText.take(300)}")
        }
        log("program: wrote $numSectors sectors to LUN $lun starting at $startSector")
    }

    fun erase(lun: Int, startSector: Long, numSectors: Long): Boolean {
        val (text, ack) = cmd(
            envelope(
                "<erase SECTOR_SIZE_IN_BYTES=\"4096\" " +
                    "physical_partition_number=\"$lun\" start_sector=\"$startSector\" " +
                    "num_of_sectors=\"$numSectors\" />"
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
