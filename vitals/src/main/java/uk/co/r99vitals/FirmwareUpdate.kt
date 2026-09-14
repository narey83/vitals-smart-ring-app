package uk.co.r99vitals

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Finds and fetches a newer ring firmware, ready for [RingOta] to flash. It does not flash
 * anything itself — downloading is safe, writing to the ring is not.
 *
 * The vendor serves a per-model manifest at `…/firmware/<model>.plist` naming a `.zip` that holds
 * one `update.ufw` (a JieLi AC632N image — see PROTOCOL.md). A manifest carries two offers: a
 * general one (`url`, `bNo`/`sNo`) and a MAC-targeted one (`mac_url`, `mac_bNo`/`mac_sNo`, and a
 * `mac` allowlist). This ring's R11M manifest leaves the general offer empty and gates the only
 * newer build to an allowlist, so most rings are told they are current — which is the honest
 * answer, not a bug.
 *
 * No reading ever leaves the phone; this reaches the vendor's static host only, and only when the
 * wearer asks to check. Nothing here is automatic.
 */
object FirmwareUpdate {

    private const val BASE = "https://staticpage.ycaviation.com/firmware/"

    /** The vendor keys the manifest by an internal model name; this ring answers to R11M. */
    const val MODEL = "R11M"

    sealed interface Result {
        /** Nothing newer applies to this ring. */
        data object UpToDate : Result
        /** A newer build is available; [ufw] is the extracted image, ready for [RingOta]. */
        data class Available(val version: String, val ufw: File) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Whether [candidate] (bNo.sNo) is newer than [current] (e.g. "V2.32" or "2.32"). A version
     * that will not parse is treated as not-newer, so a malformed manifest never offers a flash.
     */
    fun isNewer(candidate: Pair<Int, Int>, current: String): Boolean {
        val parts = current.trimStart('V', 'v').split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return false
        return candidate.first > parts[0] || (candidate.first == parts[0] && candidate.second > parts[1])
    }

    /** The `<key>k</key><string>v</string>` pairs of a plist, flattened to a map. */
    internal fun parsePlist(xml: String): Map<String, String> {
        val pairs = Regex("<key>(.*?)</key>\\s*<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        return pairs.findAll(xml).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
    }

    /**
     * Which offer in [manifest] applies to [mac] and beats [currentVersion], if any. The
     * MAC-targeted offer wins when the ring is on its allowlist; otherwise the general offer, when
     * it has a URL at all. Returns the version string and the zip URL to fetch.
     */
    internal fun chooseUpgrade(manifest: Map<String, String>, mac: String, currentVersion: String): Pair<String, String>? {
        val macUrl = manifest["mac_url"].orEmpty()
        val allow = manifest["mac"].orEmpty().split(",").map { it.trim().uppercase() }
        if (macUrl.isNotEmpty() && mac.uppercase() in allow) {
            val v = (manifest["mac_bNo"]?.toIntOrNull() ?: return null) to (manifest["mac_sNo"]?.toIntOrNull() ?: return null)
            if (isNewer(v, currentVersion)) return "${v.first}.${v.second}" to macUrl
        }
        val url = manifest["url"].orEmpty()
        if (url.isNotEmpty()) {
            val v = (manifest["bNo"]?.toIntOrNull() ?: return null) to (manifest["sNo"]?.toIntOrNull() ?: return null)
            if (isNewer(v, currentVersion)) return "${v.first}.${v.second}" to url
        }
        return null
    }

    /**
     * Checks for and, if there is one, downloads a newer firmware. Blocking network and disk work,
     * so call it off the main thread. [mac] is the ring's Bluetooth address; [currentVersion] the
     * firmware it reports now.
     */
    fun check(context: Context, mac: String, currentVersion: String, model: String = MODEL): Result {
        val manifestXml = runCatching { fetchText("$BASE$model.plist") }
            .getOrElse { return Result.Failed("could not reach the update server: ${it.message}") }
            ?: return Result.Failed("no firmware manifest for this ring")
        val manifest = parsePlist(manifestXml)
        val (version, zipUrl) = chooseUpgrade(manifest, mac, currentVersion) ?: return Result.UpToDate
        val ufw = runCatching { download(context, zipUrl) }
            .getOrElse { return Result.Failed("could not download the firmware: ${it.message}") }
            ?: return Result.Failed("the downloaded firmware held no update.ufw")
        return Result.Available(version, ufw)
    }

    private fun fetchText(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000
        }
        return try {
            if (connection.responseCode != 200) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    /** Downloads the firmware zip and writes its `update.ufw` into the cache, returning the file. */
    private fun download(context: Context, url: String): File? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000
        }
        try {
            if (connection.responseCode != 200) return null
            val dir = File(context.cacheDir, "firmware").apply { mkdirs() }
            ZipInputStream(connection.inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".ufw")) {
                        val out = File(dir, "update.ufw")
                        out.outputStream().use { zip.copyTo(it) }
                        return out
                    }
                    entry = zip.nextEntry
                }
            }
            return null
        } finally { connection.disconnect() }
    }
}
