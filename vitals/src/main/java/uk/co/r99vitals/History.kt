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
class History(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "readings.csv"))

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)

    /**
     * [manual] marks a reading the wearer asked for by tapping Measure, as opposed to one the
     * ring took on its own schedule. Rows written before this was recorded have no such column
     * and read as not manual, which is the honest answer rather than a guess either way.
     */
    data class Entry(
        val at: Date,
        val kind: String,
        val value: Int,
        val extra: Int,
        val manual: Boolean = false
    )

    private companion object {
        /** Readings closer together than this belong to the same measurement. */
        const val BURST = 90_000L

        /**
         * Held across the whole process while the file is rewritten.
         *
         * Recording is read, change, write back — and there are two writers: the activity
         * records what arrives while it is open, the collector service records what arrives
         * when it is not, and both hold a connection at once with their callbacks on different
         * threads. Without this, one could read the file while the other was midway through
         * replacing it, see nothing there, and write back a file containing only its own
         * reading. That is not a theoretical race: it emptied a day of readings.
         *
         * The lock lives on the companion because the two writers are separate instances.
         */
        val writing = Any()
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
    fun record(kind: String, value: Int, extra: Int = 0, burst: Long = BURST, manual: Boolean = false) {
        synchronized(writing) {
            runCatching {
                val now = System.currentTimeMillis()
                val lines = read()
                // Search back for the last entry of this kind, not merely the last line: the ring
                // interleaves activity frames between readings, so two heart readings are never
                // adjacent and comparing against the previous line would never match.
                val previous = lines.indexOfLast { it.split(",").getOrNull(1) == kind }
                val within = previous >= 0 &&
                    now - (lines[previous].split(",")[0].toLongOrNull() ?: 0L) < burst
                // Keep the burst's original timestamp when replacing. Updating it to now would slide
                // the window forward with every reading, so a continuous stream would collapse into a
                // single row that is rewritten for ever and never allowed to start a new one.
                if (within) {
                    val began = lines[previous].split(",")[0]
                    // A burst that began with a tap stays the wearer's reading even as it settles.
                    val asked = manual || lines[previous].split(",").getOrNull(4) == "1"
                    lines[previous] = "$began,$kind,$value,$extra,${if (asked) 1 else 0}"
                }
                else lines.add("$now,$kind,$value,$extra,${if (manual) 1 else 0}")
                save(lines)
            }
        }
    }

    /**
     * Readings the ring took while nothing was listening, stamped when they happened rather than
     * when they were read.
     *
     * The ring only hands these over when asked, and it is asked on every connection, so the
     * same records come back again and again. Anything already written within a burst of that
     * moment is therefore left alone: a backfill that ran twice must not double the day.
     */
    fun backfill(kind: String, readings: List<Triple<Long, Int, Int>>) {
        if (readings.isEmpty()) return
        synchronized(writing) {
            runCatching {
                val lines = read()
                val known = lines.mapNotNull { line ->
                    val parts = line.split(",")
                    if (parts.getOrNull(1) == kind) parts[0].toLongOrNull() else null
                }.toMutableList()
                var added = false
                readings.forEach { (at, value, extra) ->
                    if (known.none { kotlin.math.abs(it - at) < BURST }) {
                        lines.add("$at,$kind,$value,$extra,0")
                        known.add(at)
                        added = true
                    }
                }
                if (!added) return
                // Rows arrive out of order, and everything downstream reads the file as a day in
                // sequence: the charts plot it as given and latest() takes the last line.
                lines.sortBy { it.substringBefore(",").toLongOrNull() ?: 0L }
                save(lines)
            }
        }
    }

    private fun read(): MutableList<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableList() else mutableListOf()

    /**
     * Written beside the real file and moved into place, so that being killed partway through
     * leaves the old readings rather than half of the new ones.
     */
    private fun save(lines: List<String>) {
        val pending = File(file.parentFile, file.name + ".writing")
        pending.writeText(lines.joinToString("\n", postfix = "\n"))
        if (!pending.renameTo(file)) {
            file.writeText(pending.readText())
            pending.delete()
        }
    }

    fun all(): List<Entry> = runCatching {
        file.readLines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 4) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            Entry(
                Date(at), parts[1],
                parts[2].toIntOrNull() ?: return@mapNotNull null,
                parts[3].toIntOrNull() ?: 0,
                manual = parts.getOrNull(4) == "1"
            )
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

    fun asCsv(): String = "time,kind,value,extra,manual\n" + all().joinToString("\n") {
        "${stamp.format(it.at)},${it.kind},${it.value},${it.extra},${if (it.manual) 1 else 0}"
    }
}
