package uk.co.r99vitals

import android.content.Context
import java.io.File

/**
 * The workout being recorded now, written down as it happens.
 *
 * Every session belongs to the collector — the one the wearer started as much as the one it
 * found — because a run is mostly spent with the screen off, and a session held in the screen's
 * memory ended whenever Android reclaimed the screen. The collector writes the session here; the
 * screen reads it back, curve and all, whenever it is opened.
 *
 * Written as it goes rather than kept only in memory, so a collector restarted mid-run picks the
 * session up where it was instead of losing it. The first line says what the session is, and
 * every line after it is one heart reading.
 */
class LiveSession(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "session.txt"))

    data class Now(
        val sport: String,
        val since: Long,
        /** Found in the step counter rather than started by the wearer. */
        val detected: Boolean
    )

    /** The session running now, or null if none is. */
    fun read(): Now? = synchronized(writing) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val parts = file.bufferedReader().use { it.readLine() }?.split(",") ?: return@runCatching null
            if (parts.size < 3) return@runCatching null
            Now(parts[0], parts[1].toLongOrNull() ?: return@runCatching null, parts[2] == "1")
        }.getOrNull()
    }

    /** Starts a session afresh, forgetting whatever an earlier one left behind. */
    fun begin(sport: String, since: Long, detected: Boolean) = synchronized(writing) {
        runCatching { file.writeText(header(sport, since, detected)) }
        Unit
    }

    /** A detected walk that has picked up into a run. The readings so far stay with it. */
    fun rename(sport: String) = synchronized(writing) {
        runCatching {
            val now = read() ?: return@runCatching
            val beats = beats()
            file.writeText(header(sport, now.since, now.detected) + beats.joinToString("") { "$it\n" })
        }
        Unit
    }

    fun beat(bpm: Int) = synchronized(writing) {
        runCatching { if (file.exists()) file.appendText("$bpm\n") }
        Unit
    }

    /** The session's curve so far, oldest first. */
    fun beats(): List<Int> = synchronized(writing) {
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            file.readLines().drop(1).mapNotNull { it.toIntOrNull() }
        }.getOrDefault(emptyList())
    }

    fun clear() = synchronized(writing) {
        runCatching { file.delete() }
        Unit
    }

    private fun header(sport: String, since: Long, detected: Boolean) =
        "$sport,$since,${if (detected) "1" else "0"}\n"

    private companion object {
        /** The collector writes from the Bluetooth callback while the screen reads from its own. */
        val writing = Any()
    }
}
