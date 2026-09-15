package uk.co.r99vitals

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * Where a workout went, from the phone's own GPS.
 *
 * The ring has no GPS, so a route is the one part of a session the phone measures rather than
 * the ring. It stays on the phone like everything else: written to a file of its own next to the
 * workouts, drawn without a map behind it, and handed to other apps only when the wearer shares
 * it. A route is also where somebody lives, which is a reason to keep it apart from the workouts
 * file, so that it can be deleted without losing the session it belongs to.
 */
object Route {

    /** The sports a route means something for. Yoga on a mat goes nowhere. */
    val SPORTS = setOf("Walk", "Run", "Ride")

    /** One position, as the GPS gave it. Altitude and accuracy are not always known. */
    data class Fix(
        val at: Long,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null,
        /** Metres, the radius the GPS is 68% sure of. */
        val accuracy: Float? = null
    )

    fun permitted(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun folder(context: Context) = File(context.filesDir, "routes")
}

/**
 * One session's route, a fix a line, named by when the session started so it can be found from
 * the workout it belongs to.
 *
 * Appended as the fixes arrive rather than written at the end, so a collector restarted mid-run
 * keeps the route so far, as [LiveSession] keeps the heart curve.
 */
class RouteFile(private val file: File) {
    constructor(folder: File, startedAt: Long) : this(File(folder, "$startedAt.csv"))

    fun append(fix: Route.Fix) = synchronized(writing) {
        runCatching {
            file.parentFile?.mkdirs()
            // Double.toString, never a formatter: a comma for a decimal point would split the line.
            file.appendText(
                listOf(fix.at, fix.latitude, fix.longitude, fix.altitude ?: "", fix.accuracy ?: "")
                    .joinToString(",", postfix = "\n")
            )
        }
        Unit
    }

    /** Every fix held, in the order they arrived. A line cut short by a crash is skipped. */
    fun fixes(): List<Route.Fix> = synchronized(writing) {
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            file.readLines().mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size < 5) return@mapNotNull null
                Route.Fix(
                    at = parts[0].toLongOrNull() ?: return@mapNotNull null,
                    latitude = parts[1].toDoubleOrNull() ?: return@mapNotNull null,
                    longitude = parts[2].toDoubleOrNull() ?: return@mapNotNull null,
                    altitude = parts[3].toDoubleOrNull(),
                    accuracy = parts[4].toFloatOrNull()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun delete() = synchronized(writing) { runCatching { file.delete() }; Unit }

    private companion object {
        val writing = Any()
    }
}
