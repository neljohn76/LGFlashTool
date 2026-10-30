package com.neljohn76.lgflash.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException

/**
 * Thread-safe wrapper over one USB bulk IN/OUT endpoint pair.
 * All operations are serialized with a lock: the protocol layers expect
 * strict request/response ordering.
 */
class BulkTransport(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) : AutoCloseable {

    private val lock = Any()

    fun write(data: ByteArray, timeoutMs: Int = 10000) {
        synchronized(lock) {
            var off = 0
            while (off < data.size) {
                val n = conn.bulkTransfer(epOut, data, off, data.size - off, timeoutMs)
                if (n < 0) throw IOException("USB write failed at offset $off")
                off += n
            }
        }
    }

    /** One bulk read, returns whatever arrived (up to max bytes). */
    fun read(max: Int, timeoutMs: Int): ByteArray {
        synchronized(lock) {
            val buf = ByteArray(max)
            val n = conn.bulkTransfer(epIn, buf, max, timeoutMs)
            if (n < 0) throw IOException("USB read failed")
            return buf.copyOf(n)
        }
    }

    /** Reads exactly n bytes. */
    fun readExact(n: Int, timeoutMs: Int = 10000): ByteArray {
        synchronized(lock) {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = conn.bulkTransfer(epIn, out, off, n - off, timeoutMs)
                if (r < 0) throw IOException("USB read failed at offset $off (got $off/$n)")
                off += r
            }
            return out
        }
    }

    override fun close() {
        synchronized(lock) {
            conn.close()
        }
    }
}
