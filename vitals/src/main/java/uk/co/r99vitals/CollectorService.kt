package uk.co.r99vitals

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the ring's readings arriving while the app is closed.
 *
 * The ring measures on its own at whatever interval is set and pushes each result, so the work
 * is to stay connected and write down what arrives rather than to poll. A connected BLE link is
 * cheap when idle; waking the radio every fifteen minutes to reconnect would cost more.
 *
 * Nothing here reaches the network. The app has no INTERNET permission.
 */
class CollectorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var history: History
    private var gatt: BluetoothGatt? = null
    private var backoff = 0L
    private var latest = "Waiting for the first reading"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        history = History(this)
        startForeground(NOTIFICATION, notification())
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restarted by the system after being killed: pick the ring back up.
        if (gatt == null) connect()
        return START_STICKY
    }

    private fun channel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ring readings", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shows that readings are being collected" }
            )
        }
        return CHANNEL
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VitalsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, channel())
            .setContentTitle("Collecting from your ring")
            .setContentText(latest)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val address = getSharedPreferences("ring", Context.MODE_PRIVATE).getString("address", null)
            ?: return stopSelf()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) { retry(); return }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return stopSelf()
        gatt?.close()
        gatt = device.connectGatt(this, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    /** The link drops; come back for it, less eagerly each time. */
    private fun retry() {
        backoff = when (backoff) { 0L -> 5_000; 5_000L -> 20_000; 20_000L -> 60_000; else -> 300_000 }
        handler.postDelayed({ connect() }, backoff)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                backoff = 0
                gatt.discoverServices()
            } else {
                retry()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            // Subscribe and then stay quiet: the ring pushes on its own schedule.
            gatt.services.flatMap { it.characteristics }
                .filter {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
                .forEachIndexed { index, characteristic ->
                    // Spaced out because the radio carries one request at a time.
                    handler.postDelayed({ subscribe(gatt, characteristic) }, index * 350L)
                }
        }

        @Deprecated("Superseded on Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            store(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            store(characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return
        val descriptor = characteristic.getDescriptor(Ring.CLIENT_CONFIG) ?: return
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION") descriptor.value = value
            @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
        }
    }

    private fun store(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == Ring.ACTIVITY) {
            Ring.readActivity(value)?.let {
                history.record("steps", it.steps, it.calories)
                latest = "${it.steps} steps today"
                refresh()
            }
            return
        }
        when (val reading = Ring.read(value)) {
            is Ring.Reading.Heart -> {
                history.record("heart", reading.bpm)
                latest = "${reading.bpm} bpm"
                refresh()
            }
            is Ring.Reading.Oxygen -> {
                history.record("oxygen", reading.percent)
                latest = "${reading.percent}% blood oxygen"
                refresh()
            }
            is Ring.Reading.Pressure -> {
                history.record("pressure", reading.systolic, reading.diastolic)
                latest = "${reading.systolic}/${reading.diastolic}"
                refresh()
            }
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

    companion object {
        private const val CHANNEL = "readings"
        private const val NOTIFICATION = 1

        fun start(context: Context) {
            val intent = Intent(context, CollectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
