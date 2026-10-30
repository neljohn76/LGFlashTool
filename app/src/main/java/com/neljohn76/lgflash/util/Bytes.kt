package com.neljohn76.lgflash.util

/** Little-endian byte helpers and formatting used across the app. */
object Bytes {

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun u32(b: ByteArray, off: Int): Long =
        u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)

    fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    fun p32(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    /** NUL-terminated ASCII string, trimmed. */
    fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = minOf(off + len, b.size)
        while (end < max && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.US_ASCII).trim()
    }

    fun formatBytes(n: Long): String {
        if (n < 1024) return n.toString() + " B"
        val kb = n / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    fun formatSpeed(bytesPerSec: Double): String = formatBytes(bytesPerSec.toLong()) + "/s"

    fun hex(b: ByteArray, max: Int = 16): String =
        b.take(max).joinToString(" ") { String.format("%02x", it) }
}
