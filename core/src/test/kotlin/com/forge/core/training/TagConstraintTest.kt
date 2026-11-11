package com.forge.core.training

import com.forge.core.data.ExerciseLibrary
import com.forge.core.data.SampleData
import com.forge.core.model.Equipment
import com.forge.core.model.Exercise
import com.forge.core.model.ExerciseFlag
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.MovementPattern
import com.forge.core.model.MovementRestriction
import com.forge.core.model.MuscleGroup
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import com.forge.core.onboarding.ExerciseExclusion
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.onboarding.OnboardingGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tag-based constraint matching (blueprint 3.0, slice 2): the session engine
 * judges tagged exercises by movement pattern and loading traits, and only
 * falls back to slice 1's name matching for untagged exercises.
 */
class TagConstraintTest {

    private val start: Instant = Instant.parse("2026-11-10T15:00:00Z")
    private val library = ExerciseLibrary.all

    private fun constraintsFor(vararg areas: JointArea): FunctionalConstraints =
        OnboardingGate.evaluate(
            HealthProfile(jointLimitations = areas.toSet(), onboardingComplete = true),
        ).constraints

    private fun routineOf(vararg exercises: Exercise): Routine = Routine(
        id = "r-test",
        name = "Test",
        exercises = exercises.map {
            RoutineExercise(it, targetSets = 3, repRange = 6..8, targetWeightKg = 50.0)
        },
    )

    // -- The gate's structured form -------------------------------------------

    @Test
    fun `every gate exclusion carries a structured form`() {
        val constraints = OnboardingGate.evaluate(
            HealthProfile(jointLimitations = JointArea.entries.toSet(), onboardingComplete = true),
        ).constraints
        assertEquals(JointArea.entries.size, constraints.exerciseExclusions.size)
        for (exclusion in constraints.exerciseExclusions) {
            assertTrue(exclusion.restrictions.isNotEmpty(), "${exclusion.area} has no restrictions")
        }
        val lowerBack = constraints.exerciseExclusions.first { it.area == JointArea.LOWER_BACK }
        assertTrue(lowerBack.restrictions.any { it.description.contains("unsupported rowing") })
        val knee = constraints.exerciseExclusions.first { it.area == JointArea.KNEE }
        assertTrue(knee.restrictions.any { it.description.contains("deep knee flexion") })
    }

    // -- The barbell-row case (the one names could not decide) ------------------

