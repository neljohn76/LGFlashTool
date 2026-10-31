package com.neljohn76.lgflash.edl

import com.neljohn76.lgflash.fw.ByteSource
import com.neljohn76.lgflash.usb.BulkTransport

/**
 * Orchestrates an EDL session: Sahara handshake -> firehose programmer upload
 * -> firehose commands (program / erase / reset).
 *
 * The firehose programmer (.mbn / prog_ufs_firehose_*.mbn / .elf) is
 * device-specific and must be provided by the user. LG devices sometimes ship
 * it inside their KDZ firmware (look for files named "firehose"); otherwise
 * use the one matching the SoC from a trusted source.
 *
 * Storage geometry: the sector size is 4096 for UFS and 512 for eMMC
 * (based on which configure() ACKed), overridden by what getstorageinfo
 * reports when the programmer supports it. The device capacity, when
 * reported, bounds the requested write range.
 */
class EdlFlashEngine(
    private val io: BulkTransport,
    private val log: (String) -> Unit
) {
    val sahara = SaharaClient(io, log)
    private var firehose: FirehoseClient? = null

    /** Storage sector size in bytes; valid after a successful configure(). */
    var sectorSize: Long = 0L
        private set

    private var reportedNumSectors: Long? = null

    /** Returns the mode after programmer upload ("firehose" expected). */
    fun uploadProgrammer(
        programmer: ByteArray,
        onProgress: (Long, Long) -> Unit
    ): String {
        if (programmer.size < 16) throw EdlException("Programmer file too small")
        if (programmer[0] == 0x7F.toByte() && programmer[1] == 'E'.code.toByte() &&
            programmer[2] == 'L'.code.toByte() && programmer[3] == 'F'.code.toByte()
        ) {
            log("Programmer is an ELF image")
        }
        val mode = sahara.uploadProgrammer(programmer, onProgress)
        if (mode != "firehose") {
            throw EdlException(
                "Programmer reports mode '$mode', not firehose. " +
                    "This app only supports firehose programmers."
            )
        }
        firehose = FirehoseClient(io, log)
        return mode
    }

    fun configure(): Boolean {
        val fh = firehose ?: throw EdlException("Upload a firehose programmer first")
        val storage = fh.configure()
            ?: return false
        // UFS LUNs use 4096-byte sectors, eMMC uses 512-byte sectors.
        var size = if (storage == "emmc") 512L else 4096L
        val info = fh.getStorageInfo(0)
        if (info != null) {
            if (info.sectorSize != null && info.sectorSize > 0) size = info.sectorSize
            reportedNumSectors = info.numSectors
        }
        sectorSize = size
        log("EDL storage: $storage, ${size}-byte sectors" +
            (info?.numSectors?.let { ", $it sectors reported" } ?: ""))
        return true
    }

    fun programRaw(
        source: ByteSource,
        lun: Int,
        startSector: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val fh = firehose ?: throw EdlException("Upload a firehose programmer first")
        if (sectorSize <= 0) throw EdlException("configure() must succeed before programming")
        val total = source.size()
        val numSectors = (total + sectorSize - 1) / sectorSize
        val capacity = reportedNumSectors
        if (capacity != null && startSector + numSectors > capacity) {
            throw EdlException(
                "Requested write ($numSectors sectors at $startSector) exceeds the " +
                    "reported storage capacity ($capacity sectors on LUN 0). Refusing."
            )
        }
        fh.programRaw(source, lun, startSector, sectorSize, onProgress)
    }

    fun powerReset(): Boolean {
        val fh = firehose ?: throw EdlException("Upload a firehose programmer first")
        return fh.powerReset()
    }
}
