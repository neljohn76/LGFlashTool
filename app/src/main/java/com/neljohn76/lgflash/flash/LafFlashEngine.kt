package com.neljohn76.lgflash.flash

import com.neljohn76.lgflash.fw.ByteSource
import com.neljohn76.lgflash.fw.DzParser
import com.neljohn76.lgflash.laf.Gpt
import com.neljohn76.lgflash.laf.GptPartition
import com.neljohn76.lgflash.laf.Laf
import com.neljohn76.lgflash.laf.LafClient
import com.neljohn76.lgflash.laf.LafException
import com.neljohn76.lgflash.util.Bytes
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Flashes firmware to an LG device in Download Mode over the LAF protocol
 * (the phone-to-phone equivalent of LGUP / LG Flash Tool).
 */
class LafFlashEngine(
    private val client: LafClient,
    private val log: (String) -> Unit
) {

    /** Reads and parses the live GPT of the connected device. */
    fun readPartitionTable(): List<GptPartition> {
        val fd = client.openDisk()
        try {
            val table = client.readGptTable(fd)
            return Gpt.parse(table)
        } finally {
            try {
                client.closeFd(fd)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Streams selected DZ chunks (zlib-inflated, MD5/CRC-verified) to the
     * target disk at their absolute 512-byte block addresses.
     */
    fun flashDzChunks(
        source: ByteSource,
        dz: DzParser.DzFile,
        selected: Set<Int>,
        allowLowBlocks: Boolean,
        onProgress: (Long, Long) -> Unit
    ) {
        val chunks = dz.chunks.filter { selected.contains(it.index) }.sortedBy { it.targetAddr }
        if (chunks.isEmpty()) throw IllegalStateException("No chunks selected")
        val totalBytes = chunks.sumOf { it.targetSize }
        val fd = client.openDisk()
        var done = 0L
        try {
            for (c in chunks) {
                if (c.targetAddr < 34 && !allowLowBlocks) {
                    throw LafException(
                        "Chunk ${c.sliceName}/${c.chunkName} targets block ${c.targetAddr} " +
                            "(< 34 = GPT area). Enable 'Allow flashing GPT / low blocks' if you really want this."
                    )
                }
                log("Chunk ${c.index}: ${c.sliceName}/${c.chunkName} -> block ${c.targetAddr}, ${Bytes.formatBytes(c.targetSize)}")
                done = flashOneChunk(source, c, fd, done, totalBytes, onProgress)
            }
        } finally {
            try {
                client.closeFd(fd)
            } catch (_: Exception) {
            }
        }
    }

    private fun flashOneChunk(
        source: ByteSource,
        c: DzParser.DzChunk,
        fd: Int,
        doneSoFar: Long,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): Long {
        val inf = Inflater()
        val md = MessageDigest.getInstance("MD5")
        val crc = CRC32()
        val outBuf = ByteArray(Laf.MAX_IO)
        val inBuf = ByteArray(256 * 1024)
        var inPos = 0L
        var chunkDone = 0L

        try {
            // Erase the target range first (whole 512-byte sectors).
            val sectors = (c.targetSize + 511) / 512
            if (sectors > 0) {
                client.erase(fd, c.targetAddr, sectors)
            }

            while (!inf.finished() && inPos < c.dataSize) {
                val take = minOf(inBuf.size.toLong(), c.dataSize - inPos).toInt()
                val piece = source.readAt(c.dataOffset + inPos, take)
                inPos += take
                inf.setInput(piece)
                while (!inf.finished() && !inf.needsInput()) {
                    var n = inf.inflate(outBuf)
                    if (n == 0 && inf.needsDictionary()) {
                        throw LafException("Unsupported dictionary in chunk ${c.chunkName}")
                    }
                    while (n > 0) {
                        val write = minOf(n, outBuf.size)
                        md.update(outBuf, 0, write)
                        crc.update(outBuf, 0, write)
                        client.write(fd, c.targetAddr + chunkDone / 512, outBuf.copyOf(write))
                        chunkDone += write
                        onProgress(doneSoFar + chunkDone, totalBytes)
                        n -= write
                    }
                }
            }
            if (!inf.finished()) {
                throw LafException("Truncated zlib stream for chunk ${c.sliceName}/${c.chunkName}")
            }
        } finally {
            inf.end()
        }

        if (chunkDone != c.targetSize) {
            throw LafException(
                "Inflated size mismatch for ${c.chunkName}: got $chunkDone, header says ${c.targetSize}"
            )
        }
        if (!md.digest().contentEquals(c.md5)) {
            throw LafException("MD5 mismatch for chunk ${c.sliceName}/${c.chunkName}")
        }
        if (crc.value != c.crc32) {
            throw LafException("CRC32 mismatch for chunk ${c.sliceName}/${c.chunkName}")
        }
        log("Chunk ${c.chunkName}: written + verified (MD5/CRC32 ok)")
        return doneSoFar + chunkDone
    }

    /** Writes a raw image to a partition located on the live GPT. */
    fun flashRawImage(
        source: ByteSource,
        partitions: List<GptPartition>,
        partitionName: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val part = partitions.firstOrNull { it.name == partitionName }
            ?: throw LafException("Partition '$partitionName' not found in the device GPT")
        val total = source.size()
        if (total > part.sizeBytes) {
            throw LafException(
                "Image (${Bytes.formatBytes(total)}) is larger than partition " +
                    "'$partitionName' (${Bytes.formatBytes(part.sizeBytes)})"
            )
        }
        val fd = client.openDisk()
        try {
            val sectors = (total + 511) / 512
            client.erase(fd, part.firstLba, sectors)
            var done = 0L
            while (done < total) {
                val n = minOf(Laf.MAX_IO.toLong(), total - done).toInt()
                client.write(fd, part.firstLba + done / 512, source.readAt(done, n))
                done += n
                onProgress(done, total)
            }
            log("Wrote ${Bytes.formatBytes(total)} to $partitionName (LBA ${part.firstLba})")
        } finally {
            try {
                client.closeFd(fd)
            } catch (_: Exception) {
            }
        }
    }
}
