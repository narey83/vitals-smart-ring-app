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
import android.content.Intent
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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var scanButton: MaterialButton
    private lateinit var shareButton: MaterialButton
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val candidates = linkedMapOf<String, ScanResult>()

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
        shareButton = findViewById(R.id.shareButton)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        scanButton.setOnClickListener { requestBluetoothAndScan() }
        shareButton.setOnClickListener { shareLog() }
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
        candidates.clear()
        scanButton.isEnabled = false
        showStatus("Scanning for nearby Bluetooth rings…")
        append("Scan started\n")
        bluetooth.bluetoothLeScanner.startScan(scanner)
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
        val items = candidates.values.map { result ->
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"
            "$name • ${result.device.address} • ${result.rssi} dBm"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose your R99 ring")
            .setItems(items) { _, which -> connect(candidates.values.elementAt(which)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val scanner = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"
            candidates[result.device.address] = result
            append("Found $name (${result.device.address}), ${result.rssi} dBm\n")
        }
        override fun onScanFailed(errorCode: Int) { stopScan("Scan failed (code $errorCode). Restart Bluetooth and try again.") }
    }

    @SuppressLint("MissingPermission")
    private fun connect(result: ScanResult) {
        val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"
        showStatus("Connecting to $name…")
        gatt?.close()
        gatt = result.device.connectGatt(this, false, callback, BluetoothDeviceTransport.LE)
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

    private fun shareLog() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "R99 Companion Bluetooth protocol log")
            putExtra(Intent.EXTRA_TEXT, log.text.toString())
        }, "Share protocol log"))
    }

    override fun onDestroy() { gatt?.close(); super.onDestroy() }
}
