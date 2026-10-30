package com.neljohn76.lgflash.laf

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GptPartition(
    val index: Int,
    val name: String,
    val firstLba: Long,
    val lastLba: Long
) {
    val sizeBytes: Long get() = (lastLba - firstLba + 1) * 512
}

/** Minimal GPT parser over the first 34 LBAs of a disk. */
object Gpt {

    fun parse(tableBytes: ByteArray): List<GptPartition> {
        if (tableBytes.size < 34 * 512) return emptyList()
        val sig = String(tableBytes, 512, 8, Charsets.US_ASCII)
        if (sig != "EFI PART") return emptyList()

        val bb = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN)
        val entriesStartLba = bb.getLong(512 + 72).toInt()
        val numEntries = bb.getInt(512 + 80)
        val entrySize = bb.getInt(512 + 84)
        if (entrySize < 128 || entriesStartLba <= 0 || numEntries <= 0) return emptyList()

        val out = ArrayList<GptPartition>(numEntries)
        val base = entriesStartLba * 512L
        for (i in 0 until numEntries) {
            val off = (base + i * entrySize.toLong()).toInt()
            if (off + entrySize > tableBytes.size) break
            // Type GUID all-zero = unused entry
            var allZero = true
            for (j in 0 until 16) {
                if (tableBytes[off + j] != 0.toByte()) {
                    allZero = false
                    break
                }
            }
            if (allZero) continue
            val first = bb.getLong(off + 32)
            val last = bb.getLong(off + 40)
            val name = String(tableBytes, off + 56, 72, Charsets.UTF_16LE)
                .trimEnd('\u0000')
            if (first == 0L && last == 0L) continue
            out.add(GptPartition(i, name, first, last))
        }
        return out
    }
}
