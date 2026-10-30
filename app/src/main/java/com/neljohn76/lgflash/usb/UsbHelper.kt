package com.neljohn76.lgflash.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.io.IOException

/**
 * Finds LG (Download Mode) / Qualcomm (EDL) devices, asks the user for USB
 * permission and opens a bulk-pair transport.
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

    fun isLaf(d: UsbDevice): Boolean = d.vendorId == LG_VID

    fun isEdl(d: UsbDevice): Boolean =
        d.vendorId == QCOM_VID && EDL_PIDS.contains(d.productId)

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

    /** Opens the first bulk IN/OUT endpoint pair and returns a transport. */
    fun open(d: UsbDevice): BulkTransport {
        val conn: UsbDeviceConnection =
            usbManager.openDevice(d) ?: throw IOException("Cannot open USB device")
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
            }
            if (epIn != null && epOut != null) {
                return BulkTransport(conn, epIn, epOut)
            }
        }
        conn.close()
        Log.w(TAG, "No bulk endpoints on ${describe(d)}")
        throw IOException("Device has no bulk endpoints (not in Download/EDL mode?)")
    }
}

private const val TAG = "UsbHelper"
