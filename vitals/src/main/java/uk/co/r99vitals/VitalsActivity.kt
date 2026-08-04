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
    private lateinit var measureButton: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null

    /** One request at a time, as the radio requires; the callbacks release the next. */
    private val queue = ArrayDeque<() -> Boolean>()
    private var running = false
    private var step = 0

    /** Which measurement the round-robin is on, so one tap reads all three in turn. */
    private var sweep = emptyList<Int>()

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
        measureButton = findViewById(R.id.measureButton)
        applyInsets()
        measureButton.setOnClickListener { takeReadings() }
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

    /** One tap measures heart rate, then oxygen, then pressure, each taking around half a minute. */
    private fun takeReadings() {
        if (command == null) { linkState.text = "Not connected yet"; connect(); return }
        sweep = listOf(Ring.HEART, Ring.OXYGEN, Ring.PRESSURE)
        heartCaption.text = "Measuring — keep still"
        measureButton.isEnabled = false
        measureButton.text = "Measuring…"
        nextInSweep()
    }

    private fun nextInSweep() {
        val type = sweep.firstOrNull()
        if (type == null) {
            measureButton.isEnabled = true
            measureButton.text = "Take a reading"
            heartCaption.text = "Measured just now"
            return
        }
        sweep = sweep.drop(1)
        enqueue { write(Ring.startMeasuring(type)) }
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
                // The ring dates its stored records with its own clock, so keep it right.
                val now = Calendar.getInstance()
                enqueue {
                    write(
                        Ring.setClock(
                            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                            now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY),
                            now.get(Calendar.MINUTE), now.get(Calendar.SECOND)
                        )
                    )
                }
                Ring.automaticMonitoring(true).forEach { frame -> enqueue { write(frame) } }
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
            is Ring.Reading.Heart -> heartValue.text = reading.bpm.toString()
            is Ring.Reading.Oxygen -> oxygenValue.text = "${reading.percent}%"
            is Ring.Reading.Pressure -> pressureValue.text = "${reading.systolic}/${reading.diastolic}"
            is Ring.Reading.Power -> linkState.text = "Your ring · ${reading.percent}%"
            is Ring.Reading.Finished -> nextInSweep()
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
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
