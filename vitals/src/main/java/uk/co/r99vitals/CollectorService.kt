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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Keeps the ring's readings arriving while the app is closed.
 *
 * The ring pushes its step count and any measurement in flight by itself, so most of the work is
 * to stay connected and write down what arrives. A connected BLE link is cheap when idle; waking
 * the radio every fifteen minutes to reconnect would cost more.
 *
 * What the ring measures on its own schedule it keeps in its own store rather than pushing,
 * whether or not anything is connected, so that is asked for on connecting and now and then
 * after — see askForStored.
 *
 * No reading reaches the network. The one request this service makes is a daily check for a
 * newer release of the app — see Updates.
 */
class CollectorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var history: History
    private lateinit var nights: Nights
    private lateinit var workouts: Workouts
    private val saved by lazy { getSharedPreferences("ring", Context.MODE_PRIVATE) }

    /**
     * Watches the step counter for a workout, since the ring will not say when one starts.
     *
     * It lives here rather than on the screen because a walk does not wait for the app to be
     * open. This service is the only part of Vitals that is always connected, so it is the only
     * part in a position to notice.
     */
    private val detector = WorkoutDetector()

    /**
     * A session the wearer started from the Workout tab. It is run here rather than by the screen
     * for the same reason as a detected one: a run is spent with the screen off, and a session
     * the screen held ended whenever Android reclaimed it.
     */
    private var manual: LiveSession.Now? = null

    /** The session in flight, written down as it goes so the screen and a restart can read it. */
    private lateinit var live: LiveSession

    /** The phone's GPS, followed only for a walk, run or ride the wearer started. */
    private lateinit var recorder: RouteRecorder
    private var lastFix: Route.Fix? = null

    /** Distance and pace so far, from the fixes the route has kept. */
    private var track: Track? = null
    private var shownFixAt = 0L

    /** What the detected session is written down as, so a walk picking up into a run is noticed. */
    private var detectedSport: String? = null

    private val sessionRunning get() = detector.inProgress || manual != null

    /** The heart curve of the session going on now, kept whole rather than folded into the day. */
    private var sessionBeats = mutableListOf<Int>()
    private var sessionBeatTimes = mutableListOf<Long>()
    private var lastBeatAt = 0L

    /** Set when a previous run of this service left the ring measuring for a lost session. */
    private var settleSensor = false

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

    /**
     * Whether the ring is sitting on its charger, as it last said. Carried over from the history
     * like [lastHeart], so a restart does not forget a spell already under way.
     */
    @Volatile private var charging = false

    /**
     * Whether the ring is on a finger, as the last completed measurement said. Kept fresh by a
     * probe every [WEAR_ASK] — see [watchWear]. Starts true so a genuine reading is never dropped
     * before the first probe has had its say, and is not carried across restarts: unlike charging
     * it changes too often, and the ring gives no history to read it back from.
     */
    @Volatile private var worn = true

    /** Set while a wear probe's own measurement is in flight, so its result is told apart. */
    private var probing = false

    /**
     * A wear probe is a diagnostic measurement, not one requested by the configured schedule.
     * Ignore its optical output, including frames already in flight when the stop command lands.
     */
    private var probeVitalsUntil = 0L

    private fun suppressAutomaticVitalsFor(milliseconds: Long) {
        probeVitalsUntil = maxOf(probeVitalsUntil, System.currentTimeMillis() + milliseconds)
        // The foreground activity can hold a second subscription to the same ring. Tell it that
        // these frames belong to the collector's housekeeping, not to the configured schedule.
        saved.edit().putLong("suppressAutomaticVitalsUntil", probeVitalsUntil).apply()
    }

    /** At most one clock-resync attempt per connection — see resyncClockIfStopped. */
    private var clockSyncedThisConnect = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log = LinkLog(this)
        log.note("collector started")
        history = History(this)
        nights = Nights(this)
        workouts = Workouts(this)
        live = LiveSession(this)
        recorder = RouteRecorder(this)
        // Carried over from what is already written down rather than starting at nothing. The
        // ring re-notifies the reading it holds as soon as anything connects, so a service that
        // began each time not knowing the last value wrote that stale number down once per
        // start — a phantom reading every time the app was opened.
        lastHeart = history.latest("heart")?.value ?: 0
        charging = history.chargingAt()
        // Last known, so the home page and the notification do not flash a wrong wear state before
        // the first probe. See watchWear; the open app reads this same value back.
        worn = saved.getBoolean("worn", true)
        promote(location = false)
        ContextCompat.registerReceiver(
            this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Bedtime.apply(this)
        // A session in flight when the process died. One the wearer started carries on, because
        // they are still out running and said nothing about stopping: its readings so far are read
        // back, and the sensor is started again once the link is up. A detected one does not
        // survive, since the detector's evidence for it went with the process — it leaves the
        // ring streaming for a workout that no longer exists, so the sensor is put back instead.
        val left = live.read()
        if (left != null && !left.detected && System.currentTimeMillis() - left.since < MANUAL_STALE) {
            manual = left
            sessionBeats = live.beats().toMutableList()
            sessionBeatTimes = live.beatTimes().toMutableList()
            log.note("carrying on the ${left.sport} started at ${clock.format(java.util.Date(left.since))}")
        } else {
            // detectedSince is where a session was written down before 0.4.2.
            settleSensor = left != null || saved.contains("detectedSince")
            live.clear()
        }
        saved.edit().remove("detectedSport").remove("detectedSince").remove("manualWorkout").apply()
        handler.post(watchForTheEnd)
        handler.postDelayed(watchSteps, 60_000)
        handler.postDelayed(watchCharging, CHARGING_ASK)
        // Establish the initial state promptly. Waiting a whole regular interval here leaves the
        // UI saying "on finger" for minutes after a restart even when the ring was already off.
        handler.postDelayed(watchWear, FIRST_WEAR_ASK)
        handler.postDelayed(watchForUpdates, 2 * 60_000)
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restarted by the system after being killed: pick the ring back up.
        if (gatt == null) connect()
        // The wearer starting or finishing a session from the Workout tab. The screen cannot do
        // either itself: the session belongs to this service, which owns the detector and the link.
        when (intent?.action) {
            START -> intent.getStringExtra(SPORT)?.let { beginOwn(it) }
            FINISH -> if (manual != null) finishOwn() else handle(detector.finishNow())
        }
        // A route that could not be followed after a restart, because Android lets location be
        // taken up only while the app is in front, is taken up again now that it has been opened.
        followRoute()
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
        // A workout in progress takes the notification over while it lasts: it is the one thing
        // happening that the wearer did not ask for and would want to see. Nothing new is posted
        // for it — this notification is already there — so the app still interrupts nobody.
        val sport = manual?.sport ?: detector.takeIf { it.inProgress }?.sport
        // Steps lead otherwise, the way a step counter should read at a glance on the lock screen.
        val title = when {
            sport != null -> "$sport · ${elapsedMinutes()} min"
            steps > 0 -> "%,d steps".format(steps)
            else -> "Collecting from your ring"
        }
        val detail = when {
            // Steps are only the evidence for a detected session; a ride has next to none.
            sport != null -> listOfNotNull(
                sessionBeats.lastOrNull()?.let { "$it bpm" } ?: "Finding your heart rate",
                "%,d steps".format(detector.steps).takeIf { manual == null },
                routeSoFar()
            ).joinToString(" · ")
            // Said here rather than left looking like a quiet day: nothing is being counted.
            saidOutOfReach -> "Ring out of reach since ${clock.format(java.util.Date(lostAt))}"
            charging -> "Ring on the charger · readings paused"
            !worn -> "Ring off your finger · readings paused"
            steps > 0 -> "$distance m · $calories kcal · $latest"
            else -> latest
        }
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
     * Its own channel, and a quiet one: a ring left charging in another room is worth knowing
     * about when the phone is next looked at, not worth waking anyone for. The phone's own
     * notification settings can make it louder.
     */
    private fun linkChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(LINK_CHANNEL, "Ring connection", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Says when the ring has been out of reach for a while" }
            )
        }
        return LINK_CHANNEL
    }

    /** The link has been gone long enough that a day of steps is going uncounted. */
    private fun announceOutage() {
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, VitalsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hours = (System.currentTimeMillis() - lostAt) / (60 * 60 * 1000)
        val notification = Notification.Builder(this, linkChannel())
            .setContentTitle("Ring out of reach since ${clock.format(java.util.Date(lostAt))}")
            .setContentText("Nothing heard for over ${hours}h. Steps taken meanwhile are counted once it reconnects.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(LINK_NOTIFICATION, notification)
    }

    private fun workoutChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(WORKOUT_CHANNEL, "Workouts found", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Says when a walk or a run has been recorded on its own" }
            )
        }
        return WORKOUT_CHANNEL
    }

    /**
     * Says a session was recorded, for a session nobody asked for.
     *
     * Only detected ones. A workout the wearer started themselves ends with them looking at the
     * screen that ended it, and telling someone what they have just this moment done is noise.
     *
     * Its own channel rather than the collector's, so the phone's own notification settings can
     * silence these without silencing anything else — see the README.
     */
    private fun announce(event: WorkoutDetector.Event.Ended, beats: List<Int>) {
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, VitalsActivity::class.java).putExtra("tab", "workout"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val minutes = ((event.endedAt - event.startedAt) / 60_000).coerceAtLeast(1)
        val detail = buildString {
            append("$minutes min")
            if (event.steps > 0) append(" · %,d steps".format(event.steps))
            if (beats.isNotEmpty()) append(" · average ${beats.average().toInt()} bpm")
        }
        val notification = Notification.Builder(this, workoutChannel())
            .setContentTitle("${event.sport} recorded")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WORKOUT_NOTIFICATION, notification)
    }

    /**
     * A ring whose clock has stopped ticking never resets its step counter either — the running
     * total just sits there, exactly as it would if the wearer genuinely had not moved. Hours
     * past a point where that stops being the likely story, so this says so once, rather than
     * only being found by chance days later. See History.stepsFlatSince and PROTOCOL.md's "the
     * clock does not tick" for the hardware fault behind it.
     */
    private fun checkStepsStuck() {
        // A ring in its case takes no steps, and that is not a fault.
        if (charging) return
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

    // ---- Workouts ---------------------------------------------------------------------------

    /**
     * A session ends by nothing happening, which no push announces, so the clock has to notice.
     * A minute is often enough: the detector only ends a session after three quiet ones.
     */
    private val watchForTheEnd = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            handle(detector.quiet(now))
            // The wearer's own session only ends at their word — unless it has plainly been
            // forgotten, when it is kept as far as it went rather than streaming on for days.
            manual?.takeIf { now - it.since >= MANUAL_STALE }?.let { finishOwn() }
            handler.postDelayed(this, 60_000)
        }
    }

    /**
     * Asks GitHub whether a newer Vitals is out, once a day — see Updates. Here because this
     * service is always running, so a release is noticed without the app being opened. Looked
     * at hourly, so a check that failed for want of signal is tried again within the hour.
     */
    private val watchForUpdates = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 60 * 60_000L)
            if (!Updates.due(this@CollectorService)) return
            kotlin.concurrent.thread(name = "update-check") {
                runCatching { Updates.check(this@CollectorService) }
                    .onSuccess { Updates.available(this@CollectorService)?.let { Updates.announce(this@CollectorService, it) } }
                    .onFailure { log.note("could not ask GitHub for updates: ${it.message}") }
            }
        }
    }

    /**
     * The ring stops measuring on its own after about half a minute, and the completion event
     * does not always arrive, so the session restarts it on a timer rather than trusting one.
     */
    private val keepMeasuring = object : Runnable {
        override fun run() {
            if (!sessionRunning) return
            send(Ring.startMeasuring(Ring.HEART))
            handler.postDelayed(this, 35_000)
        }
    }

    /** Every step total goes past the detector, unless something else has claim to the ring. */
    private fun watchForAWorkout(total: Int) {
        // A case being carried about is not a walk.
        if (charging) {
            detector.abandon()
            return
        }
        if (!saved.getBoolean("autoWorkouts", true)) {
            // Switched off mid-walk: drop the session and hand the sensor back to its schedule.
            if (detector.inProgress) forgetSession(putTheSensorBack = true)
            detector.abandon()
            return
        }
        // The wearer's own workout wins: they have named the sport and the screen is already
        // streaming for it, so detecting the same minutes would record them a second time. The
        // sensor is deliberately left alone here — it is theirs now, not this service's.
        if (manual != null) {
            detector.abandon()
            return
        }
        handle(detector.step(System.currentTimeMillis(), total))
        // The fastest minute labels the session, so a walk can pick up into a run partway.
        if (detector.inProgress && detector.sport != detectedSport) {
            detectedSport = detector.sport
            live.rename(detector.sport)
        }
    }

    private fun handle(event: WorkoutDetector.Event?) {
        when (event) {
            is WorkoutDetector.Event.Started -> begin(event)
            is WorkoutDetector.Event.Ended -> end(event)
            null -> Unit
        }
    }

    private fun begin(event: WorkoutDetector.Event.Started) {
        detectedSport = event.sport
        startSession(event.sport, event.at, detected = true)
    }

    /** A session the wearer started, which takes over from anything the detector was building. */
    private fun beginOwn(sport: String) {
        if (manual != null) return
        // The wearer has named the sport, so a detected session covering the same minutes gives
        // way rather than being recorded a second time. The sensor stays on: it is theirs now.
        if (detector.inProgress) forgetSession(putTheSensorBack = false)
        detector.abandon()
        val now = System.currentTimeMillis()
        manual = LiveSession.Now(sport, now, detected = false)
        log.note("$sport started from the Workout tab")
        startSession(sport, now, detected = false)
        followRoute()
    }

    /**
     * The foreground service types held: always the Bluetooth link, and location only while a
     * route is being recorded.
     *
     * Named on every call, because a call that names none takes every type the manifest declares,
     * and Android refuses location from anyone without the permission, or not in front when asking.
     */
    private fun promote(location: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (location) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION, notification(), types)
        } else startForeground(NOTIFICATION, notification())
    }

    /**
     * Starts following the GPS, if the session running is the wearer's own and has somewhere to go.
     *
     * Quietly does nothing without the permission: the workout is still a workout, and the screen
     * asked for the permission before starting it. Android also refuses location to a service not
     * started from the screen, which is what a collector restarted mid-run is, so that attempt is
     * expected to fail and is tried again the next time the app is opened.
     */
    private fun followRoute() {
        val own = manual ?: return
        if (!Route.wanted(this, own.sport) || recorder.running || !Route.permitted(this)) return
        try {
            promote(location = true)
            val route = RouteFile(Route.folder(this), own.since)
            // Picked up from the file, so a collector restarted mid-run carries the distance on
            // rather than starting it again from where the wearer happens to be now.
            track = Track.of(own.sport, route.fixes())
            recorder.start(route) { fix ->
                lastFix = fix
                track?.add(fix)
                // A fix a second; the lock screen does not need telling that often.
                if (fix.at - shownFixAt >= 5_000) { shownFixAt = fix.at; refresh() }
            }
            log.note("following the route" + if (recorder.enabled) "" else ", but location is switched off")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException, or SecurityException, depending on why.
            log.note("route not followed: ${e.javaClass.simpleName}")
            recorder.stop()
            runCatching { promote(location = false) }
        }
    }

    private fun stopRoute() {
        if (!recorder.running) return
        recorder.stop()
        lastFix = null
        track = null
        promote(location = false)
    }

    /**
     * "2.41 km · 5:32 /km" once there is a route to speak of, and until then how the GPS is doing,
     * so a phone that cannot see the sky says so rather than showing nothing.
     */
    private fun routeSoFar(): String? {
        if (!recorder.running) return null
        val sport = manual?.sport ?: return null
        val so = track
        if (so == null || !so.started) {
            return lastFix?.accuracy?.let { "Waiting for GPS (±${it.toInt()} m)" } ?: "Waiting for GPS"
        }
        val metric = saved.getBoolean("distanceMetric", false)
        return listOfNotNull(
            Units.distance(so.metres.toInt(), metric),
            so.currentSpeed()?.let { Track.rate(sport, it, metric) }
        ).joinToString(" · ")
    }

    private fun startSession(sport: String, since: Long, detected: Boolean) {
        sessionBeats = mutableListOf()
        sessionBeatTimes = mutableListOf()
        lastBeatAt = 0L
        // Written down so the screen can show the session, and so a service killed mid-session
        // knows on restart what it was in the middle of.
        live.begin(sport, since, detected)
        measureForTheSession()
        refresh()
    }

    /**
     * The ring measures once and stops unless told to keep going. Half a minute of heart rate
     * every fifteen minutes is the shape of a resting day, not of a workout. Said again whenever
     * the link comes back, since a ring that dropped out mid-run has forgotten it was asked.
     */
    private fun measureForTheSession() {
        send(Ring.streamLive(true), Ring.startMeasuring(Ring.HEART))
        handler.removeCallbacks(keepMeasuring)
        handler.postDelayed(keepMeasuring, 35_000)
    }

    /** The wearer finishing their own session. Kept whole: its sport, its length, its curve and its route. */
    private fun finishOwn() {
        val own = manual ?: return
        stopRoute()
        // Measured from the file rather than the live track, which a restart mid-run may not have
        // been able to pick back up: the file has every fix whoever was listening at the time.
        val route = RouteFile(Route.folder(this), own.since)
        val went = Track.of(own.sport, route.fixes())
        val kept = workouts.save(
            own.sport, own.since, sessionBeats.toList(),
            metres = went.metres.toInt(), movingSeconds = (went.movingMillis / 1000).toInt(),
            beatTimes = sessionBeatTimes.toList()
        )
        // A route belongs to a session. One that was not worth keeping takes its route with it.
        if (!kept) route.delete()
        log.note("${own.sport} finished from the Workout tab" + if (went.metres > 0) ", ${went.metres.toInt()} m" else "")
        manual = null
        forgetSession(putTheSensorBack = true)
        refresh()
    }

    private fun end(event: WorkoutDetector.Event.Ended) {
        val beats = sessionBeats.toList()
        workouts.save(
            sport = event.sport, startedAt = event.startedAt, beats = beats,
            endedAt = event.endedAt, detected = true, steps = event.steps,
            beatTimes = sessionBeatTimes.toList()
        )
        forgetSession(putTheSensorBack = true)
        // After the save, never before: the notification says a session was recorded, and it is
        // only true once it has been.
        announce(event, beats)
        refresh()
    }

    /**
     * Forgets the session was running, and stops driving the sensor unless something else has
     * taken it over — a workout the wearer has just started is streaming for its own reasons.
     */
    private fun forgetSession(putTheSensorBack: Boolean) {
        handler.removeCallbacks(keepMeasuring)
        if (putTheSensorBack) send(Ring.stopMeasuring(), Ring.streamLive(false))
        live.clear()
        detectedSport = null
        sessionBeats = mutableListOf()
        sessionBeatTimes = mutableListOf()
        lastBeatAt = 0L
    }

    private fun elapsedMinutes() = ((System.currentTimeMillis() - (manual?.since ?: detector.since)) / 60_000)

    /**
     * A heart rate, to whichever record it belongs to — never both.
     *
     * While a workout runs, the sensor is streaming for the workout, and its readings are the
     * workout's curve: written into the day's readings too, a run's hour of 150s sat among the
     * resting 60s and dragged every average and trend. So a session's readings go to the session
     * alone, and the day's readings are what the ring measured on its schedule or when asked.
     */
    private fun heard(bpm: Int) {
        latest = "$bpm bpm"
        if (sessionRunning) keepBeat(bpm)
        else if (chosenMonitors().heart) history.record("heart", bpm)
        refresh()
    }


    /**
     * A beat, if it belongs to a session and is not one the last few seconds already hold.
     *
     * The ring pushes far faster than a curve needs while it is streaming, and every reading
     * kept ends up on one line of the workouts file.
     */
    private fun keepBeat(bpm: Int) {
        if (!sessionRunning) return
        val now = System.currentTimeMillis()
        if (now - lastBeatAt < 5_000) return
        lastBeatAt = now
        sessionBeats.add(bpm)
        sessionBeatTimes.add(now)
        live.beat(bpm, now)
        refresh()
    }

    /** Frames for the ring, sent one at a time through [queue]. */
    private fun send(vararg frames: ByteArray) {
        frames.forEach { frame -> enqueue { gatt?.let { writeCommand(it, frame) } ?: false } }
    }

    // ---- One request at a time -------------------------------------------------------------

    /**
     * Android carries one outstanding request per connection and refuses the next until the
     * last is answered, so requests wait here for their answer rather than going out on a timer.
     *
     * This used to be subscriptions spaced 350 ms apart with nothing checking they took. Any
     * that landed while the one before was still in flight was refused without a word: at 23:55
     * on 10 September the collector reconnected, heard heart rates for the next 45 minutes and
     * not one step, while the ring pushed steps the moment the app's own connection subscribed.
     */
    private val queue = ArrayDeque<() -> Boolean>()
    private var running = false
    private var request = 0

    /** [work] returns whether it went out; one that did not is skipped rather than waited on. */
    private fun enqueue(work: () -> Boolean) {
        handler.post {
            queue.addLast(work)
            if (!running) runNext()
        }
    }

    private fun runNext() {
        val work = queue.removeFirstOrNull()
        if (work == null) { running = false; return }
        running = true
        val mine = ++request
        if (!work()) { handler.post { finish(mine) }; return }
        // A request the ring accepts but never answers must not wedge everything behind it.
        handler.postDelayed({ finish(mine) }, 5_000)
    }

    private fun finish(which: Int) { if (which == request) runNext() }
    private fun answered() { handler.post { finish(request) } }

    private fun forgetRequests() { queue.clear(); running = false; request++ }

    // ---- Holding the link ------------------------------------------------------------------

    /** Whether the ring is connected, as the last state change said. */
    @Volatile private var connected = false

    /** When the step counter last pushed, or the link last came up, whichever is later. */
    @Volatile private var lastSteps = 0L

    /** When the step count was last asked for, and when the ring last answered. See [watchSteps]. */
    private var lastAsked = 0L
    @Volatile private var lastAnswered = 0L

    /** Since when there has been no link; 0 while there is one. */
    @Volatile private var lostAt = 0L
    /** Whether the ongoing notification says so, and whether the outage has been announced. */
    private var saidOutOfReach = false
    private var announcedOutage = false

    private lateinit var log: LinkLog
    private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)

    /** Short enough to read in the log: `fea1`, `2a37`, `be940001`. */
    private fun short(characteristic: BluetoothGattCharacteristic) =
        characteristic.uuid.toString().take(8).trimStart('0')

    @SuppressLint("MissingPermission")
    private fun connect() {
        // Every path here is a spell without a link — first start, a drop, a restart, Bluetooth
        // switched off — and every one of them should be noticed if it goes on.
        if (lostAt == 0L) lostAt = System.currentTimeMillis()
        val address = getSharedPreferences("ring", Context.MODE_PRIVATE).getString("address", null)
            ?: return stopSelf()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) { retry(); return }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return stopSelf()
        gatt?.close()
        forgetRequests()
        connected = false
        lastSteps = System.currentTimeMillis()
        log.note("asking for the ring")
        // autoConnect: the request waits in the Bluetooth controller until the ring is back,
        // however long that takes, with no timer of this service's involved. A direct connection
        // gives up after thirty seconds, and the next attempt then waited on this service's own
        // timer — which does not run while the phone sleeps. That is how a link dropped at 12:30
        // on 6 September stayed dropped until the app was next opened, at 21:13.
        gatt = device.connectGatt(this, true, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * An attempt refused outright, rather than one waiting for the ring: back off, so a Bluetooth
     * stack that is refusing everything is not asked again in a tight loop.
     */
    private fun retry() {
        backoff = when (backoff) { 0L -> 5_000; 5_000L -> 20_000; 20_000L -> 60_000; else -> 300_000 }
        handler.postDelayed({ connect() }, backoff)
    }

    /**
     * Once a minute: keep the step count coming, or say that it is not.
     *
     * The ring pushes its counter every couple of seconds for as long as it is subscribed, moving
     * or not, so minutes without a push on a live link mean the subscription has gone rather than
     * that the wearer is sitting still. Asked again first, since that is cheap; if even that
     * brings nothing, the link is started over, which redoes every subscription.
     *
     * The count is also asked for outright every few minutes, whatever the pushes are doing. The
     * answer comes back on the command channel rather than the counter's own, so one path failing
     * no longer means steps going unrecorded.
     */
    private val watchSteps = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 60_000)
            val now = System.currentTimeMillis()
            val link = gatt?.takeIf { connected }
            if (link == null) { noticeOutage(now); return }
            if (now - lastStoredAsk >= STORED_ASK) askForStored()
            if (now - lastAsked >= STEPS_ASK) {
                if (lastAsked != 0L && lastAnswered < lastAsked) log.note("no answer to the last step count request")
                askForSteps(link)
            }
            val quiet = now - lastSteps
            if (quiet < STEPS_QUIET) return
            val activity = link.services.flatMap { it.characteristics }.firstOrNull { it.uuid == Ring.ACTIVITY }
            if (activity != null && quiet < STEPS_LOST) {
                log.note("no step push for ${quiet / 60_000} min: subscribing again")
                enqueue { subscribe(link, activity) }
            } else {
                log.note("no step push for ${quiet / 60_000} min: starting the link over")
                connect()
            }
        }
    }

    /**
     * Every couple of minutes: ask whether the ring is on the charger.
     *
     * It never says so by itself, and on the charger its sensor still takes readings — of the
     * case rather than a finger — which would otherwise be written down as heart rates and blood
     * oxygen. See [noteCharging] for what changes while it is there.
     */
    private val watchCharging = object : Runnable {
        override fun run() {
            handler.postDelayed(this, CHARGING_ASK)
            gatt?.takeIf { connected }?.let { link -> enqueue { writeCommand(link, Ring.deviceInfo()) } }
        }
    }

    /**
     * The charging state as the ring just reported it. A change is written to the history, where
     * it marks the spell — so readings the ring stored meanwhile are dropped when backfilled —
     * and to the link log. A session that the detector had started is dropped rather than saved.
     */
    private fun noteCharging(on: Boolean) {
        // The open app may have written the change down first, so what this service acts on is
        // its own last word rather than whether the history took the row.
        history.charging(on)
        if (on == charging) return
        charging = on
        log.note(if (on) "ring on the charger: pausing readings" else "ring off the charger: readings resume")
        if (on && detector.inProgress) {
            forgetSession(putTheSensorBack = true)
            detector.abandon()
        }
        refresh()
    }

    /**
     * Every [WEAR_ASK], off the charger and outside a workout: run a short measurement purely to
     * learn whether the ring is on a finger. It is the only on-finger signal this firmware gives
     * over BLE — the wear-status commands are refused and the SIG contact bit is stuck, so
     * PROTOCOL.md's finger-detection section settles on this. A measurement taken off the finger
     * the ring aborts in about a second with a `04 0E` result of [Ring.MEASURE_NOT_WORN] and no
     * reading; a real one streams heart values, which [store] records as usual and which end the
     * probe early in [markWorn]. Skipped while charging (already paused) or mid-workout (those
     * measurements report wear for free).
     */
    private val watchWear = object : Runnable {
        override fun run() {
            handler.postDelayed(this, WEAR_ASK)
            if (charging || probing || sessionRunning) return
            val link = gatt?.takeIf { connected } ?: return
            probing = true
            suppressAutomaticVitalsFor(PROBE_TIMEOUT + PROBE_SETTLE)
            enqueue { writeCommand(link, Ring.startMeasuring(Ring.HEART)) }
            // A probe that brings back neither a reading nor a result is abandoned, so a lost one
            // does not leave the sensor running or the flag stuck.
            handler.postDelayed(endProbe, PROBE_TIMEOUT)
        }
    }

    private val endProbe = Runnable { endProbeNow() }

    /** Stops a probe's measurement, once its answer is in or it has waited long enough. */
    private fun endProbeNow() {
        if (!probing) return
        probing = false
        handler.removeCallbacks(endProbe)
        send(Ring.stopMeasuring())
    }

    /**
     * On or off a finger, as a completed measurement just said. Only a change is logged and shown;
     * a probe in flight is ended here, since its answer has now arrived.
     */
    private fun markWorn(on: Boolean) {
        endProbeNow()
        if (on == worn) return
        worn = on
        // Shared with the open app, which shows it on the home page and cannot probe for itself.
        saved.edit().putBoolean("worn", on).apply()
        log.note(if (on) "ring on a finger: readings resume" else "ring off a finger: readings paused")
        refresh()
    }

    /** The current phone-side policy; needed because this firmware does not honour every flag. */
    private fun chosenMonitors() = if (saved.getInt("interval", 15) > 0) {
        Ring.Monitors(
            heart = saved.getBoolean("monitorHeart", true),
            oxygen = saved.getBoolean("monitorOxygen", true),
            pressure = saved.getBoolean("monitorPressure", false)
        )
    } else Ring.Monitors(heart = false, oxygen = false, pressure = false)

    /**
     * The readings the ring took by itself: on connecting, and every [STORED_ASK] while connected.
     *
     * The ring measures on the schedule it was given whether or not a phone is listening, and
     * says nothing when it does — see PROTOCOL.md's "Automatic readings are stored, never pushed".
     * The records wait in its own store until asked for, and are rotated away once that fills, so
     * a collector that only listened recovered a walk out of range only if the app happened to be
     * opened before the ring made room. History drops any it already holds.
     */
    private fun askForStored() {
        lastStoredAsk = System.currentTimeMillis()
        send(Ring.storedHeart(), Ring.storedPressure(), Ring.storedOxygen())
    }

    private var lastStoredAsk = 0L

    private fun askForSteps(link: BluetoothGatt) {
        lastAsked = System.currentTimeMillis()
        enqueue { writeCommand(link, Ring.getNowStep()) }
    }

    /**
     * No link: say so on the ongoing notification after a few minutes, and with a notification
     * of its own after a couple of hours, once per outage. A missing link otherwise looks exactly
     * like a day without walking.
     */
    private fun noticeOutage(now: Long) {
        val since = lostAt.takeIf { it != 0L } ?: return
        if (!saidOutOfReach && now - since >= OUT_OF_REACH) {
            saidOutOfReach = true
            refresh()
        }
        if (!announcedOutage && now - since >= OUTAGE) {
            announcedOutage = true
            log.note("out of reach for ${(now - since) / 60_000} min: telling the wearer")
            announceOutage()
        }
    }

    /** Back in reach: take the outage off the notifications, and write down how long it was. */
    private fun backInReach() {
        val since = lostAt
        lostAt = 0L
        if (since != 0L) log.note("connected after ${(System.currentTimeMillis() - since) / 1000} s without a link")
        if (announcedOutage) getSystemService(NotificationManager::class.java).cancel(LINK_NOTIFICATION)
        announcedOutage = false
        if (saidOutOfReach) { saidOutOfReach = false; refresh() }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                log.note("connected (status $status)")
                backoff = 0
                connected = true
                // A subscription immediately replays the value the characteristic already held.
                // It is not a new measurement, even when it differs from the last database row.
                suppressAutomaticVitalsFor(CONNECTION_SETTLE)
                lastSteps = System.currentTimeMillis()
                handler.post { backInReach() }
                gatt.discoverServices()
            } else {
                val wasConnected = connected
                connected = false
                log.note(if (wasConnected) "link dropped (status $status)" else "could not connect (status $status)")
                // A link that was up and has dropped is asked for again at once; the request
                // then waits for the ring by itself. See connect().
                if (wasConnected) {
                    lostAt = System.currentTimeMillis()
                    handler.post { connect() }
                } else retry()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // Connected but unread is connected to nothing; start over rather than sit on it.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log.note("could not read the ring's services (status $status): disconnecting")
                gatt.disconnect()
                return
            }
            // Subscribe and then stay quiet: the ring pushes on its own schedule.
            gatt.services.flatMap { it.characteristics }
                .filter {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
                .forEach { characteristic -> enqueue { subscribe(gatt, characteristic) } }
            // Sleep is the one thing that has to be asked for. The ring stages a night by itself,
            // says nothing, and drops the record within about a day — so a collector that only
            // listens loses every night the wearer does not happen to open the app for.
            //
            // The room has to be asked for first: the default 20-byte payload is far smaller than
            // a night, and without this the ring answers with a count and the record never comes.
            // If the request is refused the queue carries on, and the reader drops what does not
            // fit rather than the nights never being asked for at all.
            sleepReader = SleepReader()
            clockSyncedThisConnect = false
            enqueue { gatt.requestMtu(517) }
            enqueue { writeCommand(gatt, Ring.storedSleep()) }
            // And the readings the ring took on its own schedule while out of reach, which it
            // likewise keeps to itself until asked. See askForStored.
            handler.post { askForStored() }
            // The count as it stands, straight away: after a gap this is the first word of the
            // steps taken while nothing was listening, rather than waiting for the next push.
            handler.post { askForSteps(gatt) }
            // And whether it is charging, before the first reading is taken at its word.
            enqueue { writeCommand(gatt, Ring.deviceInfo()) }
            if (sessionRunning) handler.post { measureForTheSession() }
            else if (settleSensor) send(Ring.stopMeasuring(), Ring.streamLive(false))
            settleSensor = false
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log.note("room for $mtu bytes (status $status)")
            answered()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log.note("subscribed ${short(descriptor.characteristic)}: " + if (status == BluetoothGatt.GATT_SUCCESS) "ok" else "failed (status $status)")
            answered()
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = answered()

        @Deprecated("Superseded on Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            store(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            store(characteristic, value)
        }
    }

    /**
     * Self-heals a stopped or un-reset ring clock, using the sleep timestamps this service
     * already asks for on every connection — this is the collector's only source of ring-side
     * timestamps, since it otherwise only listens rather than requesting history. See
     * VitalsActivity's resyncClockIfStopped for the foreground counterpart and why once per
     * connection is enough.
     */
    @SuppressLint("MissingPermission")
    private fun resyncClockIfStopped(gatt: BluetoothGatt, ringTimestamps: List<Long>) {
        if (clockSyncedThisConnect || !Ring.clockLooksStopped(ringTimestamps)) return
        clockSyncedThisConnect = true
        enqueue { writeCommand(gatt, Ring.setClock()) }
    }

    /** Whether the write went out. Only ever called from [queue]. */
    @SuppressLint("MissingPermission")
    private fun writeCommand(gatt: BluetoothGatt, frame: ByteArray): Boolean {
        val channel = gatt.services.firstNotNullOfOrNull {
            it.getCharacteristic(Ring.COMMAND_CHANNEL)
        } ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(channel, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") channel.value = frame
            @Suppress("DEPRECATION") channel.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") gatt.writeCharacteristic(channel)
        }
    }

    /** Whether the subscription went out. Only ever called from [queue]. */
    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(Ring.CLIENT_CONFIG) ?: return false
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") descriptor.value = value
            @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
        }
        if (!sent) log.note("subscribing ${short(characteristic)} refused before it went out")
        return sent
    }

    private fun store(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == Ring.ACTIVITY) {
            Ring.readActivity(value)?.let { recordSteps(it, pushed = true) }
            return
        }
        // Where the ring's own periodic sampling lands: it reports automatic heart readings on
        // the standard SIG characteristic, not as an 06 01 frame.
        //
        // Only a changed value counts. The ring re-notifies this characteristic on its ~90 s
        // housekeeping tick whether or not it has measured, holding the last number it took, so
        // writing down every push records one stale reading a minute rather than a measurement.
        // ponytail: a fresh measurement landing on exactly the previous bpm is indistinguishable
        // from the held value and is lost.
        if (characteristic.uuid == Ring.HEART_RATE) {
            Ring.readStandardHeartRate(value)?.takeIf { it != lastHeart }?.let {
                lastHeart = it
                // A changed value here during a probe is the ring measuring a finger, which is the
                // probe's whole answer: on, and end it. Off the finger this bit stays put.
                if (probing) markWorn(true)
                if (charging || !worn || System.currentTimeMillis() < probeVitalsUntil) return
                heard(it)
            }
            return
        }
        // The nights, in reply to the query sent on connecting.
        sleepReader.accept(value).takeIf { it.isNotEmpty() }?.let {
            nights.save(it)
            return
        }
        // The stored records, in reply to askForStored. Each reader recognises its own command and
        // ignores the other two; a monitor switched off in the app keeps its records out.
        listOf(
            Triple("heart", Ring.HEART, Ring.readStoredHeart(value)),
            Triple("oxygen", Ring.OXYGEN, Ring.readStoredOxygen(value)),
            Triple("pressure", Ring.PRESSURE, Ring.readStoredPressure(value))
        ).firstOrNull { it.third.isNotEmpty() }?.let { (kind, type, readings) ->
            log.note("${readings.size} stored $kind records, newest ${readings.maxOf { it.first }.let { clock.format(java.util.Date(it)) }}")
            if (chosenMonitors().allows(type)) history.backfill(kind, readings, outside = workouts.spans(live))
            // Not taken as evidence about the clock, as sleep is. The store keeps records stamped
            // while the clock was wrong long after it is put right, and this is asked on every
            // connection, night included — where setting the clock ends the night being staged.
            return
        }
        val reading = Ring.read(value)
        // A probe's own first live reading proves the ring is on a finger; let it through to say
        // so before the gate below could drop it.
        if (probing && reading is Ring.Reading.Heart) markWorn(true)
        if (reading != null &&
            (reading is Ring.Reading.Heart || reading is Ring.Reading.Oxygen || reading is Ring.Reading.Pressure) &&
            (System.currentTimeMillis() < probeVitalsUntil ||
                // A workout's heart rate is kept whatever the schedule's switches say: they are
                // about the day's readings, and a workout asked for its own.
                (!chosenMonitors().allows(reading) && !(sessionRunning && reading is Ring.Reading.Heart)))) return
        // On the charger, or off the finger, the sensor is reading the case or the air rather than
        // the wearer: drop the live vitals, keep everything else.
        if ((charging || !worn) && (reading is Ring.Reading.Heart || reading is Ring.Reading.Oxygen || reading is Ring.Reading.Pressure)) return
        when (reading) {
            is Ring.Reading.Heart -> heard(reading.bpm)
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
            // The answer to asking for the count outright — see watchSteps.
            is Ring.Reading.Motion -> {
                lastAnswered = System.currentTimeMillis()
                recordSteps(reading, pushed = false)
            }
            // The answer to asking about charging — see watchCharging.
            is Ring.Reading.Power -> noteCharging(reading.charging)
            // How a measurement ended, whether a probe's or a workout's: the ring says here when
            // it refused for want of a finger — see watchWear.
            is Ring.Reading.Finished -> when {
                reading.notWorn -> markWorn(false)
                reading.result == Ring.MEASURE_OK -> markWorn(true)
                else -> endProbeNow()
            }
            // ponytail: heart arrives on the SIG characteristic above, but automatic blood
            // oxygen and pressure still show up nowhere. Log what else the ring pushes while
            // unattended; drop this once those two are identified as well. Frames this app
            // knows but does not collect, such as the battery push, are not the mystery.
            else -> if (BuildConfig.DEBUG && reading == null) {
                android.util.Log.d("r99", "unrecognised push ${value.joinToString("") { "%02X".format(it) }}")
            }
        }
    }

    /** A step total from either path: the ring's own push, or its answer when asked. */
    private fun recordSteps(motion: Ring.Reading.Motion, pushed: Boolean) {
        val now = System.currentTimeMillis()
        history.record("steps", motion.steps, motion.calories)
        val today = Steps.today(history)
        steps = today.steps
        distance = motion.distance
        calories = today.calories
        // Only pushes carry a cadence. An answer every few minutes would read to the detector as
        // one long stride, and it already counts a jump after a silence as nothing.
        if (pushed) {
            if (now - lastSteps >= STEPS_QUIET) log.note("step pushes back after ${(now - lastSteps) / 60_000} min")
            lastSteps = now
            watchForAWorkout(motion.steps)
        }
        refresh()
        checkStepsStuck()
    }

    /**
     * Phone unlocked: ask the ring for the night before saying anything about it, because the
     * record is often written only as the wearer gets up, and then report if there is something
     * worth reporting.
     */
    private fun reportOnWaking() {
        val plan = SleepPlan.read(this)
        if (!plan.report) return
        gatt?.let { link -> enqueue { writeCommand(link, Ring.storedSleep()) } }
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
        recorder.stop()
        gatt?.disconnect()
        gatt?.close()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "readings"
        private const val NOTIFICATION = 1
        private const val PROBLEM_CHANNEL = "problems"
        private const val PROBLEM_NOTIFICATION = 2
        private const val WORKOUT_CHANNEL = "workouts"
        private const val WORKOUT_NOTIFICATION = 3
        private const val STUCK_THRESHOLD = 3 * 60 * 60 * 1000L
        /** No step push for this long on a live link: ask for them again. See watchSteps. */
        private const val STEPS_QUIET = 2 * 60 * 1000L
        /** Still none after asking for this long: start the link over. */
        private const val STEPS_LOST = 10 * 60 * 1000L
        /** How often the count is asked for outright, pushes or not. */
        private const val STEPS_ASK = 5 * 60 * 1000L
        /** How often the readings the ring stored by itself are asked for while connected. */
        private const val STORED_ASK = 30 * 60 * 1000L
        /** How often the ring is asked whether it is on the charger. */
        private const val CHARGING_ASK = 2 * 60 * 1000L
        /** How often the ring is probed for whether it is on a finger. See watchWear. */
        private const val WEAR_ASK = 60 * 1000L
        /** The first state should be known soon after a collector restart and connection. */
        private const val FIRST_WEAR_ASK = 15 * 1000L
        /** A wear probe bringing back nothing is abandoned after this. */
        private const val PROBE_TIMEOUT = 8 * 1000L
        /** Lets optical notifications already queued by a stopped probe drain harmlessly. */
        private const val PROBE_SETTLE = 2 * 1000L
        /** Drops held characteristic values replayed while a new link is being established. */
        private const val CONNECTION_SETTLE = 8 * 1000L
        /** No link for this long: the ongoing notification says so. */
        private const val OUT_OF_REACH = 5 * 60 * 1000L
        /** And for this long: a notification of its own. */
        private const val OUTAGE = 2 * 60 * 60 * 1000L
        private const val LINK_CHANNEL = "link"
        private const val LINK_NOTIFICATION = 4
        private const val START = "uk.co.r99vitals.START_WORKOUT"
        private const val FINISH = "uk.co.r99vitals.FINISH_WORKOUT"
        private const val SPORT = "sport"

        /**
         * A session the wearer started and never finished is given up on after this, so a
         * forgotten one does not hold the sensor on — and detection off — for days.
         */
        private const val MANUAL_STALE = 6 * 60 * 60 * 1000L

        /** Starts a session the wearer has named, from the Workout tab. */
        fun startWorkout(context: Context, sport: String) =
            send(context, Intent(context, CollectorService::class.java).setAction(START).putExtra(SPORT, sport))

        /** Ends the session now, at the wearer's word rather than by going quiet. */
        fun finishWorkout(context: Context) =
            send(context, Intent(context, CollectorService::class.java).setAction(FINISH))

        fun start(context: Context) = send(context, Intent(context, CollectorService::class.java))

        private fun send(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
