package uk.co.r99vitals

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import java.util.Date

/**
 * Health Connect refuses a whole batch over one bad record, so what goes across has to be valid
 * by construction: a route inside its session, a sport it knows, an id that replaces rather than
 * duplicates.
 */
@RunWith(RobolectricTestRunner::class)
class HealthExportTest {

    private val start = 1_789_400_000_000L

    private fun session(sport: String = "Run", minutes: Int = 10, metres: Int = 0, detected: Boolean = false) =
        Workouts.Session(Date(start), sport, minutes, listOf(120, 130), detected = detected, metres = metres, movingSeconds = 600)

    @Test fun `a run with a route is a running session with its route and distance`() {
        val fixes = (0..600).map { Route.Fix(start + it * 1000L, 55.849 + it * 1e-5, -4.237, 20.0, 5f) }
        val records = HealthExport.workoutRecords(session(metres = 1_800), fixes, ZoneOffset.UTC)
        val exercise = records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, exercise.exerciseType)
        assertEquals("workout-$start-session", exercise.metadata.clientRecordId)
        val distance = records.filterIsInstance<DistanceRecord>().single()
        assertEquals(1_800.0, distance.distance.inMeters, 0.0)
    }

    /** Kept to the minute, the session would end before the last few seconds of its route. */
    @Test fun `a route running past the recorded minutes stretches the session to hold it`() {
        val fixes = listOf(
            Route.Fix(start, 55.849, -4.237, accuracy = 5f),
            Route.Fix(start + 10 * 60_000L + 40_000, 55.85, -4.237, accuracy = 5f)
        )
        val exercise = HealthExport.workoutRecords(session(), fixes, ZoneOffset.UTC).filterIsInstance<ExerciseSessionRecord>().single()
        assertTrue(exercise.endTime.toEpochMilli() > start + 10 * 60_000L + 40_000)
    }

    /** Kept out of the day's readings, a workout's heart rate has to travel with the workout. */
    @Test fun `a workout's heart rate goes across as its own, inside the session`() {
        val timed = session(sport = "Yoga").copy(beats = listOf(80, 95, 101), beatSeconds = listOf(5, 300, 640))
        val records = HealthExport.workoutRecords(timed, emptyList(), ZoneOffset.UTC)
        val heart = records.filterIsInstance<androidx.health.connect.client.records.HeartRateRecord>().single()
        assertEquals(listOf(80L, 95L, 101L), heart.samples.map { it.beatsPerMinute })
        val exercise = records.filterIsInstance<ExerciseSessionRecord>().single()
        // 640 s is past the ten minutes the session was kept to; the session stretches to hold it.
        assertTrue(!exercise.endTime.isBefore(heart.samples.last().time))
        assertEquals("workout-$start-heart", heart.metadata.clientRecordId)
    }

    @Test fun `yoga has no route and no distance`() {
        val records = HealthExport.workoutRecords(session(sport = "Yoga"), emptyList(), ZoneOffset.UTC)
        assertEquals(1, records.size)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_YOGA, (records.single() as ExerciseSessionRecord).exerciseType)
    }

    @Test fun `a sport it does not name goes across as a workout`() {
        val exercise = HealthExport.workoutRecords(session(sport = "Other"), emptyList(), ZoneOffset.UTC).single() as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, exercise.exerciseType)
    }
}
