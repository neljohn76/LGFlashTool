package com.neljohn76.lgflash.flash

import com.neljohn76.lgflash.fw.ByteSource
import com.neljohn76.lgflash.fw.DzParser
import com.neljohn76.lgflash.laf.Gpt
import com.neljohn76.lgflash.laf.GptInfo
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
 *
 * Safety model:
 *  1. verifyDzChunks() fully decompresses and checks the MD5/CRC32/size of
 *     every selected chunk BEFORE anything is erased or written.
 *  2. flashDzChunks() then erases + streams each chunk with block-aligned
 *     writes (BlockAssembler) and re-verifies the hashes while writing.
 *  3. Chunk target ranges are bounded by the device GPT's last usable LBA
 *     when a GPT can be read, and the first 34 blocks (GPT area) require an
 *     explicit opt-in.
 */
class LafFlashEngine(
    private val client: LafClient,
    private val log: (String) -> Unit
) {

    /** Reads and parses the live GPT of the connected device. */
    fun readPartitionTable(): List<GptPartition> {
        val fd = client.openDisk()
        try {
            return Gpt.parse(client.readGptTable(fd))
        } finally {
            try {
                client.closeFd(fd)
            } catch (_: Exception) {
            }
        }
    }

    private fun readPartitionTableInfo(): GptInfo? {
        val fd = client.openDisk()
        try {
            return Gpt.parseInfo(client.readGptTable(fd))
        } finally {
            try {
                client.closeFd(fd)
            } catch (_: Exception) {
            }
        }
    }

    private fun selectedChunks(dz: DzParser.DzFile, selected: Set<Int>): List<DzParser.DzChunk> =
        dz.chunks.filter { selected.contains(it.index) }.sortedBy { it.targetAddr }

    /**
     * Pre-flash integrity pass: decompresses every selected chunk (without
     * writing anything) and verifies the inflated size, MD5 and CRC32
     * against the DZ headers. Run this before any destructive operation.
     * Note the cost: the selected chunks are decompressed twice in total
     * (once here, once while writing).
     */
    fun verifyDzChunks(
        source: ByteSource,
        dz: DzParser.DzFile,
        selected: Set<Int>,
        onProgress: (Long, Long) -> Unit
    ) {
        val chunks = selectedChunks(dz, selected)
        if (chunks.isEmpty()) throw IllegalStateException("No chunks selected")
        val totalBytes = chunks.sumOf { it.targetSize }
        var done = 0L
        for (c in chunks) {
            val md = MessageDigest.getInstance("MD5")
            val crc = CRC32()
            var inflated = 0L
            streamChunk(source, c) { buf, n ->
                md.update(buf, 0, n)
                crc.update(buf, 0, n)
                inflated += n
                onProgress(done + inflated, totalBytes)
            }
            if (inflated != c.targetSize) {
                throw LafException(
                    "Inflated size mismatch for chunk ${c.sliceName}/${c.chunkName}: " +
                        "got $inflated, header says ${c.targetSize}"
                )
            }
            if (!md.digest().contentEquals(c.md5)) {
                throw LafException("MD5 mismatch for chunk ${c.sliceName}/${c.chunkName} " +
                    "(firmware is corrupt - refusing to flash)")
            }
            if (crc.value != c.crc32) {
                throw LafException("CRC32 mismatch for chunk ${c.sliceName}/${c.chunkName} " +
                    "(firmware is corrupt - refusing to flash)")
            }
            done += c.targetSize
            log("Verified chunk ${c.chunkName} (MD5/CRC32 ok, ${Bytes.formatBytes(c.targetSize)})")
        }
        log("Firmware verification passed for ${chunks.size} chunks " +
            "(${Bytes.formatBytes(totalBytes)} total)")
    }

    /**
     * Streams selected DZ chunks to the target disk at their absolute
     * 512-byte block addresses. Call verifyDzChunks() first: this erases
     * each chunk's target range before writing it.
     */
    fun flashDzChunks(
        source: ByteSource,
        dz: DzParser.DzFile,
        selected: Set<Int>,
        allowLowBlocks: Boolean,
        onProgress: (Long, Long) -> Unit
    ) {
        val chunks = selectedChunks(dz, selected)
        if (chunks.isEmpty()) throw IllegalStateException("No chunks selected")
        val totalBytes = chunks.sumOf { it.targetSize }

        validateChunkTargets(chunks, allowLowBlocks)

        val fd = client.openDisk()
        var done = 0L
        try {
            for (c in chunks) {
                log("Chunk ${c.index}: ${c.sliceName}/${c.chunkName} -> block ${c.targetAddr}, " +
                    "${Bytes.formatBytes(c.targetSize)}")
                done = flashOneChunk(source, c, fd, done, totalBytes, onProgress)
            }
        } finally {
            try {
                client.closeFd(fd)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Bounds-checks every chunk target before any destructive operation:
     * the first 34 blocks (GPT area) need an explicit opt-in, and no chunk
     * may extend past the device GPT's last usable LBA (when the GPT can
     * be read and parsed).
     */
    private fun validateChunkTargets(chunks: List<DzParser.DzChunk>, allowLowBlocks: Boolean) {
        for (c in chunks) {
            if (c.targetAddr < 34 && !allowLowBlocks) {
                throw LafException(
                    "Chunk ${c.sliceName}/${c.chunkName} targets block ${c.targetAddr} " +
                        "(< 34 = GPT area). Enable 'Allow flashing GPT / low blocks' if you " +
                        "really want this."
                )
            }
            val endBlock = c.targetAddr + (c.targetSize + 511) / 512
            if (endBlock < 0) {
                throw LafException(
                    "Chunk ${c.sliceName}/${c.chunkName} target range overflows " +
                        "(block ${c.targetAddr} + ${c.targetSize} bytes)"
                )
            }
        }
        val gpt = try {
            readPartitionTableInfo()
        } catch (e: Exception) {
            log("Warning: could not read the device GPT (${e.message}); " +
                "cannot bound chunk targets by the partition table.")
            null
        }
        if (gpt == null) {
            log("Warning: no valid GPT detected; chunk targets are only bounded " +
                "by the low-block guard, not by the device partition table.")
            return
        }
        for (c in chunks) {
            val endBlock = c.targetAddr + (c.targetSize + 511) / 512
            if (endBlock - 1 > gpt.lastUsableLba) {
                throw LafException(
                    "Chunk ${c.sliceName}/${c.chunkName} targets blocks " +
                        "${c.targetAddr}..${endBlock - 1}, but the device GPT's last usable " +
                        "block is ${gpt.lastUsableLba}. Refusing to write past the end of " +
                        "the disk."
                )
            }
        }
    }

    /**
     * Decompresses one chunk, feeding the sink arbitrary-sized pieces as
     * they leave the inflater. Throws on truncated or corrupt streams.
     */
    private fun streamChunk(
        source: ByteSource,
        c: DzParser.DzChunk,
        sink: (ByteArray, Int) -> Unit
    ) {
        val inf = Inflater()
        val outBuf = ByteArray(Laf.MAX_IO)
        val inBuf = ByteArray(256 * 1024)
        var inPos = 0L
        try {
            while (!inf.finished() && inPos < c.dataSize) {
                val take = minOf(inBuf.size.toLong(), c.dataSize - inPos).toInt()
                inf.setInput(source.readAt(c.dataOffset + inPos, take))
                inPos += take
                while (!inf.finished() && !inf.needsInput()) {
                    val n = inf.inflate(outBuf)
                    if (n == 0 && inf.needsDictionary()) {
                        throw LafException("Unsupported dictionary in chunk ${c.chunkName}")
                    }
                    if (n > 0) sink(outBuf, n)
                }
            }
            if (!inf.finished()) {
                throw LafException("Truncated zlib stream for chunk ${c.sliceName}/${c.chunkName}")
            }
        } finally {
            inf.end()
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
        val md = MessageDigest.getInstance("MD5")
        val crc = CRC32()
        var written = 0L

        // Block-aligned writer: every client.write starts at a 512-byte
        // block boundary no matter what sizes the inflater produces.
        val assembler = BlockAssembler(512, Laf.MAX_IO) { piece ->
            client.write(fd, c.targetAddr + written / 512, piece)
            written += piece.size
            onProgress(doneSoFar + written, totalBytes)
        }

        try {
            // Erase the target range first (whole 512-byte sectors).
            val sectors = (c.targetSize + 511) / 512
            if (sectors > 0) {
                client.erase(fd, c.targetAddr, sectors)
            }
            streamChunk(source, c) { buf, n ->
                md.update(buf, 0, n)
                crc.update(buf, 0, n)
                assembler.offer(buf, n)
            }
            assembler.flush()
        } catch (e: Exception) {
            throw LafException(
                "Failed while writing chunk ${c.sliceName}/${c.chunkName} " +
                    "(${Bytes.formatBytes(written)} of ${Bytes.formatBytes(c.targetSize)} " +
                    "written): ${e.message}"
            )
        }

        if (written != c.targetSize) {
            throw LafException(
                "Inflated size mismatch for ${c.chunkName}: got $written, " +
                    "header says ${c.targetSize}"
            )
        }
        if (!md.digest().contentEquals(c.md5)) {
            throw LafException("MD5 mismatch for chunk ${c.sliceName}/${c.chunkName} " +
                "after writing - the data on disk may be corrupt")
        }
        if (crc.value != c.crc32) {
            throw LafException("CRC32 mismatch for chunk ${c.sliceName}/${c.chunkName} " +
                "after writing - the data on disk may be corrupt")
        }
        log("Chunk ${c.chunkName}: written + verified (MD5/CRC32 ok)")
        return doneSoFar + written
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
        val sectors = (total + 511) / 512
        if (part.firstLba + sectors - 1 > part.lastLba) {
            throw LafException(
                "Image needs $sectors sectors starting at LBA ${part.firstLba}, " +
                    "but partition '$partitionName' ends at LBA ${part.lastLba}"
            )
        }
        val fd = client.openDisk()
        try {
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
