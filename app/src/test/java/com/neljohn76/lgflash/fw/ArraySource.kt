package com.neljohn76.lgflash.fw

/** Simple in-memory ByteSource for parser tests. */
class ArraySource(private val data: ByteArray) : ByteSource {
    override fun size(): Long = data.size.toLong()
    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "Read out of bounds: $offset+$length of ${data.size}"
        }
        return data.copyOfRange(offset.toInt(), (offset + length).toInt())
    }
}
