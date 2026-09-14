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
        /** Nothing newer is on the server. */
        data object UpToDate : Result
        /**
         * A newer build is available; [ufw] is the extracted image, ready for [RingOta].
         * [approved] is whether the vendor lists this ring for it: `false` means the build exists
         * and is newer but was withheld from this ring's group — offered anyway at the wearer's
         * word, with the extra risk made plain, since a build gated away can be gated for a reason.
         */
        data class Available(val version: String, val ufw: File, val approved: Boolean) : Result
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

    /** A version to fetch: its number, the zip URL, and whether the vendor lists this ring for it. */
    internal data class Upgrade(val version: String, val url: String, val approved: Boolean)

    /**
     * The newest offer in [manifest] that beats [currentVersion], or null when nothing on the
     * server is newer. The general offer (`url`) is always the ring's own and counts as approved.
     * The MAC-targeted offer (`mac_url`) is taken whenever it is newer — approved only when [mac]
     * is on the `mac` allowlist. A build outside the list is still returned so the wearer can
     * choose it, with [Upgrade.approved] false; whichever offer names the higher version wins.
     */
    internal fun chooseUpgrade(manifest: Map<String, String>, mac: String, currentVersion: String): Upgrade? {
        val offers = buildList {
            val macUrl = manifest["mac_url"].orEmpty()
            val macV = manifest["mac_bNo"]?.toIntOrNull() to manifest["mac_sNo"]?.toIntOrNull()
            if (macUrl.isNotEmpty() && macV.first != null && macV.second != null) {
                val approved = mac.uppercase() in manifest["mac"].orEmpty().split(",").map { it.trim().uppercase() }
                add(Upgrade("${macV.first}.${macV.second}", macUrl, approved) to (macV.first!! to macV.second!!))
            }
            val url = manifest["url"].orEmpty()
            val v = manifest["bNo"]?.toIntOrNull() to manifest["sNo"]?.toIntOrNull()
            if (url.isNotEmpty() && v.first != null && v.second != null) {
                add(Upgrade("${v.first}.${v.second}", url, true) to (v.first!! to v.second!!))
            }
        }
        return offers.filter { isNewer(it.second, currentVersion) }.maxByOrNull { it.second.first * 1000 + it.second.second }?.first
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
        val upgrade = chooseUpgrade(manifest, mac, currentVersion) ?: return Result.UpToDate
        val ufw = runCatching { download(context, upgrade.url) }
            .getOrElse { return Result.Failed("could not download the firmware: ${it.message}") }
            ?: return Result.Failed("the downloaded firmware held no update.ufw")
        return Result.Available(upgrade.version, ufw, upgrade.approved)
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
