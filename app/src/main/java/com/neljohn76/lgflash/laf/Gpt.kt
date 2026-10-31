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

/** Result of a strict GPT parse over the first 34 LBAs of a disk. */
data class GptInfo(
    val partitions: List<GptPartition>,
    val firstUsableLba: Long,
    val lastUsableLba: Long
)

/**
 * Minimal GPT parser over the first 34 LBAs of a disk.
 *
 * parse() is lenient (empty list when no valid GPT is present) for UI use;
 * parseInfo() is the strict variant that also validates the header geometry
 * and exposes the usable LBA range used to bound write targets.
 */
object Gpt {

    fun parse(tableBytes: ByteArray): List<GptPartition> =
        parseInfo(tableBytes)?.partitions ?: emptyList()

    /** Returns null when the data is not a self-consistent GPT. */
    fun parseInfo(tableBytes: ByteArray): GptInfo? {
        if (tableBytes.size < 34 * 512) return null
        val sig = String(tableBytes, 512, 8, Charsets.US_ASCII)
        if (sig != "EFI PART") return null

        val bb = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN)
        val firstUsableLba = bb.getLong(512 + 40)
        val lastUsableLba = bb.getLong(512 + 48)
        val entriesStartLba = bb.getLong(512 + 72)
        val numEntries = bb.getInt(512 + 80)
        val entrySize = bb.getInt(512 + 84)

        // Sanity-check the header geometry; reject nonsense instead of
        // looping over attacker-controlled counts.
        if (firstUsableLba < 2 || lastUsableLba < firstUsableLba ||
            entriesStartLba < 2 || entriesStartLba >= firstUsableLba ||
            numEntries <= 0 || numEntries > 1024 ||
            entrySize < 128 || entrySize > 512
        ) {
            return null
        }
        // The entry array must fit between its start LBA and firstUsableLba,
        // and must be fully contained in the bytes we actually read.
        val entriesBytes = numEntries.toLong() * entrySize.toLong()
        val entriesSectors = (entriesBytes + 511) / 512
        if (entriesSectors > firstUsableLba - entriesStartLba ||
            lastUsableLba > Long.MAX_VALUE / 512 ||
            entriesStartLba > Long.MAX_VALUE - entriesSectors ||
            entriesStartLba + entriesSectors > tableBytes.size / 512L
        ) {
            return null
        }

        val out = ArrayList<GptPartition>(minOf(numEntries, 128))
        val base = entriesStartLba * 512L
        val ranges = ArrayList<LongRange>()
        for (i in 0 until numEntries) {
            val off = (base + i * entrySize.toLong()).toInt()
            if (off < 0 || off > tableBytes.size - entrySize) return null
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
            if (first == 0L && last == 0L) continue
            // Reject malformed entries rather than presenting a partial GPT
            // whose remaining ranges could be mistaken for safe targets.
            if (first < firstUsableLba || first > last || last > lastUsableLba) return null
            val range = first..last
            if (ranges.any { it.first <= range.last && range.first <= it.last }) return null
            ranges.add(range)
            val name = String(tableBytes, off + 56, 72, Charsets.UTF_16LE)
                .trimEnd('\u0000')
            out.add(GptPartition(i, name, first, last))
        }
        return GptInfo(out, firstUsableLba, lastUsableLba)
    }
}
