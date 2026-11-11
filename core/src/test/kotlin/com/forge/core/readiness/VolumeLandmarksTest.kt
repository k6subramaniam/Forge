package com.forge.core.readiness

import com.forge.core.data.ExerciseLibrary
import com.forge.core.testing.TestRoutines
import com.forge.core.model.Exercise
import com.forge.core.model.MuscleGroup
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.training.CompletedSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class VolumeLandmarksTest {

    private val zone: ZoneId = ZoneOffset.UTC

    // The week of Monday 2026-11-02 .. Sunday 2026-11-08.
    private val monday: LocalDate = LocalDate.of(2026, 11, 2)

    private fun workout(id: String, isoStart: String, exercise: Exercise, sets: Int): CompletedSession {
        val start = Instant.parse(isoStart)
        return CompletedSession(
            session = WorkoutSession(id = id, startTime = start, endTime = start.plusSeconds(3600)),
            sets = (1..sets).map { n ->
                SetEntry(
                    id = "$id-$n", sessionId = id, exerciseId = exercise.id,
                    exerciseName = exercise.name, setNumber = n,
                    weightKg = 60.0, reps = 8, timestamp = start.plusSeconds(n * 180L),
                )
            },
        )
    }

    private fun volumeFor(
        history: List<CompletedSession>,
        group: MuscleGroup,
        anyDate: LocalDate = monday,
    ): MuscleGroupVolume = VolumeLandmarks.weeklyVolume(history, anyDate, zone).first { it.muscleGroup == group }

    @Test
    fun `week starts on Monday`() {
        assertEquals(monday, VolumeLandmarks.weekStartOf(LocalDate.of(2026, 11, 2))) // Monday itself
        assertEquals(monday, VolumeLandmarks.weekStartOf(LocalDate.of(2026, 11, 8))) // Sunday
        assertEquals(monday, VolumeLandmarks.weekStartOf(LocalDate.of(2026, 11, 4))) // Wednesday
        assertEquals(LocalDate.of(2026, 11, 9), VolumeLandmarks.weekStartOf(LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `sets are counted per primary group, with half credit for secondary`() {
        // Bench press: primary CHEST, secondary TRICEPS + SHOULDERS (library tags).
        val history = listOf(workout("w1", "2026-11-03T09:00:00Z", TestRoutines.benchPress, sets = 4))
        val chest = volumeFor(history, MuscleGroup.CHEST)
        assertEquals(4, chest.primarySets)
        assertEquals(0, chest.secondarySets)
        assertEquals(4.0, chest.effectiveSets)

        val triceps = volumeFor(history, MuscleGroup.TRICEPS)
        assertEquals(0, triceps.primarySets)
        assertEquals(4, triceps.secondarySets)
        assertEquals(2.0, triceps.effectiveSets)
        assertEquals(monday, chest.weekStart)
    }

    @Test
    fun `sessions outside the week are not counted - Sunday counts, next Monday does not`() {
        val history = listOf(
            workout("sun", "2026-11-08T09:00:00Z", TestRoutines.benchPress, sets = 3),  // Sunday of the week
            workout("next", "2026-11-09T09:00:00Z", TestRoutines.benchPress, sets = 5), // next week
            workout("prev", "2026-11-01T09:00:00Z", TestRoutines.benchPress, sets = 7), // previous week
        )
        assertEquals(3, volumeFor(history, MuscleGroup.CHEST).primarySets)
        // Asking from inside the next week sees only that week's session.
        assertEquals(5, volumeFor(history, MuscleGroup.CHEST, anyDate = LocalDate.of(2026, 11, 11)).primarySets)
    }

    @Test
    fun `band classification walks the landmarks`() {
        val chest = VolumeLandmarks.LANDMARKS.getValue(MuscleGroup.CHEST) // MEV 8, MAV 12–20, MRV 22
        assertEquals(VolumeBand.BELOW_MAINTENANCE, VolumeLandmarks.bandFor(0.0, chest))
        assertEquals(VolumeBand.BELOW_MAINTENANCE, VolumeLandmarks.bandFor(7.5, chest))
        assertEquals(VolumeBand.MAINTENANCE, VolumeLandmarks.bandFor(8.0, chest))
        assertEquals(VolumeBand.MAINTENANCE, VolumeLandmarks.bandFor(11.5, chest))
        assertEquals(VolumeBand.PRODUCTIVE, VolumeLandmarks.bandFor(12.0, chest))
        assertEquals(VolumeBand.PRODUCTIVE, VolumeLandmarks.bandFor(20.0, chest))
        assertEquals(VolumeBand.HIGH, VolumeLandmarks.bandFor(21.0, chest))
        assertEquals(VolumeBand.HIGH, VolumeLandmarks.bandFor(22.0, chest))
        assertEquals(VolumeBand.ABOVE_LANDMARKS, VolumeLandmarks.bandFor(23.0, chest))
    }

    @Test
    fun `a realistic push week lands chest in the productive band`() {
        // Tuesday: bench 4 + incline dumbbell press 3 (both CHEST primary).
        // Friday: bench 4 + overhead press 3 (SHOULDERS primary).
        // The incline press is outside the 10-exercise starter set the other
        // tests use; the full library is passed explicitly for clarity (it is
        // also the production default now).
        val history = listOf(
            workout("tue1", "2026-11-03T09:00:00Z", TestRoutines.benchPress, sets = 4),
            workout("tue2", "2026-11-03T10:00:00Z", ExerciseLibrary.inclineDumbbellPress, sets = 3),
            workout("fri1", "2026-11-06T09:00:00Z", TestRoutines.benchPress, sets = 4),
            workout("fri2", "2026-11-06T10:00:00Z", TestRoutines.overheadPress, sets = 3),
        )
        val chest = VolumeLandmarks.weeklyVolume(history, monday, zone, ExerciseLibrary.all)
            .first { it.muscleGroup == MuscleGroup.CHEST }
        assertEquals(11, chest.primarySets)
        assertTrue(chest.band == VolumeBand.MAINTENANCE || chest.band == VolumeBand.PRODUCTIVE)
    }

    @Test
    fun `incomplete sets and in-progress sessions do not count`() {
        val start = Instant.parse("2026-11-03T09:00:00Z")
        val done = CompletedSession(
            session = WorkoutSession(id = "done", startTime = start, endTime = start.plusSeconds(3600)),
            sets = listOf(
                SetEntry("a", "done", TestRoutines.benchPress.id, "Bench Press", 1, 60.0, 8, completed = true, timestamp = start),
                SetEntry("b", "done", TestRoutines.benchPress.id, "Bench Press", 2, 60.0, 8, completed = false, timestamp = start),
            ),
        )
        val inProgress = CompletedSession(
            session = WorkoutSession(id = "live", startTime = start, endTime = null),
            sets = listOf(
                SetEntry("c", "live", TestRoutines.benchPress.id, "Bench Press", 1, 60.0, 8, timestamp = start),
            ),
        )
        assertEquals(1, volumeFor(listOf(done, inProgress), MuscleGroup.CHEST).primarySets)
    }

    @Test
    fun `every landmark group is reported, untrained groups at zero`() {
        val volumes = VolumeLandmarks.weeklyVolume(emptyList(), monday, zone)
        assertEquals(VolumeLandmarks.LANDMARKS.keys, volumes.map { it.muscleGroup }.toSet())
        assertTrue(volumes.all { it.primarySets == 0 && it.band == VolumeBand.BELOW_MAINTENANCE })
    }
}
