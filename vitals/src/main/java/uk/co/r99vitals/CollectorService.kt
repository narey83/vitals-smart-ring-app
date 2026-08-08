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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

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
    private lateinit var nights: Nights

    /** A night arrives split across frames; this holds them until the record is whole. */
    private var sleepReader = SleepReader()

    /**
     * The morning report waits for the phone to be picked up.
     *
     * Registered here rather than in the manifest because unlocking is not a broadcast an app may
     * sit waiting for from cold — but this service is already running, holding the ring, which is
     * exactly the thing that knows how the night went.
     */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = reportOnWaking()
    }
    private var gatt: BluetoothGatt? = null
    private var backoff = 0L
    private var steps = 0
    private var distance = 0
    private var calories = 0
    private var latest = "Waiting for the first reading"

    /** The last heart rate written down, so a value merely being repeated is not a measurement. */
    private var lastHeart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        history = History(this)
        nights = Nights(this)
        // Carried over from what is already written down rather than starting at nothing. The
        // ring re-notifies the reading it holds as soon as anything connects, so a service that
        // began each time not knowing the last value wrote that stale number down once per
        // start — a phantom reading every time the app was opened.
        lastHeart = history.latest("heart")?.value ?: 0
        startForeground(NOTIFICATION, notification())
        ContextCompat.registerReceiver(
            this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Bedtime.apply(this)
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
        // Steps lead, the way a step counter should read at a glance on the lock screen.
        val title = if (steps > 0) "%,d steps".format(steps) else "Collecting from your ring"
        val detail = if (steps > 0) "$distance m · $calories kcal · $latest" else latest
        return Notification.Builder(this, channel())
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Readable on the lock screen without unlocking, which is the point of it.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    private fun problemChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(PROBLEM_CHANNEL, "Ring problems", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Warns when a reading looks stuck rather than just quiet" }
            )
        }
        return PROBLEM_CHANNEL
    }

    /**
     * A ring whose clock has stopped ticking never resets its step counter either — the running
     * total just sits there, exactly as it would if the wearer genuinely had not moved. Hours
     * past a point where that stops being the likely story, so this says so once, rather than
     * only being found by chance days later. See History.stepsFlatSince and PROTOCOL.md's "the
     * clock does not tick" for the hardware fault behind it.
     */
    private fun checkStepsStuck() {
        val flatSince = history.stepsFlatSince() ?: return
        val stuckFor = System.currentTimeMillis() - flatSince.time
        if (stuckFor < STUCK_THRESHOLD) return
        val prefs = getSharedPreferences("ring", Context.MODE_PRIVATE)
        // One notification per stuck spell, not one per reading — the flat run's own start time
        // is the run's identity, so a repeat of it means nothing has changed since the last alert.
        if (prefs.getLong("stepsStuckAlertedFor", 0L) == flatSince.time) return
        prefs.edit().putLong("stepsStuckAlertedFor", flatSince.time).apply()
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, VitalsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hours = stuckFor / (60 * 60 * 1000)
        val notification = Notification.Builder(this, problemChannel())
            .setContentTitle("Steps look stuck")
            .setContentText("The ring's step count hasn't moved in over ${hours}h — might need a restart.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(PROBLEM_NOTIFICATION, notification)
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
            val notifying = gatt.services.flatMap { it.characteristics }
                .filter {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
            notifying.forEachIndexed { index, characteristic ->
                // Spaced out because the radio carries one request at a time.
                handler.postDelayed({ subscribe(gatt, characteristic) }, index * 350L)
            }
            // Sleep is the one thing that has to be asked for. The ring stages a night by itself,
            // says nothing, and drops the record within about a day — so a collector that only
            // listens loses every night the wearer does not happen to open the app for.
            //
            // The room has to be asked for first: the default 20-byte payload is far smaller than
            // a night, and without this the ring answers with a count and the record never comes.
            sleepReader = SleepReader()
            handler.postDelayed({ requestRoomForANight(gatt) }, notifying.size * 350L + 500L)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            askForNights(gatt)
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
    private fun requestRoomForANight(gatt: BluetoothGatt) {
        // If the request itself fails there is no callback to carry on from, so ask anyway and
        // let the reader drop what does not fit rather than never asking at all.
        if (!gatt.requestMtu(517)) askForNights(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun askForNights(gatt: BluetoothGatt) {
        val channel = gatt.services.firstNotNullOfOrNull {
            it.getCharacteristic(Ring.COMMAND_CHANNEL)
        } ?: return
        val frame = Ring.storedSleep()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(channel, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION") channel.value = frame
            @Suppress("DEPRECATION") channel.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") gatt.writeCharacteristic(channel)
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
                val baseline = history.latestBefore("steps", History.startOfToday())
                history.record("steps", it.steps, it.calories)
                steps = (it.steps - (baseline?.value ?: 0)).coerceAtLeast(0)
                distance = it.distance
                calories = (it.calories - (baseline?.extra ?: 0)).coerceAtLeast(0)
                refresh()
                checkStepsStuck()
            }
            return
        }
        // Where the ring's own periodic sampling lands: it reports automatic heart readings on
        // the standard SIG characteristic, not as an 06 01 frame.
        //
        // Only a changed value counts. The ring re-notifies this characteristic on its ~90 s
        // housekeeping tick whether or not it has measured, holding the last number it took, so
        // writing down every push records one stale reading a minute rather than a measurement.
        // ponytail: a fresh measurement landing on exactly the previous bpm is indistinguishable
        // from the held value and is lost. If that matters, the wear status frame (06 13) would
        // say whether the ring is measuring at all.
        if (characteristic.uuid == Ring.HEART_RATE) {
            Ring.readStandardHeartRate(value)?.takeIf { it != lastHeart }?.let {
                lastHeart = it
                history.record("heart", it)
                latest = "$it bpm"
                refresh()
            }
            return
        }
        // The nights, in reply to the query sent on connecting.
        sleepReader.accept(value).takeIf { it.isNotEmpty() }?.let {
            nights.save(it)
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
            // ponytail: heart arrives on the SIG characteristic above, but automatic blood
            // oxygen and pressure still show up nowhere. Log what else the ring pushes while
            // unattended; drop this once those two are identified as well. Frames this app
            // knows but does not collect, such as the battery reply, are not the mystery.
            else -> if (BuildConfig.DEBUG && reading == null) {
                android.util.Log.d("r99", "unrecognised push ${value.joinToString("") { "%02X".format(it) }}")
            }
        }
    }

    /**
     * Phone unlocked: ask the ring for the night before saying anything about it, because the
     * record is often written only as the wearer gets up, and then report if there is something
     * worth reporting.
     */
    private fun reportOnWaking() {
        val plan = SleepPlan.read(this)
        if (!plan.report) return
        gatt?.let { askForNights(it) }
        handler.postDelayed({
            val night = SleepInsight.merge(nights.all()).lastOrNull()
            val reported = getSharedPreferences("ring", Context.MODE_PRIVATE).getLong("reportedNight", 0L)
            if (SleepReport.due(plan, night, System.currentTimeMillis(), reported)) {
                SleepReport.post(this, night!!, plan)
            }
        }, 6_000)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "readings"
        private const val NOTIFICATION = 1
        private const val PROBLEM_CHANNEL = "problems"
        private const val PROBLEM_NOTIFICATION = 2
        private const val STUCK_THRESHOLD = 3 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, CollectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
