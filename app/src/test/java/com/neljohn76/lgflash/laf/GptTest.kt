package com.neljohn76.lgflash.laf

import com.neljohn76.lgflash.util.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GPT parse tests over synthetic 34-LBA tables. */
class GptTest {

    private fun newTable(entries: Int, entrySize: Int, lastUsable: Long): ByteArray {
        val t = ByteArray(34 * 512)
        // Protective MBR boot signature
        t[510] = 0x55.toByte()
        t[511] = 0xAA.toByte()
        // GPT header at LBA 1 (offset 512)
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(t, 512)
        Bytes.p32(t, 512 + 8, 0x00010000)            // revision
        Bytes.p32(t, 512 + 12, 92)                  // header size
        Bytes.p32(t, 512 + 72, 2)                  // (entries start LBA, low part)
        val firstUsable = 2L + (entries.toLong() * entrySize + 511) / 512
        writeU64(t, 512 + 40, firstUsable)          // first usable LBA
        writeU64(t, 512 + 48, lastUsable)           // last usable LBA
        writeU64(t, 512 + 72, 2)                    // entries start LBA
        Bytes.p32(t, 512 + 80, entries)             // num entries
        Bytes.p32(t, 512 + 84, entrySize)           // entry size
        return t
    }

    private fun writeU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun addEntry(
        t: ByteArray, index: Int, first: Long, last: Long, name: String
    ) {
        val off = 1024 + index * 128
        t[off] = 0x01 // non-zero type GUID
        writeU64(t, off + 32, first)
        writeU64(t, off + 40, last)
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        nameBytes.copyInto(t, off + 56)
    }

    @Test
    fun parsesPartitions_and_lastUsableLba() {
        val t = newTable(entries = 4, entrySize = 128, lastUsable = 1000)
        addEntry(t, 0, 34, 100, "boot")
        addEntry(t, 1, 101, 900, "system")
        val info = Gpt.parseInfo(t)!!
        assertEquals(2, info.partitions.size)
        assertEquals("boot", info.partitions[0].name)
        assertEquals(34L, info.partitions[0].firstLba)
        assertEquals(100L, info.partitions[0].lastLba)
        assertEquals((100 - 34 + 1) * 512, info.partitions[0].sizeBytes)
        assertEquals("system", info.partitions[1].name)
        assertEquals(1000L, info.lastUsableLba)
    }

    @Test
    fun skipsUnusedAndOutOfRangeEntries() {
        val t = newTable(entries = 4, entrySize = 128, lastUsable = 500)
        addEntry(t, 0, 34, 100, "ok")
        addEntry(t, 1, 34, 600, "beyond-last-usable") // out of range -> skipped
        addEntry(t, 2, 0, 0, "unused")                // zero LBAs -> skipped
        // entry 3: all-zero type GUID (never written) -> unused
        val parts = Gpt.parse(t)
        assertEquals(listOf("ok"), parts.map { it.name })
    }

    @Test
    fun rejectsBadSignature() {
        val t = newTable(4, 128, 1000)
        "XXXXXXX".toByteArray(Charsets.US_ASCII).copyInto(t, 512)
        assertNull(Gpt.parseInfo(t))
        assertEquals(emptyList<GptPartition>(), Gpt.parse(t))
    }

    @Test
    fun rejectsInconsistentGeometry() {
        // entry size smaller than 128
        var t = newTable(4, 64, 1000)
        assertNull(Gpt.parseInfo(t))
        // entry count larger than the entries area between LBA 2 and firstUsableLba
        t = newTable(4, 128, 1000)
        Bytes.p32(t, 512 + 80, 1024) // claim 1024 entries of 128 bytes
        assertNull(Gpt.parseInfo(t))
        // lastUsableLba < firstUsableLba
        t = newTable(4, 128, 1)
        assertNull(Gpt.parseInfo(t))
    }

    @Test
    fun rejectsShortInput() {
        assertNull(Gpt.parseInfo(ByteArray(34 * 512 - 1)))
        assertNull(Gpt.parseInfo(ByteArray(512)))
    }
}
