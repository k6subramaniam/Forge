package com.forge.core.data

import com.forge.core.model.ExerciseFlag
import com.forge.core.model.MovementPattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExerciseLibraryTest {

    @Test
    fun `the library is a solid starter set with unique ids and names`() {
        assertTrue(ExerciseLibrary.all.size >= 30, "expected at least 30 exercises, got ${ExerciseLibrary.all.size}")
        assertEquals(ExerciseLibrary.all.size, ExerciseLibrary.all.map { it.id }.toSet().size)
        assertEquals(ExerciseLibrary.all.size, ExerciseLibrary.all.map { it.name }.toSet().size)
    }

    @Test
    fun `every library exercise is fully tagged`() {
        for (exercise in ExerciseLibrary.all) {
            val tags = exercise.tags
            assertNotNull(tags, "${exercise.name} has no tags")
            assertTrue(tags!!.jointsLoaded.isNotEmpty(), "${exercise.name} loads no joints")
        }
    }

    @Test
    fun `every movement pattern is represented`() {
        val covered = ExerciseLibrary.all.mapNotNull { it.tags?.pattern }.toSet()
        assertEquals(MovementPattern.entries.toSet(), covered)
    }

    @Test
    fun `the starter ten keep their ids and are the library instances`() {
        assertEquals(10, SampleData.exercises.size)
        for (exercise in SampleData.exercises) {
            assertSame(exercise, ExerciseLibrary.byId(exercise.id))
        }
        // The exact scaffold ids, unchanged.
        assertEquals(
            setOf(
                "ex-bench-press", "ex-overhead-press", "ex-tricep-pushdown",
                "ex-barbell-row", "ex-pull-up", "ex-bicep-curl",
                "ex-squat", "ex-deadlift", "ex-leg-press", "ex-calf-raise",
            ),
            SampleData.exercises.map { it.id }.toSet(),
        )
    }

    @Test
    fun `the constraint substitutes are first-class tagged exercises`() {
        for (id in listOf("ex-landmine-press", "ex-box-squat", "ex-leg-press", "ex-chest-supported-row", "ex-trap-bar-deadlift")) {
            val exercise = ExerciseLibrary.byId(id)
            assertNotNull(exercise, "$id missing from the library")
            assertNotNull(exercise!!.tags, "$id is untagged")
        }
    }

    @Test
    fun `supported and unsupported rows carry different traits`() {
        // The distinction name matching could never make (blueprint 3.0).
        assertTrue(ExerciseLibrary.barbellRow.tags!!.flags.contains(ExerciseFlag.UNSUPPORTED_ROW))
        assertFalse(ExerciseLibrary.chestSupportedRow.tags!!.flags.contains(ExerciseFlag.UNSUPPORTED_ROW))
        assertEquals(
            ExerciseLibrary.barbellRow.tags!!.pattern,
            ExerciseLibrary.chestSupportedRow.tags!!.pattern,
        )
    }
}
