package uk.co.r99vitals

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a newer Vitals has been released on GitHub.
 *
 * This is the one thing the app asks the network, and all it asks: GitHub's public "latest
 * release" address for this repository, at most once a day, with nothing in the request but the
 * app's own version. No reading, no identifier and nothing about the ring goes with it; GitHub
 * sees what any web request shows, the phone's address. Switched off in Settings, nothing reaches
 * the network at all.
 *
 * Releases are the tags `v<version>` described in gradle.properties. A draft or a pre-release is
 * never offered: GitHub's "latest" skips both.
 */
object Updates {

    data class Release(val version: String, val page: String)

    private const val DAY = 24 * 60 * 60 * 1000L
    private const val CHANNEL = "updates"
    private const val NOTIFICATION = 5

    private fun prefs(context: Context) = context.getSharedPreferences("ring", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("updateChecks", true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("updateChecks", on).apply()

    /** Switched on, and a day since GitHub last answered. A check that failed does not count. */
    fun due(context: Context, now: Long = System.currentTimeMillis()) =
        enabled(context) && now - prefs(context).getLong("updateCheckedAt", 0L) >= DAY

    /** The newest release GitHub has told of, if it is newer than this build. */
    fun available(context: Context): Release? {
        val version = prefs(context).getString("updateVersion", null) ?: return null
        val page = prefs(context).getString("updatePage", null) ?: return null
        return Release(version, page).takeIf { newer(version, BuildConfig.VERSION_NAME) }
    }

    /**
     * Asks GitHub now and remembers the answer. Blocking, so never on the main thread. Throws if
     * GitHub could not be asked, so a phone without signal is not taken to mean "up to date".
     */
    fun check(context: Context) {
        // Where Network is a permission the wearer grants, and it has not been, the request would
        // fail as "unable to resolve host", which says nothing about why.
        if (context.checkSelfPermission(android.Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("the Network permission is off")
        }
        val latest = fetch(BuildConfig.REPO)
        prefs(context).edit().apply {
            putLong("updateCheckedAt", System.currentTimeMillis())
            if (latest != null) putString("updateVersion", latest.version).putString("updatePage", latest.page)
            else remove("updateVersion").remove("updatePage")
        }.apply()
    }

    /** The latest release of [repo], or null while it has none. */
    private fun fetch(repo: String): Release? {
        val connection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "R99-Vitals/${BuildConfig.VERSION_NAME}")
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parse(connection.inputStream.bufferedReader().use { it.readText() }, repo)
                // What GitHub says for a repository with no release published yet.
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** GitHub's release, as far as this app cares: its version and the page to read it on. */
    fun parse(json: String, repo: String): Release? {
        val release = JSONObject(json)
        val tag = release.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val page = release.optString("html_url").takeIf { it.isNotBlank() } ?: "https://github.com/$repo/releases"
        return Release(tag.removePrefix("v"), page)
    }

    /**
     * Whether [latest] comes after [installed], compared number by number: 0.10.0 is after 0.9.0,
     * which comparing the text would get backwards. A tag may carry a leading `v`, and anything
     * after a hyphen is ignored.
     */
    fun newer(latest: String, installed: String): Boolean {
        fun parts(version: String) = version.trim().removePrefix("v").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Says so once for each release. The same release announced every day is noise, and the
     * Settings page carries it anyway until it is installed.
     *
     * On its own channel, and a quiet one: an update is worth seeing when the phone is next
     * picked up, not worth a sound.
     */
    fun announce(context: Context, release: Release) {
        if (prefs(context).getString("updateAnnounced", null) == release.version) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Says when a newer version of Vitals is on GitHub" }
            )
        }
        val open = PendingIntent.getActivity(
            context, 5, Intent(Intent.ACTION_VIEW, Uri.parse(release.page)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setContentTitle("Vitals ${release.version} is out")
            .setContentText("You have ${BuildConfig.VERSION_NAME}. Tap to see what changed and download it.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION, notification)
        prefs(context).edit().putString("updateAnnounced", release.version).apply()
    }
}
