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
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
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
    private lateinit var live: LiveSession
    private lateinit var nights: Nights

    /** Holds the frames of a night together until the record inside them is whole. */
    private val sleepReader = SleepReader()

    private var interval = 15   // minutes; 0 means off
    /** Read by the collector, which does the detecting; held here only so Settings can show it. */
    private var autoWorkouts by mutableStateOf(true)
    private var routeSports by mutableStateOf(emptySet<String>())
    private var monitors = Ring.Monitors()
    private var settingsOpen by mutableStateOf(false)
    /** Whether Android leaves the collector alone rather than rationing it; read again on resume. */
    private var unrestricted by mutableStateOf(false)
    /** What Settings says about updates — see Updates, and checkForUpdates for the words. */
    private var updates by mutableStateOf(UpdateState())
    /** Null once done. Every screen it passes through writes as it goes, same as Settings does. */
    private var onboarding by mutableStateOf<OnboardingStep?>(null)
    private var nightMode by mutableStateOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    private var profile by mutableStateOf(Profile())
    private var plan by mutableStateOf(SleepPlan())
    private val saved by lazy { getSharedPreferences("ring", MODE_PRIVATE) }
    private var ringAddress: String?
        get() = saved.getString("address", null)
        set(value) { saved.edit().putString("address", value).apply() }
    /** The ring's own advertised name, read off the device once it has actually answered as one. */
    private var ringName: String?
        get() = saved.getString("ringName", null)
        set(value) { saved.edit().putString("ringName", value).apply() }
    private var scanning = false
    private val found = linkedMapOf<String, ScanResult>()
    private var retryDelay = 0L

    /**
     * Set from the moment a freshly-chosen device is asked to connect until its services come
     * back, so a device that answers with no [Ring.COMMAND_CHANNEL] — a phone, a pair of
     * headphones, anything else nearby that happens to be a connectable BLE device — is told
     * apart from a ring that has merely dropped a routine reconnect.
     */
    private var verifying = false

    /**
     * A device the wearer just chose that is being checked, held separately so it does not
     * overwrite a ring that already works until it has proved itself a ring. Without this, choosing
     * the wrong device — or the real ring caught mid-firmware-update, when it answers as its update
     * loader with no command channel — used to wipe the good pairing on the spot.
     */
    private var pendingAddress: String? = null

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

    /** At most one clock-resync attempt per connection — see resyncClockIfStopped. */
    private var clockSyncedThisConnect = false

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
                    // One onboarding page on its own, to read it without a fresh install:
                    //   --es do onboarding --es step Location
                    "onboarding" -> onboarding = runCatching {
                        OnboardingStep.valueOf(intent.getStringExtra("step") ?: "Splash")
                    }.getOrNull()
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
        live = LiveSession(this)
        nights = Nights(this)
        // Set before anything is drawn. AppCompatDelegate rather than a flag Compose reads,
        // because it switches the whole configuration: the status bar icons come from
        // values-night, and a palette the app picked on its own would leave them wrong.
        nightMode = saved.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(nightMode)
        // Changing it recreates the activity, so remember which page was open across that.
        settingsOpen = savedInstanceState?.getBoolean("settings") == true
        interval = saved.getInt("interval", 15)
        autoWorkouts = saved.getBoolean("autoWorkouts", true)
        routeSports = Route.SPORTS.filter { Route.wanted(this, it) }.toSet()
        monitors = Ring.Monitors(
            heart = saved.getBoolean("monitorHeart", true),
            oxygen = saved.getBoolean("monitorOxygen", true),
            pressure = saved.getBoolean("monitorPressure", false)
        )
        profile = Profile.read(saved)
        plan = SleepPlan.read(saved)
        val onboardingSeen = saved.getInt("onboardingSeen", 0)
        val lastHeart = history.latest("heart")
        val lastOxygen = history.latest("oxygen")
        val lastPressure = history.latest("pressure")
        onboarding = savedInstanceState?.getInt("onboarding", -1)?.takeIf { it >= 0 }
            ?.let { OnboardingStep.entries.getOrNull(it) }
            ?: if (onboardingSeen < ONBOARDING_VERSION) OnboardingStep.Splash else null
        ui = ui.copy(
            heart = lastHeart?.value, heartAt = lastHeart?.at?.time,
            oxygen = lastOxygen?.value, oxygenAt = lastOxygen?.at?.time,
            systolic = lastPressure?.value, diastolic = lastPressure?.extra,
            pressureAt = lastPressure?.at?.time,
            interval = interval,
            stepGoal = saved.getInt("goal", 10_000),
            distanceMetric = profile.distanceMetric,
            monitors = monitors,
            celebrate = birthdayGreeting(),
            ringName = ringName
        )
        tabFor(intent)?.let { tab = it }
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
            if (onboarding != null) {
                OnboardingFlow(
                    step = onboarding!!,
                    profile = profile,
                    onProfile = { updateProfile(it) },
                    stepGoal = ui.stepGoal,
                    onGoal = { updateGoal(it) },
                    plan = plan,
                    onPlan = { updatePlan(it) },
                    link = ui.link,
                    onNext = { advanceOnboarding() },
                    onBack = { retreatOnboarding() },
                    onEnableNotifications = { askNotificationThenAdvance() },
                    onEnableLocation = { askLocationThenAdvance() },
                    onFindRing = { askThenConnect() }
                )
            } else if (settingsOpen) {
                SettingsPage(
                    profile = profile,
                    state = ui,
                    firmware = ui.firmware,
                    ringAddress = ringAddress,
                    // Typing writes to the phone on every keystroke, which is cheap. The ring is
                    // only told once, on the way out, rather than a frame per character.
                    nightMode = nightMode,
                    plan = plan,
                    onPlan = { updatePlan(it) },
                    onProfile = { updateProfile(it) },
                    onNightMode = { mode ->
                        nightMode = mode
                        saved.edit().putInt("night", mode).apply()
                        AppCompatDelegate.setDefaultNightMode(mode)
                    },
                    onGoal = { updateGoal(it) },
                    onInterval = { minutes ->
                        interval = minutes
                        saved.edit().putInt("interval", minutes).apply()
                        ui = ui.copy(interval = minutes)
                        applyInterval()
                    },
                    autoWorkouts = autoWorkouts,
                    onAutoWorkouts = { on ->
                        autoWorkouts = on
                        saved.edit().putBoolean("autoWorkouts", on).apply()
                    },
                    routeSports = routeSports,
                    onRouteSport = { sport, on ->
                        saved.edit().putBoolean("route$sport", on).apply()
                        routeSports = if (on) routeSports + sport else routeSports - sport
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
                    unrestricted = unrestricted,
                    onBackground = { runInBackground() },
                    updates = updates,
                    onUpdateChecks = { on ->
                        Updates.setEnabled(this, on)
                        updates = updates.copy(enabled = on, status = null)
                    },
                    onCheckUpdates = { checkForUpdates() },
                    onCheckFirmware = { checkFirmware() },
                    onTestFirmware = { testFirmware() },
                    onReflash = { reflashCurrent() },
                    onUpdateFirmware = { confirmFirmwareUpdate() },
                    onOpen = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
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
                    onRelabelWorkout = { at, sport -> relabelWorkout(at, sport) },
                    routeOf = { at -> RouteFile(Route.folder(this), at).fixes() },
                    onDeleteRoute = { at ->
                        RouteFile(Route.folder(this), at).delete()
                        ui = ui.copy(routes = routesHeld())
                    },
                    onShareRoute = { at -> shareRoute(at) },
                    onCalibrate = { sheet = Sheet.Calibrate },
                    onRefreshSteps = { refreshSteps() },
                    dayFor = { pageFor(it) }
                )
            }
        }
        showTrend()
        ui = ui.copy(pastWorkouts = workouts.all(), routes = routesHeld(), nights = nights.all())
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, overAdb, IntentFilter("uk.co.r99vitals.RUN"), ContextCompat.RECEIVER_EXPORTED
            )
        }
        // Onboarding's own Pairing screen decides when to reach for the ring; asking here as
        // well would pop the permission dialog under the splash before it has even said why.
        if (onboarding == null) askThenConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapping a notification while the app is already open should still land on its tab.
        tabFor(intent)?.let { tab = it; dayOffset = 0 }
    }

    /** Which tab a notification wants, if it was a notification that opened the app. */
    private fun tabFor(intent: Intent?) = when (intent?.getStringExtra("tab")) {
        "sleep" -> Tab.Sleep
        "workout" -> Tab.Workout
        else -> null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("settings", settingsOpen)
        outState.putInt("onboarding", onboarding?.ordinal ?: -1)
    }

    /** Shared by Settings and onboarding, so a name typed in either place behaves the same way. */
    private fun updateProfile(next: Profile) {
        profile = next
        next.write(saved)
        ui = ui.copy(
            distanceMetric = next.distanceMetric, celebrate = birthdayGreeting(),
            // A name typed in while the ring is already connected should show up in the
            // greeting straight away, not only after the next reconnect.
            link = if (command != null) greeting() else ui.link
        )
    }

    private fun updateGoal(goal: Int) {
        ui = ui.copy(stepGoal = goal)
        saved.edit().putInt("goal", goal).apply()
    }

    private fun updatePlan(next: SleepPlan) {
        plan = next
        next.write(saved)
        // Booked straight away: a reminder the wearer has just switched on and that only starts
        // working after the next restart is a broken switch.
        Bedtime.apply(this, next)
    }

    private val onboardingNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Granted or refused, onboarding moves on either way — see askNotificationThenAdvance.
        advanceOnboarding()
    }

    private fun askNotificationThenAdvance() {
        val already = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (already) advanceOnboarding() else onboardingNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val onboardingLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Refused is as good as skipped: a route is asked for again when a walk is first started.
        advanceOnboarding()
    }

    private fun askLocationThenAdvance() {
        if (Route.permitted(this)) advanceOnboarding()
        else onboardingLocation.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** "Check now" with the Network permission off asks for it first, then checks. */
    private val askNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) checkForUpdates() else updates = updates.copy(status = "Network permission is off")
    }

    /** Settings' "Check now": asks GitHub straight away rather than waiting for the daily check. */
    private fun checkForUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            askNetwork.launch(Manifest.permission.INTERNET)
            return
        }
        updates = updates.copy(status = "Checking…")
        kotlin.concurrent.thread(name = "update-check") {
            val asked = runCatching { Updates.check(this) }
            runOnUiThread {
                val available = Updates.available(this)
                updates = updates.copy(
                    available = available,
                    status = when {
                        asked.isFailure -> "Couldn't reach GitHub"
                        available != null -> null
                        else -> "Up to date"
                    }
                )
            }
        }
    }

    // ---- Ring firmware update -----------------------------------------------------------------

    /** The downloaded image waiting to be flashed, if a check found a newer one. */
    private var firmwareFile: java.io.File? = null
    /** Whether the vendor lists this ring for that build — see [FirmwareUpdate.Result.Available]. */
    private var firmwareApproved = true
    @Volatile private var firmwareBusy = false
    private var ota: RingOta? = null

    /**
     * Looks for newer ring firmware and downloads it — network and disk, so off the main thread,
     * and only when the wearer asks. The flash itself is [confirmFirmwareUpdate]; this only
     * prepares it. See FirmwareUpdate and PROTOCOL.md's "Updating the firmware".
     */
    private fun checkFirmware() {
        if (firmwareBusy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            askNetwork.launch(Manifest.permission.INTERNET)
            return
        }
        val address = ringAddress ?: return
        val version = ui.firmware ?: run { ui = ui.copy(firmwareStatus = "Connect the ring first"); return }
        if (!RingCompatibility.canUpdate(ui.ringName, version)) {
            ui = ui.copy(firmwareStatus = RingCompatibility.reason(ui.ringName, version) ?: "Unsupported ring"); return
        }
        firmwareBusy = true
        ui = ui.copy(firmwareStatus = "Checking…", firmwareUpgradable = false)
        kotlin.concurrent.thread(name = "firmware-check") {
            val result = FirmwareUpdate.check(this, address, version)
            runOnUiThread {
                firmwareBusy = false
                ui = when (result) {
                    is FirmwareUpdate.Result.UpToDate -> ui.copy(firmwareStatus = "Up to date", firmwareUpgradable = false)
                    is FirmwareUpdate.Result.Available -> {
                        firmwareFile = result.ufw
                        firmwareApproved = result.approved
                        val note = if (result.approved) "Update ready: ${result.version}"
                            else "Update ready: ${result.version} · not vendor-approved for this ring"
                        ui.copy(firmwareStatus = note, firmwareUpgradable = true)
                    }
                    is FirmwareUpdate.Result.Failed -> ui.copy(firmwareStatus = result.reason, firmwareUpgradable = false)
                }
            }
        }
    }

    /** The point of no return, so it is a plain question with the hazard spelled out. */
    private fun confirmFirmwareUpdate() {
        val ufw = firmwareFile ?: return
        val address = ringAddress ?: return
        val base = "This rewrites the ring's own software over Bluetooth using the maker's flashing " +
            "process. Keep the ring on its charger and the phone right beside it, and leave the app " +
            "open. If the link drops partway through, the ring can be left unusable."
        // A build the vendor withheld from this ring may have been withheld for a reason; say so
        // plainly rather than bury it, since it raises the odds of exactly that dead ring.
        val message = if (firmwareApproved) base
            else "$base\n\nThe maker does not list your ring for this build — it may not be meant " +
                "for your ring's hardware, which makes a bad flash more likely. Only go on if you " +
                "accept that risk."
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update ring firmware?")
            .setMessage(message)
            .setPositiveButton("Update") { _, _ -> flashFirmware(address, ufw) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Hands the ring to [RingOta] with the link to itself: the always-on collector is stopped and
     * this screen's own connection dropped, so nothing else is driving the ring mid-flash. Either
     * outcome gives collection the ring back — see [resumeAfterFlash].
     */
    /**
     * Deliberately re-flash the version the ring already runs, as a first real-write test: the
     * image is the one known-correct for this ring, so it is the least risky way to prove the
     * flash mechanism before trusting it with a genuine upgrade.
     */
    private fun reflashCurrent() {
        if (firmwareBusy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            askNetwork.launch(Manifest.permission.INTERNET); return
        }
        val address = ringAddress ?: return
        val version = ui.firmware ?: run { ui = ui.copy(firmwareStatus = "Connect the ring first"); return }
        firmwareBusy = true
        ui = ui.copy(firmwareStatus = "Fetching $version…")
        kotlin.concurrent.thread(name = "firmware-reflash") {
            val ufw = FirmwareUpdate.imageForVersion(this, version)
            runOnUiThread {
                firmwareBusy = false
                if (ufw == null) ui = ui.copy(firmwareStatus = "Could not fetch $version")
                else confirmReflash(address, version, ufw)
            }
        }
    }

    private fun confirmReflash(address: String, version: String, ufw: java.io.File) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Re-flash $version?")
            .setMessage(
                "This writes the same firmware the ring already runs ($version), to prove the update " +
                    "works before trusting a real upgrade. It carries the same risk as any flash: keep " +
                    "the ring on its charger and the phone beside it, and leave the app open. If the " +
                    "link drops partway through, the ring can be left unusable."
            )
            .setPositiveButton("Re-flash") { _, _ -> flashFirmware(address, ufw) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The safe half of the flow: run the auth + info exchange but write nothing. */
    private fun testFirmware() {
        val ufw = firmwareFile ?: return
        val address = ringAddress ?: return
        flashFirmware(address, ufw, verifyOnly = true)
    }

    @SuppressLint("MissingPermission")
    private fun flashFirmware(address: String, ufw: java.io.File, verifyOnly: Boolean = false) {
        // Never write to a ring the app is not sure it can safely flash — the last line of defence
        // behind the UI gate. See RingCompatibility.
        if (!RingCompatibility.canUpdate(ui.ringName, ui.firmware)) {
            ui = ui.copy(firmwareStatus = RingCompatibility.reason(ui.ringName, ui.firmware) ?: "Unsupported ring")
            return
        }
        firmwareBusy = true
        ui = ui.copy(firmwareStatus = if (verifyOnly) "Testing…" else "Starting…")
        stopService(Intent(this, CollectorService::class.java))
        handler.removeCallbacks(askBattery)
        gatt?.disconnect(); gatt?.close(); gatt = null; command = null
        ota = RingOta(applicationContext, address, ufw.absolutePath, object : RingOta.Listener {
            override fun onProgress(percent: Int) = runOnUiThread { ui = ui.copy(firmwareStatus = "Updating… $percent%") }
            override fun onReconnecting() = runOnUiThread { ui = ui.copy(firmwareStatus = "Ring restarting…") }
            override fun onVerified(info: String) = runOnUiThread {
                firmwareBusy = false
                ui = ui.copy(firmwareStatus = "Link OK — $info (nothing flashed)")
                ota?.stop(); ota = null; resumeAfterFlash()
            }
            override fun onSuccess() = runOnUiThread {
                firmwareBusy = false; firmwareFile = null
                ui = ui.copy(firmwareStatus = "Updated", firmwareUpgradable = false)
                ota?.stop(); ota = null; resumeAfterFlash()
            }
            override fun onFailure(message: String) = runOnUiThread {
                firmwareBusy = false
                ui = ui.copy(firmwareStatus = "${if (verifyOnly) "Test failed" else "Failed"}: $message")
                ota?.stop(); ota = null; resumeAfterFlash()
            }
        }, verifyOnly = verifyOnly).also { it.start() }
    }

    /** After a flash ends either way, give the ring back to ordinary collection. */
    private fun resumeAfterFlash() {
        CollectorService.start(this)
        handler.post(askBattery)
        if (ringAddress != null) askThenConnect()
    }

    private fun isUnrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** Where the one-time setup has got to. See [setUp]. */
    private enum class Setup { Access, Background, Health }

    /**
     * Everything the app asks for once, in one sitting, the first time the ring connects: the
     * collector has just started, and nothing else is on screen to compete with the system's own
     * dialogs. Each step waits for the one before to be answered, and each is skipped where it is
     * already granted or where this phone has no such thing to grant. Refused, a step is not asked
     * again unprompted.
     *
     * 1. **Network and Sensors**, on the Android builds that make them the wearer's to grant —
     *    GrapheneOS, for one. Standard Android grants network access at install and has no
     *    Sensors permission, so there this step asks nothing. Network is for the update check
     *    alone; see Updates. Vitals reads nothing from the phone's own sensors — the ring's data
     *    arrives over Bluetooth — but Sensors is asked for here so it is not left off by default.
     * 2. **Battery optimisation.** It lets the phone defer the collector while it sleeps, and a
     *    ring that has dropped the link is then not asked for again until something wakes the
     *    phone — which reads, the next day, as hours nobody walked.
     * 3. **Health Connect** — "Fitness and wellness" — so readings can be handed to other apps.
     *    Granted here, the day so far goes across straight away.
     */
    private fun setUp(step: Setup = Setup.Access) {
        when (step) {
            Setup.Access -> {
                val wanted = accessToAsk()
                if (wanted.isEmpty() || saved.getBoolean("askedAccess", false)) return setUp(Setup.Background)
                saved.edit().putBoolean("askedAccess", true).apply()
                setupAccess.launch(wanted.toTypedArray())
            }
            Setup.Background -> {
                unrestricted = isUnrestricted()
                if (unrestricted || saved.getBoolean("askedBackground", false)) return setUp(Setup.Health)
                saved.edit().putBoolean("askedBackground", true).apply()
                val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                if (runCatching { setupBackground.launch(ask) }.isFailure) setUp(Setup.Health)
            }
            Setup.Health -> {
                if (!health.available || saved.getBoolean("askedHealth", false)) return
                saved.edit().putBoolean("askedHealth", true).apply()
                lifecycleScope.launch { if (!health.granted()) healthPermission.launch(health.requested) }
            }
        }
    }

    /**
     * Set when the ring connected while the app was not on screen. A dialog asked for then would
     * never be seen but would still count as asked, so setup waits for the app to come back.
     */
    private var setupPending = false

    private fun setUpWhenSeen() {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) setUp()
        else setupPending = true
    }

    private val setupAccess = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        setUp(Setup.Background)
    }

    private val setupBackground = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = isUnrestricted()
        setUp(Setup.Health)
    }

    /**
     * Network and Sensors, where they exist as permissions and are not yet granted. On standard
     * Android the first is granted at install and the second does not exist, so this is empty.
     */
    private fun accessToAsk() = listOf(Manifest.permission.INTERNET, OTHER_SENSORS).filter { name ->
        runCatching { packageManager.getPermissionInfo(name, 0) }.isSuccess &&
            ContextCompat.checkSelfPermission(this, name) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Settings' "Run in background" row: asks to be left off battery optimisation, or, once that
     * is allowed, opens the app's own settings page — the system dialog cannot take it away again.
     */
    private fun runInBackground() {
        unrestricted = isUnrestricted()
        val intent = if (unrestricted) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        saved.edit().putBoolean("askedBackground", true).apply()
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    private fun advanceOnboarding() {
        val steps = OnboardingStep.entries
        val next = onboarding?.let { steps.getOrNull(steps.indexOf(it) + 1) }
        onboarding = next
        if (next == null) {
            saved.edit().putInt("onboardingSeen", ONBOARDING_VERSION).apply()
            // Already connected if pairing succeeded during onboarding; only chase the ring if
            // it did not, same as a normal launch would.
            if (command == null) askThenConnect()
        }
    }

    private fun retreatOnboarding() {
        val steps = OnboardingStep.entries
        onboarding = onboarding?.let { steps.getOrNull(steps.indexOf(it) - 1) } ?: onboarding
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
        // A candidate being verified takes precedence, so a device the wearer just chose is tried
        // without yet replacing a ring that already works — see [pendingAddress] and rejectNonRing.
        val address = pendingAddress ?: ringAddress
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
                // Held as a candidate, not committed: only a device that proves itself a ring
                // replaces the stored one — see the verify-success path and rejectNonRing.
                pendingAddress = choices[which].first
                verifying = true
                connect()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The chosen device answered, but not as a ring would. Drops that candidate; a ring already
     *  paired is kept and reconnected, not thrown away. */
    @SuppressLint("MissingPermission")
    private fun rejectNonRing() {
        verifying = false
        pendingAddress = null
        gatt?.disconnect(); gatt?.close(); gatt = null; command = null
        if (ringAddress != null) {
            // A ring is already remembered — the chosen device simply was not it. Keep the pairing
            // and go back to it, rather than throwing away a ring that works (which is how a ring
            // caught mid-update, answering only as its loader, used to be forgotten).
            ui = ui.copy(link = "That wasn't your ring — reconnecting", connected = false)
            connect()
        } else {
            ui = ui.copy(link = "That wasn't a ring — tap to choose again", connected = false, ringName = null)
        }
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

    /**
     * Self-heals a stopped or un-reset ring clock using timestamps already arriving on the
     * connections every open of this app makes — no extra command needed, since the ring has
     * no "what time is it" query to ask instead (see PROTOCOL.md). Once per connection: firing
     * again on the next kind of record to arrive would needlessly cost another day of steps.
     */
    private fun resyncClockIfStopped(ringTimestamps: List<Long>) {
        if (clockSyncedThisConnect || !Ring.clockLooksStopped(ringTimestamps)) return
        clockSyncedThisConnect = true
        enqueue { write(Ring.setClock()) }
    }

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

    /**
     * Shared by the passive ACTIVITY push and an explicit GetNowStep reply — same shape, same
     * write. The ring reports a running total that resets on its own midnight, not the phone's,
     * so what is shown is worked out from the day's readings by Steps rather than read off it.
     */
    private fun showSteps(motion: Ring.Reading.Motion, manual: Boolean) {
        history.record("steps", motion.steps, motion.calories, manual = manual)
        val today = Steps.today(history)
        ui = ui.copy(steps = today.steps, distance = motion.distance, calories = today.calories)
    }

    /** Asks the ring for its running step total right now, instead of waiting for the next push. */
    private fun refreshSteps() {
        if (command == null) { ui = ui.copy(link = "Not connected yet"); connect(); return }
        userAsked = true
        enqueue { write(Ring.getNowStep()) }
    }

    /** A real cuff reading, sent once to correct the ring's own pulse-wave estimate. */
    private fun calibratePressure(systolic: Int, diastolic: Int) {
        sheet = Sheet.None
        if (command == null) { ui = ui.copy(link = "Not connected yet"); connect(); return }
        enqueue { write(Ring.calibratePressure(systolic, diastolic)) }
    }

    /**
     * A workout runs the sensor for as long as it lasts, and every reading is kept rather than
     * collapsed: during exercise the shape of the climb is the point.
     *
     * The collector runs it, not this screen. A run is spent with the screen off, and a session
     * held here ended whenever Android reclaimed the activity; the collector is already holding
     * the ring and is kept alive for exactly that. This screen asks, and then shows what it is
     * told — see [readSession].
     */
    private fun startWorkout(sport: String) {
        if (command == null) { ui = ui.copy(link = "Not connected yet"); connect(); return }
        // A walk, run or ride records its route, and location is asked for here, the first time it
        // is wanted, rather than during setup for a feature someone may never use. The workout
        // starts whatever the answer: a route is a part of it, not a condition for it.
        if (Route.wanted(this, sport) && !Route.permitted(this)) {
            routeFor = sport
            askLocation.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        beginWorkout(sport)
    }

    /** The sport waiting on the location answer. */
    private var routeFor: String? = null

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        routeFor?.let { beginWorkout(it) }
        routeFor = null
    }

    private fun beginWorkout(sport: String) {
        CollectorService.startWorkout(this, sport)
        // Shown at once rather than on the next look at the session, which is up to five
        // seconds away and would leave the Start button looking as though it did nothing.
        startedAt = System.currentTimeMillis()
        ui = ui.copy(
            workout = sport, workoutSince = startedAt,
            workoutBeats = emptyList(), streaming = true, workoutDetected = false,
            workoutRoute = emptyList(), workoutRouting = following(sport, detected = false)
        )
    }

    /** Whichever kind of session it is, the collector holds its readings and writes the record. */
    private fun stopWorkout() {
        CollectorService.finishWorkout(this)
        handler.postDelayed({ readSession() }, 800)
    }

    /** When this screen last asked for a session, so the moment before the collector has it is not read as its end. */
    private var startedAt = 0L

    /** Shows the session the collector is running, if it is running one, with its curve so far. */
    private fun readSession() {
        val now = live.read()
        when {
            now != null -> {
                val beats = live.beats()
                val routing = following(now.sport, now.detected)
                val route = if (routing) RouteFile(Route.folder(this), now.since).fixes() else emptyList()
                if (now.sport != ui.workout || now.since != ui.workoutSince || now.detected != ui.workoutDetected ||
                    beats != ui.workoutBeats || route.size != ui.workoutRoute.size || routing != ui.workoutRouting
                ) ui = ui.copy(
                    workout = now.sport, workoutSince = now.since, workoutBeats = beats,
                    workoutDetected = now.detected, streaming = true,
                    workoutRoute = route, workoutRouting = routing
                )
            }
            ui.workout != null && System.currentTimeMillis() - startedAt > 3_000 -> {
                ui = ui.copy(
                    workout = null, workoutDetected = false, workoutBeats = emptyList(),
                    streaming = false, pastWorkouts = workouts.all(), routes = routesHeld()
                )
                showTrend()
            }
        }
    }

    /** Whether a session is following the GPS: the wearer's own, for a sport they want a route for, with location allowed. */
    private fun following(sport: String, detected: Boolean) =
        !detected && Route.wanted(this, sport) && Route.permitted(this)

    /** Finished sessions whose route is still on the phone, by start time. */
    private fun routesHeld(): Set<Long> =
        Route.folder(this).listFiles()?.mapNotNull { it.name.removeSuffix(".csv").toLongOrNull() }?.toSet() ?: emptySet()

    /** Only while the app is in front: nothing needs polling when there is no screen to update. */
    private val watchSession = object : Runnable {
        override fun run() {
            readSession()
            handler.postDelayed(this, 5_000)
        }
    }

    /** The wearer correcting a guess. Only the sport changes, and only for that one session. */
    private fun relabelWorkout(startedAt: Long, sport: String) {
        workouts.relabel(startedAt, sport)
        ui = ui.copy(pastWorkouts = workouts.all(), routes = routesHeld())
    }

    /**
     * Battery and charging state only arrive when asked, so ask every couple of minutes. On the
     * charger the level climbs and the state flips, and neither shows if nothing enquires.
     */
    private var watching = false

    private val askBattery = object : Runnable {
        override fun run() {
            if (command != null) enqueue { write(Ring.deviceInfo()) }
            // The collector runs the wear probe on its own connection; the screen just reflects
            // its latest word so the home page stays in step with what is being recorded.
            ui = ui.copy(worn = saved.getBoolean("worn", true))
            // Often while the screen is being looked at, rarely otherwise. The ring never
            // announces going on or off charge, so the only way to notice is to keep asking.
            handler.postDelayed(this, if (watching) 20_000 else 180_000)
        }
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
            write(Ring.setUserInfo(profile.maleForRing, profile.age, profile.heightCm, profile.weightKg))
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
        ringName = null
        ui = ui.copy(
            link = "Looking for your ring", connected = false, battery = null, firmware = null,
            ringName = null
        )
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
        val entries = history.between(kind, start, end)
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
                // What the counter read when today began. The ring keeps climbing from it until
                // its own midnight, which is not the phone's, so today's first readings are
                // measured from here rather than counted as steps in their own right.
                val baseline = history.latestBefore("steps", start)?.value ?: 0
                // The ring reports a running total, so both the bars and the rows below them are
                // differences between totals. Steps works that out once, for each quarter hour,
                // and the hours are the sums of those.
                val hours = Steps.hours(entries, baseline)
                VitalDay(
                    "Movement", "steps", Ink.motion,
                    Icons.Rounded.DirectionsWalk,
                    Steps.total(entries, baseline).takeIf { it > 0 }?.let { "%,d".format(it) },
                    hours.map { it.steps }, entries,
                    note = if (dayOffset == 0) Steps.silence(history.latest("steps")?.at?.time, System.currentTimeMillis()) else null,
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

    /** What the header says once the ring is actually there to be read. */
    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val time = when { hour < 12 -> "morning"; hour < 18 -> "afternoon"; else -> "evening" }
        val who = profile.firstName.takeIf { it.isNotEmpty() }
        return "Good $time" + (who?.let { ", $it" } ?: "")
    }

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
            trend = history.between("heart", since).map { it.value },
            trendCaption = "Last 24 hours · " + history.summary("heart", since)
        )
    }

    /**
     * Hands the readings to Health Connect so other apps on this phone can use them. On-device
     * only: nothing leaves the phone.
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
            else healthPermission.launch(health.requested)
        }
    }

    private fun writeToHealthConnect() {
        healthLabel = "Sending…"
        lifecycleScope.launch {
            val outcome = runCatching {
                val entries = history.all()
                health.send(entries) + health.sendSteps(entries) +
                    health.sendSleep(SleepInsight.merge(nights.all())) +
                    health.sendWorkouts(workouts.all()) { at -> RouteFile(Route.folder(this@VitalsActivity), at).fixes() }
            }
            healthLabel = outcome.fold(
                onSuccess = { if (it == 0) "Nothing to send yet" else "Sent $it records to Health Connect" },
                onFailure = { "Health Connect refused: ${it.message ?: "unknown"}" }
            )
        }
    }

    /**
     * One route, as GPX, to whichever app the wearer picks. Written to a folder of the cache that
     * is emptied each time, so a shared route does not linger outside its workout.
     */
    private fun shareRoute(startedAt: Long) {
        val session = ui.pastWorkouts.firstOrNull { it.at.time == startedAt } ?: return
        val fixes = RouteFile(Route.folder(this), startedAt).fixes()
        if (fixes.isEmpty()) return
        val folder = java.io.File(cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val file = java.io.File(folder, Gpx.fileName(session.sport, startedAt))
        runCatching { file.writeText(Gpx.of(session.sport, startedAt, fixes)) }.onFailure { return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.shared", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "${session.sport} route")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share route"
            )
        )
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
                // The one check that actually says "ring": nothing else on the command channel
                // answers to this UUID, so a device without it is not one, whatever it looked
                // like in the scan list.
                if (command == null) {
                    if (verifying) rejectNonRing() else ui = ui.copy(link = "Could not read the ring", connected = false)
                    return@runOnUiThread
                }
                verifying = false
                // Proved itself a ring: now, and only now, the candidate becomes the stored ring.
                pendingAddress?.let { ringAddress = it; pendingAddress = null }
                clockSyncedThisConnect = false
                // The name it actually answers to, rather than a placeholder — read once, here,
                // because this is the one moment already gated on the ring having proved itself.
                // Kept if the name will not resolve this time: Android often returns null for a
                // bonded device, and a good name once read should not be lost to that.
                ringName = runCatching { gatt.device.name }.getOrNull() ?: ringName
                ui = ui.copy(ringName = ringName)
                CollectorService.start(this@VitalsActivity)
                if (onboarding == OnboardingStep.Pairing) advanceOnboarding()
                setUpWhenSeen()
                enqueue { write(Ring.deviceInfo()) }
                // The readings taken while nothing was listening. The ring keeps them to itself
                // until asked, so every connection asks; History drops the ones already held.
                enqueue { write(Ring.storedHeart()) }
                enqueue { write(Ring.storedPressure()) }
                enqueue { write(Ring.storedOxygen()) }
                // Nights, which are only ever a backfill: the ring stages sleep by itself and
                // hands the record over when asked, never while it is happening.
                // The collector asks for these as well, on its own connection, so they arrive with
                // the app closed; asking here too only makes an opened app current at once.
                enqueue { write(Ring.storedSleep()) }
                // The clock itself is left alone here: writing it makes the ring abandon a
                // running sleep session, which its own log reports as "exit sleep because time
                // change". resyncClockIfStopped only fires once the ring's own timestamps prove
                // the clock is stuck, not on ordinary drift, so a real sleep session is never at
                // risk — see PROTOCOL.md's "The ring's clock stops".
                Ring.automaticMonitoring(chosenMonitors(), if (interval > 0) interval else 15)
                    .forEach { frame -> enqueue { write(frame) } }
                enqueue { ui = ui.copy(link = greeting()); false }
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
            Ring.readActivity(value)?.let { showSteps(it, manual = false) }
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
            resyncClockIfStopped(fresh.map { it.startedAt })
            return
        }
        // The stored records, arriving in reply to the history queries sent on connecting. Each
        // reader recognises its own command and ignores the other two.
        listOf(
            "heart" to Ring.readStoredHeart(value),
            "oxygen" to Ring.readStoredOxygen(value),
            "pressure" to Ring.readStoredPressure(value)
        ).firstOrNull { it.second.isNotEmpty() }?.let { (kind, readings) ->
            val type = when (kind) {
                "heart" -> Ring.HEART
                "oxygen" -> Ring.OXYGEN
                else -> Ring.PRESSURE
            }
            if (interval > 0 && monitors.allows(type)) history.backfill(kind, readings)
            resyncClockIfStopped(readings.map { it.first })
            return
        }
        // On the charger the sensor reads the case. Shown, since someone looking at the screen can
        // see where the ring is, but not written down unless they asked for it themselves.
        // A reading the wearer asked for is always theirs. Otherwise the ring must be on a finger
        // and off the charger for the sensor to be reading a person rather than the case or air.
        val keep = { reading: Ring.Reading ->
            userAsked || (
                !ui.charging && ui.worn && interval > 0 && monitors.allows(reading) &&
                    System.currentTimeMillis() >= saved.getLong("suppressAutomaticVitalsUntil", 0L)
                )
        }
        when (val reading = Ring.read(value)) {
            is Ring.Reading.Heart -> {
                ui = ui.copy(heart = reading.bpm, heartAt = System.currentTimeMillis())
                if (keep(reading)) history.record(
                    "heart", reading.bpm,
                    burst = if (ui.workout != null) 10_000L else 90_000L,
                    manual = userAsked
                )
                showTrend()
            }
            is Ring.Reading.Oxygen -> {
                ui = ui.copy(oxygen = reading.percent, oxygenAt = System.currentTimeMillis())
                if (keep(reading)) history.record("oxygen", reading.percent, manual = userAsked)
            }
            is Ring.Reading.Pressure -> {
                ui = ui.copy(systolic = reading.systolic, diastolic = reading.diastolic, pressureAt = System.currentTimeMillis())
                if (keep(reading)) history.record("pressure", reading.systolic, reading.diastolic, manual = userAsked)
            }
            // GetNowStep's reply. No Finished event follows a single request/reply command like
            // this one, so the manual flag is cleared here rather than left for that event.
            is Ring.Reading.Motion -> {
                showSteps(reading, manual = userAsked)
                userAsked = false
            }
            is Ring.Reading.Power -> {
                // Written down here as well as by the collector, which asks less often; History
                // only keeps a change, so the two never record one spell twice.
                history.charging(reading.charging)
                ui = ui.copy(
                    link = greeting(), battery = reading.percent, charging = reading.charging,
                    firmware = reading.firmware
                )
            }
            // Pushed on its own schedule, ahead of the next poll — see the Battery doc comment
            // in Ring.kt for why the poll stays in place alongside it regardless.
            is Ring.Reading.Battery -> ui = ui.copy(battery = reading.percent)
            is Ring.Reading.Finished -> {
                setButtonsEnabled(true)
                userAsked = false
                // The ring says here when it refused a measurement for want of a finger. A reading
                // the wearer took themselves is trusted as theirs, so this only steers the
                // automatic ones — see [keep]. Written to the shared pref too, so the battery tick
                // reads back what this just learned rather than overwriting it.
                val wornNow = if (reading.notWorn) false else if (reading.result == Ring.MEASURE_OK) true else ui.worn
                if (wornNow != ui.worn) {
                    ui = ui.copy(worn = wornNow)
                    saved.edit().putBoolean("worn", wornNow).apply()
                }
                ui = ui.copy(measuring = null)
                showTrend()
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
        // Coming back from the system's dialog, or from the app's own settings page.
        unrestricted = isUnrestricted()
        if (setupPending) { setupPending = false; setUp() }
        // The collector may have found a release while the app was closed.
        updates = updates.copy(enabled = Updates.enabled(this), available = Updates.available(this))
        handler.removeCallbacks(askBattery)
        handler.post(askBattery)
        // Collection continues with the app closed; starting it here is idempotent.
        if (ringAddress != null) CollectorService.start(this)
        if (command == null && ringAddress != null) askThenConnect()
        showTrend()
        // A walk taken with the app closed is already recorded by the time it is opened.
        ui = ui.copy(pastWorkouts = workouts.all(), routes = routesHeld())
        handler.removeCallbacks(watchSession)
        handler.post(watchSession)
    }

    override fun onPause() {
        super.onPause()
        watching = false
        handler.removeCallbacks(watchSession)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { unregisterReceiver(overAdb) }
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        super.onDestroy()
    }

    companion object {
        /** Bumped so an existing install sees the onboarding once more; never for anything else. */
        private const val ONBOARDING_VERSION = 2
        /**
         * The Sensors permission some Android builds add, GrapheneOS among them. Named by its
         * string, since standard Android has no such permission to name. See setUp.
         */
        private const val OTHER_SENSORS = "android.permission.OTHER_SENSORS"
    }
}
