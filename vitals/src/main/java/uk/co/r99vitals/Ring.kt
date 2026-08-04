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

    /** Turns on the ring's own periodic sampling, so it gathers without being asked. */
    fun automaticMonitoring(on: Boolean, minutes: Int = 5): List<ByteArray> {
        val flag = if (on) 0x01.toByte() else 0x00.toByte()
        return listOf(
            frame(0x01, 0x0C, byteArrayOf(flag, minutes.toByte())),
            frame(0x01, 0x26, byteArrayOf(flag, minutes.toByte()))
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

    /** The ring stamps stored records with its own clock, so it is worth keeping accurate. */
    fun setClock(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) =
        frame(
            0x01, 0x00,
            byteArrayOf(
                year.toByte(), (year shr 8).toByte(),
                month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), second.toByte(), 0x00
            )
        )

    sealed interface Reading {
        data class Heart(val bpm: Int) : Reading
        data class Oxygen(val percent: Int) : Reading
        /** Estimated from the pulse waveform, not measured with a cuff. */
        data class Pressure(val systolic: Int, val diastolic: Int) : Reading
        data class Motion(val steps: Int, val distance: Int, val calories: Int) : Reading
        data class Power(val percent: Int, val charging: Boolean, val firmware: String) : Reading
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
            0x04 to 0x0E -> payload.takeIf { it.isNotEmpty() }?.let { Reading.Finished(at(0)) }
            0x02 to 0x00 -> payload.takeIf { it.size >= 6 }?.let {
                Reading.Power(at(5), at(4) != 0, "V${at(3)}.${at(2)}")
            }
            else -> null
        }
    }

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
