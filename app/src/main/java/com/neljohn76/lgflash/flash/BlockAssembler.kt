package com.neljohn76.lgflash.flash

/**
 * Buffers a decompressed byte stream so that every emitted write starts at
 * a 512-byte block boundary. This is required because LAF WRTE addresses
 * whole blocks: writing "the next N bytes" while N is not a multiple of
 * 512 would otherwise resume at the START of the next block and overwrite
 * the tail of the previous one.
 *
 * offer() accumulates bytes and emits block-aligned chunks (up to maxChunk,
 * which must be a multiple of blockSize); flush() emits the final partial
 * block, which the LAF device accepts at a block-aligned offset.
 */
class BlockAssembler(
    private val blockSize: Int,
    private val maxChunk: Int,
    private val sink: (ByteArray) -> Unit
) {
    init {
        require(blockSize > 0 && maxChunk > 0 && maxChunk % blockSize == 0) {
            "maxChunk must be a positive multiple of blockSize"
        }
    }

    private val buffer = ByteArray(maxChunk + blockSize)
    private var buffered = 0

    /** Total bytes emitted through the sink. */
    var bytesEmitted: Long = 0L
        private set

    fun offer(src: ByteArray, len: Int = src.size) {
        require(len >= 0)
        require(buffered + len <= buffer.size) {
            "BlockAssembler overflow: $buffered + $len > ${buffer.size} " +
                "(feed smaller pieces, max $maxChunk per offer)"
        }
        src.copyInto(buffer, buffered, 0, len)
        buffered += len

        var pos = 0
        while (buffered - pos >= blockSize) {
            val n = minOf(maxChunk, ((buffered - pos) / blockSize) * blockSize)
            val piece = buffer.copyOfRange(pos, pos + n)
            sink(piece)
            bytesEmitted += n
            pos += n
        }
        if (pos > 0) {
            buffer.copyInto(buffer, 0, pos, buffered)
            buffered -= pos
        }
    }

    /** Emits the remaining (< blockSize) bytes, if any. */
    fun flush() {
        if (buffered > 0) {
            val piece = buffer.copyOf(buffered)
            sink(piece)
            bytesEmitted += buffered
            buffered = 0
        }
    }
}
