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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.Calendar
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
    private lateinit var pressureButton: MaterialButton
    private lateinit var readAllButton: MaterialButton
    private lateinit var deviceLogButton: MaterialButton
    private lateinit var capabilityButton: MaterialButton
    private lateinit var firmwareButton: MaterialButton
    private lateinit var browseButton: MaterialButton
    private lateinit var expandButton: MaterialButton
    private lateinit var goalButton: MaterialButton
    private lateinit var aboutYouButton: MaterialButton
    private lateinit var valueFirmware: TextView
    private lateinit var commandInput: EditText
    private lateinit var frameCheck: CheckBox
    private lateinit var chooseChannelCheck: CheckBox
    private lateinit var monitorButton: MaterialButton
    private lateinit var photoButton: MaterialButton
    private lateinit var timeButton: MaterialButton
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var valueHeart: TextView
    private lateinit var valueOxygen: TextView
    private lateinit var valuePressure: TextView
    private lateinit var valueSteps: TextView
    private lateinit var valueBattery: TextView
    private lateinit var valueLink: TextView
    private var linkMtu = 0

    private lateinit var pauseButton: MaterialButton
    private lateinit var quietCheck: CheckBox
    private lateinit var hexCheck: CheckBox

    private var monitoring = false
    private var shutterMode = false
    private var paused = false
    private val held = StringBuilder()
    private var heldLines = 0

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

    /**
     * Lets the app be driven over adb instead of by tapping, which is far quicker when working
     * through a protocol:
     *
     *   adb shell am broadcast -a uk.co.r99companion.RUN --es do connect
     *   adb shell am broadcast -a uk.co.r99companion.RUN --es hex 020C
     *
     * Registered at runtime and exported, so any app on the phone could also send to it. That is
     * an acceptable trade in a debug tool that only ever talks to a ring, but it is the reason
     * this belongs in the debugger and not in Vitals.
     */
    private val overAdb = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val hex = intent?.getStringExtra("hex")
            val instruction = intent?.getStringExtra("do")
            runOnUiThread {
                when {
                    instruction == "connect" -> requestBluetoothThen { connectToKnownRing() }
                    instruction == "readall" -> readEverything()
                    instruction == "log" -> send(byteArrayOf(0x02, 0x08), "device log")
                    instruction == "info" -> send(byteArrayOf(0x02, 0x00, 0x47, 0x43), "device info")
                    instruction == "caps" -> send(byteArrayOf(0x02, 0x01, 0x47, 0x46), "capabilities")
                    instruction == "clock" -> setRingClock()
                    hex != null -> parseHex(hex)?.let { bytes ->
                        val named = ALL_COMMANDS.firstOrNull {
                            bytes.size >= 2 && it.group == (bytes[0].toInt() and 0xFF) &&
                                it.command == (bytes[1].toInt() and 0xFF)
                        }?.name ?: "raw $hex"
                        dispatch(bytes, named)
                    } ?: showStatus("adb: \"$hex\" is not valid hex")
                }
            }
        }
    }

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
        chooseChannelCheck = findViewById(R.id.chooseChannelCheck)
        quietCheck = findViewById(R.id.quietCheck)
        hexCheck = findViewById(R.id.hexCheck)
        pauseButton = findViewById(R.id.pauseButton)
        pauseButton.setOnClickListener { setPaused(!paused) }
        heartButton = findViewById(R.id.heartButton)
        oxygenButton = findViewById(R.id.oxygenButton)
        readAllButton = findViewById(R.id.readAllButton)
        readAllButton.setOnClickListener { readEverything() }
        browseButton = findViewById(R.id.browseButton)
        browseButton.setOnClickListener { browseCommands() }
        expandButton = findViewById(R.id.expandButton)
        expandButton.setOnClickListener { showText("Bluetooth protocol log", log.text.toString()) }
        goalButton = findViewById(R.id.goalButton)
        goalButton.setOnClickListener { setStepGoal() }
        aboutYouButton = findViewById(R.id.aboutYouButton)
        aboutYouButton.setOnClickListener { setUserInfo() }
        valueFirmware = findViewById(R.id.valueFirmware)
        firmwareButton = findViewById(R.id.firmwareButton)
        firmwareButton.setOnClickListener {
            send(byteArrayOf(0x02, 0x00, 0x47, 0x43), "firmware and hardware info")
        }
        capabilityButton = findViewById(R.id.capabilityButton)
        capabilityButton.setOnClickListener {
            send(byteArrayOf(0x02, 0x01, 0x47, 0x46), "what this ring supports")
        }
        deviceLogButton = findViewById(R.id.deviceLogButton)
        deviceLogButton.setOnClickListener { send(byteArrayOf(0x02, 0x08), "device log request") }
        pressureButton = findViewById(R.id.pressureButton)
        monitorButton = findViewById(R.id.monitorButton)
        photoButton = findViewById(R.id.photoButton)
        timeButton = findViewById(R.id.timeButton)
        valueHeart = findViewById(R.id.valueHeart)
        valueOxygen = findViewById(R.id.valueOxygen)
        valuePressure = findViewById(R.id.valuePressure)
        valueSteps = findViewById(R.id.valueSteps)
        valueBattery = findViewById(R.id.valueBattery)
        valueLink = findViewById(R.id.valueLink)
        monitorButton.setOnClickListener { setAutomaticMonitoring(!monitoring) }
        photoButton.setOnClickListener { setShutterMode(!shutterMode) }
        timeButton.setOnClickListener { setRingClock() }
        heartButton.setOnClickListener { measure(0x00, "heart rate") }
        oxygenButton.setOnClickListener { measure(0x02, "blood oxygen") }
        pressureButton.setOnClickListener { measure(0x01, "blood pressure") }
        sendButton.setOnClickListener { sendCommand() }
        scanButton.setOnClickListener { requestBluetoothThen { startScan() } }
        connectButton.setOnClickListener { requestBluetoothThen { connectToKnownRing() } }
        shareButton.setOnClickListener { shareLog() }
        // Restore earlier captures: a restart must never destroy evidence already gathered.
        // The file grows without limit; only the recent tail is worth putting on screen.
        log.text = runCatching { logFile.readText().takeLast(20_000) }.getOrDefault("")
        append("\n=== session ${sessionClock.format(Date())} ===\n")
        append("This app does not send health data anywhere.\n")
        ContextCompat.registerReceiver(
            this, overAdb, IntentFilter("uk.co.r99companion.RUN"), ContextCompat.RECEIVER_EXPORTED
        )
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
                    valueLink.text = "Link — connected to $RING_ADDRESS"
                    showStatus("Connected. Reading ring Bluetooth services…")
                    append("Connected; discovering services\n")
                    gatt.discoverServices()
                } else {
                    clearSteps()
                    linkMtu = 0
                    valueLink.text = "Link — disconnected (status $statusCode)"
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
                // Deliberately NOT setting the clock here. The ring abandons a running sleep
                // session when its clock moves ("exit sleep because time change" in its own
                // log), so setting it on every connection destroys sleep tracking. Use the
                // button, when awake and not wearing it.
            }
        }

        @Deprecated("Use the byte-array overload on Android 13+")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: byteArrayOf()
            runOnUiThread { append("READ ${shortUuid(characteristic.uuid)}: ${value.toHex()} (status $statusCode)\n") }
            stepComplete()
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            runOnUiThread { append("READ ${shortUuid(characteristic.uuid)}: ${value.toHex()} (status $statusCode)\n") }
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
            runOnUiThread { append("  SUBSCRIBED ${shortUuid(descriptor.characteristic.uuid)} (status $statusCode)\n") }
            stepComplete()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            runOnUiThread { append("  WRITE-ACK ${shortUuid(characteristic.uuid)} (status $statusCode)\n") }
            stepComplete()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, statusCode: Int) {
            runOnUiThread {
                linkMtu = mtu
                valueLink.text = "Link — connected to $RING_ADDRESS, MTU $mtu"
                append("MTU now $mtu bytes (status $statusCode)\n")
            }
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
        if (active == null || writable.isEmpty()) { reportNotConnected("That command"); return }
        val payload = parseHex(commandInput.text.toString())
        if (payload == null) { showStatus("Enter an even number of hex digits, such as 03."); return }
        val bytes = if (frameCheck.isChecked) asFrame(payload) else payload
        if (payload.size >= 2) {
            val name = ALL_COMMANDS.firstOrNull {
                it.group == (payload[0].toInt() and 0xFF) && it.command == (payload[1].toInt() and 0xFF)
            }?.name ?: "raw command"
            awaiting = Triple(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF, name)
        }
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

    /**
     * Stored records open with a uint32 little endian count of seconds since 2000-01-01, not the
     * Unix epoch. Confirmed against a record written while this app was watching.
     */
    private fun stamp(record: List<Byte>): String {
        var seconds = 0L
        for (i in 3 downTo 0) seconds = (seconds shl 8) or (record[i].toLong() and 0xFF)
        val epoch2000 = 946_684_800_000L
        return sessionClock.format(Date(epoch2000 + seconds * 1000L))
    }

    private fun readable(bytes: List<Byte>) =
        bytes.map { it.toInt() and 0xFF }.filter { it in 32..126 }.map { it.toChar() }.joinToString("").trim()

    private val deviceLog = StringBuilder()

    /**
     * The device log arrives as a run of frames rather than one reply: byte 0 of each payload is
     * 0x01 to open, 0x02 to continue and 0xFF to close. Showing the first frame on its own gives
     * only the chunk marker, so the run is gathered and shown once it ends.
     */
    private fun collectDeviceLog(value: ByteArray): Boolean {
        if (value.size < 6) return false
        if ((value[0].toInt() and 0xFF) != 0x02 || (value[1].toInt() and 0xFF) != 0x08) return false
        val payload = value.copyOfRange(4, value.size - 2)
        if (payload.isEmpty()) return true
        val marker = payload[0].toInt() and 0xFF
        if (marker == 0x01) deviceLog.setLength(0)
        readable(payload.drop(1)).let { if (it.isNotEmpty()) deviceLog.append(it).append("\n") }
        if (marker == 0xFF || marker == 0x01 && payload.size <= 2) {
            if (deviceLog.isNotEmpty()) showDeviceLog(formatDeviceLog(deviceLog.toString()))
            awaiting = null
        }
        return true
    }

    /**
     * The firmware packs several entries into each frame and repeats its version on every one,
     * which reads as one unbroken block. Split it back into an entry per line, grouped by day,
     * with the repeated version prefix dropped.
     */
    private fun formatDeviceLog(raw: String): String {
        val entry = Regex(
            """Log\s+(\d+):\s*<([\d-]+)\s+([\d:]+)>\s*(?:\[[^\]]*])?\s*(.*?)(?=Log\s+\d+:|$)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val out = StringBuilder()
        var day = ""
        for (match in entry.findAll(raw)) {
            val (number, date, time, message) = match.destructured
            if (date != day) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(date).append('\n')
                day = date
            }
            out.append(time).append("  ")
                .append(message.trim().replace(Regex("\\s+"), " "))
                .append("   #").append(number).append('\n')
        }
        return if (out.isEmpty()) raw else out.toString().trimEnd()
    }

    private fun showDeviceLog(text: String) = showText("The ring's internal log", text)

    /** One readable, selectable, shareable full-height text view, used for anything long. */
    private fun showText(title: String, text: String) {
        val view = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(36, 24, 36, 24)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Share"))
            }
            .show()
        // Long output is nearly always read from the end.
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Asks for a whole number, then hands it to the caller. */
    private fun askForNumber(title: String, hint: String, current: Int, onValue: (Int) -> Unit) {
        val field = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setHint(hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(field)
            .setPositiveButton("Send to ring") { _, _ ->
                field.text.toString().trim().toIntOrNull()?.let(onValue)
                    ?: showStatus("That was not a whole number.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** settingGoal: a type byte, the goal as uint32 little endian, then two trailing bytes. */
    private fun setStepGoal() = askForNumber("Daily step goal", "steps", 10_000) { goal ->
        send(
            byteArrayOf(
                0x01, 0x02, 0x00,
                goal.toByte(), (goal shr 8).toByte(), (goal shr 16).toByte(), (goal shr 24).toByte(),
                0x00, 0x00
            ),
            "step goal of $goal"
        )
    }

    /**
     * settingUserInfo takes four bytes. The SDK gives their order no names, so each is asked for
     * plainly and the frame is shown before it is sent rather than dressed up as certainty.
     */
    private fun setUserInfo() {
        val fields = listOf("Height in cm" to 175, "Weight in kg" to 75, "Age in years" to 40, "Sex, 0 or 1" to 1)
        val values = fields.map { it.second }.toIntArray()
        fun ask(index: Int) {
            if (index == fields.size) {
                send(
                    byteArrayOf(0x01, 0x03) + values.map { it.toByte() }.toByteArray(),
                    "your details (${values.joinToString(", ")})"
                )
                return
            }
            askForNumber(fields[index].first, "", values[index]) { entered ->
                values[index] = entered and 0xFF
                ask(index + 1)
            }
        }
        ask(0)
    }

    private fun logNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val reading = if (characteristic.uuid == HEART_RATE) decodeHeartRate(value) else decodeFrame(value)
        val label = shortUuid(characteristic.uuid)
        val time = clock.format(Date())
        val fileText = "$time NOTIFY $label: ${value.toHex()}" + (reading?.let { "   -> $it" } ?: "") + "\n"
        // On screen, lead with the meaning; the hex is optional and the file keeps it regardless.
        val screenText = when {
            reading != null && !hexCheck.isChecked -> "$time  $reading\n"
            reading != null -> "$time  $reading\n           ${value.toHex()}\n"
            else -> "$time  $label: ${value.toHex()}\n"
        }
        // fea1 and 2a37 repeat every second or two and say nothing the panel does not show.
        val routine = label == "fea1" || label == "2a37"
        record(fileText, screenText, routine)
        updateReadings(characteristic, value)
        if (collectDeviceLog(value)) return
        val expected = awaiting
        if (expected != null && value.size >= 6 &&
            (value[0].toInt() and 0xFF) == expected.first &&
            (value[1].toInt() and 0xFF) == expected.second
        ) {
            awaiting = null
            showReply(expected.third, value)
        }
    }

    /**
     * Mirrors whatever the ring reports into the readings panel. Every field here was confirmed
     * against the hardware or read out of the vendor SDK's own parser; see PROTOCOL.md.
     */
    private fun updateReadings(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        fun at(source: ByteArray, i: Int) = source[i].toInt() and 0xFF
        // fea1 is a bare 10-byte push with no frame around it.
        if (shortUuid(characteristic.uuid) == "fea1" && value.size >= 9) {
            val steps = at(value, 1) or (at(value, 2) shl 8) or (at(value, 3) shl 16)
            val distance = at(value, 4) or (at(value, 5) shl 8) or (at(value, 6) shl 16)
            val calories = at(value, 7) or (at(value, 8) shl 8)
            valueSteps.text = "Steps — $steps, distance $distance, calories $calories"
            return
        }
        if (value.size < 6) return
        val group = at(value, 0)
        val command = at(value, 1)
        val payload = value.copyOfRange(4, value.size - 2)
        fun byte(i: Int) = at(payload, i)
        when {
            group == 0x06 && command == 0x01 && payload.isNotEmpty() ->
                valueHeart.text = "Heart rate — ${byte(0)} bpm"
            group == 0x06 && command == 0x02 && payload.isNotEmpty() ->
                valueOxygen.text = "Blood oxygen — ${byte(0)}%"
            group == 0x06 && command == 0x03 && payload.size >= 2 ->
                valuePressure.text = "Blood pressure — ${byte(0)}/${byte(1)} (estimated)"
            // GetDeviceInfo. The SDK reads the version as main.sub and the battery at [4]/[5];
            // the version agrees with the [V2.32] the ring stamps on its own log lines.
            group == 0x02 && command == 0x00 && payload.size >= 6 -> {
                valueBattery.text = "Battery — ${byte(5)}%" +
                    if (byte(4) != 0) " (charging)" else ""
                valueFirmware.text = "Firmware — V${byte(3)}.${byte(2)}, device id ${byte(0)}"
            }
            group == 0x02 && command == 0x0C && payload.size >= 8 -> {
                val steps = byte(0) or (byte(1) shl 8) or (byte(2) shl 16)
                val calories = byte(3) or (byte(4) shl 8)
                val distance = byte(5) or (byte(6) shl 8) or (byte(7) shl 16)
                valueSteps.text = "Steps — $steps, distance $distance, calories $calories"
            }
        }
    }

    /**
     * Turns on the ring's own periodic sampling. Without this the ring measures only when asked,
     * which is why its stored history comes back empty.
     */
    private fun setAutomaticMonitoring(on: Boolean) {
        val flag = if (on) 0x01.toByte() else 0x00.toByte()
        val minutes = 0x05.toByte()
        if (!send(byteArrayOf(0x01, 0x0C, flag, minutes), "heart-rate monitoring")) return
        send(byteArrayOf(0x01, 0x26, flag, minutes), "blood-oxygen monitoring")
        monitoring = on
        monitorButton.text = "Automatic monitoring: ${if (on) "on, every 5 min" else "off"}"
    }

    private fun setShutterMode(on: Boolean) {
        if (!send(byteArrayOf(0x03, 0x0E, if (on) 0x01 else 0x00), "shutter mode")) return
        shutterMode = on
        photoButton.text = "Shutter mode: ${if (on) "on — shake the ring" else "off"}"
    }

    /** The ring keeps its own clock, and its history is stamped with it. */
    private fun setRingClock() {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        send(byteArrayOf(
            0x01, 0x00,
            year.toByte(), (year shr 8).toByte(),
            (now.get(Calendar.MONTH) + 1).toByte(),
            now.get(Calendar.DAY_OF_MONTH).toByte(),
            now.get(Calendar.HOUR_OF_DAY).toByte(),
            now.get(Calendar.MINUTE).toByte(),
            now.get(Calendar.SECOND).toByte(),
            0x00
        ), "clock")
    }

    /**
     * The command whose reply should be shown in a dialog rather than only appended to the log.
     * The ring echoes group and command, so the reply is matched on those.
     */
    private var awaiting: Triple<Int, Int, String>? = null

    /**
     * A command with no connection behind it used to fail into the status line, far up the page
     * from the buttons, so it looked as though nothing had happened at all.
     */
    private fun reportNotConnected(label: String) {
        showStatus("Not connected — tap \"Connect straight to my ring\" first.")
        AlertDialog.Builder(this)
            .setTitle("Not connected")
            .setMessage("$label was not sent.\n\nTap \"Connect straight to my ring\" at the top, " +
                "wait for the log to say it is listening, then try again.")
            .setPositiveButton("Connect now") { _, _ -> requestBluetoothThen { connectToKnownRing() } }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Renders the capability bitmap as two plain lists rather than 60 bytes of hex. */
    private fun describeCapabilities(payload: ByteArray): String {
        fun set(c: Capability) =
            c.index < payload.size && ((payload[c.index].toInt() and 0xFF) shr c.bit) and 1 == 1
        val yes = CAPABILITIES.filter(::set).map { it.label }
        val no = CAPABILITIES.filterNot(::set).map { it.label }
        return buildString {
            append("This ring implements ${yes.size} of ${CAPABILITIES.size} features.\n\n")
            append("PRESENT\n")
            yes.forEach { append("  ").append(it).append('\n') }
            append("\nABSENT — the firmware has no such feature, so no amount of\n")
            append("protocol work will produce this data.\n\n")
            no.chunked(3).forEach { append("  ").append(it.joinToString(", ")).append('\n') }
        }
    }

    private fun showReply(label: String, value: ByteArray) {
        val payload = value.copyOfRange(4, value.size - 2)
        val bytes = payload.joinToString(" ") { "%02X".format(it) }.ifEmpty { "(no payload)" }
        val text = payload.map { it.toInt() and 0xFF }
            .filter { it in 32..126 }.map { it.toChar() }.joinToString("")
        val meaning = decodeFrame(value)
            ?: if (payload.size == 1) {
                if (payload[0] == 0x00.toByte()) "Accepted." else "Rejected by the ring."
            } else null
        // A reply this app understands needs no hex: that is working shown for its own sake.
        // Raw bytes appear when there is nothing to translate, or when they are asked for.
        // The capability bitmap is a list, not a sentence, so it gets the full-height view.
        if ((value[0].toInt() and 0xFF) == 0x02 && (value[1].toInt() and 0xFF) == 0x01 && payload.size >= 8) {
            showText("What this ring supports", describeCapabilities(payload))
            return
        }
        val wantHex = meaning == null || hexCheck.isChecked
        // Only call it text when it plausibly is: a lone byte that happens to fall in the
        // printable range is a number, not a word.
        val looksTextual = text.length >= 4 && text.length >= payload.size - 1
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(buildString {
                append(meaning ?: "The ring answered, but this app cannot translate it yet.")
                if (looksTextual) append("\n\n").append(text)
                if (wantHex) append("\n\nRaw reply from the ring\n").append(bytes)
            })
            .setPositiveButton("Close", null)
            .show()
    }

    /** Frames and queues one command, reporting whether the radio accepted it. */
    private fun send(bytes: ByteArray, label: String): Boolean {
        val active = gatt
        val channel = writable[COMMAND_CHANNEL]
        if (active == null || channel == null) { reportNotConnected(label); return false }
        val frame = asFrame(bytes)
        awaiting = Triple(bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF, label)
        enqueue {
            val sent = write(active, channel, frame)
            append("\n${clock.format(Date())} SET $label: ${frame.toHex()}" +
                "${if (sent) "" else "  [REFUSED by radio]"}\n")
            sent
        }
        return true
    }

    /**
     * Readings the ring pushes unprompted once a measurement is running. Each was confirmed by
     * starting the matching measurement and watching the values move; see PROTOCOL.md.
     */
    private fun decodeFrame(value: ByteArray): String? {
        if (value.size < 6) return null
        val group = value[0].toInt() and 0xFF
        val command = value[1].toInt() and 0xFF
        val payload = value.copyOfRange(4, value.size - 2)
        fun byte(i: Int) = payload[i].toInt() and 0xFF
        return when {
            group == 0x06 && command == 0x01 && payload.isNotEmpty() -> "${byte(0)} bpm"
            group == 0x06 && command == 0x02 && payload.isNotEmpty() -> "${byte(0)}% blood oxygen"
            // Bytes beyond the first two are left raw: their meaning is not established.
            group == 0x06 && command == 0x03 && payload.size >= 2 -> "blood pressure ${byte(0)}/${byte(1)}"
            group == 0x04 && command == 0x0E && payload.isNotEmpty() ->
                "${measurementName(byte(0))} measurement finished"
            // The ring is not a Bluetooth keyboard: this reaches whichever app holds the
            // connection, so no other camera app can ever see it.
            group == 0x04 && command == 0x03 -> "shutter pressed on the ring"
            // GetDeviceInfo, named from the vendor SDK's own parser.
            group == 0x02 && command == 0x00 && payload.size >= 6 -> buildString {
                append("firmware V${byte(3)}.${byte(2)}")
                append(", battery ${byte(5)}%")
                if (byte(4) != 0) append(" (charging)")
                append(", device id ${byte(0)}")
            }
            group == 0x02 && command == 0x0C && payload.size >= 8 -> {
                val steps = byte(0) or (byte(1) shl 8) or (byte(2) shl 16)
                val calories = byte(3) or (byte(4) shl 8)
                val distance = byte(5) or (byte(6) shl 8) or (byte(7) shl 16)
                "$steps steps, $distance distance, $calories calories"
            }
            group == 0x02 && command == 0x01 && payload.size >= 8 -> {
                val on = CAPABILITIES.count { it.index < payload.size && (byte(it.index) shr it.bit) and 1 == 1 }
                "$on of ${CAPABILITIES.size} features supported — tap to list them"
            }
            group == 0x02 && command == 0x03 && payload.isNotEmpty() ->
                "the ring calls itself \"${readable(payload.toList())}\""
            // Stored heart rate: six bytes per record, a timestamp then the reading.
            group == 0x05 && command == 0x15 && payload.size >= 6 ->
                payload.toList().chunked(6).filter { it.size == 6 }.joinToString("\n") {
                    "${stamp(it)}  ${it[5].toInt() and 0xFF} bpm"
                }
            // Stored blood pressure: eight bytes per record.
            group == 0x05 && command == 0x17 && payload.size >= 8 ->
                payload.toList().chunked(8).filter { it.size == 8 }.joinToString("\n") {
                    "${stamp(it)}  ${it[5].toInt() and 0xFF}/${it[6].toInt() and 0xFF}"
                }
            // Only the queries answer with a count; the pushes above carry data.
            group == 0x05 && command !in setOf(0x15, 0x17) && payload.size >= 2 -> {
                val count = byte(0) or (byte(1) shl 8)
                if (count == 0) "no records stored" else "$count record${if (count == 1) "" else "s"} stored"
            }
            // The ring's two refusals. Without these the app looked like it did nothing at all.
            payload.size == 1 && payload[0] == 0xFC.toByte() ->
                "this firmware does not implement that command"
            payload.size == 1 && payload[0] == 0xFE.toByte() ->
                "the ring rejected that — it wants an argument"
            // The firmware's own debug log. Byte 0 is a chunk marker, not text.
            group == 0x02 && command == 0x08 -> readable(payload.drop(1)).ifEmpty { null }
            else -> null
        }
    }

    private fun measurementName(type: Int) = when (type) {
        0x00 -> "heart rate"
        0x01 -> "blood pressure"
        0x02 -> "blood oxygen"
        else -> "type $type"
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
        Triple(0x02, 0x02, byteArrayOf()) to "GetDeviceMac",
        Triple(0x02, 0x03, byteArrayOf()) to "GetDeviceName",
        Triple(0x02, 0x07, byteArrayOf()) to "GetDeviceUserConfig",
        Triple(0x02, 0x08, byteArrayOf()) to "GetDeviceLog",
        Triple(0x02, 0x09, byteArrayOf()) to "GetThemeInfo",
        Triple(0x02, 0x0A, byteArrayOf()) to "GetElectrodeLocation",
        Triple(0x02, 0x0B, byteArrayOf()) to "GetDeviceScreenInfo",
        Triple(0x02, 0x0C, byteArrayOf()) to "GetNowStep",
        Triple(0x02, 0x0D, byteArrayOf()) to "GetHistoryOutline",
        Triple(0x02, 0x0E, byteArrayOf()) to "GetRealTemp",
        Triple(0x02, 0x0F, byteArrayOf()) to "GetScreenInfo",
        Triple(0x02, 0x10, byteArrayOf()) to "GetHeavenEarthAndFiveElement",
        Triple(0x02, 0x11, byteArrayOf()) to "GetRealBloodOxygen",
        Triple(0x02, 0x12, byteArrayOf()) to "GetCurrentAmbientLightIntensity",
        Triple(0x02, 0x13, byteArrayOf()) to "GetCurrentAmbientTempAndHumidity",
        Triple(0x02, 0x14, byteArrayOf()) to "GetScheduleInfo",
        Triple(0x02, 0x15, byteArrayOf()) to "GetSensorSamplingInfo",
        Triple(0x02, 0x16, byteArrayOf()) to "GetCurrentSystemWorkingMode",
        Triple(0x02, 0x17, byteArrayOf()) to "GetInsuranceRelatedInfo",
        Triple(0x02, 0x18, byteArrayOf()) to "GetUploadConfigurationInfoOfReminder",
        Triple(0x02, 0x19, byteArrayOf()) to "GetStatusOfManualMode",
        Triple(0x02, 0x1A, byteArrayOf()) to "GetEventReminderInfo",
        Triple(0x02, 0x1B, byteArrayOf()) to "GetChipScheme",
        Triple(0x02, 0x1F, byteArrayOf()) to "GetDeviceRemindInfo",
        Triple(0x02, 0x20, byteArrayOf()) to "GetAllRealDataFromDevice",
        Triple(0x02, 0x21, byteArrayOf()) to "GetLaserTreatmentParams",
        Triple(0x02, 0x22, byteArrayOf()) to "GetALiIOTActivationState",
        Triple(0x02, 0x23, byteArrayOf()) to "GetScreenParameters",
        Triple(0x02, 0x24, byteArrayOf()) to "GetCardInfo",
        Triple(0x02, 0x25, byteArrayOf()) to "GetPowerStatistics",
        Triple(0x02, 0x26, byteArrayOf()) to "GetSleepStatus",
        Triple(0x02, 0x27, byteArrayOf()) to "GetEcgMode",
        Triple(0x02, 0x28, byteArrayOf()) to "GetMeasurementFunction",
        Triple(0x02, 0x29, byteArrayOf()) to "GetAlgorithmicLicense",
        Triple(0x02, 0x2A, byteArrayOf()) to "GetTerminalConf",
        Triple(0x02, 0x2B, byteArrayOf()) to "GetSunGoldConf",
        Triple(0x02, 0x2F, byteArrayOf()) to "GetRingProductionTestHostConfig",
        Triple(0x02, 0x30, byteArrayOf()) to "GetIdentificationCode",
        Triple(0x02, 0x31, byteArrayOf()) to "GetBloodPressureCalibrationValue",
        Triple(0x02, 0x32, byteArrayOf()) to "GetRingSizeAndColor",
        Triple(0x02, 0x33, byteArrayOf()) to "GetVibrationSettings",
        Triple(0x05, 0x02, byteArrayOf()) to "Health_HistorySport",
        Triple(0x05, 0x04, byteArrayOf()) to "Health_HistorySleep",
        Triple(0x05, 0x06, byteArrayOf()) to "Health_HistoryHeart",
        Triple(0x05, 0x08, byteArrayOf()) to "Health_HistoryBlood",
        Triple(0x05, 0x09, byteArrayOf()) to "Health_HistoryAll",
        Triple(0x05, 0x1A, byteArrayOf()) to "Health_HistoryBloodOxygen",
        Triple(0x05, 0x1C, byteArrayOf()) to "Health_HistoryTempAndHumidity",
        Triple(0x05, 0x1E, byteArrayOf()) to "Health_HistoryTemp",
        Triple(0x05, 0x20, byteArrayOf()) to "Health_HistoryAmbientLight",
        Triple(0x05, 0x29, byteArrayOf()) to "Health_HistoryFall",
        Triple(0x05, 0x2B, byteArrayOf()) to "Health_HistoryHealthMonitoring",
        Triple(0x05, 0x2D, byteArrayOf()) to "Health_HistorySportMode",
        Triple(0x05, 0x2F, byteArrayOf()) to "Health_HistoryComprehensiveMeasureData",
        Triple(0x05, 0x31, byteArrayOf()) to "health_BackgroundReminderRecord",
        Triple(0x05, 0x33, byteArrayOf()) to "Health_History_Body_Data",
        Triple(0x05, 0x35, byteArrayOf()) to "Health_LocationData",
        Triple(0x05, 0x37, byteArrayOf()) to "Health_SedentaryRecords",
        Triple(0x05, 0x38, byteArrayOf()) to "Health_History_Sedentary_Data",
        Triple(0x05, 0x39, byteArrayOf()) to "Health_JiuleComprehensive",
        Triple(0x05, 0x3B, byteArrayOf()) to "Health_HistoryWarning",
        Triple(0x05, 0x66, byteArrayOf()) to "Health_HistoryWearingStatus",
        Triple(0x05, 0x73, byteArrayOf()) to "Health_HistoryRespiratoryTraining",
        Triple(0x05, 0x76, byteArrayOf()) to "Health_HistoryPowerOnOff",
        Triple(0x05, 0x80, byteArrayOf()) to "Health_HistoryBlock",
        Triple(0x07, 0x00, byteArrayOf()) to "Collect_QueryNum",
        Triple(0x07, 0x05, byteArrayOf()) to "Collect_File_Count",
        Triple(0x07, 0x06, byteArrayOf()) to "Collect_File_List",
    )

    /**
     * Every command the vendor SDK knows, reachable by hand. Nothing is withheld: the ones that
     * can erase or reset the ring are marked and confirmed, not hidden. Whatever is typed in the
     * hex box is used as the payload.
     */
    private fun browseCommands() {
        val grouped = ALL_COMMANDS.groupBy { it.group }.toSortedMap()
        val keys = grouped.keys.toList()
        val labels = (listOf("Search by name…") + keys.map { group ->
            val name = COMMAND_GROUPS[group] ?: "Group"
            "%02X  %s  (%d)".format(group, name, grouped.getValue(group).size)
        }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("All ${ALL_COMMANDS.size} SDK commands")
            .setItems(labels) { _, which ->
                if (which == 0) searchCommands() else browseGroup(keys[which - 1])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** 329 entries is too many to scroll, so they can be searched by name. */
    private fun searchCommands() {
        val field = EditText(this).apply {
            hint = "heart, sleep, battery, factory…"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Search commands")
            .setView(field)
            .setPositiveButton("Search") { _, _ ->
                val term = field.text.toString().trim()
                val hits = ALL_COMMANDS.filter { it.name.contains(term, ignoreCase = true) }
                when {
                    term.isEmpty() -> browseCommands()
                    hits.isEmpty() -> AlertDialog.Builder(this)
                        .setTitle("Nothing matched \"$term\"")
                        .setPositiveButton("Back") { _, _ -> searchCommands() }
                        .show()
                    else -> showMatches(term, hits)
                }
            }
            .setNegativeButton("Back") { _, _ -> browseCommands() }
            .show()
    }

    private fun showMatches(term: String, hits: List<RingCommand>) {
        val labels = hits.map {
            "%02X %02X  %s%s".format(it.group, it.command, it.name, if (it.risky) "   [careful]" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${hits.size} matching \"$term\"")
            .setItems(labels) { _, which -> confirmThenSend(hits[which]) }
            .setNegativeButton("Back") { _, _ -> searchCommands() }
            .show()
    }

    private fun browseGroup(group: Int) {
        val commands = ALL_COMMANDS.filter { it.group == group }
        val labels = commands.map {
            "%02X %02X  %s%s".format(it.group, it.command, it.name, if (it.risky) "   [careful]" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(COMMAND_GROUPS[group] ?: "Group %02X".format(group))
            .setItems(labels) { _, which -> confirmThenSend(commands[which]) }
            .setNegativeButton("Back") { _, _ -> browseCommands() }
            .show()
    }

    /**
     * A few commands only answer when given a literal argument the vendor app sends; without it
     * the ring replies FE. These are used when the hex box is empty.
     */
    private val defaultPayloads = mapOf(
        (0x02 to 0x00) to byteArrayOf(0x47, 0x43),   // "GC"  GetDeviceInfo
        (0x02 to 0x01) to byteArrayOf(0x47, 0x46),   // "GF"  GetDeviceSupportFunction
        (0x02 to 0x03) to byteArrayOf(0x47, 0x50),   // "GP"  GetDeviceName
        (0x02 to 0x07) to byteArrayOf(0x43, 0x46),   // "CF"  GetDeviceUserConfig
        (0x02 to 0x11) to byteArrayOf(0x49, 0x53),   // "IS"  GetRealBloodOxygen
        (0x02 to 0x12) to byteArrayOf(0x4A, 0x54),   // "JT"  GetCurrentAmbientLightIntensity
        (0x02 to 0x13) to byteArrayOf(0x4B, 0x55),   // "KU"  GetCurrentAmbientTempAndHumidity
        (0x02 to 0x28) to byteArrayOf(0x47, 0x46),   // "GF"  GetMeasurementFunction
        (0x02 to 0x29) to byteArrayOf(0x47, 0x46),   // "GF"  GetAlgorithmicLicense
        (0x02 to 0x2A) to byteArrayOf(0x47, 0x46),   // "GF"  GetTerminalConf
        (0x02 to 0x2B) to byteArrayOf(0x47, 0x43),   // "GC"  GetSunGoldConf
        // settingRestoreFactory needs "RSYS"; deliberately not listed, so a wipe cannot be a
        // single mistaken tap. Type 52535953 by hand if you ever genuinely want it.
    )

    /**
     * Sends any command to any writable characteristic. The command channel is what the ring
     * normally listens on, but nothing stops a command being aimed elsewhere, so the choice is
     * offered rather than assumed.
     */
    private fun dispatch(bytes: ByteArray, label: String) {
        val active = gatt
        if (active == null || writable.isEmpty()) { reportNotConnected(label); return }
        val frame = if (frameCheck.isChecked) asFrame(bytes) else bytes
        if (bytes.size >= 2) {
            awaiting = Triple(bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF, label)
        }
        // The ring only ever listens on one channel, so asking every time is noise. The other
        // five stay reachable behind a tickbox for anyone deliberately probing them.
        val usual = writable[COMMAND_CHANNEL]
        if (!chooseChannelCheck.isChecked && usual != null) {
            deliver(active, usual, frame, label)
            return
        }
        val targets = writable.keys.toTypedArray()
        val preferred = targets.indexOf(COMMAND_CHANNEL).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(label)
            .setSingleChoiceItems(targets, preferred) { dialog, which ->
                dialog.dismiss()
                deliver(active, writable.getValue(targets[which]), frame, label)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deliver(
        gatt: BluetoothGatt,
        channel: BluetoothGattCharacteristic,
        frame: ByteArray,
        label: String
    ) {
        enqueue {
            val sent = write(gatt, channel, frame)
            append("\n${clock.format(Date())} SEND $label -> ${shortUuid(channel.uuid)}: " +
                "${frame.toHex()}${if (sent) "" else "  [REFUSED by radio]"}\n")
            sent
        }
    }

    private fun confirmThenSend(command: RingCommand) {
        val payload = parseHex(commandInput.text.toString())
            ?: defaultPayloads[command.group to command.command]
            ?: byteArrayOf()
        val bytes = byteArrayOf(command.group.toByte(), command.command.toByte()) + payload
        if (!command.risky) { dispatch(bytes, command.name); return }
        AlertDialog.Builder(this)
            .setTitle(command.name)
            .setMessage(
                "This command can erase stored data, reset the ring, or start a firmware " +
                    "transfer. It will be sent as ${asFrame(bytes).toHex()}.\n\nSend it?"
            )
            .setPositiveButton("Send anyway") { _, _ -> dispatch(bytes, command.name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readEverything() {
        val active = gatt
        val channel = writable[COMMAND_CHANNEL]
        if (active == null || channel == null) { reportNotConnected("That measurement"); return }
        append("\n=== reading every safe query ===\n")
        readOnlyQueries.forEach { (spec, name) ->
            val (group, command, listed) = spec
            val payload = if (listed.isEmpty()) defaultPayloads[group to command] ?: listed else listed
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
        if (active == null || channel == null) { reportNotConnected("That request"); return }
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
    private fun append(text: String) = record(text, text, routine = false)

    /**
     * The file always gets everything. The screen is the part a person has to read, so it can
     * be paused, can drop the once-a-second traffic, and can show decoded values without hex.
     */
    private fun record(fileText: String, screenText: String, routine: Boolean) {
        runCatching { logFile.appendText(fileText) }
        if (routine && quietCheck.isChecked) return
        if (paused) {
            held.append(screenText)
            heldLines += screenText.count { it == '\n' }
            pauseButton.text = "Resume ($heldLines)"
            return
        }
        log.append(screenText)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setPaused(value: Boolean) {
        paused = value
        if (!paused) {
            log.append(held)
            held.setLength(0)
            heldLines = 0
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        pauseButton.text = if (paused) "Resume (0)" else "Pause"
        showStatus(if (paused) "Log paused — scroll back and read; nothing is being lost." else "Log running.")
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
        runCatching { unregisterReceiver(overAdb) }
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
