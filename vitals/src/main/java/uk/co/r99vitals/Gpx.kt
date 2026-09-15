package uk.co.r99vitals

import java.time.Instant

/**
 * A route as GPX, the file every mapping and training app opens.
 *
 * This is how a route reaches a map: Vitals draws none, and the wearer shares the file with an app
 * that has one — OsmAnd, Strava, anything — which then does with it whatever that app does. Sent
 * only when asked, through Android's share sheet, and never anywhere by Vitals itself.
 *
 * The raw fixes go across, less the vague ones [Track] ignores too: the app on the other end has
 * its own smoothing and would rather have the real positions than this one's averages.
 */
object Gpx {

    fun of(sport: String, startedAt: Long, fixes: List<Route.Fix>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Vitals" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <metadata><time>").append(Instant.ofEpochMilli(startedAt)).append("</time></metadata>\n")
        append("  <trk>\n")
        append("    <name>").append(escape(sport)).append("</name>\n")
        append("    <type>").append(type(sport)).append("</type>\n")
        append("    <trkseg>\n")
        fixes.filter { (it.accuracy ?: 0f) <= Track.WORST_ACCURACY }.forEach { fix ->
            // Double.toString, which is never written with a comma, whatever the phone's language.
            append("      <trkpt lat=\"").append(fix.latitude).append("\" lon=\"").append(fix.longitude).append("\">")
            fix.altitude?.let { append("<ele>").append(it).append("</ele>") }
            append("<time>").append(Instant.ofEpochMilli(fix.at)).append("</time></trkpt>\n")
        }
        append("    </trkseg>\n")
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /** "vitals-run-2026-09-14.gpx" — what the receiving app shows before it is opened. */
    fun fileName(sport: String, startedAt: Long): String {
        val day = Instant.ofEpochMilli(startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "vitals-${sport.lowercase().filter { it.isLetterOrDigit() }}-$day.gpx"
    }

    /** The activity types Strava and others read from `<type>`. */
    private fun type(sport: String) = when (sport) {
        "Walk" -> "walking"
        "Run" -> "running"
        "Ride" -> "cycling"
        else -> "other"
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
