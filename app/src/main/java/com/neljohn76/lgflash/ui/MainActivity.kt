package com.neljohn76.lgflash.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.neljohn76.lgflash.R
import com.neljohn76.lgflash.edl.EdlFlashEngine
import com.neljohn76.lgflash.flash.LafFlashEngine
import com.neljohn76.lgflash.fw.DzParser
import com.neljohn76.lgflash.fw.Firmware
import com.neljohn76.lgflash.fw.FirmwareDetect
import com.neljohn76.lgflash.laf.GptPartition
import com.neljohn76.lgflash.laf.LafClient
import com.neljohn76.lgflash.usb.BulkTransport
import com.neljohn76.lgflash.usb.UsbHelper
import com.neljohn76.lgflash.util.Bytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    private lateinit var usb: UsbHelper
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var device: UsbDevice? = null
    private var transport: BulkTransport? = null
    private var lafClient: LafClient? = null
    private var edlEngine: EdlFlashEngine? = null

    private var firmwarePfd: ParcelFileDescriptor? = null
    private var firmwareChannel: FileChannel? = null
    private var firmware: Firmware? = null
    private var programmer: ByteArray? = null
    private var partitions: List<GptPartition> = emptyList()
    private var busy = false
    private var lastProgressTime = 0L
    private var lastProgressBytes = 0L

    private lateinit var tvDeviceStatus: TextView
    private lateinit var tvFirmwareInfo: TextView
    private lateinit var tvProgrammer: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var radioLaf: RadioButton
    private lateinit var radioEdl: RadioButton
    private lateinit var cardEdl: View
    private lateinit var editLun: EditText
    private lateinit var editStartSector: EditText
    private lateinit var chkAutoReboot: CheckBox
    private lateinit var chkAllowLowBlocks: CheckBox
    private val adapter = ItemAdapter()

    private val pickFirmware =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFirmwarePicked(uri)
        }

    private val pickProgrammer =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onProgrammerPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usb = UsbHelper(this)

        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
        tvFirmwareInfo = findViewById(R.id.tvFirmwareInfo)
        tvProgrammer = findViewById(R.id.tvProgrammer)
        tvProgress = findViewById(R.id.tvProgress)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)
        progressBar = findViewById(R.id.progress)
        radioLaf = findViewById(R.id.radioLaf)
        radioEdl = findViewById(R.id.radioEdl)
        cardEdl = findViewById(R.id.cardEdl)
        editLun = findViewById(R.id.editLun)
        editStartSector = findViewById(R.id.editStartSector)
        chkAutoReboot = findViewById(R.id.chkAutoReboot)
        chkAllowLowBlocks = findViewById(R.id.chkAllowLowBlocks)

        val list = findViewById<RecyclerView>(R.id.listItems)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectOrRequest() }
        findViewById<Button>(R.id.btnPickFirmware).setOnClickListener {
            pickFirmware.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnPickProgrammer).setOnClickListener {
            pickProgrammer.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnFlash).setOnClickListener { onFlashClicked() }
        findViewById<Button>(R.id.btnReboot).setOnClickListener { onRebootClicked() }
        findViewById<Button>(R.id.btnPowerOff).setOnClickListener { onPowerOffClicked() }

        val modeGroup = findViewById<android.widget.RadioGroup>(R.id.modeGroup)
        modeGroup.setOnCheckedChangeListener { _, _ ->
            cardEdl.visibility = if (radioEdl.isChecked) View.VISIBLE else View.GONE
            closeTransport()
            refreshDeviceStatus()
        }

        log("LG Flash Tool started. Connect the target phone in Download Mode (LAF) or EDL 9008 via OTG.")
        refreshDeviceStatus()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbHelper.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshDeviceStatus()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(usbReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (dev != null && (usb.isLaf(dev) || usb.isEdl(dev))) {
            device = dev
            refreshDeviceStatus()
            connectOrRequest()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeTransport()
        try {
            firmwarePfd?.close()
        } catch (_: Exception) {
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbHelper.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (granted && dev != null) {
                        device = dev
                        connect()
                    } else {
                        log("USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (dev != null && (usb.isLaf(dev) || usb.isEdl(dev))) {
                        device = dev
                        refreshDeviceStatus()
                        connectOrRequest()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (dev != null && dev == device) {
                        device = null
                        closeTransport()
                        log("Device detached")
                        refreshDeviceStatus()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun isLafMode(): Boolean = radioLaf.isChecked

    private fun log(msg: String) {
        mainHandler.post {
            tvLog.append(msg + "\n")
            if (tvLog.text.length > 200000) {
                tvLog.text = tvLog.text.subSequence(tvLog.text.length - 60000, tvLog.text.length)
            }
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setProgress(done: Long, total: Long) {
        mainHandler.post {
            val now = System.currentTimeMillis()
            var speed = 0.0
            if (lastProgressTime in 1 until now) {
                speed = (done - lastProgressBytes) * 1000.0 / (now - lastProgressTime)
            }
            lastProgressTime = now
            lastProgressBytes = done
            if (total > 0) {
                progressBar.progress = ((done * 1000) / total).toInt()
                tvProgress.text = Bytes.formatBytes(done) + " / " + Bytes.formatBytes(total) +
                    "  (" + Bytes.formatSpeed(speed) + ")"
            } else {
                progressBar.progress = 0
                tvProgress.text = Bytes.formatBytes(done)
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun runIo(block: suspend () -> Unit) {
        if (busy) {
            toast("Busy - wait for the current operation")
            return
        }
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                mainHandler.post { tvProgress.text = "Failed: ${e.message}" }
            } finally {
                busy = false
            }
        }
    }

    private fun closeTransport() {
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
        lafClient = null
        edlEngine = null
    }

    private fun refreshDeviceStatus() {
        val dev = device ?: usb.find().firstOrNull()
        device = dev
        tvDeviceStatus.text = if (dev == null) {
            getString(R.string.no_device)
        } else {
            val perm = if (usb.hasPermission(dev)) "permission granted" else "permission needed"
            "Detected: " + usb.describe(dev) + "\n" + perm
        }
    }

    private fun refreshRows() {
        when (val fw = firmware) {
            is Firmware.Dz -> adapter.showChunks(fw.dz)
            is Firmware.Raw -> adapter.showPartitions(partitions)
            null -> adapter.clear()
        }
    }

    // ---------------------------------------------------------------- actions

    private fun connectOrRequest() {
        val dev = device ?: usb.find().firstOrNull()
        if (dev == null) {
            toast("No LG / EDL device found")
            refreshDeviceStatus()
            return
        }
        device = dev
        refreshDeviceStatus()
        if (!usb.hasPermission(dev)) {
            log("Requesting USB permission for ${usb.describe(dev)} ...")
            usb.requestPermission(dev)
            return
        }
        connect()
    }

    private fun connect() = runIo {
        val dev = device ?: throw IllegalStateException("No device")
        closeTransport()
        val t = usb.open(dev)
        transport = t
        if (isLafMode()) {
            val client = LafClient(t, ::log)
            client.tryHello()
            client.kilo()
            lafClient = client
            val parts = LafFlashEngine(client, ::log).readPartitionTable()
            partitions = parts
            if (parts.isEmpty()) {
                log("No GPT found - partition names unavailable (raw image mode needs a partition name)")
            } else {
                log("Partition table: ${parts.size} entries (e.g. " +
                    parts.take(8).joinToString { it.name } + ")")
            }
            withContext(Dispatchers.Main) { refreshRows() }
        } else {
            val engine = EdlFlashEngine(t, ::log)
            edlEngine = engine
            engine.sahara.readHello()
            log("EDL device ready. Select a firehose programmer, then FLASH.")
        }
    }

    private fun onFirmwarePicked(uri: Uri) = runIo {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open the selected file")
        try {
            firmwarePfd?.close()
        } catch (_: Exception) {
        }
        firmwarePfd = pfd
        val channel = FileInputStream(pfd.fileDescriptor).channel
        firmwareChannel = channel
        val name = uri.lastPathSegment ?: "firmware"
        val fw = FirmwareDetect.detect(channel, name, ::log, { d, t -> setProgress(d, t) }, cacheDir)
        firmware = fw
        withContext(Dispatchers.Main) {
            when (fw) {
                is Firmware.Dz -> {
                    tvFirmwareInfo.text = fw.container + " - " + fw.dz.device + " - " +
                        fw.dz.version + " - " + fw.dz.chunks.size + " chunks"
                    log("Firmware loaded. Review the chunk list, then press FLASH.")
                }
                is Firmware.Raw -> {
                    tvFirmwareInfo.text = "Raw image: " + fw.fileName + " (" +
                        Bytes.formatBytes(fw.source.size()) + ")"
                    log("Raw image loaded. Connect in LAF mode to list partitions, select a target, then FLASH.")
                }
            }
            refreshRows()
        }
    }

    private fun onProgrammerPicked(uri: Uri) = runIo {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read the selected programmer")
        programmer = bytes
        withContext(Dispatchers.Main) {
            tvProgrammer.text = Bytes.formatBytes(bytes.size.toLong()) + " loaded"
        }
        log("Firehose programmer loaded (${bytes.size} bytes)")
    }

    private fun confirmThen(action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.warning_title)
            .setMessage(R.string.warning_body)
            .setPositiveButton(R.string.yes_i_understand) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onFlashClicked() {
        if (busy) {
            toast("Busy")
            return
        }
        val fw = firmware ?: run {
            toast("Select a firmware file first")
            return
        }
        val autoReboot = chkAutoReboot.isChecked
        if (isLafMode()) {
            val clientReady = lafClient != null
            if (!clientReady) {
                toast("Connect the device first")
                return
            }
            when (fw) {
                is Firmware.Dz -> {
                    val selected = adapter.checkedIds()
                    if (selected.isEmpty()) {
                        toast("Select at least one chunk")
                        return
                    }
                    val allowLow = chkAllowLowBlocks.isChecked
                    confirmThen { flashLafDz(fw, selected, allowLow, autoReboot) }
                }
                is Firmware.Raw -> {
                    val targetId = adapter.checkedIds().firstOrNull()
                    if (targetId == null) {
                        toast("Select the target partition")
                        return
                    }
                    val target = partitions.firstOrNull { it.index == targetId }
                    if (target == null) {
                        toast("Target partition unknown - reconnect the device")
                        return
                    }
                    confirmThen { flashLafRaw(fw, target.name, autoReboot) }
                }
            }
        } else {
            val prog = programmer ?: run {
                toast("Select a firehose programmer first")
                return
            }
            val lun = editLun.text.toString().toIntOrNull() ?: 0
            val start = editStartSector.text.toString().toLongOrNull() ?: 0L
            confirmThen { flashEdl(prog, fw, lun, start, autoReboot) }
        }
    }

    private fun flashLafDz(
        fw: Firmware.Dz,
        selected: Set<Int>,
        allowLowBlocks: Boolean,
        autoReboot: Boolean
    ) = runIo {
        val client = lafClient ?: throw IllegalStateException("Not connected")
        val engine = LafFlashEngine(client, ::log)
        mainHandler.post { tvProgress.text = "Flashing..." }
        engine.flashDzChunks(fw.source, fw.dz, selected, allowLowBlocks) { d, t ->
            setProgress(d, t)
        }
        log("FLASH COMPLETE - ${selected.size} chunks written and verified")
        if (autoReboot) {
            client.reboot()
            log("CTRL RSET sent - target reboots now")
        }
    }

    private fun flashLafRaw(fw: Firmware.Raw, partitionName: String, autoReboot: Boolean) = runIo {
        val client = lafClient ?: throw IllegalStateException("Not connected")
        LafFlashEngine(client, ::log).flashRawImage(fw.source, partitions, partitionName) { d, t ->
            setProgress(d, t)
        }
        log("FLASH COMPLETE - $partitionName written")
        if (autoReboot) {
            client.reboot()
            log("CTRL RSET sent - target reboots now")
        }
    }

    private fun flashEdl(
        prog: ByteArray,
        fw: Firmware,
        lun: Int,
        startSector: Long,
        autoReboot: Boolean
    ) = runIo {
        val t = transport ?: throw IllegalStateException("Not connected")
        val engine = edlEngine ?: EdlFlashEngine(t, ::log).also { edlEngine = it }
        log("Uploading firehose programmer via Sahara...")
        engine.uploadProgrammer(prog) { d, total -> setProgress(d, total) }
        if (!engine.configure()) {
            throw RuntimeException("Firehose configure failed - wrong or incompatible programmer?")
        }
        if (fw is Firmware.Raw) {
            log("Programming raw image to LUN $lun at sector $startSector (4096-byte sectors)")
            engine.programRaw(fw.source, lun, startSector) { d, total -> setProgress(d, total) }
            log("FLASH COMPLETE")
        } else {
            log("Programmer is live. To write an image in EDL mode, select a raw .img/.bin as the firmware file.")
        }
        if (autoReboot) {
            engine.powerReset()
            log("Firehose power reset sent")
        }
    }

    private fun onRebootClicked() = runIo {
        if (isLafMode()) {
            val client = lafClient ?: throw IllegalStateException("Not connected")
            client.reboot()
            log("CTRL RSET sent - target reboots now")
        } else {
            val engine = edlEngine ?: throw IllegalStateException("Not connected")
            if (engine.powerReset()) {
                log("Firehose power reset sent")
            } else {
                throw RuntimeException("Power reset was NAKed")
            }
        }
    }

    private fun onPowerOffClicked() = runIo {
        val client = lafClient ?: throw IllegalStateException("Connect in LAF mode first")
        client.powerOff()
        log("CTRL POFF sent - target powers off")
    }
}

/** Rows for the firmware chunk list (DZ mode) or the device partition list (raw mode). */
class ItemAdapter : RecyclerView.Adapter<ItemAdapter.VH>() {

    data class Row(
        val id: Int,
        val title: String,
        val sub: String,
        var checked: Boolean,
        val enabled: Boolean
    )

    private val rows = ArrayList<Row>()
    var singleSelect = false

    fun clear() {
        rows.clear()
        notifyDataSetChanged()
    }

    fun showChunks(dz: DzParser.DzFile) {
        singleSelect = false
        rows.clear()
        rows.addAll(
            dz.chunks.map {
                Row(
                    it.index,
                    it.sliceName + " / " + it.chunkName,
                    "block " + it.targetAddr + " - " + Bytes.formatBytes(it.targetSize),
                    true,
                    true
                )
            }
        )
        notifyDataSetChanged()
    }

    fun showPartitions(parts: List<GptPartition>) {
        singleSelect = true
        rows.clear()
        if (parts.isEmpty()) {
            rows.add(Row(-1, "(no partition table - connect the device in LAF mode)", "", false, false))
        } else {
            rows.addAll(
                parts.map {
                    Row(
                        it.index,
                        it.name,
                        "LBA " + it.firstLba + " - " + Bytes.formatBytes(it.sizeBytes),
                        false,
                        true
                    )
                }
            )
        }
        notifyDataSetChanged()
    }

    fun checkedIds(): Set<Int> = rows.filter { it.checked }.map { it.id }.toSet()

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        holder.title.text = r.title
        holder.sub.text = r.sub
        holder.check.isEnabled = r.enabled
        holder.check.isChecked = r.checked
        holder.check.setOnCheckedChangeListener(null)
        holder.itemView.setOnClickListener {
            if (r.enabled) {
                holder.check.toggle()
            }
        }
        holder.check.setOnCheckedChangeListener { _, checked ->
            if (singleSelect && checked) {
                for (i in rows.indices) {
                    rows[i].checked = i == position
                }
                notifyDataSetChanged()
            } else {
                r.checked = checked
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.itemCheck)
        val title: TextView = v.findViewById(R.id.itemTitle)
        val sub: TextView = v.findViewById(R.id.itemSub)
    }
}
