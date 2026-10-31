package com.neljohn76.lgflash.fw

import com.neljohn76.lgflash.util.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * DZ parser tests over synthetic fixtures. The fixtures reproduce the
 * kdztools dztool.py layout (real LG firmware samples are not embeddable
 * here; field offsets were verified against kdztools sources).
 */
class DzParserTest {

    private class ChunkSpec(
        val slice: String,
        val name: String,
        val raw: ByteArray,
        val targetAddr: Long
    )

    private fun zlib(data: ByteArray): ByteArray {
        val def = Deflater()
        def.setInput(data)
        def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) {
            val n = def.deflate(buf)
            out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }

    private fun build(vararg specs: ChunkSpec): ByteArray {
        val data = ByteArrayOutputStream()
        val compressed = specs.map { zlib(it.raw) }
        // file header
        val header = ByteArray(512)
        DzParser.FILE_MAGIC.copyInto(header, 0)
        "TESTDEV".toByteArray(Charsets.US_ASCII).copyInto(header, 16)
        "9.0".toByteArray(Charsets.US_ASCII).copyInto(header, 48)
        Bytes.p32(header, 192, specs.size.toLong())
        data.write(header)
        // chunk headers
        var dataPtr = 512L + specs.size * 512L
        for (i in specs.indices) {
            val ch = ByteArray(512)
            DzParser.CHUNK_MAGIC.copyInto(ch, 0)
            specs[i].slice.toByteArray(Charsets.US_ASCII).copyInto(ch, 4)
            specs[i].name.toByteArray(Charsets.US_ASCII).copyInto(ch, 36)
            Bytes.p32(ch, 100, specs[i].raw.size.toLong())
            Bytes.p32(ch, 104, compressed[i].size.toLong())
            md5(specs[i].raw).copyInto(ch, 108)
            Bytes.p32(ch, 124, specs[i].targetAddr)
            Bytes.p32(ch, 136, crc32(specs[i].raw))
            data.write(ch)
        }
        for (c in compressed) data.write(c)
        return data.toByteArray()
    }

    @Test
    fun parsesSyntheticDz() {
        val raw1 = ByteArray(1000) { (it % 13).toByte() }
        val raw2 = ByteArray(700) { (it % 29).toByte() }
        val dzBytes = build(
            ChunkSpec("lz0", "boot_a", raw1, targetAddr = 34),
            ChunkSpec("lz0", "system_a", raw2, targetAddr = 12345)
        )
        val src = ArraySource(dzBytes)
        assertTrue(DzParser.isDz(src))
        val dz = DzParser.parse(src)
        assertEquals("TESTDEV", dz.device)
        assertEquals("9.0", dz.version)
        assertEquals(2, dz.chunks.size)

        val c0 = dz.chunks[0]
        assertEquals("lz0", c0.sliceName)
        assertEquals("boot_a", c0.chunkName)
        assertEquals(1000L, c0.targetSize)
        assertEquals(34L, c0.targetAddr)
        assertTrue(md5(raw1).contentEquals(c0.md5))
        assertEquals(crc32(raw1), c0.crc32)
        // data must decompress back to the original
        val inf = java.util.zip.Inflater()
        inf.setInput(src.readAt(c0.dataOffset, c0.dataSize.toInt()))
        val out = ByteArray(1000)
        assertEquals(1000, inf.inflate(out))
        assertTrue(out.contentEquals(raw1))
        inf.end()

        val c1 = dz.chunks[1]
        assertEquals(12345L, c1.targetAddr)
        assertEquals(512L + 2 * 512L, c1.dataOffset) // data starts after the table
    }

    @Test
    fun rejectsBadMagic() {
        val b = ByteArray(600)
        b[0] = 0x11
        assertFalse(DzParser.isDz(ArraySource(b)))
        assertThrows(IllegalArgumentException::class.java) {
            DzParser.parse(ArraySource(b))
        }
    }

    @Test
    fun rejectsImplausibleChunkCount() {
        val b = build(ChunkSpec("s", "n", ByteArray(10), 34))
        Bytes.p32(b, 192, 50000) // > MAX_CHUNKS
        assertThrows(IllegalArgumentException::class.java) {
            DzParser.parse(ArraySource(b))
        }
    }

    @Test
    fun rejectsChunkDataPastEndOfFile() {
        val b = build(ChunkSpec("s", "n", ByteArray(10), 34))
        Bytes.p32(b, 104, 100000) // lie about the first chunk's data size
        assertThrows(IllegalArgumentException::class.java) {
            DzParser.parse(ArraySource(b))
        }
    }

    @Test
    fun rejectsBadChunkHeaderMagic() {
        val b = build(ChunkSpec("s", "n", ByteArray(10), 34))
        b[512] = 0x00 // corrupt the first chunk header magic
        assertThrows(IllegalArgumentException::class.java) {
            DzParser.parse(ArraySource(b))
        }
    }
}
