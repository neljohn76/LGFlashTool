package com.neljohn76.lgflash.fw

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream

/**
 * Random-access view over firmware data. The whole app works on this
 * abstraction so KDZ, DZ and raw files can be streamed without loading
 * them into memory.
 */
interface ByteSource {
    fun size(): Long
    fun readAt(offset: Long, length: Int): ByteArray
}

/** Random access over a SAF file channel. */
class ChannelSource(private val channel: java.nio.channels.FileChannel) : ByteSource {
    private val lock = Any()

    override fun size(): Long = synchronized(lock) { channel.size() }

    override fun readAt(offset: Long, length: Int): ByteArray {
        synchronized(lock) {
            val buf = ByteBuffer.allocate(length)
            channel.position(offset)
            while (buf.hasRemaining()) {
                val n = channel.read(buf)
                if (n < 0) {
                    throw IOException("Unexpected EOF at $offset (+${buf.position()}/$length)")
                }
            }
            return buf.array()
        }
    }
}

/** A byte range of another source (e.g. an embedded file inside a KDZ). */
class SubSource(
    private val parent: ByteSource,
    private val base: Long,
    private val length: Long
) : ByteSource {
    override fun size(): Long = length

    override fun readAt(offset: Long, len: Int): ByteArray {
        require(offset >= 0 && offset + len <= length) { "Read out of sub-source bounds" }
        return parent.readAt(base + offset, len)
    }
}

/**
 * Inflates a zlib-compressed region (zlib-wrapped KDZ payloads) once, then
 * serves random access over the result. Inflates to a temp file in the app
 * cache dir (payloads can be multiple GB). The in-memory fallback is only
 * used for small payloads: a compressed region above
 * [MAX_MEMORY_INFLATE_BYTES] fails with a clear error instead of taking
 * the app down with an OutOfMemoryError.
 */
class InflatedSource(
    private val parent: ByteSource,
    private val base: Long,
    private val length: Long,
    private val cacheDir: File?,
    private val log: (String) -> Unit,
    private val onProgress: (Long, Long) -> Unit
) : ByteSource {

    companion object {
        /** Compressed-size cap for the in-memory fallback (~256 MiB). */
        const val MAX_MEMORY_INFLATE_BYTES = 256L * 1024 * 1024
    }

    private var memory: ByteArray? = null
    private var file: RandomAccessFile? = null
    private var total = -1L
    private val lock = Any()

    private fun ensureInflated() {
        synchronized(lock) {
            if (total >= 0) return
            val tmp: File? = try {
                cacheDir?.let {
                    val free = it.usableSpace
                    if (free in 1 until 128L * 1024 * 1024) {
                        log("Warning: only ${free / (1024 * 1024)} MB free in the cache dir; " +
                            "inflating a multi-GB payload will likely fail.")
                    }
                    File.createTempFile("kdz_dz_", ".tmp", it)
                }
            } catch (e: IOException) {
                log("Temp file unavailable (${e.message})")
                null
            }
            if (tmp == null) {
                if (length > MAX_MEMORY_INFLATE_BYTES) {
                    throw IOException(
                        "Payload is ${length / (1024 * 1024)} MB compressed - too large to " +
                            "inflate in memory and no cache dir is available. Free up storage " +
                            "or extract the .dz file on another device."
                    )
                }
                log("No cache dir; inflating ${length} bytes in memory")
            }
            val input = SourceInputStream(parent, base, length)
            val zin = InflaterInputStream(input)
            val buf = ByteArray(256 * 1024)
            var done = 0L
            if (tmp != null) {
                try {
                    RandomAccessFile(tmp, "rw").use { raf ->
                        var n = zin.read(buf)
                        while (n >= 0) {
                            raf.write(buf, 0, n)
                            done += n
                            onProgress(done, -1)
                            n = zin.read(buf)
                        }
                    }
                    file = RandomAccessFile(tmp, "r")
                    total = tmp.length()
                    log("Inflated payload to ${total} bytes on disk")
                } catch (e: IOException) {
                    log("Disk inflate failed (${e.message}); retrying in memory")
                    try { zin.close() } catch (_: Exception) {}
                    try { input.close() } catch (_: Exception) {}
                    tmp.delete()
                    inflateToMemory()
                } finally {
                    try { zin.close() } catch (_: Exception) {}
                    try { input.close() } catch (_: Exception) {}
                }
            } else {
                inflateToMemory()
            }
        }
    }

    private fun inflateToMemory() {
        if (length > MAX_MEMORY_INFLATE_BYTES) {
            throw IOException(
                "Disk inflate failed and the ${length / (1024 * 1024)} MB payload is too " +
                    "large to inflate in memory."
            )
        }
        val input = SourceInputStream(parent, base, length)
        val zin = InflaterInputStream(input)
        try {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(256 * 1024)
            var n = zin.read(buf)
            var done = 0L
            while (n >= 0) {
                bos.write(buf, 0, n)
                done += n
                onProgress(done, -1)
                n = zin.read(buf)
            }
            memory = bos.toByteArray()
            total = memory!!.size.toLong()
            log("Inflated payload in memory: ${total} bytes")
        } finally {
            try { zin.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
    }

    override fun size(): Long {
        ensureInflated()
        return total
    }

    override fun readAt(offset: Long, length: Int): ByteArray {
        ensureInflated()
        synchronized(lock) {
            if (offset < 0 || offset + length > total) {
                throw IOException("Read out of inflated source bounds ($offset+$length of $total)")
            }
            val out = ByteArray(length)
            val f = file
            if (f != null) {
                f.seek(offset)
                var done = 0
                while (done < length) {
                    val n = f.read(out, done, length - done)
                    if (n < 0) throw IOException("Unexpected EOF in temp file")
                    done += n
                }
            } else {
                memory!!.copyInto(out, 0, offset.toInt(), offset.toInt() + length)
            }
            return out
        }
    }

    private class SourceInputStream(
        private val src: ByteSource,
        private val base: Long,
        private val len: Long
    ) : InputStream() {
        private var pos = 0L

        override fun read(): Int {
            if (pos >= len) return -1
            return src.readAt(base + pos++, 1)[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, cnt: Int): Int {
            if (pos >= len) return -1
            val n = minOf(cnt.toLong(), len - pos).toInt()
            val chunk = src.readAt(base + pos, n)
            chunk.copyInto(b, off, 0, n)
            pos += n
            return n
        }
    }
}
