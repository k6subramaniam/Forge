package com.forge.core.training

import com.forge.core.testing.TestRoutines
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ProgressiveOverloadTest {

    private fun setsFor(exerciseId: String, vararg repsAndDone: Pair<Int, Boolean>): List<SetEntry> =
        repsAndDone.mapIndexed { index, (reps, done) ->
            SetEntry(
                id = "set-$exerciseId-$index",
                sessionId = "session-1",
                exerciseId = exerciseId,
                exerciseName = "",
                setNumber = index + 1,
                weightKg = 0.0,
                reps = reps,
                completed = done,
                timestamp = Instant.parse("2026-11-10T15:00:00Z"),
            )
        }

    private val benchTarget = RoutineExercise(
        exercise = TestRoutines.benchPress,
        targetSets = 4,
        repRange = 6..8,
        targetWeightKg = 60.0,
    )

    private val squatTarget = RoutineExercise(
        exercise = TestRoutines.squat,
        targetSets = 4,
        repRange = 5..6,
        targetWeightKg = 80.0,
    )

    @Test
    fun `upper body exercise earns the 2_5 kg increment when all sets top out`() {
        val sets = setsFor(
            TestRoutines.benchPress.id,
            8 to true, 8 to true, 8 to true, 9 to true, // 9 reps also counts (above range top)
        )
        val suggestion = ProgressiveOverload.suggest(TestRoutines.benchPress, benchTarget, sets)
        assertNotNull(suggestion)
        assertEquals(2.5, suggestion!!.incrementKg, 0.0)
        assertEquals(62.5, suggestion.suggestedWeightKg, 0.0)
    }

    @Test
    fun `lower body exercise earns the 5 kg increment`() {
        val sets = setsFor(TestRoutines.squat.id, 6 to true, 6 to true, 6 to true, 6 to true)
        val suggestion = ProgressiveOverload.suggest(TestRoutines.squat, squatTarget, sets)
        assertNotNull(suggestion)
        assertEquals(5.0, suggestion!!.incrementKg, 0.0)
        assertEquals(85.0, suggestion.suggestedWeightKg, 0.0)
    }

    @Test
    fun `no suggestion when one set falls short of the top of the range`() {
        val sets = setsFor(TestRoutines.benchPress.id, 8 to true, 8 to true, 7 to true, 8 to true)
        assertNull(ProgressiveOverload.suggest(TestRoutines.benchPress, benchTarget, sets))
    }

    @Test
    fun `no suggestion when fewer sets than prescribed were completed`() {
        val sets = setsFor(TestRoutines.benchPress.id, 8 to true, 8 to true, 8 to true)
        assertNull(ProgressiveOverload.suggest(TestRoutines.benchPress, benchTarget, sets))
    }

    @Test
    fun `incomplete sets and other exercises do not count`() {
        val sets = setsFor(TestRoutines.benchPress.id, 8 to true, 8 to true, 8 to true, 8 to false) +
            setsFor(TestRoutines.squat.id, 8 to true)
        assertNull(ProgressiveOverload.suggest(TestRoutines.benchPress, benchTarget, sets))
    }
}
