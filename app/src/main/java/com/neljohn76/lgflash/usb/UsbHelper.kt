package com.neljohn76.lgflash.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.io.IOException

/**
 * Finds LG (Download Mode) / Qualcomm (EDL) devices, asks the user for USB
 * permission and opens a bulk-pair transport.
 *
 * Device identification: download-mode PIDs vary per LG model (lglaf's udev
 * rules list 633e, 627f, 6298, 633a, 6000, ...), so LAF matching cannot use
 * a single VID:PID pair. Instead a LAF candidate is an LG-VID device exposing
 * an interface with EXACTLY two bulk endpoints and nothing else - MTP-mode
 * phones (same vendor ID) carry an additional interrupt endpoint and are
 * thereby excluded.
 */
class UsbHelper(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.neljohn76.lgflash.USB_PERMISSION"
        private const val LG_VID = 0x1004
        private const val QCOM_VID = 0x05C6
        private val EDL_PIDS = setOf(0x9008, 0x9006, 0x9025)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** All attached devices that look like a target (LG download mode or QCOM EDL). */
    fun find(): List<UsbDevice> =
        usbManager.deviceList.values.filter { isLaf(it) || isEdl(it) }

    fun isLaf(d: UsbDevice): Boolean =
        d.vendorId == LG_VID && bulkOnlyInterface(d) != null

    fun isEdl(d: UsbDevice): Boolean =
        d.vendorId == QCOM_VID && EDL_PIDS.contains(d.productId)

    /**
     * The interface with exactly two bulk endpoints (one IN, one OUT) and no
     * other endpoints, or null. This is the shape of the LAF download-mode
     * interface; MTP/PTP interfaces have a third (interrupt) endpoint.
     */
    private fun bulkOnlyInterface(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            if (intf.endpointCount != 2) continue
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            var others = 0
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                } else {
                    others++
                }
            }
            if (epIn != null && epOut != null && others == 0) return intf
        }
        return null
    }

    fun hasPermission(d: UsbDevice): Boolean = usbManager.hasPermission(d)

    fun requestPermission(d: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(d, pi)
    }

    fun describe(d: UsbDevice): String =
        String.format("%04x:%04x", d.vendorId, d.productId) +
            " " + (d.productName ?: "LG device")

    /**
     * Opens a bulk IN/OUT endpoint pair and claims the interface (bulk
     * transfers are not valid on an unclaimed interface). The transport
     * releases the interface again on close().
     */
    fun open(d: UsbDevice): BulkTransport {
        val conn: UsbDeviceConnection =
            usbManager.openDevice(d) ?: throw IOException("Cannot open USB device")
        try {
            // Prefer the strict two-bulk-endpoint interface, then fall back
            // to any interface that has a bulk pair (EDL programmers vary).
            val preferred = bulkOnlyInterface(d)
            var target: UsbInterface? = null
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (pass in 0 until 2) {
                for (i in 0 until d.interfaceCount) {
                    val intf = d.getInterface(i)
                    if (pass == 0 && intf != preferred) continue
                    if (pass == 1 && intf == preferred) continue
                    var in1: UsbEndpoint? = null
                    var out1: UsbEndpoint? = null
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) in1 = ep else out1 = ep
                        }
                    }
                    if (in1 != null && out1 != null) {
                        target = intf
                        epIn = in1
                        epOut = out1
                        break
                    }
                }
                if (target != null) break
            }
            if (target == null || epIn == null || epOut == null) {
                throw IOException("Device has no bulk endpoints (not in Download/EDL mode?)")
            }
            if (!conn.claimInterface(target, true)) {
                throw IOException("Cannot claim USB interface ${target.id} of ${describe(d)}")
            }
            return BulkTransport(conn, target, epIn, epOut)
        } catch (e: Exception) {
            try {
                conn.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }
}

private const val TAG = "UsbHelper"
