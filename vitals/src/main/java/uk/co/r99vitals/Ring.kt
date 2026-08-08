package uk.co.r99vitals

import java.util.UUID

/**
 * The slice of the ring's protocol a vitals app needs. The full picture, including how it was
 * recovered and which parts are verified against hardware, is in PROTOCOL.md at the repository
 * root; the debugger app exercises all 329 commands.
 *
 * Frame layout:  group | command | total length (uint16 LE) | payload | CRC (uint16 LE)
 */
object Ring {
    val COMMAND_CHANNEL: UUID = UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5")
    val LIVE_DATA: UUID = UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5")
    val ACTIVITY: UUID = UUID.fromString("0000fea1-0000-1000-8000-00805f9b34fb")
    val HEART_RATE: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HEART = 0x00
    const val PRESSURE = 0x01
    const val OXYGEN = 0x02

    /** CRC-16/CCITT-FALSE, appended little endian. */
    private fun crc(data: ByteArray): Int {
        var reg = 0xFFFF
        for (byte in data) {
            reg = reg xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                reg = if (reg and 0x8000 != 0) ((reg shl 1) xor 0x1021) and 0xFFFF else (reg shl 1) and 0xFFFF
            }
        }
        return reg
    }

    /** Wraps group, command and payload into a frame the ring will accept. */
    fun frame(group: Int, command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val total = 4 + payload.size + 2
        val head = byteArrayOf(group.toByte(), command.toByte(), total.toByte(), (total shr 8).toByte()) + payload
        val sum = crc(head)
        return head + byteArrayOf(sum.toByte(), (sum shr 8).toByte())
    }

    /** Asks the ring to run a measurement; readings then arrive on [LIVE_DATA]. */
    fun startMeasuring(type: Int) = frame(0x03, 0x2F, byteArrayOf(0x01, type.toByte()))

    fun stopMeasuring() = frame(0x03, 0x2F, byteArrayOf(0x00, 0x00))

    /**
     * Continuous streaming rather than a single measurement.
     *
     * AppControlReal, captured from the vendor app, which used it whenever it wanted a live
     * figure instead of a thirty-second reading. Readings then arrive for as long as it is left
     * on, which is what a workout wants and what a spot measurement cannot give.
     */
    fun streamLive(on: Boolean, type: Int = 0x02) =
        frame(0x03, 0x09, byteArrayOf(if (on) 0x01 else 0x00, 0x00, type.toByte()))

    /** Reads firmware and battery. The literal "GC" is required; the ring refuses without it. */
    fun deviceInfo() = frame(0x02, 0x00, byteArrayOf(0x47, 0x43))

    /**
     * Which measurements the ring takes on its own.
     *
     * Each has its own command, so they are genuinely independent: turning blood oxygen off
     * leaves heart rate sampling untouched rather than silencing everything.
     */
    data class Monitors(
        val heart: Boolean = true,
        val oxygen: Boolean = true,
        /** Off by default — see [automaticMonitoring] for why. */
        val pressure: Boolean = false
    )

    /**
     * Turns on the ring's own periodic sampling, so it gathers without being asked.
     *
     * Every monitor is addressed on every call, including the ones being turned off: the ring
     * keeps this setting itself, so a monitor that is simply not mentioned stays however it was
     * last left, which is how a switch turned off in the app comes back on by itself.
     *
     * `01 0C` and `01 26` are verified against hardware and answer `00`. **`01 1C` is refused**:
     * this firmware replies `FC`, not implemented, so the ring takes blood pressure on its own
     * schedule for nobody. It stays off by default and the frame is still sent, because being
     * turned off is the one thing the command can usefully say. Stored pressure records do
     * exist — see `05 08` in PROTOCOL.md — so the ring measures it alongside something else.
     */
    fun automaticMonitoring(monitors: Monitors, minutes: Int = 5): List<ByteArray> {
        fun flag(on: Boolean) = if (on) 0x01.toByte() else 0x00.toByte()
        return listOf(
            frame(0x01, 0x0C, byteArrayOf(flag(monitors.heart), minutes.toByte())),
            frame(0x01, 0x26, byteArrayOf(flag(monitors.oxygen), minutes.toByte())),
            frame(0x01, 0x1C, byteArrayOf(flag(monitors.pressure), minutes.toByte()))
        )
    }

    /** settingGoal: a type byte, the goal as uint32 little endian, then two trailing bytes. */
    fun setStepGoal(steps: Int) = frame(
        0x01, 0x02,
        byteArrayOf(
            0x00,
            steps.toByte(), (steps shr 8).toByte(), (steps shr 16).toByte(), (steps shr 24).toByte(),
            0x00, 0x00
        )
    )

    /**
     * settingUserInfo. The ring uses height and weight to turn steps into distance and calories,
     * so these change the numbers it reports rather than merely being stored.
     *
     * **The field order is inferred, not verified.** The vendor SDK sends four bytes and names
     * them nowhere; this is the conventional order for rings in this class. The frame length and
     * CRC are computed either way, so the worst case is the ring rejecting it with `FE` rather
     * than the connection dropping. If distance starts reading oddly, this is the thing to
     * re-capture — see PROTOCOL.md.
     */
    fun setUserInfo(male: Boolean, age: Int, heightCm: Int, weightKg: Int) = frame(
        0x01, 0x03,
        byteArrayOf(
            if (male) 0x01 else 0x00,
            age.coerceIn(1, 120).toByte(),
            heightCm.coerceIn(50, 250).toByte(),
            weightKg.coerceIn(20, 200).toByte()
        )
    )

    // No setClock here on purpose. Writing the ring's clock erases its stored records, and the
    // ring sets its own at midnight, so a vitals app can only lose data by sending it. The
    // command itself is documented in PROTOCOL.md and exposed, marked risky, by the debugger.

    /**
     * settingSkin: one byte, the vendor's own six-level scale, lightest to darkest — see
     * [SkinTone]. Optical sensors (heart rate, oxygen, blood pressure) read less reliably on
     * darker skin without it.
     */
    fun setSkinTone(tone: SkinTone) = frame(0x01, 0x15, byteArrayOf(tone.code.toByte()))

    /**
     * AppBloodCalibration: two bytes, systolic then diastolic. Sent once, from a real cuff
     * reading, so the ring can calibrate its own pulse-wave estimate against it.
     */
    fun calibratePressure(systolic: Int, diastolic: Int) = frame(
        0x03, 0x03,
        byteArrayOf(systolic.coerceIn(60, 250).toByte(), diastolic.coerceIn(40, 150).toByte())
    )

    sealed interface Reading {
        data class Heart(val bpm: Int) : Reading
        data class Oxygen(val percent: Int) : Reading
        /** Estimated from the pulse waveform, not measured with a cuff. */
        data class Pressure(val systolic: Int, val diastolic: Int) : Reading
        data class Motion(val steps: Int, val distance: Int, val calories: Int) : Reading
        data class Power(val percent: Int, val charging: Boolean, val firmware: String) : Reading
        /**
         * Real_UploadBatteryLevel, pushed on its own rather than in reply to [deviceInfo].
         *
         * **The single-byte layout is inferred, not verified** — by analogy with every other
         * live push in this group (`06 01`/`02`/`03` are one or two bytes, no envelope), since
         * the vendor SDK source available for this ring does not cover this command's reply
         * shape. `deviceInfo()` stays polled alongside this as the confirmed fallback for
         * charging state and firmware, which this push does not carry either way. If the
         * percentage reads oddly, check `files/protocol-log.txt` for what `06 15` actually
         * contains — see PROTOCOL.md.
         */
        data class Battery(val percent: Int) : Reading
        data class Finished(val type: Int) : Reading
    }

    /** Decodes a frame the ring pushed, or null if it carries nothing this app displays. */
    fun read(value: ByteArray): Reading? {
        if (value.size < 6) return null
        val payload = value.copyOfRange(4, value.size - 2)
        fun at(i: Int) = payload[i].toInt() and 0xFF
        return when (value[0].toInt() and 0xFF to (value[1].toInt() and 0xFF)) {
            0x06 to 0x01 -> payload.takeIf { it.isNotEmpty() }?.let { Reading.Heart(at(0)) }
            0x06 to 0x02 -> payload.takeIf { it.isNotEmpty() }?.let { Reading.Oxygen(at(0)) }
            0x06 to 0x03 -> payload.takeIf { it.size >= 2 }?.let { Reading.Pressure(at(0), at(1)) }
            0x06 to 0x15 -> payload.takeIf { it.isNotEmpty() }?.let { Reading.Battery(at(0)) }
            0x04 to 0x0E -> payload.takeIf { it.isNotEmpty() }?.let { Reading.Finished(at(0)) }
            0x02 to 0x00 -> payload.takeIf { it.size >= 6 }?.let {
                Reading.Power(at(5), at(4) != 0, "V${at(3)}.${at(2)}")
            }
            else -> null
        }
    }

    /**
     * Asks for the readings the ring took on its own schedule.
     *
     * This is the only way to see them. The ring does not push an automatic reading when it
     * takes one — it writes it to its own store and says nothing — so a client that merely
     * listens sees a day of taps and nothing between them. Each query is answered with a count
     * and then the records themselves, as a push under a different command.
     *
     * Blood oxygen comes from the whole-history query rather than `05 1A`, which this firmware
     * accepts and never answers.
     */
    fun storedHeart() = frame(0x05, 0x06)
    /** Nights, which the ring stages itself and never volunteers. Read them with [SleepReader]. */
    fun storedSleep() = frame(0x05, 0x04)
    fun storedPressure() = frame(0x05, 0x08)
    fun storedOxygen() = frame(0x05, 0x09)

    /** Stored heart rates: six bytes a record, the reading last. */
    fun readStoredHeart(value: ByteArray) = records(value, 0x15, 6).mapNotNull { record ->
        record.reading(5)?.let { Triple(takenAt(record), it, 0) }
    }

    /**
     * Stored blood oxygen, from the whole-history reply: twenty bytes a record of which only
     * the percentage is filled in. The rest is the vendor's comprehensive layout — heart rate
     * variability, temperature and the others this ring does not implement — and reads as zero.
     */
    fun readStoredOxygen(value: ByteArray) = records(value, 0x18, 20).mapNotNull { record ->
        record.reading(9)?.let { Triple(takenAt(record), it, 0) }
    }

    /** Stored blood pressure: eight bytes a record, systolic then diastolic. */
    fun readStoredPressure(value: ByteArray) = records(value, 0x17, 8).mapNotNull { record ->
        record.reading(5)?.let { Triple(takenAt(record), it, record[6].toInt() and 0xFF) }
    }

    /** Splits a stored-history push into its fixed-width records, or nothing if it is not one. */
    private fun records(value: ByteArray, command: Int, size: Int): List<List<Byte>> {
        if (value.size < 6) return emptyList()
        if ((value[0].toInt() and 0xFF) != 0x05 || (value[1].toInt() and 0xFF) != command) return emptyList()
        return value.copyOfRange(4, value.size - 2).toList().chunked(size).filter { it.size == size }
    }

    /** A zero is an unused slot in the ring's store, not a reading of nothing. */
    private fun List<Byte>.reading(at: Int) = (this[at].toInt() and 0xFF).takeIf { it != 0 }

    /**
     * The ring counts seconds from 2000 rather than from the epoch.
     *
     * Records often share a timestamp — twenty of them in one capture — so a run of them is
     * worth the hour it falls in and not the minute; the burst window in [History] collapses
     * each run to a single row.
     */
    private fun takenAt(record: List<Byte>): Long {
        var seconds = 0L
        for (i in 3 downTo 0) seconds = (seconds shl 8) or (record[i].toLong() and 0xFF)
        return EPOCH_2000 + seconds * 1000L
    }

    private const val EPOCH_2000 = 946_684_800_000L

    /** The activity characteristic is a bare push with no frame around it. */
    fun readActivity(value: ByteArray): Reading.Motion? {
        if (value.size < 9) return null
        fun at(i: Int) = value[i].toInt() and 0xFF
        return Reading.Motion(
            steps = at(1) or (at(2) shl 8) or (at(3) shl 16),
            distance = at(4) or (at(5) shl 8) or (at(6) shl 16),
            calories = at(7) or (at(8) shl 8)
        )
    }

    /** The standard SIG heart rate characteristic. Its contact bit is unreliable on this ring. */
    fun readStandardHeartRate(value: ByteArray): Int? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 != 0) {
            if (value.size < 3) return null
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else value[1].toInt() and 0xFF
        return bpm.takeIf { it > 0 }
    }
}
