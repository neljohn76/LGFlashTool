package com.neljohn76.lgflash.fw

import com.neljohn76.lgflash.util.Bytes

/**
 * LG KDZ container (per ehem/kdztools, GPLv3).
 *
 * 8-byte magic, then a table of 272-byte entries:
 *   name[256], length u64, offset u64.
 * Between entries a 0x03 byte means one more entry follows, 0x00 ends the
 * table. (KDZ v2 files start directly with a DZ header and are handled by
 * the DZ path.)
 */
object KdzParser {

    /** A sane upper bound on the number of embedded files. */
    const val MAX_ENTRIES = 4096

    data class KdzEntry(val name: String, val length: Long, val offset: Long)

    val MAGICS = listOf(
        byteArrayOf(0x28, 0x05, 0x00, 0x00, 0x34, 0x31, 0x25, 0x80.toByte()),
        byteArrayOf(0x18, 0x05, 0x00, 0x00, 0x32, 0x79, 0x44, 0x50)
    )

    fun isKdz(input: ByteSource): Boolean {
        if (input.size() < 512) return false
        val m = input.readAt(0, 8)
        return MAGICS.any { m.contentEquals(it) }
    }

    fun parse(input: ByteSource): List<KdzEntry> {
        val totalSize = input.size()
        val entries = ArrayList<KdzEntry>()
        var pos = 8L
        var cont = true
        var last = false
        while (cont) {
            if (entries.size >= MAX_ENTRIES) {
                throw IllegalArgumentException(
                    "KDZ entry table exceeds ${MAX_ENTRIES} entries - corrupt file"
                )
            }
            val e = input.readAt(pos, 272)
            pos += 272
            val name = Bytes.cstr(e, 0, 256)
            val length = Bytes.u64(e, 256)
            val offset = Bytes.u64(e, 264)
            entries.add(KdzEntry(name, length, offset))
            // Validate the entry range against the actual file size
            // (overflow-safe: length > size - offset <=> beyond the file).
            if (offset < 0 || length < 0 || offset > totalSize || length > totalSize - offset) {
                throw IllegalArgumentException(
                    "KDZ entry '$name' points outside the file " +
                        "(offset $offset + length $length, file size $totalSize)"
                )
            }
            cont = !last
            val t = input.readAt(pos, 1)[0]
            pos += 1
            when {
                t == 0x03.toByte() -> last = true   // one more footer entry follows
                t == 0x00.toByte() -> cont = false  // end of table
                else -> pos -= 1                     // no separator, next entry directly
            }
        }
        return entries
    }
}
