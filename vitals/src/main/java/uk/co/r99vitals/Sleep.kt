package uk.co.r99vitals

import android.content.Context
import java.io.File

/**
 * The nights the ring staged for itself.
 *
 * Sleep is the one thing this ring works out on its own and never pushes: it watches all night,
 * decides deep from light from REM, writes one record, and says nothing until asked. Asking is
 * `Health_HistorySleep` (`05 04`); the records come back under `05 13`.
 *
 * The record is the only history here that is not fixed-width. A 20-byte header carries the
 * night's span, its totals and its own total size, and then one 8-byte entry per stage runs to
 * the end of it. A night is bigger than a BLE frame, so the entries arrive split across frames
 * and mid-entry — hence [SleepReader], which holds the pieces until a record is whole.
 *
 * Layout from the vendor SDK's `DataUnpack` case 4, and confirmed against a real night: the
 * header's deep, light and REM totals matched the sum of the stage entries carrying each code
 * exactly, which is also what pins each code to its name.
 */
object Sleep {

    const val DEEP = 0xF1
    const val LIGHT = 0xF2
    const val REM = 0xF3
    const val AWAKE = 0xF4

    /** The ring counts seconds from 2000, as it does in every record it keeps. */
    private const val EPOCH_2000 = 946_684_800_000L

    /** One stage: when it began, what the ring called it, how long it lasted. */
    data class Stage(val startedAt: Long, val code: Int, val seconds: Int)

    /**
     * One night. The totals are summed from the stages rather than kept separately — the ring
     * sends both and they agree, so keeping one of them is a file that cannot contradict itself.
     */
    data class Night(val startedAt: Long, val stages: List<Stage>) {
        val endedAt get() = stages.lastOrNull()?.let { it.startedAt + it.seconds * 1000L } ?: startedAt
        fun seconds(code: Int) = stages.filter { it.code == code }.sumOf { it.seconds }
        /** Time asleep, which is not time in bed: the wakings in between are not sleep. */
        val asleep get() = stages.filter { it.code != AWAKE }.sumOf { it.seconds }
        val inBed get() = ((endedAt - startedAt) / 1000).toInt()
    }

    fun name(code: Int) = when (code) {
        DEEP -> "Deep"
        LIGHT -> "Light"
        REM -> "REM"
        AWAKE -> "Awake"
        else -> "0x%02X".format(code)
    }

    /**
     * Reads whole records out of [payload], and says how many bytes it used.
     *
     * A record that runs past the end is left alone rather than half-read: its bytes stay for the
     * next frame to complete.
     */
    fun read(payload: ByteArray): Pair<List<Night>, Int> {
        fun u8(i: Int) = payload[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
        fun time(i: Int) = EPOCH_2000 + (u24(i).toLong() or (u8(i + 3).toLong() shl 24)) * 1000L

        val nights = mutableListOf<Night>()
        var at = 0
        while (at + 20 <= payload.size) {
            val size = u16(at + 2)
            // A size that is not a header plus whole entries is not a record; nothing after it
            // can be trusted either, so the rest is dropped rather than resynchronised.
            if (size < 20 || (size - 20) % 8 != 0) return nights to payload.size
            if (at + size > payload.size) break
            nights += Night(
                startedAt = time(at + 4),
                stages = (at + 20 until at + size step 8).map {
                    Stage(startedAt = time(it + 1), code = u8(it), seconds = u24(it + 5))
                }
            )
            at += size
        }
        return nights to at
    }
}

/**
 * Gathers the frames of a `05 13` push until the records inside them are whole.
 *
 * One per connection, because two connections are two conversations: the activity and the
 * collector each hold their own and neither can splice the other's frames into its record.
 */
class SleepReader {
    private val pending = mutableListOf<Byte>()

    fun accept(value: ByteArray): List<Sleep.Night> {
        if (value.size < 6) return emptyList()
        if ((value[0].toInt() and 0xFF) != 0x05 || (value[1].toInt() and 0xFF) != 0x13) return emptyList()
        pending += value.copyOfRange(4, value.size - 2).toList()
        val (nights, used) = Sleep.read(pending.toByteArray())
        repeat(used) { pending.removeAt(0) }
        // A record that never completes would otherwise sit in front of every later one for the
        // life of the connection. Two nights' worth is far more than the ring ever sends at once.
        if (pending.size > 4096) pending.clear()
        return nights
    }
}

/**
 * The nights, kept on the phone.
 *
 * The ring holds only a handful and rotates the rest away, so what it hands over is copied here
 * and kept. It hands over the same nights on every connection, so a night already written down is
 * left exactly as it is.
 */
class Nights(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "sleep.csv"))

    /**
     * One stage per line: which night it belongs to, when it began, what it was, how long.
     *
     * Appended, never rewritten. [all] sorts what it reads, so the file does not have to be kept
     * in order, and a night already in it is skipped — the ring offers the same nights on every
     * connection.
     */
    fun save(nights: List<Sleep.Night>) {
        if (nights.isEmpty()) return
        synchronized(writing) {
            runCatching {
                val known = all().map { it.startedAt }.toSet()
                val rows = nights
                    .filter { it.startedAt !in known && it.stages.isNotEmpty() }
                    .flatMap { night ->
                        night.stages.map { "${night.startedAt},${it.startedAt},${it.code},${it.seconds}" }
                    }
                if (rows.isNotEmpty()) file.appendText(rows.joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun all(): List<Sleep.Night> = runCatching {
        file.readLines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 4) return@mapNotNull null
            val night = parts[0].toLongOrNull() ?: return@mapNotNull null
            night to Sleep.Stage(
                startedAt = parts[1].toLongOrNull() ?: return@mapNotNull null,
                code = parts[2].toIntOrNull() ?: return@mapNotNull null,
                seconds = parts[3].toIntOrNull() ?: return@mapNotNull null
            )
        }
            .groupBy({ it.first }, { it.second })
            .map { (start, stages) -> Sleep.Night(start, stages.sortedBy { it.startedAt }) }
            .sortedBy { it.startedAt }
    }.getOrDefault(emptyList())

    private companion object {
        /** As in [History]: the activity and the collector both write this file. */
        val writing = Any()
    }
}
