package uk.co.r99vitals

/**
 * Finds a workout in the step counter, because the ring will never announce one.
 *
 * The ring has no notion of an activity beginning. `Health_HistorySport` has answered with zero
 * records on every capture from both this app and the vendor's, and nothing in its capability
 * bitmap recognises exercise — the sport flags it does set are modes you put it into, not
 * something it reports (see PROTOCOL.md). What it does do, unprompted, is push its running step
 * total every couple of seconds for as long as anything is connected. The difference between
 * two of those pushes is cadence, and a cadence that holds is what a walk or a run actually is.
 *
 * Deliberately slow to believe it. Confirming a session turns the heart sensor on for as long
 * as the session lasts, which costs the ring's battery, so a brisk crossing of the kitchen must
 * not be enough: the cadence has to hold for [Settings.confirmAfter] before anything starts.
 * The session is then backdated to when the walking began rather than to the late moment the
 * threshold was finally believed — otherwise every detected workout would be five minutes
 * shorter than the one that happened.
 *
 * Only walking and running are found this way, and the class says so rather than guessing:
 * cycling, rowing and yoga produce almost no steps, and no arithmetic on a step counter will
 * recover them.
 */
class WorkoutDetector(private val settings: Settings = Settings()) {

    data class Settings(
        /** Steps a minute that means walking somewhere, rather than moving about a room. */
        val walking: Int = 100,
        /** And that means running. The fastest minute of a session is what labels it. */
        val running: Int = 140,
        /** How long the cadence must hold before a session is believed and the sensor turned on. */
        val confirmAfter: Long = 5 * 60_000L,
        /** How long it must stop for before the session is over rather than merely interrupted. */
        val restAfter: Long = 3 * 60_000L,
        /** The trailing span cadence is measured across. */
        val window: Long = 60_000L,
        /** A rate needs a span to be a rate: anything shorter than this says nothing at all. */
        val shortest: Long = 30_000L,
        /** Longer than this between pushes is a dropped link, so the steps either side of it
         *  cannot be differenced into a cadence — they are a gap, not a sprint. */
        val silence: Long = 5 * 60_000L
    )

    sealed interface Event {
        /** A session has been confirmed. [at] is backdated to when the walking began. */
        data class Started(val at: Long, val sport: String) : Event
        /** A session is over. [endedAt] is the last moment of movement, not the quiet after it. */
        data class Ended(
            val startedAt: Long,
            val endedAt: Long,
            val sport: String,
            val steps: Int
        ) : Event
    }

    /** One push: when it landed, and the steps it added to the total. */
    private data class Sample(val at: Long, val added: Int)

    private val window = ArrayDeque<Sample>()
    private var lastTotal: Int? = null
    private var lastAt = 0L

    /** The start of the unbroken spell of walking going on now, or null if none is. */
    private var walkingSince: Long? = null

    /** The last moment the cadence was still above walking pace, which is what holds a session open. */
    private var lastWalking = 0L

    /**
     * The last push that actually carried a step, which is where a session is cut.
     *
     * Not the same moment as [lastWalking], and the difference matters. Cadence is measured
     * across a trailing window, so it stays above walking pace for a while after the wearer has
     * stopped — the window is still full of the walk. Ending a session there would hand every
     * one of them a tail of standing still, up to a whole window long at a run's pace.
     */
    private var lastStep = 0L

    /** Set once a spell has lasted long enough to be a session; this is its backdated start. */
    private var startedAt: Long? = null
    private var walked = 0
    private var fastest = 0

    /** Whether a confirmed session is running now, which is what turns the heart sensor on. */
    val inProgress: Boolean get() = startedAt != null

    /** When the session running now began — already backdated — or zero if none is. */
    val since: Long get() = startedAt ?: 0L

    /** What the session looks like so far. A spell that speeds up partway becomes a run. */
    val sport: String get() = if (fastest >= settings.running) RUN else WALK

    /** Steps counted into the session so far, for the record it will eventually be saved as. */
    val steps: Int get() = walked

