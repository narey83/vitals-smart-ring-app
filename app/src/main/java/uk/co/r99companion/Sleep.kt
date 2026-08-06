package uk.co.r99companion

/**
 * Stored sleep, which is the one history the ring does not keep in fixed-width records.
 *
 * `Health_HistorySleep` (`05 04`) answers with a count like every other query and then pushes the
 * records under `05 13`. Each record is a 20-byte header carrying its own total size, followed by
 * one 8-byte entry per stage. The ring's own log describes a night the same way — `sleep
 * size=284,items=1,packets==2` alongside `Save sleep record total: 1, forms: 33`, and
 * 20 + 33 * 8 = 284 — so the layout is confirmed from two directions.
 *
 * The field layout is the vendor SDK's, `DataUnpack` case 4.
 */

/** One stage: when it began, what the ring called it, and how long it ran. */
data class SleepStage(val startedAt: Long, val code: Int, val seconds: Int)

/** One stored sleep record. Totals come from the header, not from summing [stages]. */
data class SleepNight(
    val startedAt: Long,
    val endedAt: Long,
    val deepSeconds: Int,
    val lightSeconds: Int,
    val remSeconds: Int,
    val stages: List<SleepStage>
)

/** The ring counts seconds from 2000, as it does in every other record. */
private const val EPOCH_2000 = 946_684_800_000L

/**
 * Names for the stage codes. `F4` is awake on the SDK's own authority — it is the code
 * `DataUnpack` counts as a waking. The other three are read against the ring's log, which prints
 * its transitions in words (`LIGHT->DEEP`, `DEEP->REM`).
 *
 * ponytail: if a night's stage sums disagree with the header totals below, deep and light are
 * the pair to swap first — printing both makes that visible without another capture.
 */
fun sleepStageName(code: Int) = when (code) {
    0xF1 -> "deep"
    0xF2 -> "light"
    0xF3 -> "REM"
    0xF4 -> "awake"
    else -> "stage 0x%02X".format(code)
}

/**
 * Splits a `05 13` payload into whole nights, and says how many bytes it used.
 *
 * A night is bigger than a BLE frame — the one measured came in two, cut mid-entry — so a record
 * running past the end of the payload is left for the next frame to finish rather than half-read.
 */
fun readSleep(payload: ByteArray): Pair<List<SleepNight>, Int> {
    fun u8(i: Int) = payload[i].toInt() and 0xFF
    fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
    fun time(i: Int) = EPOCH_2000 + (u24(i).toLong() or (u8(i + 3).toLong() shl 24)) * 1000L

    val nights = mutableListOf<SleepNight>()
    var at = 0
    while (at + 20 <= payload.size) {
        val size = u16(at + 2)
        // A size that is not a header plus whole entries is not a record, and nothing after it
        // can be trusted either.
        if (size < 20 || (size - 20) % 8 != 0) return nights to payload.size
        if (at + size > payload.size) break

        // A deep-sleep count of 0xFFFF marks the newer header: it drops the light-sleep count,
        // adds REM, and keeps its totals in seconds where the older one used minutes.
        val newer = u16(at + 12) == 0xFFFF
        val scale = if (newer) 1 else 60
        nights += SleepNight(
            startedAt = time(at + 4),
            endedAt = time(at + 8),
            deepSeconds = u16(at + 16) * scale,
            lightSeconds = u16(at + 18) * scale,
            remSeconds = if (newer) u16(at + 14) else 0,
            stages = (at + 20 until at + size step 8).map {
                SleepStage(startedAt = time(it + 1), code = u8(it), seconds = u24(it + 5))
            }
        )
        at += size
    }
    return nights to at
}

/** Holds the frames of a `05 13` push until the records inside them are whole. */
class SleepFrames {
    private val pending = mutableListOf<Byte>()

    fun accept(payload: ByteArray): List<SleepNight> {
        pending += payload.toList()
        val (nights, used) = readSleep(pending.toByteArray())
        repeat(used) { pending.removeAt(0) }
        if (pending.size > 4096) pending.clear()
        return nights
    }
}
