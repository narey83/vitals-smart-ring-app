package uk.co.r99vitals

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * Follows the phone's GPS for as long as a session wants a route.
 *
 * The platform's own GPS provider rather than Google's fused location: Vitals runs on phones with
 * no Google services at all (GrapheneOS among them), and a route wants satellites, not the guess
 * from nearby Wi-Fi that fused location falls back on. A fix a second is what a GPS gives anyway,
 * and is fine enough to follow a corner at a run.
 *
 * Only ever started by the collector while it holds the location foreground service type, which
 * is what lets it go on with the screen off. See CollectorService.followRoute.
 */
class RouteRecorder(private val context: Context) {

    private val manager get() = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    val running get() = listener != null

    /** Whether the phone's location is switched on at all; without it no fix will ever come. */
    val enabled get() = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    /** Throws SecurityException without the location permission; the caller decides what that means. */
    @SuppressLint("MissingPermission")
    fun start(route: RouteFile, onFix: (Route.Fix) -> Unit) {
        if (running) return
        // Every method overridden: before Android 11 the other three have no default, and a
        // provider being switched on or off would call one that is not there.
        val following = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = Route.Fix(
                    at = location.time,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude.takeIf { location.hasAltitude() },
                    accuracy = location.accuracy.takeIf { location.hasAccuracy() }
                )
                route.append(fix)
                onFix(fix)
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Never called from Android 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, following, Looper.getMainLooper())
        listener = following
    }

    fun stop() {
        listener?.let { runCatching { manager.removeUpdates(it) } }
        listener = null
    }
}