    @Test
    fun `a lower back limitation excludes the barbell row through its unsupported trait`() {
        val state = SessionEngine.start(
            SampleData.pullA, sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.LOWER_BACK), library = library,
        )
        val rowSlot = state.exercises[0]
        assertEquals(SampleData.barbellRow, rowSlot.substitutedFor)
        assertNotNull(rowSlot.adjustmentReason)
        // The reason names the trait restriction, proving the tag path decided it —
        // slice 1's name matcher deliberately let this exercise through.
        assertTrue(rowSlot.adjustmentReason!!.contains("no unsupported rowing"))
        assertTrue(state.exercises.none { it.exercise.id == SampleData.barbellRow.id })
        // The rest of Pull A is untouched.
        assertEquals(SampleData.pullUp, state.exercises[1].exercise)
        assertEquals(SampleData.bicepCurl, state.exercises[2].exercise)
    }

    @Test
    fun `a chest-supported row is kept under the same lower back limitation`() {
        val routine = routineOf(ExerciseLibrary.chestSupportedRow, ExerciseLibrary.seatedCableRow)
        val state = SessionEngine.start(
            routine, sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.LOWER_BACK), library = library,
        )
        assertEquals(
            listOf("Chest-Supported Row", "Seated Cable Row"),
            state.exercises.map { it.exercise.name },
        )
        assertTrue(state.exercises.all { it.substitutedFor == null })
        assertTrue(state.droppedExercises.isEmpty())
    }

    @Test
    fun `with both rows programmed only the unsupported one is swapped`() {
        val routine = routineOf(ExerciseLibrary.barbellRow, ExerciseLibrary.chestSupportedRow)
        val constraints = FunctionalConstraints(
            exerciseExclusions = listOf(
                ExerciseExclusion(
                    area = JointArea.LOWER_BACK,
                    excludedMovements = listOf("Heavy unsupported row"),
                    substitutions = listOf("Chest-supported row"),
                    reason = "Test lower back limitation.",
                    restrictions = listOf(
                        MovementRestriction(
                            flags = setOf(ExerciseFlag.UNSUPPORTED_ROW),
                            description = "no unsupported rowing",
                        ),
                    ),
                ),
            ),
            heartRateCapPercentOfMax = null,
            intensityMayOnlyScaleDown = false,
            promptConstraints = emptyList(),
        )
        val state = SessionEngine.start(routine, sessionId = "s1", startTime = start, constraints = constraints, library = library)

        // The barbell row resolves to the real supported-row exercise...
        assertEquals("ex-chest-supported-row", state.exercises[0].exercise.id)
        assertEquals(SampleData.barbellRow, state.exercises[0].substitutedFor)
        // ...and the programmed supported row is exactly where it was.
        assertEquals("ex-chest-supported-row", state.exercises[1].exercise.id)
        assertNull(state.exercises[1].substitutedFor)
    }

    @Test
    fun `the barbell row is kept when the limitation does not restrict its traits`() {
        val state = SessionEngine.start(
            SampleData.pullA, sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.KNEE), library = library,
        )
        assertEquals(SampleData.barbellRow, state.exercises[0].exercise)
        assertNull(state.exercises[0].substitutedFor)
    }

    // -- Slice 1 cases re-expressed as tags -------------------------------------

    @Test
    fun `the shoulder case matches on the overhead trait and resolves a real landmine press`() {
        val state = SessionEngine.start(
            SampleData.pushA, sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.SHOULDER), library = library,
        )
        val overhead = state.exercises[1]
        assertEquals("ex-landmine-press", overhead.exercise.id)
        assertEquals(SampleData.overheadPress, overhead.substitutedFor)
        assertTrue(overhead.adjustmentReason!!.contains("no overhead pressing"))
    }

    @Test
    fun `the knee case matches on deep flexion and resolves a real box squat`() {
        val state = SessionEngine.start(
            SampleData.legsA, sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.KNEE), library = library,
        )
        val squatSlot = state.exercises[0]
        assertEquals("ex-box-squat", squatSlot.exercise.id)
        assertEquals(SampleData.squat, squatSlot.substitutedFor)
        assertTrue(squatSlot.adjustmentReason!!.contains("no deep knee flexion under load"))
    }

    @Test
    fun `a step-up survives a knee limitation even though it shares the lunge pattern`() {
        val routine = routineOf(ExerciseLibrary.walkingLunge, ExerciseLibrary.stepUp)
        val state = SessionEngine.start(
            routine, sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.KNEE), library = library,
        )
        // The lunge carries the deep-flexion trait and is swapped out...
        assertEquals(ExerciseLibrary.walkingLunge, state.exercises[0].substitutedFor)
        // ...while the step-up — a gate-named knee substitute — is kept as-is.
        assertEquals(ExerciseLibrary.stepUp, state.exercises[1].exercise)
        assertNull(state.exercises[1].substitutedFor)
    }

    @Test
    fun `a lower back limitation swaps the deadlift for the trap bar`() {
        val state = SessionEngine.start(
            SampleData.legsA, sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.LOWER_BACK), library = library,
        )
        val deadliftSlot = state.exercises[1]
        assertEquals("ex-trap-bar-deadlift", deadliftSlot.exercise.id)
        assertEquals(SampleData.deadlift, deadliftSlot.substitutedFor)
        assertTrue(deadliftSlot.adjustmentReason!!.contains("no heavy spinal loading"))
        // The squat carries no spinal-loading trait and stays.
        assertEquals(SampleData.squat, state.exercises[0].exercise)
    }

    @Test
    fun `a descriptive substitution phrase resolves to the exercise it names`() {
        // The hip gate's substitute is the phrase "Box squat above parallel".
        val state = SessionEngine.start(
            SampleData.legsA, sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.HIP), library = library,
        )
        val squatSlot = state.exercises[0]
        assertEquals("ex-box-squat", squatSlot.exercise.id)
        assertEquals(SampleData.squat, squatSlot.substitutedFor)
        assertTrue(squatSlot.adjustmentReason!!.contains("no deep hip flexion under load"))
    }

    // -- Restriction semantics ---------------------------------------------------

    @Test
    fun `a substitute carrying the restricted trait is passed over for the next one`() {
        val constraints = FunctionalConstraints(
            exerciseExclusions = listOf(
                ExerciseExclusion(
                    area = JointArea.LOWER_BACK,
                    excludedMovements = emptyList(),
                    substitutions = listOf("Good Morning", "Hip Thrust"),
                    reason = "Test lower back limitation.",
                    restrictions = listOf(
                        MovementRestriction(
                            flags = setOf(ExerciseFlag.HEAVY_SPINAL_LOADING),
                            description = "no heavy spinal loading",
                        ),
                    ),
                ),
            ),
            heartRateCapPercentOfMax = null,
            intensityMayOnlyScaleDown = false,
            promptConstraints = emptyList(),
        )
        val state = SessionEngine.start(
            routineOf(ExerciseLibrary.deadlift), sessionId = "s1", startTime = start,
            constraints = constraints, library = library,
        )
        // Good Morning resolves to a real exercise but carries the very trait
        // being restricted, so the engine moves on to the hip thrust.
        assertEquals("ex-hip-thrust", state.exercises.single().exercise.id)
        assertEquals(ExerciseLibrary.deadlift, state.exercises.single().substitutedFor)
    }

    @Test
    fun `restrictions can match on movement pattern alone`() {
        val constraints = FunctionalConstraints(
            exerciseExclusions = listOf(
                ExerciseExclusion(
                    area = JointArea.SHOULDER,
                    excludedMovements = emptyList(),
                    substitutions = listOf("Cable lateral raise"),
                    reason = "Test shoulder limitation.",
                    restrictions = listOf(
                        MovementRestriction(
                            patterns = setOf(MovementPattern.VERTICAL_PRESS),
                            description = "no vertical pressing",
                        ),
                    ),
                ),
            ),
            heartRateCapPercentOfMax = null,
            intensityMayOnlyScaleDown = false,
            promptConstraints = emptyList(),
        )
        val state = SessionEngine.start(
            SampleData.pushA, sessionId = "s1", startTime = start,
            constraints = constraints, library = library,
        )
        val overhead = state.exercises[1]
        assertEquals("ex-cable-lateral-raise", overhead.exercise.id)
        assertTrue(overhead.adjustmentReason!!.contains("no vertical pressing"))
        // The landmine press is a vertical press too — pattern matching catches
        // it even though it carries no risk flags.
        val withLandmine = SessionEngine.start(
            routineOf(ExerciseLibrary.landminePress), sessionId = "s2", startTime = start,
            constraints = constraints, library = library,
        )
        assertEquals(ExerciseLibrary.landminePress, withLandmine.exercises.single().substitutedFor)
    }

    @Test
    fun `freeform additions are matched on tags as well`() {
        var state = SessionEngine.startFreeform(sessionId = "s2", startTime = start)
        state = SessionEngine.addExercise(
            state,
            RoutineExercise(ExerciseLibrary.barbellRow, targetSets = 3, repRange = 6..8, targetWeightKg = 60.0),
            constraints = constraintsFor(JointArea.LOWER_BACK),
            library = library,
        )
        assertEquals(SampleData.barbellRow, state.exercises.single().substitutedFor)
        assertTrue(state.exercises.single().adjustmentReason!!.contains("no unsupported rowing"))
    }

    // -- Name-matching fallback (untagged exercises) ------------------------------

    @Test
    fun `an untagged custom exercise still matches exclusions by name`() {
        val customOverhead = Exercise(
            id = "custom-ohp", name = "Overhead Press",
            muscleGroup = MuscleGroup.SHOULDERS, equipment = Equipment.BARBELL,
            isCustom = true,
        )
        assertNull(customOverhead.tags)
        val state = SessionEngine.start(
            routineOf(customOverhead), sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.SHOULDER),
        )
        val slot = state.exercises.single()
        assertEquals(customOverhead, slot.substitutedFor)
        assertEquals("Landmine press", slot.exercise.name)
        // The quoted movement name in the reason shows the name path decided it.
        assertTrue(slot.adjustmentReason!!.contains("'Overhead press'"))
    }

    @Test
    fun `an untagged exercise whose name matches nothing is kept, and a free-text exclusion can still catch it`() {
        val zercher = Exercise(
            id = "custom-zercher", name = "Zercher Squat",
            muscleGroup = MuscleGroup.QUADS, equipment = Equipment.BARBELL,
            isCustom = true,
        )
        // The knee gate's names ("Deep squat", "Lunge", ...) do not cover this
        // name and there are no tags to match on — the documented fallback limit.
        val kept = SessionEngine.start(
            routineOf(zercher), sessionId = "s1", startTime = start,
            constraints = constraintsFor(JointArea.KNEE),
        )
        assertEquals(zercher, kept.exercises.single().exercise)
        assertNull(kept.exercises.single().substitutedFor)

        // A free-text exclusion written for it catches it by name, as before.
        val freeText = FunctionalConstraints(
            exerciseExclusions = listOf(
                ExerciseExclusion(
                    area = JointArea.KNEE,
                    excludedMovements = listOf("Zercher squat"),
                    substitutions = listOf("Leg press"),
                    reason = "User-declared limitation.",
                ),
            ),
            heartRateCapPercentOfMax = null,
            intensityMayOnlyScaleDown = false,
            promptConstraints = emptyList(),
        )
        val swapped = SessionEngine.start(
            routineOf(zercher), sessionId = "s2", startTime = start, constraints = freeText,
        )
        assertEquals(SampleData.legPress, swapped.exercises.single().exercise)
        assertEquals(zercher, swapped.exercises.single().substitutedFor)
    }
}
