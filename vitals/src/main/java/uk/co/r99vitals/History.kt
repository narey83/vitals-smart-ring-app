package uk.co.r99vitals

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every reading the app sees, kept on the phone.
 *
 * The ring keeps its own small rolling store and deletes from it as it fills, so history cannot
 * depend on the ring remembering. Anything observed is written here instead, timestamped by the
 * phone, and survives the ring forgetting. Reading the ring's own stored records is a separate
 * matter and only ever a backfill.
 *
 * A file of one reading per line rather than a database: the volume is a few readings an hour,
 * it is trivially exportable, and it can be read by anything.
 */
class History(context: Context) {
    private val file = File(context.filesDir, "readings.csv")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)

    data class Entry(val at: Date, val kind: String, val value: Int, val extra: Int)

    private companion object {
        /** Readings closer together than this belong to the same measurement. */
        const val BURST = 90_000L
    }

    /**
     * One entry per measurement, not one per frame.
     *
     * The ring streams a reading a second while it measures, so a single thirty-second
     * measurement would otherwise leave thirty near-identical rows: noise in the list and a
     * chart plotting the same moment over and over. A reading that arrives within [BURST] of the
     * previous one of the same kind replaces it, so what is kept is where the measurement
     * settled rather than where it started.
     */
    fun record(kind: String, value: Int, extra: Int = 0) {
        runCatching {
            val now = System.currentTimeMillis()
            val lines = if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableList()
                else mutableListOf()
            // Search back for the last entry of this kind, not merely the last line: the ring
            // interleaves activity frames between readings, so two heart readings are never
            // adjacent and comparing against the previous line would never match.
            val previous = lines.indexOfLast { it.split(",").getOrNull(1) == kind }
            val within = previous >= 0 &&
                now - (lines[previous].split(",")[0].toLongOrNull() ?: 0L) < BURST
            if (within) lines[previous] = "$now,$kind,$value,$extra"
            else lines.add("$now,$kind,$value,$extra")
            file.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    fun all(): List<Entry> = runCatching {
        file.readLines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 4) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            Entry(Date(at), parts[1], parts[2].toIntOrNull() ?: return@mapNotNull null, parts[3].toIntOrNull() ?: 0)
        }
    }.getOrDefault(emptyList())

    fun latest(kind: String): Entry? = all().lastOrNull { it.kind == kind }

    /** A day's readings of one kind, summarised the way a trend is actually read. */
    fun summary(kind: String, since: Long): String {
        val values = all().filter { it.kind == kind && it.at.time >= since }.map { it.value }
        if (values.isEmpty()) return "no readings yet"
        return "${values.size} readings · low ${values.min()} · high ${values.max()} · average ${values.average().toInt()}"
    }

    fun report(): String {
        val entries = all()
        if (entries.isEmpty()) return "Nothing recorded yet.\n\nTake a reading and it will be kept here."
        return buildString {
            append("${entries.size} readings held on this phone\n\n")
            entries.takeLast(200).reversed().forEach { entry ->
                val value = when (entry.kind) {
                    "heart" -> "${entry.value} bpm"
                    "oxygen" -> "${entry.value}%"
                    "pressure" -> "${entry.value}/${entry.extra} (estimated)"
                    "steps" -> "${entry.value} steps"
                    else -> entry.value.toString()
                }
                append(stamp.format(entry.at)).append("  ").append(entry.kind.padEnd(9)).append(value).append('\n')
            }
        }
    }

    fun asCsv(): String = "time,kind,value,extra\n" + all().joinToString("\n") {
        "${stamp.format(it.at)},${it.kind},${it.value},${it.extra}"
    }
}