    /**
     * A step total, straight off the ring.
     *
     * The totals themselves are never differenced across a gap or a reset — only the increments
     * between consecutive pushes are, and an increment that cannot be trusted is counted as
     * zero. That keeps a midnight rollover and a reconnection from both reading as a sprint.
     */
    fun step(at: Long, total: Int): Event? {
        val previous = lastTotal
        val quiet = lastAt != 0L && at - lastAt > settings.silence
        // A counter that has gone backwards is the ring's own midnight reset, and one that has
        // jumped after a silence covers time nothing was listening for. Neither is a cadence.
        val added = if (previous == null || total < previous || quiet) 0 else total - previous
        lastTotal = total
        lastAt = at

        if (added > 0) lastStep = at
        window.addLast(Sample(at, added))
        while (window.size > 1 && at - window.first().at > settings.window) window.removeFirst()

        val cadence = cadence(at)
        val moving = cadence != null && cadence >= settings.walking
        if (moving) {
            fastest = maxOf(fastest, cadence!!)
            lastWalking = at
            if (walkingSince == null) {
                // Backdated to the start of the window the cadence was measured across, since
                // that is the earliest moment this pace is known to have been held.
                walkingSince = window.first().at
                // A session already running keeps counting; one still being judged is measured
                // from the start of this spell, so earlier pottering does not join it.
                if (startedAt == null) walked = movedInWindow() else walked += added
            } else walked += added
        } else {
            walkingSince = null
            if (startedAt == null) { walked = 0; fastest = 0 } else walked += added
        }

        startedAt?.let { began ->
            return if (at - lastWalking >= settings.restAfter) finish(began) else null
        }
        walkingSince?.let { since ->
            if (at - since >= settings.confirmAfter) {
                startedAt = since
                return Event.Started(since, sport)
            }
        }
        return null
    }

    /**
     * Time passing with nothing arriving.
     *
     * A session ends by stopping, and stopping is the absence of pushes rather than a push of
     * its own — a wearer who sits down, and a link that drops, both simply go quiet. Something
     * has to notice on the clock instead, or a walk that ended at lunchtime would still be
     * recording at bedtime.
     */
    fun quiet(at: Long): Event? {
        val began = startedAt ?: run {
            // A spell that was building towards a session when the pushes stopped proves
            // nothing: it is dropped rather than confirmed on evidence that never arrived.
            if (walkingSince != null && at - lastAt > settings.silence) abandon()
            return null
        }
        return if (at - lastWalking >= settings.restAfter) finish(began) else null
    }

    /**
     * Ends the session now, keeping it, for a wearer who has finished and said so.
     *
     * The end is still the last moment of movement rather than the moment of the tap, so
     * finishing a walk on the doorstep and putting the phone down records the walk either way.
     */
    fun finishNow(): Event.Ended? = startedAt?.let { finish(it) }

    /** Drops whatever is in flight without recording it. */
    fun abandon() {
        window.clear()
        walkingSince = null
        startedAt = null
        walked = 0
        fastest = 0
        lastWalking = 0
        lastStep = 0
    }

    private fun finish(began: Long): Event.Ended {
        val ended = Event.Ended(began, maxOf(lastStep, began), sport, walked)
        abandon()
        // Cleared along with the session: the next push starts a fresh window rather than
        // differencing against a total from before the workout.
        lastTotal = null
        return ended
    }

    /** Steps a minute across the window, or null while it is too short to be a rate. */
    private fun cadence(at: Long): Int? {
        val oldest = window.first()
        val span = at - oldest.at
        if (span < settings.shortest) return null
        return (movedInWindow() * 60_000L / span).toInt()
    }

    /**
     * The oldest sample's own increment is left out: it was added before the window opened, and
     * counting it against the window's span would report steps that were taken earlier.
     */
    private fun movedInWindow() = window.drop(1).sumOf { it.added }

    companion object {
        const val WALK = "Walk"
        const val RUN = "Run"
    }
}
