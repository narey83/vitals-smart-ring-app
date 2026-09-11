package uk.co.r99vitals

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened to the link to the ring, kept on the phone as plain text.
 *
 * Android's own Bluetooth log reaches back a few hours at best, so a night of missing steps was
 * otherwise diagnosed from whatever it still held. This keeps the collector's side of it —
 * connections, drops, subscriptions and what the watchdogs did about them — for as long as it
 * fits, and reads back without a screen:
 *
 *     adb shell run-as uk.co.r99vitals cat files/link-log.txt
 *
 * When it outgrows [limit] it moves aside to `link-log.old.txt`, so the last two stretches are
 * always there and neither grows without bound.
 */
class LinkLog(private val file: File, private val limit: Long = 256 * 1024) {
    constructor(context: Context) : this(File(context.filesDir, "link-log.txt"))

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)

    fun note(line: String, at: Long = System.currentTimeMillis()) = synchronized(writing) {
        runCatching {
            if (file.length() > limit) file.renameTo(File(file.parentFile, "link-log.old.txt"))
            file.appendText("${stamp.format(Date(at))}  $line\n")
        }
        Unit
    }

    private companion object {
        /** Callbacks arrive on Bluetooth's threads as well as the main one. */
        val writing = Any()
    }
}
