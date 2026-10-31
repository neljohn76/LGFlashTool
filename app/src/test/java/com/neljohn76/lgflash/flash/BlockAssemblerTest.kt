package com.neljohn76.lgflash.flash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlockAssembler tests: every emitted write must start at a 512-byte block
 * boundary regardless of the piece sizes the inflater produces; the final
 * partial block is emitted by flush().
 */
class BlockAssemblerTest {

    private class Collector : (ByteArray) -> Unit {
        val pieces = ArrayList<ByteArray>()
        override fun invoke(p: ByteArray) {
            pieces.add(p)
        }
    }

    @Test
    fun emitsBlockAlignedPieces_forOddFeedSizes() {
        val input = ByteArray(5000) { ((it * 31 + 7) % 251).toByte() }
        val c = Collector()
        val a = BlockAssembler(512, 31 * 512, c)
        // Feed odd-sized pieces like an inflater would produce
        var pos = 0
        while (pos < input.size) {
            val n = minOf(333, input.size - pos)
            a.offer(input.copyOfRange(pos, pos + n), n)
            pos += n
        }
        a.flush()
        // All pieces except the last are multiples of 512; the last is < 512.
        for (i in 0 until c.pieces.size - 1) {
            assertEquals(0, c.pieces[i].size % 512)
            assertTrue(c.pieces[i].size <= 31 * 512)
        }
        assertTrue(c.pieces.last().size in 1..512)
        // Concatenation must reproduce the input exactly
        val joined = c.pieces.reduce { acc, b -> acc + b }
        assertTrue(joined.contentEquals(input))
        assertEquals(input.size.toLong(), a.bytesEmitted)
    }

    @Test
    fun alignedChunks_passThrough_unsplit() {
        val data = ByteArray(31 * 512) { 0x55.toByte() }
        val c = Collector()
        val a = BlockAssembler(512, 31 * 512, c)
        a.offer(data)
        a.flush()
        assertEquals(1, c.pieces.size)
        assertTrue(c.pieces[0].contentEquals(data))
    }

    @Test
    fun multipleConsecutiveWrites_keepOrder() {
        val first = ByteArray(1024) { 1 }
        val second = ByteArray(700) { 2 }
        val c = Collector()
        val a = BlockAssembler(512, 31 * 512, c)
        a.offer(first)
        a.offer(second)
        a.flush()
        val joined = c.pieces.reduce { acc, b -> acc + b }
        assertTrue(joined.contentEquals(first + second))
        assertEquals(1024 + 700, a.bytesEmitted)
        // pieces: 1024 (two blocks), then one full 512 block, then the
        // 188-byte remainder from flush()
        assertEquals(0, c.pieces[0].size % 512)
        assertEquals(512, c.pieces[1].size)
        assertEquals(188, c.pieces[2].size)
    }

    @Test
    fun shortFinalChunk_emittedByFlushOnly() {
        val data = ByteArray(100) { 3 }
        val c = Collector()
        val a = BlockAssembler(512, 31 * 512, c)
        a.offer(data)
        assertEquals(0, c.pieces.size) // nothing block-aligned yet
        assertEquals(0L, a.bytesEmitted)
        a.flush()
        assertEquals(1, c.pieces.size)
        assertEquals(100, c.pieces[0].size)
        assertTrue(c.pieces[0].contentEquals(data))
        assertEquals(100L, a.bytesEmitted)
    }

    @Test
    fun flushWithoutPending_isNoop() {
        val c = Collector()
        val a = BlockAssembler(512, 31 * 512, c)
        a.offer(ByteArray(512))
        assertEquals(1, c.pieces.size)
        a.flush()
        assertEquals(1, c.pieces.size)
    }

    @Test
    fun rejectsOversizedOffer() {
        val a = BlockAssembler(512, 1024, {})
        assertThrows(IllegalArgumentException::class.java) {
            a.offer(ByteArray(2000), 2000) // buffered + len > maxChunk + blockSize
        }
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            BlockAssembler(512, 1000, {}) // maxChunk not a multiple of blockSize
        }
        assertThrows(IllegalArgumentException::class.java) {
            BlockAssembler(0, 512, {})
        }
    }
}
