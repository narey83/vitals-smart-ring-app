package uk.co.r99vitals

import android.Manifest
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A quiet view of what the ring knows. The debugger app in this repository is where the protocol
 * is explored; this one shows readings and nothing else.
 */
private val hourMinute = SimpleDateFormat("HH:mm", Locale.UK)

class VitalsActivity : AppCompatActivity() {
    private var ui by mutableStateOf(VitalsState())
    private var tab by mutableStateOf(Tab.Today)
    private var dayOffset by mutableStateOf(0)
    private var sheet by mutableStateOf(Sheet.None)
    private var report by mutableStateOf("")
    private var healthLabel by mutableStateOf("Add to Health Connect")
    private val health by lazy { HealthExport(this) }
    private lateinit var history: History
    private lateinit var workouts: Workouts
    private lateinit var nights: Nights

    /** Holds the frames of a night together until the record inside them is whole. */
    private val sleepReader = SleepReader()

    private var interval = 15   // minutes; 0 means off
    private var monitors = Ring.Monitors()
    private var settingsOpen by mutableStateOf(false)
    private var nightMode by mutableStateOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    private var profile by mutableStateOf(Profile())
    private var plan by mutableStateOf(SleepPlan())
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

    /** Set while a measurement the wearer tapped for is running, so its readings are marked. */
    private var userAsked = false

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
                    "workout" -> startWorkout("Walk")
                    "stopworkout" -> stopWorkout()
                    // The morning report is posted on unlocking, which cannot be faked from a
                    // shell — this shows the same notification for the last night held, so its
                    // wording can be read without waiting for tomorrow morning.
                    "report" -> SleepInsight.merge(nights.all()).lastOrNull()
                        ?.let { SleepReport.post(this@VitalsActivity, it, plan) }
                }
            }
        }
    }

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Only the Bluetooth answers decide this. Notifications are asked for in the same
        // breath, and refusing them must not stop the ring being read — that would trade the
        // whole app for a reminder nobody wanted.
        val bluetooth = result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        if (bluetooth.values.all { it }) connect() else ui = ui.copy(link = "Bluetooth access needed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = History(this)
        workouts = Workouts(this)
        nights = Nights(this)
        // Set before anything is drawn. AppCompatDelegate rather than a flag Compose reads,
        // because it switches the whole configuration: the status bar icons come from
        // values-night, and a palette the app picked on its own would leave them wrong.
        nightMode = saved.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(nightMode)
        // Changing it recreates the activity, so remember which page was open across that.
        settingsOpen = savedInstanceState?.getBoolean("settings") == true
        interval = saved.getInt("interval", 15)
        monitors = Ring.Monitors(
            heart = saved.getBoolean("monitorHeart", true),
            oxygen = saved.getBoolean("monitorOxygen", true),
            pressure = saved.getBoolean("monitorPressure", false)
        )
        profile = Profile.read(saved)
        plan = SleepPlan.read(saved)
        ui = ui.copy(
            interval = interval,
            stepGoal = saved.getInt("goal", 10_000),
            metric = profile.metric,
            monitors = monitors,
            celebrate = birthdayGreeting()
        )
        if (intent?.getStringExtra("tab") == "sleep") tab = Tab.Sleep
        setContent {
            VitalsSheet(
                sheet = sheet,
                state = ui,
                report = report,
                onShare = { shareReadings(); sheet = Sheet.None },
                onHealth = { sendToHealthConnect() },
                healthLabel = healthLabel,
                onCalibrate = { systolic, diastolic -> calibratePressure(systolic, diastolic) },
                onDismiss = { sheet = Sheet.None }
            )
            BackHandler(settingsOpen) { closeSettings() }
            if (settingsOpen) {
                SettingsPage(
                    profile = profile,
                    state = ui,
                    firmware = ui.firmware,
                    ringAddress = ringAddress,
                    // Typing writes to the phone on every keystroke, which is cheap. The ring is
                    // only told once, on the way out, rather than a frame per character.
                    nightMode = nightMode,
                    plan = plan,
                    onPlan = {
                        plan = it
                        it.write(saved)
                        // Booked straight away: a reminder the wearer has just switched on and
                        // that only starts working after the next restart is a broken switch.
                        Bedtime.apply(this, it)
                    },
                    onProfile = {
                        profile = it
                        it.write(saved)
                        ui = ui.copy(metric = it.metric, celebrate = birthdayGreeting())
                    },
                    onNightMode = { mode ->
                        nightMode = mode
                        saved.edit().putInt("night", mode).apply()
                        AppCompatDelegate.setDefaultNightMode(mode)
                    },
                    onGoal = { goal ->
                        ui = ui.copy(stepGoal = goal)
                        saved.edit().putInt("goal", goal).apply()
                    },
                    onInterval = { minutes ->
                        interval = minutes
                        saved.edit().putInt("interval", minutes).apply()
                        ui = ui.copy(interval = minutes)
                        applyInterval()
                    },
                    onMonitors = { chosen ->
                        monitors = chosen
                        saved.edit()
                            .putBoolean("monitorHeart", chosen.heart)
                            .putBoolean("monitorOxygen", chosen.oxygen)
                            .putBoolean("monitorPressure", chosen.pressure)
                            .apply()
                        ui = ui.copy(monitors = chosen)
                        applyInterval()
                    },
                    onRepair = { forgetRing() },
                    onExport = { report = history.report(); sheet = Sheet.Export },
                    onBack = { closeSettings() }
                )
            } else {
                Shell(
                    tab = tab,
                    onTab = { tab = it; dayOffset = 0 },
                    state = ui,
                    dayOffset = dayOffset,
                    onDay = { dayOffset = it.coerceAtMost(0) },
                    onMeasure = { type ->
                        measure(type, when (type) {
                            Ring.HEART -> "heart rate"
                            Ring.OXYGEN -> "blood oxygen"
                            else -> "blood pressure"
                        })
                    },
                    onSettings = { settingsOpen = true },
                    onLink = { if (command == null) askThenConnect() },
                    sleepTarget = plan.target,
                    onStartWorkout = { startWorkout(it) },
                    onStopWorkout = { stopWorkout() },
                    onCalibrate = { sheet = Sheet.Calibrate },
                    dayFor = { pageFor(it) }
                )
            }
        }
        showTrend()
        ui = ui.copy(pastWorkouts = workouts.all(), nights = nights.all())
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, overAdb, IntentFilter("uk.co.r99vitals.RUN"), ContextCompat.RECEIVER_EXPORTED
            )
        }
        askThenConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapping the morning report while the app is already open should still land on Sleep.
        if (intent.getStringExtra("tab") == "sleep") { tab = Tab.Sleep; dayOffset = 0 }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("settings", settingsOpen)
    }

    private fun askThenConnect() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Notifications are asked for here too: the bedtime reminder and the morning report
            // are the only things this app ever interrupts anyone with, and both are off until
            // switched on, so asking once alongside Bluetooth is the whole of it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
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
        userAsked = true
        setButtonsEnabled(false)
        enqueue { write(Ring.startMeasuring(type)) }
    }

    /** A real cuff reading, sent once to correct the ring's own pulse-wave estimate. */
    private fun calibratePressure(systolic: Int, diastolic: Int) {
        sheet = Sheet.None
        if (command == null) { ui = ui.copy(link = "Not connected yet"); connect(); return }
        enqueue { write(Ring.calibratePressure(systolic, diastolic)) }
    }

    /**
     * Continuous tracking, for a walk or a workout. The ring keeps sending until told to stop,
     * so readings are kept every fifteen seconds rather than collapsed to one per measurement:
     * during exercise the shape of the climb is the point.
     */
    /**
     * A workout runs the sensor for as long as it lasts. The ring ends a measurement after about
     * half a minute, so the session keeps starting another, and every reading is kept rather
     * than collapsed: during exercise the shape of the climb is the point.
     */
    private fun startWorkout(sport: String) {
        if (command == null) { ui = ui.copy(link = "Not connected yet"); connect(); return }
        ui = ui.copy(
            workout = sport, workoutSince = System.currentTimeMillis(),
            workoutBeats = emptyList(), streaming = true
        )
        enqueue { write(Ring.streamLive(true)) }
        enqueue { write(Ring.startMeasuring(Ring.HEART)) }
        keepMeasuring()
    }

    private fun stopWorkout() {
        // Keep the session whole: its sport, its length and its curve, none of which survive
        // being folded into the day's readings.
        ui.workout?.let { workouts.save(it, ui.workoutSince, ui.workoutBeats) }
        ui = ui.copy(workout = null, streaming = false, pastWorkouts = workouts.all())
        handler.removeCallbacks(keepGoing)
        enqueue { write(Ring.streamLive(false)) }
        enqueue { write(Ring.stopMeasuring()) }
        showTrend()
    }

    /**
     * The ring stops measuring on its own, and the completion event does not always arrive, so
     * the session restarts it on a timer rather than trusting the event.
     */
    private val keepGoing = Runnable {
        if (ui.workout != null && command != null) {
            enqueue { write(Ring.startMeasuring(Ring.HEART)) }
            keepMeasuring()
        }
    }

    /**
     * Battery and charging state only arrive when asked, so ask every couple of minutes. On the
     * charger the level climbs and the state flips, and neither shows if nothing enquires.
     */
    private var watching = false

    private val askBattery = object : Runnable {
        override fun run() {
            if (command != null) enqueue { write(Ring.deviceInfo()) }
            // Often while the screen is being looked at, rarely otherwise. The ring never
            // announces going on or off charge, so the only way to notice is to keep asking.
            handler.postDelayed(this, if (watching) 20_000 else 180_000)
        }
    }

    private fun keepMeasuring() {
        handler.removeCallbacks(keepGoing)
        handler.postDelayed(keepGoing, 35_000)
    }

    private fun setButtonsEnabled(enabled: Boolean) { /* driven by ui.measuring */ }

    /** An interval of Off means nothing is monitored, whatever the individual switches say. */
    private fun chosenMonitors(): Ring.Monitors =
        if (interval > 0) monitors else Ring.Monitors(heart = false, oxygen = false, pressure = false)

    private fun applyInterval() {
        ui = ui.copy(interval = interval)
        if (command == null) { ui = ui.copy(link = "Not connected yet"); return }
        Ring.automaticMonitoring(chosenMonitors(), if (interval > 0) interval else 15)
            .forEach { frame -> enqueue { write(frame) } }
    }

    /**
     * Leaving settings is when the ring is told, so a name typed one letter at a time does not
     * become a frame per letter. Both writes are harmless to repeat.
     */
    private fun closeSettings() {
        settingsOpen = false
        if (command == null) return
        enqueue { write(Ring.setStepGoal(ui.stepGoal)) }
        enqueue {
            write(Ring.setUserInfo(profile.male, profile.age, profile.heightCm, profile.weightKg))
        }
        profile.skinTone?.let { tone -> enqueue { write(Ring.setSkinTone(tone)) } }
    }

    /** Readings already recorded stay put; this forgets the ring, not the history. */
    @SuppressLint("MissingPermission")
    private fun forgetRing() {
        runCatching { gatt?.close() }
        gatt = null
        command = null
        ringAddress = null
        ui = ui.copy(link = "Looking for your ring", connected = false, battery = null, firmware = null)
        settingsOpen = false
        askThenConnect()
    }

    /** One vital, one day, assembled from what has been written down. */
    @androidx.compose.runtime.Composable
    private fun pageFor(which: Tab): VitalDay {
        val start = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = start + 24 * 60 * 60 * 1000
        val kind = when (which) {
            Tab.Oxygen -> "oxygen"
            Tab.Pressure -> "pressure"
            Tab.Steps -> "steps"
            else -> "heart"
        }
        val entries = history.all().filter { it.kind == kind && it.at.time in start until end }
        val readings = entries.map { it.value }
        // Where in the day each reading happened, as a fraction, so the chart can place it.
        val positions = entries.map { ((it.at.time - start).toFloat() / (24 * 60 * 60 * 1000)) }
        val last = entries.lastOrNull()
        return when (which) {
            Tab.Oxygen -> VitalDay(
                "Blood oxygen", "%", Ink.oxygen,
                Icons.Rounded.Bloodtype,
                last?.value?.toString(), readings, entries,
                positions = positions,
                rows = rows(entries) { "${it.value}%" }
            )
            Tab.Pressure -> VitalDay(
                "Blood pressure", "mmHg", Ink.pressure,
                Icons.Rounded.MonitorHeart,
                last?.let { "${it.value}/${it.extra}" }, readings, entries,
                diastolic = entries.map { it.extra },
                note = "Estimated from the pulse waveform, not measured with a cuff.",
                positions = positions,
                rows = rows(entries) { "${it.value}/${it.extra}" }
            )
            Tab.Steps -> {
                // The ring reports a running total, so both the bars and the rows below them are
                // differences between totals. Steps works that out once, for each quarter hour,
                // and the hours are the sums of those.
                val hours = Steps.hours(entries)
                VitalDay(
                    "Movement", "steps", Ink.motion,
                    Icons.Rounded.DirectionsWalk,
                    Steps.total(entries).takeIf { it > 0 }?.let { "%,d".format(it) },
                    hours.map { it.steps }, entries,
                    canMeasure = false, asBars = true,
                    // A quarter hour the ring never reported on is not the same as one spent
                    // still, so only the quarters it did report on are listed.
                    rows = hours.flatMap { it.slots }.map {
                        Reading(
                            "%02d:%02d".format(it.hour, it.minute),
                            if (it.steps > 0) "%,d".format(it.steps) else "0"
                        )
                    }
                )
            }
            else -> VitalDay(
                "Heart rate", "bpm", Ink.heart,
                Icons.Rounded.Favorite,
                last?.value?.toString(), readings, entries,
                positions = positions,
                rows = rows(entries) { it.value.toString() }
            )
        }
    }

    /**
     * A day's readings, worded for the list beneath the chart.
     *
     * Steps are the exception and do their own thing, because a running total has to be
     * differenced before it means anything. Everything else is simply read.
     */
    private fun rows(entries: List<History.Entry>, value: (History.Entry) -> String) =
        entries.map { Reading(hourMinute.format(it.at), value(it), manual = it.manual) }

    /** Worded here so the screen only has to decide whether to show it. */
    private fun birthdayGreeting(): String? {
        if (!profile.birthdayToday) return null
        val who = profile.name.trim().takeIf { it.isNotEmpty() }
        val greeting = if (who != null) "Happy birthday, $who!" else "Happy birthday!"
        return "$greeting You are ${profile.age} today."
    }

    private fun showTrend() {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        ui = ui.copy(
            trend = history.all().filter { it.kind == "heart" && it.at.time >= since }.map { it.value },
            trendCaption = "Last 24 hours · " + history.summary("heart", since)
        )
    }

    /**
     * Hands the readings to Health Connect so other apps on this phone can use them. On-device
     * only: nothing leaves the phone, and this app still has no INTERNET permission.
     */
    private val healthPermission = registerForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(health.permissions)) writeToHealthConnect()
        else healthLabel = "Health Connect permission refused"
    }

    private fun sendToHealthConnect() {
        if (!health.available) {
            healthLabel = "Health Connect is not set up on this phone"
            return
        }
        healthLabel = "Checking Health Connect…"
        lifecycleScope.launch {
            if (health.granted()) writeToHealthConnect()
            else healthPermission.launch(health.permissions)
        }
    }

    private fun writeToHealthConnect() {
        healthLabel = "Sending…"
        lifecycleScope.launch {
            val outcome = runCatching {
                val entries = history.all()
                health.send(entries) + health.sendSteps(entries) +
                    health.sendSleep(SleepInsight.merge(nights.all()))
            }
            healthLabel = outcome.fold(
                onSuccess = { if (it == 0) "Nothing to send yet" else "Sent $it records to Health Connect" },
                onFailure = { "Health Connect refused: ${it.message ?: "unknown"}" }
            )
        }
    }

    private fun shareReadings() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, "Vitals readings")
                    putExtra(Intent.EXTRA_TEXT, history.asCsv())
                }, "Export readings"
            )
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
                    handler.removeCallbacks(askBattery)
                    // Charging is a live state. With no link there is nothing to base it on, so
                    // it is dropped rather than left showing whatever was true when the ring
                    // last spoke, which may have been hours ago.
                    ui = ui.copy(link = "Reconnecting…", connected = false, charging = false)
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) { ui = ui.copy(link = "Could not read the ring"); return@runOnUiThread }
                // Ask for room before asking for anything else. The default ATT payload is 20
                // bytes, and a day of stored readings comes back as a single 150-byte frame:
                // without this the ring answers with a count and the records never arrive at
                // all, which reads exactly like a ring that has not recorded anything.
                enqueue { gatt.requestMtu(517) }
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
                // The readings taken while nothing was listening. The ring keeps them to itself
                // until asked, so every connection asks; History drops the ones already held.
                enqueue { write(Ring.storedHeart()) }
                enqueue { write(Ring.storedPressure()) }
                enqueue { write(Ring.storedOxygen()) }
                // Nights, which are only ever a backfill: the ring stages sleep by itself and
                // hands the record over when asked, never while it is happening.
                // ponytail: asked here and not by the collector, which sends nothing and only
                // listens. The ring holds several nights, so opening the app every few days is
                // enough; give the collector a command channel if that stops being true.
                enqueue { write(Ring.storedSleep()) }
                // The clock is deliberately left alone: writing it makes the ring abandon a
                // running sleep session, which its own log reports as "exit sleep because time
                // change". Sleep data matters more than a few seconds of drift.
                Ring.automaticMonitoring(chosenMonitors(), if (interval > 0) interval else 15)
                    .forEach { frame -> enqueue { write(frame) } }
                enqueue { ui = ui.copy(link = "Your ring"); false }
                handler.removeCallbacks(askBattery)
                handler.post(askBattery)
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
                // This is a bare live mirror of the ring's counter, so it is just as liable as
                // History to catch the ring's stale, not-yet-reset total right after midnight.
                // History already tracks whether that reset has happened; ask it rather than
                // trusting the raw push as today's count.
                val last = history.latest("steps")
                val stillYesterday = last != null && it.steps >= last.value &&
                    !History.sameDay(last.at.time, System.currentTimeMillis())
                if (!stillYesterday) {
                    ui = ui.copy(steps = it.steps, distance = it.distance, calories = it.calories)
                }
            }
            return
        }
        if (characteristic.uuid == Ring.HEART_RATE) {
            Ring.readStandardHeartRate(value)?.let { ui = ui.copy(heart = it) }
            return
        }
        // A night arrives in pieces, so this reader answers with nothing until it holds a whole
        // record and then with every night in it at once.
        sleepReader.accept(value).takeIf { it.isNotEmpty() }?.let { fresh ->
            nights.save(fresh)
            ui = ui.copy(nights = nights.all())
            return
        }
        // The stored records, arriving in reply to the history queries sent on connecting. Each
        // reader recognises its own command and ignores the other two.
        listOf(
            "heart" to Ring.readStoredHeart(value),
            "oxygen" to Ring.readStoredOxygen(value),
            "pressure" to Ring.readStoredPressure(value)
        ).firstOrNull { it.second.isNotEmpty() }?.let { (kind, readings) ->
            history.backfill(kind, readings)
            return
        }
        when (val reading = Ring.read(value)) {
            is Ring.Reading.Heart -> {
                ui = ui.copy(heart = reading.bpm)
                history.record(
                    "heart", reading.bpm,
                    burst = if (ui.workout != null) 10_000L else 90_000L,
                    manual = userAsked
                )
                if (ui.workout != null) ui = ui.copy(workoutBeats = ui.workoutBeats + reading.bpm)
                showTrend()
            }
            is Ring.Reading.Oxygen -> {
                ui = ui.copy(oxygen = reading.percent)
                history.record("oxygen", reading.percent, manual = userAsked)
            }
            is Ring.Reading.Pressure -> {
                ui = ui.copy(systolic = reading.systolic, diastolic = reading.diastolic)
                history.record("pressure", reading.systolic, reading.diastolic, manual = userAsked)
            }
            is Ring.Reading.Power -> ui = ui.copy(
                link = "Your ring", battery = reading.percent, charging = reading.charging,
                firmware = reading.firmware
            )
            // Pushed on its own schedule, ahead of the next poll — see the Battery doc comment
            // in Ring.kt for why the poll stays in place alongside it regardless.
            is Ring.Reading.Battery -> ui = ui.copy(battery = reading.percent)
            is Ring.Reading.Finished -> {
                setButtonsEnabled(true)
                userAsked = false
                ui = ui.copy(measuring = null)
                showTrend()
                if (ui.workout != null) keepMeasuring()
            }
            // ponytail: frames nothing here understands, in debug builds only. This is how the
            // missing history turned up — the ring was answering with a record count and the
            // records themselves were never arriving, which nothing else would have shown.
            else -> if (BuildConfig.DEBUG && reading == null) {
                android.util.Log.d("r99app", "unread ${value.joinToString("") { "%02X".format(it) }}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        watching = true
        handler.removeCallbacks(askBattery)
        handler.post(askBattery)
        // Collection continues with the app closed; starting it here is idempotent.
        if (ringAddress != null) CollectorService.start(this)
        if (command == null && ringAddress != null) askThenConnect()
        showTrend()
    }

    override fun onPause() {
        super.onPause()
        watching = false
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
