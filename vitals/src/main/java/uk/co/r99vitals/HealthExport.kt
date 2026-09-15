package uk.co.r99vitals

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.ZoneId

/**
 * Hands readings to Health Connect, so other apps on the phone can use them.
 *
 * Health Connect is on-device inter-process communication, not a network service: nothing here
 * leaves the phone. Whether anything is
 * shared at all remains the wearer's decision, made once when they grant these permissions and
 * revocable in Android's own settings rather than in this app.
 */
class HealthExport(private val context: Context) {

    val permissions = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class)
    )

    /**
     * What is asked for: everything above, and routes. Routes are asked for but not required —
     * Health Connect lets them be refused on their own, and a workout goes across without its
     * route rather than not at all.
     */
    val requested = permissions + HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE

    // The ring took these readings itself, so they are attributed to it rather than to the phone.
    private val ring = Metadata.autoRecorded(Device(type = Device.TYPE_RING))

    companion object {
        /**
         * One workout as Health Connect records: the session, and its distance if it went
         * anywhere. Kept apart from the client so it can be checked without Health Connect.
         */
        fun workoutRecords(session: Workouts.Session, fixes: List<Route.Fix>, zone: java.time.ZoneOffset): List<Record> {
            val start = session.at.time
            val good = fixes.filter { it.at >= start && (it.accuracy ?: 0f) <= Track.WORST_ACCURACY }
            // The session is kept to the minute, and a route's last fix can land in the seconds
            // after it; Health Connect refuses a route that runs past the end of its session.
            val end = maxOf(start + session.minutes * 60_000L, (good.lastOrNull()?.at ?: 0L) + 1_000L)
            val id = "workout-$start"
            // Started by the wearer is actively recorded; found in the step counter is not.
            val phone = Device(type = Device.TYPE_PHONE)
            fun meta(kind: String) = if (session.detected) Metadata.autoRecorded(phone, "$id-$kind", 1)
                else Metadata.activelyRecorded(phone, "$id-$kind", 1)
            val route = good.takeIf { it.size >= 2 }?.let { kept ->
                ExerciseRoute(kept.map { fix ->
                    ExerciseRoute.Location(
                        time = Instant.ofEpochMilli(fix.at),
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        horizontalAccuracy = fix.accuracy?.let { Length.meters(it.toDouble()) },
                        altitude = fix.altitude?.let { Length.meters(it) }
                    )
                })
            }
            val exercise = ExerciseSessionRecord(
                startTime = Instant.ofEpochMilli(start), startZoneOffset = zone,
                endTime = Instant.ofEpochMilli(end), endZoneOffset = zone,
                metadata = meta("session"),
                exerciseType = when (session.sport) {
                    "Walk" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                    "Run" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                    "Ride" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                    "Yoga" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
                    else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
                },
                title = session.sport,
                exerciseRoute = route
            )
            val distance = session.metres.takeIf { it > 0 }?.let {
                DistanceRecord(
                    startTime = Instant.ofEpochMilli(start), startZoneOffset = zone,
                    endTime = Instant.ofEpochMilli(end), endZoneOffset = zone,
                    distance = Length.meters(it.toDouble()),
                    metadata = meta("distance")
                )
            }
            return listOfNotNull(exercise, distance)
        }
    }

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    val available: Boolean get() = availability() == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient? get() =
        if (available) HealthConnectClient.getOrCreate(context) else null

    suspend fun granted(): Boolean =
        client?.permissionController?.getGrantedPermissions()?.containsAll(permissions) ?: false

    /**
     * Workouts, each as an exercise session with its route where one was kept and allowed, and
     * the distance it covered. Written with the start time as the record's own id, so sending
     * again replaces a session rather than adding a second copy of it — including one whose sport
     * has been corrected since.
     */
    suspend fun sendWorkouts(sessions: List<Workouts.Session>, routeOf: (Long) -> List<Route.Fix>): Int {
        val connect = client ?: return 0
        val routes = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE in connect.permissionController.getGrantedPermissions()
        val zone = ZoneId.systemDefault().rules.getOffset(Instant.now())
        val records = sessions.flatMap { session ->
            workoutRecords(session, if (routes) routeOf(session.at.time) else emptyList(), zone)
        }
        if (records.isEmpty()) return 0
        records.chunked(400).forEach { connect.insertRecords(it) }
        return records.size
    }

    /**
     * Writes everything held to Health Connect and reports how many records went across.
     * Readings already sent are written again with the same instants, which Health Connect
     * de-duplicates by origin and time rather than piling up.
     */
    suspend fun send(entries: List<History.Entry>): Int {
        val connect = client ?: return 0
        val zone = ZoneId.systemDefault().rules.getOffset(Instant.now())
        val records = entries.mapNotNull { entry ->
            val at = entry.at.toInstant()
            when (entry.kind) {
                "heart" -> HeartRateRecord(
                    startTime = at, startZoneOffset = zone,
                    endTime = at.plusSeconds(1), endZoneOffset = zone,
                    samples = listOf(HeartRateRecord.Sample(at, entry.value.toLong())),
                    metadata = ring
                )
                "oxygen" -> OxygenSaturationRecord(
                    time = at, zoneOffset = zone,
                    percentage = Percentage(entry.value.toDouble()),
                    metadata = ring
                )
                "pressure" -> BloodPressureRecord(
                    time = at, zoneOffset = zone,
                    systolic = Pressure.millimetersOfMercury(entry.value.toDouble()),
                    diastolic = Pressure.millimetersOfMercury(entry.extra.toDouble()),
                    metadata = ring
                )
                // The ring reports a running total; Health Connect wants a count for a period,
                // so a day's steps are written as one record covering that day.
                else -> null
            }
        }
        if (records.isEmpty()) return 0
        // Health Connect refuses very large writes, so send them in batches.
        records.chunked(400).forEach { connect.insertRecords(it) }
        return records.size
    }

    /**
     * Nights, with the stages inside them, so anything else on the phone reads the same sleep this
     * app shows rather than a total it has to guess the shape of.
     *
     * Fragments go across as well: it is not this app's business to decide that half an hour the
     * ring recorded did not happen. What it does decide — scores, averages — stays here.
     */
    suspend fun sendSleep(nights: List<Sleep.Night>): Int {
        val connect = client ?: return 0
        val zone = ZoneId.systemDefault().rules.getOffset(Instant.now())
        val records = nights.filter { it.stages.isNotEmpty() }.map { night ->
            SleepSessionRecord(
                startTime = Instant.ofEpochMilli(night.startedAt), startZoneOffset = zone,
                endTime = Instant.ofEpochMilli(night.endedAt), endZoneOffset = zone,
                stages = night.stages.map { stage ->
                    SleepSessionRecord.Stage(
                        startTime = Instant.ofEpochMilli(stage.startedAt),
                        endTime = Instant.ofEpochMilli(stage.startedAt + stage.seconds * 1000L),
                        stage = when (stage.code) {
                            Sleep.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
                            Sleep.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
                            Sleep.REM -> SleepSessionRecord.STAGE_TYPE_REM
                            else -> SleepSessionRecord.STAGE_TYPE_AWAKE
                        }
                    )
                },
                metadata = ring
            )
        }
        if (records.isEmpty()) return 0
        connect.insertRecords(records)
        return records.size
    }

    /**
     * Steps arrive as a running total, so each day becomes a single record of what the counter
     * rose by across it, measured from where the day before left off — see Steps.total.
     */
    suspend fun sendSteps(entries: List<History.Entry>): Int {
        val connect = client ?: return 0
        val zone = ZoneId.systemDefault().rules.getOffset(Instant.now())
        val byDay = entries.filter { it.kind == "steps" }.sortedBy { it.at }
            .groupBy { it.at.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() }
        var baseline = 0
        val records = byDay.mapNotNull { (day, readings) ->
            val total = Steps.total(readings, baseline)
            baseline = readings.last().value
            if (total <= 0) return@mapNotNull null
            StepsRecord(
                startTime = day.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                startZoneOffset = zone,
                endTime = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusSeconds(1),
                endZoneOffset = zone,
                count = total.toLong(),
                metadata = ring
            )
        }
        if (records.isEmpty()) return 0
        connect.insertRecords(records)
        return records.size
    }
}
