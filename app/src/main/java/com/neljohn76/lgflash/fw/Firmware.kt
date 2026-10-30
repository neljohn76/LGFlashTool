package com.neljohn76.lgflash.fw

import java.io.File
import java.nio.channels.FileChannel

/**
 * Auto-detects a picked firmware file and exposes it in a unified shape.
 */
sealed class Firmware {
    /** DZ firmware (a .dz file, or the .dz payload inside a .kdz). */
    class Dz(
        val source: ByteSource,
        val dz: DzParser.DzFile,
        val container: String // "DZ", "KDZ" or "KDZ(zlib)"
    ) : Firmware()

    /** Raw partition image to flash to a partition chosen from the device GPT. */
    class Raw(
        val source: ByteSource,
        val fileName: String
    ) : Firmware()
}

object FirmwareDetect {

    fun detect(
        channel: FileChannel,
        fileName: String,
        log: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
        cacheDir: File? = null
    ): Firmware {
        val input: ByteSource = ChannelSource(channel)

        // A bare DZ file (also covers KDZ v2, which starts with a DZ header).
        if (DzParser.isDz(input)) {
            val dz = DzParser.parse(input)
            log("DZ file ok: device=${dz.device}, version=${dz.version}, ${dz.chunks.size} chunks")
            return Firmware.Dz(input, dz, "DZ")
        }

        if (KdzParser.isKdz(input)) {
            val entries = KdzParser.parse(input)
            log("KDZ container with ${entries.size} entries: " +
                entries.joinToString { "${it.name} (${it.length} bytes)" })
            val dzEntry = entries.firstOrNull {
                it.length > 0 && it.offset > 0 && it.name.endsWith(".dz", true)
            } ?: throw IllegalArgumentException(
                "KDZ contains no .dz payload (TOT/BIN-style KDZ payloads are not " +
                    "supported yet). Entries: " + entries.joinToString { it.name }
            )
            val sub = SubSource(input, dzEntry.offset, dzEntry.length)
            if (DzParser.isDz(sub)) {
                val dz = DzParser.parse(sub)
                log("KDZ -> embedded DZ ok: device=${dz.device}, version=${dz.version}, ${dz.chunks.size} chunks")
                return Firmware.Dz(sub, dz, "KDZ")
            }
            // Some KDZ variants zlib-compress the embedded DZ.
            val peek = sub.readAt(0, 2)
            if (peek[0] == 0x78.toByte()) {
                log("KDZ payload is zlib-wrapped; inflating (this can take a while)...")
                val inflated = InflatedSource(input, dzEntry.offset, dzEntry.length, cacheDir, log, onProgress)
                val dz = DzParser.parse(inflated)
                log("KDZ -> inflated DZ ok: device=${dz.device}, version=${dz.version}, ${dz.chunks.size} chunks")
                return Firmware.Dz(inflated, dz, "KDZ(zlib)")
            }
            throw IllegalArgumentException("KDZ payload is neither a raw nor zlib DZ file")
        }

        // Anything else: treat as a raw partition image.
        return Firmware.Raw(input, fileName)
    }
}
