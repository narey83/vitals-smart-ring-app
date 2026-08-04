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
import android.bluetooth.BluetoothStatusCodes
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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.util.Calendar

/**
 * A quiet view of what the ring knows. The debugger app in this repository is where the protocol
 * is explored; this one shows readings and nothing else.
 */
class VitalsActivity : AppCompatActivity() {
    private lateinit var linkState: TextView
    private lateinit var heartValue: TextView
    private lateinit var heartCaption: TextView
    private lateinit var oxygenValue: TextView
    private lateinit var pressureValue: TextView
    private lateinit var stepsValue: TextView
    private lateinit var stepsCaption: TextView
    private lateinit var heartButton: MaterialButton
    private lateinit var oxygenButton: MaterialButton
    private lateinit var pressureButton: MaterialButton
    private lateinit var historyButton: MaterialButton
    private lateinit var intervalButton: MaterialButton
    private var interval = 5
    private lateinit var heartTrend: TextView
    private lateinit var heartChart: TrendView
    private lateinit var history: History

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
    ) { result -> if (result.values.all { it }) connect() else linkState.text = "Bluetooth access needed" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vitals)
        linkState = findViewById(R.id.linkState)
        heartValue = findViewById(R.id.heartValue)
        heartCaption = findViewById(R.id.heartCaption)
        oxygenValue = findViewById(R.id.oxygenValue)
        pressureValue = findViewById(R.id.pressureValue)
        stepsValue = findViewById(R.id.stepsValue)
        stepsCaption = findViewById(R.id.stepsCaption)
        heartButton = findViewById(R.id.heartButton)
        oxygenButton = findViewById(R.id.oxygenButton)
        pressureButton = findViewById(R.id.pressureButton)
        historyButton = findViewById(R.id.historyButton)
        intervalButton = findViewById(R.id.intervalButton)
        heartTrend = findViewById(R.id.heartTrend)
        heartChart = findViewById(R.id.heartChart)
        history = History(this)
        applyInsets()
        heartButton.setOnClickListener { measure(Ring.HEART, "heart rate") }
        oxygenButton.setOnClickListener { measure(Ring.OXYGEN, "blood oxygen") }
        pressureButton.setOnClickListener { measure(Ring.PRESSURE, "blood pressure") }
        historyButton.setOnClickListener { showHistory() }
        intervalButton.setOnClickListener { chooseInterval() }
        showTrend()
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, overAdb, IntentFilter("uk.co.r99vitals.RUN"), ContextCompat.RECEIVER_EXPORTED
            )
        }
        askThenConnect()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
            insets
        }
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
        if (bluetooth == null || !bluetooth.isEnabled) { linkState.text = "Turn Bluetooth on"; return }
        linkState.text = "Connecting to your ring"
        // A paired ring stops advertising, so it is reached by address rather than by scanning.
        val device = runCatching { bluetooth.getRemoteDevice(RING_ADDRESS) }.getOrNull() ?: return
        gatt?.close()
        queue.clear(); running = false; step++
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
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
        if (command == null) { linkState.text = "Not connected yet"; connect(); return }
        heartCaption.text = "Measuring $label — keep still"
        setButtonsEnabled(false)
        enqueue { write(Ring.startMeasuring(type)) }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        heartButton.isEnabled = enabled
        oxygenButton.isEnabled = enabled
        pressureButton.isEnabled = enabled
    }

    /**
     * How often the ring measures on its own. The ring does this whether or not the app is open,
     * which is what fills the chart overnight; the app only has to ask once.
     */
    private fun chooseInterval() {
        val choices = intArrayOf(0, 5, 15, 30, 60)
        val labels = arrayOf("Off", "Every 5 minutes", "Every 15 minutes", "Every 30 minutes", "Every hour")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Automatic readings")
            .setSingleChoiceItems(labels, choices.indexOf(interval).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                interval = choices[which]
                applyInterval()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyInterval() {
        intervalButton.text = if (interval == 0) {
            "Automatic readings: off"
        } else "Automatic readings: every $interval min"
        if (command == null) { linkState.text = "Not connected yet"; return }
        Ring.automaticMonitoring(interval > 0, if (interval > 0) interval else 5)
            .forEach { frame -> enqueue { write(frame) } }
    }

    private fun showTrend() {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        heartTrend.text = "Last 24 hours · " + history.summary("heart", since)
        heartChart.show(
            history.all().filter { it.kind == "heart" && it.at.time >= since }.map { it.value },
            ContextCompat.getColor(this, R.color.heart)
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
                    linkState.text = "Reading your ring"
                    gatt.discoverServices()
                } else {
                    command = null
                    linkState.text = "Ring disconnected"
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) { linkState.text = "Could not read the ring"; return@runOnUiThread }
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
                Ring.automaticMonitoring(interval > 0, if (interval > 0) interval else 5)
                    .forEach { frame -> enqueue { write(frame) } }
                enqueue { linkState.text = "Your ring"; false }
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
                stepsValue.text = "%,d".format(it.steps)
                stepsCaption.text = "steps today · ${it.distance} m · ${it.calories} kcal"
            }
            return
        }
        if (characteristic.uuid == Ring.HEART_RATE) {
            Ring.readStandardHeartRate(value)?.let { heartValue.text = it.toString() }
            return
        }
        when (val reading = Ring.read(value)) {
            is Ring.Reading.Heart -> {
                heartValue.text = reading.bpm.toString()
                history.record("heart", reading.bpm)
                showTrend()
            }
            is Ring.Reading.Oxygen -> {
                oxygenValue.text = "${reading.percent}%"
                history.record("oxygen", reading.percent)
            }
            is Ring.Reading.Pressure -> {
                pressureValue.text = "${reading.systolic}/${reading.diastolic}"
                history.record("pressure", reading.systolic, reading.diastolic)
            }
            is Ring.Reading.Power -> linkState.text = "Your ring · ${reading.percent}%"
            is Ring.Reading.Finished -> {
                setButtonsEnabled(true)
                heartCaption.text = "Measured just now"
                showTrend()
            }
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { unregisterReceiver(overAdb) }
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        super.onDestroy()
    }

    private companion object {
        /** Until pairing is built, the ring this was developed against. */
        const val RING_ADDRESS = "07:35:00:04:8D:43"
    }
}
