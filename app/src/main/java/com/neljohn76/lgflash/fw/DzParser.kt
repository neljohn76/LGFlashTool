package com.neljohn76.lgflash.fw

import com.neljohn76.lgflash.util.Bytes

/**
 * LG DZ firmware archive (per ehem/kdztools, GPLv3).
 *
 * 512-byte file header: magic 32 96 18 74, device @16[32], version @48[144],
 * chunkCount u32 @192. Then chunkCount 512-byte chunk headers: magic
 * 30 12 95 78, sliceName @4[32], chunkName @36[64], targetSize u32 @100,
 * dataSize u32 @104, md5 @108[16], targetAddr u32 @124 (absolute 512-byte
 * block), trimCount u32 @128, dev u32 @132, crc32 u32 @136. Chunk data is
 * zlib-compressed and stored sequentially after the chunk table.
 */
object DzParser {

    val FILE_MAGIC = byteArrayOf(0x32, 0x96, 0x18, 0x74)
    val CHUNK_MAGIC = byteArrayOf(0x30, 0x12, 0x95, 0x78)

    data class DzChunk(
        val index: Int,
        val sliceName: String,
        val chunkName: String,
        val targetSize: Long,
        val dataSize: Long,
        val md5: ByteArray,
        val targetAddr: Long,
        val trimCount: Long,
        val dev: Long,
        val crc32: Long,
        val dataOffset: Long
    ) {
        val targetSizeBytes: Long get() = targetSize
    }

    data class DzFile(
        val device: String,
        val version: String,
        val chunks: List<DzChunk>
    )

    fun isDz(input: ByteSource): Boolean {
        if (input.size() < 512) return false
        return input.readAt(0, 4).contentEquals(FILE_MAGIC)
    }

    fun parse(input: ByteSource): DzFile {
        val header = input.readAt(0, 512)
        if (!header.sliceArray(0 until 4).contentEquals(FILE_MAGIC)) {
            throw IllegalArgumentException("Not a DZ file (bad magic)")
        }
        val device = Bytes.cstr(header, 16, 32)
        val version = Bytes.cstr(header, 48, 144)
        val chunkCount = Bytes.u32(header, 192).toInt()
        if (chunkCount < 0 || chunkCount > 100000) {
            throw IllegalArgumentException("Implausible chunk count: $chunkCount")
        }

        val chunks = ArrayList<DzChunk>(chunkCount)
        var dataPtr = 512L + chunkCount * 512L
        for (i in 0 until chunkCount) {
            val ch = input.readAt(512L + i * 512L, 512)
            if (!ch.sliceArray(0 until 4).contentEquals(CHUNK_MAGIC)) {
                throw IllegalArgumentException("Bad chunk header magic at index $i")
            }
            val sliceName = Bytes.cstr(ch, 4, 32)
            val chunkName = Bytes.cstr(ch, 36, 64)
            val targetSize = Bytes.u32(ch, 100)
            val dataSize = Bytes.u32(ch, 104)
            val md5 = ch.copyOfRange(108, 124)
            val targetAddr = Bytes.u32(ch, 124)
            val trimCount = Bytes.u32(ch, 128)
            val dev = Bytes.u32(ch, 132)
            val crc32 = Bytes.u32(ch, 136)
            chunks.add(
                DzChunk(i, sliceName, chunkName, targetSize, dataSize, md5,
                    targetAddr, trimCount, dev, crc32, dataPtr)
            )
            dataPtr += dataSize
        }
        return DzFile(device, version, chunks)
    }
}
