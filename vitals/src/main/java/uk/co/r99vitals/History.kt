package uk.co.r99vitals

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Indexed on-device storage. Existing CSV installs are imported once. */
class History private constructor(private val dbFile: File, legacy: File?) {
    constructor(context: Context) : this(File(context.filesDir, "readings.db"), File(context.filesDir, "readings.csv"))
    constructor(file: File) : this(file, null)

    data class Entry(val at: Date, val kind: String, val value: Int, val extra: Int, val manual: Boolean = false)

    companion object {
        private const val BURST = 90_000L
        private val writing = Any()
        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
    private val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null).apply {
        execSQL("CREATE TABLE IF NOT EXISTS readings (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, value INTEGER NOT NULL, extra INTEGER NOT NULL DEFAULT 0, manual INTEGER NOT NULL DEFAULT 0)")
        execSQL("CREATE INDEX IF NOT EXISTS readings_kind_at ON readings(kind, at)")
    }

    init { migrate(legacy) }

    private fun migrate(csv: File?) {
        if (csv == null || !csv.exists() || count() != 0L) return
        synchronized(writing) {
            if (count() != 0L) return
            transaction {
                csv.forEachLine { line ->
                    val p = line.split(',')
                    insert(p.getOrNull(0)?.toLongOrNull() ?: return@forEachLine,
                        p.getOrNull(1) ?: return@forEachLine,
                        p.getOrNull(2)?.toIntOrNull() ?: return@forEachLine,
                        p.getOrNull(3)?.toIntOrNull() ?: 0, p.getOrNull(4) == "1")
                }
            }
            csv.renameTo(File(csv.parentFile, "${csv.name}.migrated"))
        }
    }

    fun record(kind: String, value: Int, extra: Int = 0, burst: Long = BURST, manual: Boolean = false) = synchronized(writing) {
        transaction {
            val now = System.currentTimeMillis()
            // Settled into the newest row that has actually happened. A record the ring stamped
            // while its clock ran ahead sorts after now, and measuring against that one made every
            // reading after it a row of its own — several a second during a workout.
            val old = latest(kind, now)
            if (old != null && now - old.at.time in 0 until burst) {
                db.update("readings", values(value, extra, manual || old.manual),
                    "id = (SELECT id FROM readings WHERE kind = ? AND at <= ? ORDER BY at DESC, id DESC LIMIT 1)",
                    arrayOf(kind, now.toString()))
            } else insert(now, kind, value, extra, manual)
        }
    }

    /**
     * Writes down the ring going on or off the charger, as a `charging` row of 1 or 0, and
     * answers whether that was news. Only a change is written, so the activity and the collector
     * can both report what they hear without the spell being recorded twice.
     */
    fun charging(on: Boolean, at: Long = System.currentTimeMillis()): Boolean = synchronized(writing) {
        if (chargingAt(at) == on) return false
        // Past the burst window on purpose: a ring lifted off and dropped back within a minute
        // is two changes, not a correction of one.
        insert(at, "charging", if (on) 1 else 0, 0, false)
        true
    }

    /** Whether the ring was on the charger at [at], as far as the recorded spells say. */
    fun chargingAt(at: Long = System.currentTimeMillis()): Boolean =
        latestBefore("charging", at + 1)?.value == 1

    /**
     * Readings the ring stored while nothing was listening. Any taken while it sat on the charger
     * are dropped: the sensor was reading the case, not a finger.
     */
    fun backfill(kind: String, readings: List<Triple<Long, Int, Int>>) {
        if (readings.isEmpty()) return
        synchronized(writing) { transaction {
            readings.forEach { (at, value, extra) ->
                if (chargingAt(at)) return@forEach
                db.rawQuery("SELECT 1 FROM readings WHERE kind=? AND at>? AND at<? LIMIT 1",
                    arrayOf(kind, (at - BURST).toString(), (at + BURST).toString())).use {
                    if (!it.moveToFirst()) insert(at, kind, value, extra, false)
                }
            }
        } }
    }

    private fun transaction(action: () -> Unit) {
        db.beginTransaction()
        try { action(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun values(value: Int, extra: Int, manual: Boolean) = ContentValues().apply {
        put("value", value); put("extra", extra); put("manual", if (manual) 1 else 0)
    }

    private fun insert(at: Long, kind: String, value: Int, extra: Int, manual: Boolean) {
        db.insertOrThrow("readings", null, values(value, extra, manual).apply { put("at", at); put("kind", kind) })
    }

    private fun count() = db.rawQuery("SELECT COUNT(*) FROM readings", null).use { it.moveToFirst(); it.getLong(0) }
    private fun Cursor.entry() = Entry(Date(getLong(0)), getString(1), getInt(2), getInt(3), getInt(4) != 0)

    fun all(): List<Entry> = db.rawQuery("SELECT at,kind,value,extra,manual FROM readings ORDER BY at,id", null).use { c ->
        buildList { while (c.moveToNext()) add(c.entry()) }
    }

    fun between(kind: String, start: Long, end: Long = Long.MAX_VALUE): List<Entry> = db.rawQuery(
        "SELECT at,kind,value,extra,manual FROM readings WHERE kind=? AND at>=? AND at<? ORDER BY at,id",
        arrayOf(kind, start.toString(), end.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.entry()) } }

    /** The newest reading as of [now]. One dated after it is a wrong clock, not the latest news. */
    fun latest(kind: String, now: Long = System.currentTimeMillis()): Entry? = latestBefore(kind, now + 1)

    fun latestBefore(kind: String, at: Long): Entry? = db.rawQuery(
        "SELECT at,kind,value,extra,manual FROM readings WHERE kind=? AND at<? ORDER BY at DESC,id DESC LIMIT 1",
        arrayOf(kind, at.toString())).use { if (it.moveToFirst()) it.entry() else null }

    fun stepsFlatSince(): Date? {
        val steps = all().filter { it.kind == "steps" }
        val current = steps.lastOrNull() ?: return null
        var first = current
        for (i in steps.indices.reversed()) { if (steps[i].value != current.value) break; first = steps[i] }
        return first.at
    }

    fun summary(kind: String, since: Long): String {
        return db.rawQuery("SELECT COUNT(*),MIN(value),MAX(value),AVG(value) FROM readings WHERE kind=? AND at>=?",
            arrayOf(kind, since.toString())).use { c ->
            c.moveToFirst()
            if (c.getLong(0) == 0L) "no readings yet"
            else "${c.getLong(0)} readings · low ${c.getInt(1)} · high ${c.getInt(2)} · average ${c.getDouble(3).toInt()}"
        }
    }

    fun report(): String {
        val entries = all()
        if (entries.isEmpty()) return "Nothing recorded yet.\n\nTake a reading and it will be kept here."
        return buildString {
            append("${entries.size} readings held on this phone\n\n")
            entries.takeLast(200).reversed().forEach { e ->
                val v = when (e.kind) { "heart" -> "${e.value} bpm"; "oxygen" -> "${e.value}%"; "pressure" -> "${e.value}/${e.extra} (estimated)"; "steps" -> "${e.value} steps"; "charging" -> if (e.value == 1) "on the charger" else "off the charger"; else -> e.value.toString() }
                append(stamp.format(e.at)).append("  ").append(e.kind.padEnd(9)).append(v).append('\n')
            }
        }
    }

    fun asCsv() = "time,kind,value,extra,manual\n" + all().joinToString("\n") {
        "${stamp.format(it.at)},${it.kind},${it.value},${it.extra},${if (it.manual) 1 else 0}"
    }
}
