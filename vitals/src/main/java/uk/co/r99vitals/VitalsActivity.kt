package uk.co.r99vitals

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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * A quiet view of what the ring knows. The debugger app in this repository is where the protocol
 * is explored; this one shows readings and nothing else.
 */
class VitalsActivity : AppCompatActivity() {
    private var ui by mutableStateOf(VitalsState())
    private lateinit var history: History

    private var interval = 15   // minutes; 0 means off
    private val saved by lazy { getSharedPreferences("ring", MODE_PRIVATE) }
    private var ringAddress: String?
        get() = saved.getString("address", null)
        set(value) { saved.edit().putString("address", value).apply() }
    private var scanning = false
    private val found = linkedMapOf<String, ScanResult>()
    private var retryDelay = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null

    /** One request at a time, as the radio requires; the callbacks release the next. */
    private val queue = ArrayDeque<() -> Boolean>()
    private var running = false
    private var step = 0

    /** Which measurement the round-robin is on, so one tap reads all three in turn. */
    private var sweep = emptyList<Int>()

    /**
     * Debug-only control over adb, so the app can be driven without tapping:
     *
     *   adb shell am broadcast -a uk.co.r99vitals.RUN --es do heart
     *
     * Registered only in debug builds. A released Vitals has no exported receiver at all.
     */
    private val overAdb = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                when (intent?.getStringExtra("do")) {
                    "connect" -> askThenConnect()
                    "heart" -> measure(Ring.HEART, "heart rate")
                    "oxygen" -> measure(Ring.OXYGEN, "blood oxygen")
                    "pressure" -> measure(Ring.PRESSURE, "blood pressure")
                }
            }
        }
    }

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.all { it }) connect() else ui = ui.copy(link = "Bluetooth access needed") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = History(this)
        interval = getSharedPreferences("ring", MODE_PRIVATE).getInt("interval", 15)
        setContent {
            VitalsScreen(
                state = ui,
                onMeasure = { type ->
                    measure(type, when (type) {
                        Ring.HEART -> "heart rate"
                        Ring.OXYGEN -> "blood oxygen"
                        else -> "blood pressure"
                    })
                },
                onInterval = { chooseInterval() },
                onHistory = { showHistory() },
                onLink = { if (command == null) askThenConnect() }
            )
        }
        showTrend()
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, overAdb, IntentFilter("uk.co.r99vitals.RUN"), ContextCompat.RECEIVER_EXPORTED
            )
        }
        askThenConnect()
    }

    private fun askThenConnect() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) connect()
        else permissions.launch(needed)
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) { ui = ui.copy(link = "Turn Bluetooth on"); return }
        val address = ringAddress
        if (address == null) { pair(); return }
        ui = ui.copy(link = "Connecting to your ring")
        // A paired ring stops advertising, so it is reached by address rather than by scanning.
        val device = runCatching { bluetooth.getRemoteDevice(address) }.getOrNull() ?: return
        gatt?.close()
        queue.clear(); running = false; step++
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** First run: find a ring to remember. A ring already paired elsewhere will not appear. */
    @SuppressLint("MissingPermission")
    private fun pair() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        scanning = true
        found.clear()
        ui = ui.copy(link = "Looking for a ring")
        scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        handler.postDelayed({ finishPairing() }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun finishPairing() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        // A ring already paired to the phone has stopped advertising and will never appear in
        // a scan, so anything already bonded is offered alongside what was heard. Closest first
        // among the rest: the ring you are wearing is the nearest one.
        val bonded = adapter?.bondedDevices.orEmpty().map { it.address to (it.name ?: "Paired device") }
        val heard = found.values.sortedByDescending { it.rssi }
            .filterNot { result -> bonded.any { it.first == result.device.address } }
            .map { it.device.address to "${it.device.name ?: it.scanRecord?.deviceName ?: "Unnamed"}  ·  ${it.rssi} dBm" }
        val choices = bonded.map { it.first to "${it.second}  ·  already paired" } + heard
        if (choices.isEmpty()) { ui = ui.copy(link = "No ring found — tap to retry"); return }
        val labels = choices.map { it.second }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Which one is your ring?")
            .setItems(labels) { _, which ->
                ringAddress = choices[which].first
                connect()
                CollectorService.start(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.isConnectable) found[result.device.address] = result
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            ui = ui.copy(link = "Scan failed — tap to retry")
        }
    }

    /** The ring drops the link when it feels like it, so keep coming back, less eagerly each time. */
    private fun scheduleReconnect() {
        if (ringAddress == null) return
        retryDelay = when (retryDelay) { 0L -> 3_000; 3_000L -> 10_000; 10_000L -> 30_000; else -> 60_000 }
        handler.postDelayed({ if (command == null) connect() }, retryDelay)
    }

    private fun enqueue(work: () -> Boolean) {
        queue.addLast(work)
        if (!running) runNext()
    }

    private fun runNext() {
        val work = queue.removeFirstOrNull()
        if (work == null) { running = false; return }
        running = true
        val mine = ++step
        if (!work()) { handler.post { finish(mine) }; return }
        // A characteristic that accepts a request but never answers must not wedge the queue.
        handler.postDelayed({ if (mine == step) finish(mine) }, 5_000)
    }

    private fun finish(which: Int) { if (which == step) runNext() }
    private fun done() { handler.post { finish(step) } }

    @SuppressLint("MissingPermission")
    private fun write(bytes: ByteArray): Boolean {
        val target = command ?: return false
        val active = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            active.writeCharacteristic(target, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") target.value = bytes
            target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") active.writeCharacteristic(target)
        }
    }

    /** Each vital is measured on its own, taking around half a minute. */
    private fun measure(type: Int, label: String) {
        if (command == null) { ui = ui.copy(link = "Not connected yet"); connect(); return }
        ui = ui.copy(measuring = "Measuring $label — keep still")
        setButtonsEnabled(false)
        enqueue { write(Ring.startMeasuring(type)) }
    }

    private fun setButtonsEnabled(enabled: Boolean) { /* driven by ui.measuring */ }

    /**
     * How often the ring measures on its own. The ring does this whether or not the app is open,
     * which is what fills the chart overnight; the app only has to ask once.
     */
    private fun chooseInterval() {
        val choices = intArrayOf(0, 15, 30, 60)
        val labels = arrayOf("Off", "Every 15 minutes", "Every 30 minutes", "Every hour")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Automatic readings")
            .setSingleChoiceItems(labels, choices.indexOf(interval).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                interval = choices[which]
                saved.edit().putInt("interval", interval).apply()
                applyInterval()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyIntervalLabel() {
        ui = ui.copy(interval = interval)
    }

    private fun applyInterval() {
        applyIntervalLabel()
        if (command == null) { ui = ui.copy(link = "Not connected yet"); return }
        Ring.automaticMonitoring(interval > 0, if (interval > 0) interval else 15)
            .forEach { frame -> enqueue { write(frame) } }
    }

    private fun showTrend() {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        ui = ui.copy(
            trend = history.all().filter { it.kind == "heart" && it.at.time >= since }.map { it.value },
            trendCaption = "Last 24 hours · " + history.summary("heart", since)
        )
    }

    private fun showHistory() {
        val view = TextView(this).apply {
            text = history.report()
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(40, 28, 40, 28)
            setTextColor(ContextCompat.getColor(this@VitalsActivity, R.color.text))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("History")
            .setView(android.widget.ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Export") { _, _ ->
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Vitals readings")
                        putExtra(android.content.Intent.EXTRA_TEXT, history.asCsv())
                    }, "Export readings"))
            }
            .show()
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
            runOnUiThread {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    retryDelay = 0
                    ui = ui.copy(link = "Reading your ring", connected = true)
                    gatt.discoverServices()
                } else {
                    command = null
                    ui = ui.copy(link = "Reconnecting…", connected = false)
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) { ui = ui.copy(link = "Could not read the ring"); return@runOnUiThread }
                gatt.services.forEach { service ->
                    service.characteristics.forEach { characteristic ->
                        if (characteristic.uuid == Ring.COMMAND_CHANNEL) command = characteristic
                        val bits = characteristic.properties
                        val notifies = bits and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        if (notifies) enqueue { subscribe(gatt, characteristic) }
                    }
                }
                enqueue { write(Ring.deviceInfo()) }
                // The clock is deliberately left alone: writing it makes the ring abandon a
                // running sleep session, which its own log reports as "exit sleep because time
                // change". Sleep data matters more than a few seconds of drift.
                Ring.automaticMonitoring(interval > 0, if (interval > 0) interval else 15)
                    .forEach { frame -> enqueue { write(frame) } }
                enqueue { ui = ui.copy(link = "Your ring"); false }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = done()
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = done()
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = done()

        @Deprecated("Superseded on Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            runOnUiThread { show(characteristic, value) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            runOnUiThread { show(characteristic, value) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(Ring.CLIENT_CONFIG) ?: return false
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

    private fun show(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == Ring.ACTIVITY) {
            Ring.readActivity(value)?.let {
                ui = ui.copy(steps = it.steps, distance = it.distance, calories = it.calories)
            }
            return
        }
        if (characteristic.uuid == Ring.HEART_RATE) {
            Ring.readStandardHeartRate(value)?.let { ui = ui.copy(heart = it) }
            return
        }
        when (val reading = Ring.read(value)) {
            is Ring.Reading.Heart -> {
                ui = ui.copy(heart = reading.bpm)
                history.record("heart", reading.bpm)
                showTrend()
            }
            is Ring.Reading.Oxygen -> {
                ui = ui.copy(oxygen = reading.percent)
                history.record("oxygen", reading.percent)
            }
            is Ring.Reading.Pressure -> {
                ui = ui.copy(systolic = reading.systolic, diastolic = reading.diastolic)
                history.record("pressure", reading.systolic, reading.diastolic)
            }
            is Ring.Reading.Power -> ui = ui.copy(link = "Your ring", battery = reading.percent)
            is Ring.Reading.Finished -> {
                setButtonsEnabled(true)
                ui = ui.copy(measuring = null)
                showTrend()
            }
            else -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        // Collection continues with the app closed; starting it here is idempotent.
        if (ringAddress != null) CollectorService.start(this)
        if (command == null && ringAddress != null) askThenConnect()
        showTrend()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { unregisterReceiver(overAdb) }
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        super.onDestroy()
    }


}
