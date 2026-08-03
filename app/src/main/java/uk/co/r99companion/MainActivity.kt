package uk.co.r99companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var scanButton: MaterialButton
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startScan() else showStatus("Bluetooth permission is needed to find the ring.")
    }

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scanButton = findViewById(R.id.scanButton)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        scanButton.setOnClickListener { requestBluetoothAndScan() }
        append("This app does not send health data anywhere.\n")
    }

    private fun requestBluetoothAndScan() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startScan()
        else permissions.launch(needed)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) { showStatus("Turn Bluetooth on, then try again."); return }
        if (scanning) return
        scanning = true
        scanButton.isEnabled = false
        showStatus("Scanning for nearby Bluetooth rings…")
        append("Scan started\n")
        bluetooth.bluetoothLeScanner.startScan(scanner)
        handler.postDelayed({ stopScan("No ring found. Wake or charge it, then retry.") }, 15_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(message: String? = null) {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanner)
        scanButton.isEnabled = true
        message?.let(::showStatus)
    }

    private val scanner = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"
            // R99 variants are commonly advertised as R99, SmartHealth, or with no name.
            append("Found $name (${result.device.address}), ${result.rssi} dBm\n")
            stopScan()
            showStatus("Connecting to $name…")
            gatt?.close()
            gatt = result.device.connectGatt(this@MainActivity, false, callback, BluetoothDeviceTransport.LE)
        }
        override fun onScanFailed(errorCode: Int) { stopScan("Scan failed (code $errorCode). Restart Bluetooth and try again.") }
    }

    private object BluetoothDeviceTransport { const val LE = 2 }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, state: Int) {
            runOnUiThread {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    showStatus("Connected. Reading ring Bluetooth services…")
                    append("Connected; discovering services\n")
                    gatt.discoverServices()
                } else {
                    showStatus("Ring disconnected.")
                    append("Disconnected (status $statusCode)\n")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            runOnUiThread {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) { showStatus("Could not read services ($statusCode)."); return@runOnUiThread }
                showStatus("Connected. Service map captured — send this log to continue.")
                gatt.services.forEach { service ->
                    append("SERVICE ${service.uuid}\n")
                    service.characteristics.forEach { characteristic ->
                        append("  CHAR ${characteristic.uuid} props=${characteristic.properties}\n")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) gatt.readCharacteristic(characteristic)
                    }
                }
            }
        }

        @Deprecated("Use the byte-array overload on Android 13+")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            val value = characteristic.value ?: byteArrayOf()
            runOnUiThread { append("READ ${characteristic.uuid}: ${value.toHex()} (status $statusCode)\n") }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            runOnUiThread { append("READ ${characteristic.uuid}: ${value.toHex()} (status $statusCode)\n") }
        }
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    private fun showStatus(text: String) { status.text = text }
    private fun append(text: String) {
        log.append(text)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() { gatt?.close(); super.onDestroy() }
}
