package uk.co.r99companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var scanButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var shareButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var heartButton: MaterialButton
    private lateinit var oxygenButton: MaterialButton
    private lateinit var readAllButton: MaterialButton
    private lateinit var commandInput: EditText
    private lateinit var frameCheck: CheckBox
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView

    /** Every characteristic the ring will accept a write on, keyed by a short label. */
    private val writable = linkedMapOf<String, BluetoothGattCharacteristic>()
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val candidates = linkedMapOf<String, ScanResult>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)
    private val sessionClock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)

    /** The on-screen log is mirrored here so a restart cannot lose a capture. */
    private val logFile: File by lazy { File(filesDir, "protocol-log.txt") }

    /**
     * A GATT connection carries one request at a time; a second read issued before the first
     * reports back is simply dropped by the radio. Every request therefore queues here, and the
     * matching callback releases the next one. A step returns true once the radio has accepted
     * it, and false when there is nothing to wait for.
     */
    private val steps = ArrayDeque<() -> Boolean>()
    private var stepRunning = false
    private var currentStep = 0

    private var afterPermission: (() -> Unit)? = null

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = afterPermission
        afterPermission = null
        if (result.values.all { it }) granted?.invoke()
        else showStatus("Bluetooth permission is needed to reach the ring.")
    }

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        insetTheLayoutBelowTheSystemBars()
        scanButton = findViewById(R.id.scanButton)
        shareButton = findViewById(R.id.shareButton)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        connectButton = findViewById(R.id.connectButton)
        sendButton = findViewById(R.id.sendButton)
        commandInput = findViewById(R.id.commandInput)
        frameCheck = findViewById(R.id.frameCheck)
        heartButton = findViewById(R.id.heartButton)
        oxygenButton = findViewById(R.id.oxygenButton)
        readAllButton = findViewById(R.id.readAllButton)
        readAllButton.setOnClickListener { readEverything() }
        heartButton.setOnClickListener { measure(0x00, "heart rate") }
        oxygenButton.setOnClickListener { measure(0x02, "SpO2") }
        sendButton.setOnClickListener { sendCommand() }
        scanButton.setOnClickListener { requestBluetoothThen { startScan() } }
        connectButton.setOnClickListener { requestBluetoothThen { connectToKnownRing() } }
        shareButton.setOnClickListener { shareLog() }
        // Restore earlier captures: a restart must never destroy evidence already gathered.
        log.text = runCatching { logFile.readText() }.getOrDefault("")
        append("\n=== session ${sessionClock.format(Date())} ===\n")
        append("This app does not send health data anywhere.\n")
    }

    /**
     * Android 15 and newer draw every app edge to edge, which puts the title under the status
     * bar clock and the log under the gesture bar. Keep the layout's own padding and add the
     * system bar insets on top of it.
     */
    private fun insetTheLayoutBelowTheSystemBars() {
        val root = findViewById<View>(R.id.root)
        val start = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                start[0] + bars.left,
                start[1] + bars.top,
                start[2] + bars.right,
                start[3] + bars.bottom
            )
            insets
        }
    }

    private fun requestBluetoothThen(action: () -> Unit) {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) action()
        else {
            afterPermission = action
            permissions.launch(needed)
        }
    }

    /**
     * A ring already paired to the phone has stopped advertising, so it never appears in a
     * scan. Going straight to its address is the only way to reach it.
     */
    @SuppressLint("MissingPermission")
    private fun connectToKnownRing() {
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) { showStatus("Turn Bluetooth on, then try again."); return }
        stopScan()
        val device = runCatching { bluetooth.getRemoteDevice(RING_ADDRESS) }.getOrNull()
        if (device == null) { showStatus("$RING_ADDRESS is not a usable Bluetooth address."); return }
        connect(device, device.name ?: "saved ring")
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) { showStatus("Turn Bluetooth on, then try again."); return }
        if (scanning) return
        scanning = true
        candidates.clear()
        scanButton.isEnabled = false
        showStatus("Scanning for nearby Bluetooth rings…")
        append("Scan started\n")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetooth.bluetoothLeScanner.startScan(null, settings, scanner)
        handler.postDelayed({ finishScan() }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(message: String? = null) {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanner)
        scanButton.isEnabled = true
        message?.let(::showStatus)
    }

    private fun finishScan() {
        if (!scanning) return
        stopScan()
        if (candidates.isEmpty()) {
            showStatus("No ring found. Wake or charge it, then retry.")
            return
        }
        // Closest radio first, because the ring is the one on your hand.
        val found = candidates.values.sortedByDescending { it.rssi }
        val items = found.map { "${nameOf(it)} • ${it.device.address} • ${it.rssi} dBm" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose your R99 ring")
            .setItems(items) { _, which -> connect(found[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val scanner = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // A device re-advertises several times a second, so only its first sighting is logged.
            val firstSighting = !candidates.containsKey(result.device.address)
            candidates[result.device.address] = result
            if (firstSighting) append("Found ${nameOf(result)} (${result.device.address}), ${describeAdvert(result)}\n")
        }
        override fun onScanFailed(errorCode: Int) { stopScan("Scan failed (code $errorCode). Restart Bluetooth and try again.") }
    }

    @SuppressLint("MissingPermission")
    private fun nameOf(result: ScanResult) =
        result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"

    /**
     * Most nearby devices advertise no name at all, so the advertised service UUIDs and
     * manufacturer id are what actually tell a ring apart from a neighbour's earbuds.
     */
    private fun describeAdvert(result: ScanResult): String {
        val record = result.scanRecord
        val services = record?.serviceUuids.orEmpty().joinToString(" ") { it.uuid.toString() }
        val manufacturers = record?.manufacturerSpecificData?.let { data ->
            (0 until data.size()).joinToString(" ") { "0x%04X".format(data.keyAt(it)) }
        }.orEmpty()
        return buildList {
            add("${result.rssi} dBm")
            add(if (result.isConnectable) "connectable" else "not-connectable")
            if (services.isNotEmpty()) add("services=[$services]")
            if (manufacturers.isNotEmpty()) add("mfr=[$manufacturers]")
        }.joinToString(", ")
    }

    @SuppressLint("MissingPermission")
    private fun connect(result: ScanResult) = connect(result.device, nameOf(result))

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, label: String) {
        showStatus("Connecting to $label…")
        append("Connecting to $label (${device.address})\n")
        gatt?.close()
        clearSteps()
        writable.clear()
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun enqueue(step: () -> Boolean) {
        steps.addLast(step)
        if (!stepRunning) runNextStep()
    }

    private fun runNextStep() {
        val step = steps.removeFirstOrNull()
        if (step == null) { stepRunning = false; return }
        stepRunning = true
        val mine = ++currentStep
        // A request the radio refuses outright gets no callback, so move on rather than stall.
        if (!step()) { handler.post { finishStep(mine) }; return }
        // A characteristic that accepts a request but never answers must not wedge the queue
        // behind it: everything after would look sent while never leaving the phone.
        handler.postDelayed({
            if (mine == currentStep) {
                append("  (no answer within ${STEP_TIMEOUT_MS / 1000}s, moving on)\n")
                finishStep(mine)
            }
        }, STEP_TIMEOUT_MS)
    }

    private fun finishStep(which: Int) {
        if (which != currentStep) return
        runNextStep()
    }

    private fun stepComplete() { handler.post { finishStep(currentStep) } }

    private fun clearSteps() { steps.clear(); stepRunning = false; currentStep++ }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, state: Int) {
            runOnUiThread {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    showStatus("Connected. Reading ring Bluetooth services…")
                    append("Connected; discovering services\n")
                    gatt.discoverServices()
                } else {
                    clearSteps()
                    showStatus("Ring disconnected.")
                    append("Disconnected (status $statusCode)\n")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            runOnUiThread {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) { showStatus("Could not read services ($statusCode)."); return@runOnUiThread }
                showStatus("Connected. Reading every characteristic…")
                // Ask for room to see whole packets; rings often exceed the 23-byte default.
                enqueue { gatt.requestMtu(517) }
                gatt.services.forEach { service ->
                    append("SERVICE ${service.uuid}\n")
                    service.characteristics.forEach { characteristic ->
                        val bits = characteristic.properties
                        append("  CHAR ${characteristic.uuid} props=$bits (${describe(bits)})\n")
                        if (bits and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                            writable[shortUuid(characteristic.uuid)] = characteristic
                        }
                        if (bits and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                            enqueue { gatt.readCharacteristic(characteristic) }
                        }
                        // The ring pushes its live readings; without this the log stays static.
                        if (bits and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            enqueue { subscribe(gatt, characteristic) }
                        }
                    }
                }
                enqueue {
                    showStatus("Service map captured. Keep the ring on, then share the log.")
                    append("Listening for live notifications…\n")
                    false
                }
            }
        }

        @Deprecated("Use the byte-array overload on Android 13+")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: byteArrayOf()
            runOnUiThread { append("READ ${characteristic.uuid}: ${value.toHex()} (status $statusCode)\n") }
            stepComplete()
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            runOnUiThread { append("READ ${characteristic.uuid}: ${value.toHex()} (status $statusCode)\n") }
            stepComplete()
        }

        @Deprecated("Use the byte-array overload on Android 13+")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: byteArrayOf()
            runOnUiThread { logNotification(characteristic, value) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            runOnUiThread { logNotification(characteristic, value) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            runOnUiThread { append("  SUBSCRIBED ${descriptor.characteristic.uuid} (status $statusCode)\n") }
            stepComplete()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            runOnUiThread { append("  WRITE-ACK ${shortUuid(characteristic.uuid)} (status $statusCode)\n") }
            stepComplete()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, statusCode: Int) {
            runOnUiThread { append("MTU now $mtu bytes (status $statusCode)\n") }
            stepComplete()
        }
    }

    /** Turns on notifications locally, then tells the ring to start sending them. */
    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG) ?: return false
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") descriptor.value = value
            @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
        }
    }

    /**
     * The ring answers nothing until it is asked. This writes a hand-typed frame to whichever
     * channel is chosen; any reply arrives on the notify characteristics already subscribed.
     */
    @SuppressLint("MissingPermission")
    private fun sendCommand() {
        val active = gatt
        if (active == null || writable.isEmpty()) { showStatus("Connect to the ring first."); return }
        val payload = parseHex(commandInput.text.toString())
        if (payload == null) { showStatus("Enter an even number of hex digits, such as 03."); return }
        val bytes = if (frameCheck.isChecked) asFrame(payload) else payload
        val targets = writable.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Send ${bytes.toHex()} to")
            .setItems(targets) { _, which ->
                val characteristic = writable.getValue(targets[which])
                enqueue {
                    // Logged here, at the moment it actually leaves the phone — never at queue
                    // time, or the log would claim writes that never happened.
                    val sent = write(active, characteristic, bytes)
                    append("\n${clock.format(Date())} WRITE ${shortUuid(characteristic.uuid)}: " +
                        "${bytes.toHex()}${if (sent) "" else "  [REFUSED by radio]"}\n")
                    sent
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") characteristic.value = bytes
            characteristic.writeType = type
            @Suppress("DEPRECATION") gatt.writeCharacteristic(characteristic)
        }
    }

    private fun logNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val reading = if (characteristic.uuid == HEART_RATE) decodeHeartRate(value) else null
        append("${clock.format(Date())} NOTIFY ${shortUuid(characteristic.uuid)}: ${value.toHex()}" +
            (reading?.let { "   -> $it" } ?: "") + "\n")
    }

    /**
     * 0x2A37 is a Bluetooth SIG standard, not this vendor's invention, so it can be decoded
     * outright. Bit 0 of the flags picks the value width; bits 1 and 2 report skin contact.
     */
    private fun decodeHeartRate(value: ByteArray): String? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 != 0) {
            if (value.size < 3) return null
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else {
            if (value.size < 2) return null
            value[1].toInt() and 0xFF
        }
        val contact = when {
            flags and 0x04 == 0 -> "contact sensing unsupported"
            flags and 0x02 != 0 -> "on the finger"
            else -> "NOT on the finger"
        }
        return "$bpm bpm, $contact"
    }

    private fun parseHex(text: String): ByteArray? {
        val digits = text.replace(Regex("[^0-9A-Fa-f]"), "")
        if (digits.isEmpty() || digits.length % 2 != 0) return null
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * The ring's own frame, recovered from a capture of the vendor app:
     *
     *     group | command | total length (16-bit LE) | payload | CRC (16-bit LE)
     *
     * Input is typed as group, command, then payload; the length and CRC are computed here.
     * A frame whose length field is wrong is not merely ignored — the firmware drops the link.
     */
    private fun asFrame(bytes: ByteArray): ByteArray {
        if (bytes.size < 2) return bytes
        val total = 4 + (bytes.size - 2) + 2
        val head = byteArrayOf(bytes[0], bytes[1], total.toByte(), (total shr 8).toByte()) +
            bytes.copyOfRange(2, bytes.size)
        val sum = crc16(head)
        return head + byteArrayOf(sum.toByte(), (sum shr 8).toByte())
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial value 0xFFFF, no reflection. */
    private fun crc16(data: ByteArray): Int {
        var reg = 0xFFFF
        for (byte in data) {
            reg = reg xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                reg = if (reg and 0x8000 != 0) ((reg shl 1) xor 0x1021) and 0xFFFF else (reg shl 1) and 0xFFFF
            }
        }
        return reg
    }

    /**
     * Every read-only query the ring understands, named from the vendor's own SDK table (see
     * COMMANDS.md). Deliberately excludes the settings, delete and OTA groups: those change or
     * erase the ring rather than reporting on it.
     *
     * The two payloads below are the literal bytes the vendor app sends — "GC" and "GF".
     */
    private val readOnlyQueries = listOf(
        Triple(0x02, 0x00, byteArrayOf(0x47, 0x43)) to "GetDeviceInfo",
        Triple(0x02, 0x01, byteArrayOf(0x47, 0x46)) to "GetDeviceSupportFunction",
        Triple(0x02, 0x0C, byteArrayOf()) to "GetNowStep",
        Triple(0x02, 0x11, byteArrayOf()) to "GetRealBloodOxygen",
        Triple(0x02, 0x25, byteArrayOf()) to "GetPowerStatistics",
        Triple(0x02, 0x26, byteArrayOf()) to "GetSleepStatus",
        Triple(0x02, 0x28, byteArrayOf()) to "GetMeasurementFunction",
        Triple(0x02, 0x32, byteArrayOf()) to "GetRingSizeAndColor",
        Triple(0x05, 0x02, byteArrayOf()) to "Health_HistorySport",
        Triple(0x05, 0x04, byteArrayOf()) to "Health_HistorySleep",
        Triple(0x05, 0x06, byteArrayOf()) to "Health_HistoryHeart",
        Triple(0x05, 0x1A, byteArrayOf()) to "Health_HistoryBloodOxygen",
    )

    private fun readEverything() {
        val active = gatt
        val channel = writable[COMMAND_CHANNEL]
        if (active == null || channel == null) { showStatus("Connect to the ring first."); return }
        append("\n=== reading every safe query ===\n")
        readOnlyQueries.forEach { (spec, name) ->
            val (group, command, payload) = spec
            val bytes = asFrame(byteArrayOf(group.toByte(), command.toByte()) + payload)
            enqueue {
                val sent = write(active, channel, bytes)
                append("\n${clock.format(Date())} ASK $name: ${bytes.toHex()}" +
                    "${if (sent) "" else "  [REFUSED by radio]"}\n")
                sent
            }
        }
        showStatus("Asking the ring for everything it will report…")
    }

    /** Asks the ring to take a live reading; the result arrives on the standard 0x2A37. */
    private fun measure(type: Byte, label: String) {
        val active = gatt
        val channel = writable[COMMAND_CHANNEL]
        if (active == null || channel == null) { showStatus("Connect to the ring first."); return }
        val bytes = asFrame(byteArrayOf(0x03, 0x2F, 0x01, type))
        enqueue {
            val sent = write(active, channel, bytes)
            append("\n${clock.format(Date())} START $label: ${bytes.toHex()}${if (sent) "" else "  [REFUSED by radio]"}\n")
            sent
        }
        showStatus("Asked the ring to measure $label. Keep it on and stay still…")
    }

    /** 0000ae01-0000-…-9b34fb prints as ae01; anything bespoke keeps its leading block. */
    private fun shortUuid(uuid: UUID): String {
        val text = uuid.toString()
        return if (text.endsWith("-0000-1000-8000-00805f9b34fb")) text.substring(4, 8) else text.substring(0, 8)
    }

    private fun describe(bits: Int) = buildList {
        if (bits and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-no-response")
        if (bits and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (bits and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString(", ").ifEmpty { "none" }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    private fun showStatus(text: String) { status.text = text }
    private fun append(text: String) {
        log.append(text)
        runCatching { logFile.appendText(text) }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun shareLog() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "R99 Companion Bluetooth protocol log")
            putExtra(Intent.EXTRA_TEXT, log.text.toString())
        }, "Share protocol log"))
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        stopScan()
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        super.onDestroy()
    }

    private companion object {
        val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** The paired R99. Change this if the app is pointed at a different ring. */
        const val RING_ADDRESS = "07:35:00:04:8D:43"

        const val STEP_TIMEOUT_MS = 5_000L

        val HEART_RATE: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        /** be940001: the channel the vendor app drives the ring through. */
        const val COMMAND_CHANNEL = "be940001"
    }
}
