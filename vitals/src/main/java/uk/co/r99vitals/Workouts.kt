package uk.co.r99vitals

import android.content.Context
import java.io.File
import java.util.Date

/**
 * Finished workouts, kept whole.
 *
 * A session is not the same thing as a day's readings: it has a sport, a duration and its own
 * curve, and averaging it into the daily trend would lose all three. One line per workout, the
 * beats stored alongside it, so a finished session can be opened again exactly as it was.
 *
 * Sessions arrive two ways — started by the wearer, or found in the step counter by
 * [WorkoutDetector] — and a detected one carries the steps that gave it away. Which of the two
 * it was is recorded rather than hidden, since a guess the app made should be correctable and
 * should say that it was a guess.
 */
class Workouts(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "workouts.csv"))

    data class Session(
        val at: Date,
        val sport: String,
        val minutes: Int,
        val beats: List<Int>,
        /** Found in the step counter rather than started by the wearer. */
        val detected: Boolean = false,
        /** Steps taken during it, which is the whole evidence a detected session rests on. */
        val steps: Int = 0,
        /** How far it went, from the phone's GPS, for a walk, run or ride that recorded a route. */
        val metres: Int = 0,
        /** Time spent moving rather than stood at a crossing — what pace is measured over. */
        val movingSeconds: Int = 0
    ) {
        /** Metres a second over moving time, or null without a route. */
        val speed: Double? get() = if (metres > 0 && movingSeconds > 0) metres.toDouble() / movingSeconds else null

        val low get() = beats.minOrNull() ?: 0
        val high get() = beats.maxOrNull() ?: 0
        val average get() = if (beats.isEmpty()) 0 else beats.average().toInt()
    }

    /**
     * Writes a session down.
     *
     * [endedAt] is passed rather than assumed to be now, because a detected session is only
     * recognised as over once it has been quiet for a while — taking the clock at the moment of
     * writing would stretch every one of them by the length of that silence.
     *
     * Answers whether anything was written, so a route recorded for a session that turned out to
     * be nothing can be thrown away with it.
     */
    fun save(
        sport: String,
        startedAt: Long,
        beats: List<Int>,
        endedAt: Long = System.currentTimeMillis(),
        detected: Boolean = false,
        steps: Int = 0,
        metres: Int = 0,
        movingSeconds: Int = 0
    ): Boolean {
        // A session the ring gave no readings for is nothing at all — unless it was detected,
        // where the steps are the record, or it went somewhere, where the route is: a ring off
        // the finger on a ride does not make the ride not have happened.
        if (beats.isEmpty() && steps == 0 && metres == 0) return false
        val minutes = ((endedAt - startedAt) / 60_000).toInt().coerceAtLeast(1)
        val line = listOf(
            startedAt.toString(), sport, minutes.toString(), beats.joinToString(" "),
            if (detected) "1" else "0", steps.toString(), metres.toString(), movingSeconds.toString()
        ).joinToString(",")
        return synchronized(writing) { runCatching { file.appendText("$line\n") }.isSuccess }
    }

    /**
     * The wearer correcting the app's guess.
     *
     * Only the sport changes, and only for the one session: how it came to be recorded stays
     * true whatever it ends up being called.
     */
    fun relabel(startedAt: Long, sport: String) = synchronized(writing) {
        runCatching {
            if (!file.exists()) return@runCatching
            val lines = file.readLines().map { line ->
                val parts = line.split(",")
                if (parts.size >= 4 && parts[0].toLongOrNull() == startedAt) {
                    parts.toMutableList().also { it[1] = sport }.joinToString(",")
                } else line
            }
            file.writeText(lines.joinToString("\n", postfix = "\n"))
        }
        Unit
    }

    /** Every session held, oldest first. Lines written before sessions were detected, or had routes, still read. */
    fun all(): List<Session> = synchronized(writing) {
        runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size < 4) return@mapNotNull null
                Session(
                    at = Date(parts[0].toLongOrNull() ?: return@mapNotNull null),
                    sport = parts[1],
                    minutes = parts[2].toIntOrNull() ?: 0,
                    beats = parts[3].split(" ").mapNotNull { it.toIntOrNull() },
                    detected = parts.getOrNull(4) == "1",
                    steps = parts.getOrNull(5)?.toIntOrNull() ?: 0,
                    metres = parts.getOrNull(6)?.toIntOrNull() ?: 0,
                    movingSeconds = parts.getOrNull(7)?.toIntOrNull() ?: 0
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /**
         * The collector appends from the Bluetooth callback while the activity may be rewriting
         * a label from the screen — one process, two threads, one file.
         */
        val writing = Any()
    }
}
