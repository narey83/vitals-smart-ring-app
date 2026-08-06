package uk.co.r99vitals

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.ZoneId

/**
 * Hands readings to Health Connect, so other apps on the phone can use them.
 *
 * Health Connect is on-device inter-process communication, not a network service: nothing here
 * leaves the phone, and the app still declares no INTERNET permission. Whether anything is
 * shared at all remains the wearer's decision, made once when they grant these permissions and
 * revocable in Android's own settings rather than in this app.
 */
class HealthExport(private val context: Context) {

    val permissions = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class)
    )

    // The ring took these readings itself, so they are attributed to it rather than to the phone.
    private val ring = Metadata.autoRecorded(Device(type = Device.TYPE_RING))

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    val available: Boolean get() = availability() == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient? get() =
        if (available) HealthConnectClient.getOrCreate(context) else null

    suspend fun granted(): Boolean =
        client?.permissionController?.getGrantedPermissions()?.containsAll(permissions) ?: false

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

    /** Steps arrive as a running total, so each day becomes a single record of its highest count. */
    suspend fun sendSteps(entries: List<History.Entry>): Int {
        val connect = client ?: return 0
        val zone = ZoneId.systemDefault().rules.getOffset(Instant.now())
        val byDay = entries.filter { it.kind == "steps" }
            .groupBy { it.at.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() }
        val records = byDay.mapNotNull { (day, readings) ->
            val total = readings.maxOfOrNull { it.value } ?: return@mapNotNull null
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
