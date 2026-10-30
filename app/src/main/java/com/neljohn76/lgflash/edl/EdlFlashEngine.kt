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
 */
class EdlFlashEngine(
    private val io: BulkTransport,
    private val log: (String) -> Unit
) {
    val sahara = SaharaClient(io, log)
    private var firehose: FirehoseClient? = null

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
        return fh.configure()
    }

    fun programRaw(
        source: ByteSource,
        lun: Int,
        startSector: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val fh = firehose ?: throw EdlException("Upload a firehose programmer first")
        fh.programRaw(source, lun, startSector, 4096, onProgress)
    }

    fun powerReset(): Boolean {
        val fh = firehose ?: throw EdlException("Upload a firehose programmer first")
        return fh.powerReset()
    }
}
