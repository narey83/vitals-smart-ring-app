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
 */
class Workouts(context: Context) {
    private val file = File(context.filesDir, "workouts.csv")

    data class Session(
        val at: Date,
        val sport: String,
        val minutes: Int,
        val beats: List<Int>
    ) {
        val low get() = beats.minOrNull() ?: 0
        val high get() = beats.maxOrNull() ?: 0
        val average get() = if (beats.isEmpty()) 0 else beats.average().toInt()
    }

    fun save(sport: String, startedAt: Long, beats: List<Int>) {
        if (beats.isEmpty()) return
        val minutes = ((System.currentTimeMillis() - startedAt) / 60000).toInt().coerceAtLeast(1)
        runCatching {
            file.appendText("$startedAt,$sport,$minutes,${beats.joinToString(" ")}\n")
        }
    }

    fun all(): List<Session> = runCatching {
        file.readLines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 4) return@mapNotNull null
            Session(
                at = Date(parts[0].toLongOrNull() ?: return@mapNotNull null),
                sport = parts[1],
                minutes = parts[2].toIntOrNull() ?: 0,
                beats = parts[3].split(" ").mapNotNull { it.toIntOrNull() }
            )
        }
    }.getOrDefault(emptyList())
}
