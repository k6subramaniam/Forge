package com.forge.core.sync

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.training.DroppedExercise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate

class ForgeWireTest {

    private val day: LocalDate = LocalDate.of(2026, 11, 16)
    // Sub-millisecond precision must survive: set ids and ordering depend on exact instants.
    private val start: Instant = Instant.parse("2026-11-16T07:00:00.123456789Z")

    private val bench = ExerciseLibrary.benchPress.copy(tags = null)
    private val set = SetEntry(
        id = "s1-set-1", sessionId = "s1", exerciseId = bench.id, exerciseName = bench.name, setNumber = 1,
        weightKg = 62.5, reps = 10, completed = true, timestamp = start.plusSeconds(300),
        rpe = 8.5, rir = 2, painReported = true,
    )

    private val plans = WatchPlans(
        generatedAt = start,
        firstDay = day,
        dayCount = 7,
        days = listOf(
            WorkoutPlan(
                date = day,
                routineId = "routine-push",
                routineName = "Push",
                exercises = listOf(
                    PlannedExercise(
                        RoutineExercise(bench, 3, 8..12, 62.5, restSeconds = 150),
                        weightReason = "All sets hit 12 — add 2.5 kg.",
                        lastTopSet = set,
                        substitutedFor = ExerciseLibrary.overheadPress.copy(tags = null),
                        adjustmentReason = "Shoulder: avoid overhead pressing",
                    ),
                ),
                droppedExercises = listOf(DroppedExercise(ExerciseLibrary.uprightRow.copy(tags = null), "Shoulder")),
            ),
        ),
        menu = listOf(MenuExercise(bench, set), MenuExercise(ExerciseLibrary.squat.copy(tags = null), null)),
        existingPrs = listOf(PRRecord(bench.id, bench.name, PrType.MAX_WEIGHT, 60.0, day.minusDays(7))),
        blockedReason = null,
    )

    @Test
    fun `plans round-trip exactly`() {
        assertEquals(plans, ForgeWire.decodePlans(ForgeWire.encodePlans(plans)))
    }

    @Test
    fun `blocked plans round-trip with their reason`() {
        val blocked = plans.copy(days = emptyList(), menu = emptyList(), existingPrs = emptyList(), blockedReason = "Clearance")
        assertEquals(blocked, ForgeWire.decodePlans(ForgeWire.encodePlans(blocked)))
    }

    @Test
    fun `a finished session round-trips with every field`() {
        val session = WorkoutSession(
            id = "s1", routineId = "routine-push", routineName = "Push", startTime = start,
            endTime = start.plusSeconds(3_600), notes = "Felt strong", avgHeartRateBpm = 128,
            maxHeartRateBpm = 171, heartRateRecoveryBpm = 31,
        )
        val decoded = ForgeWire.decodeSession(ForgeWire.encodeSession(session, listOf(set)))
        assertEquals(session, decoded.session)
        assertEquals(listOf(set), decoded.sets)
    }

    @Test
    fun `fields a newer app added are ignored`() {
        val json = ForgeWire.encodePlans(plans).toString(Charsets.UTF_8)
            .replaceFirst("{", "{\"addedLater\":{\"x\":1},")
        assertEquals(plans, ForgeWire.decodePlans(json.toByteArray()))
    }

    @Test
    fun `a payload from a newer format version is refused, not misread`() {
        val json = ForgeWire.encodePlans(plans).toString(Charsets.UTF_8)
            .replaceFirst("\"v\":${ForgeWire.VERSION}", "\"v\":${ForgeWire.VERSION + 1}")
        val error = assertThrows<WireFormatException> { ForgeWire.decodePlans(json.toByteArray()) }
        assertTrue(error.fromNewerVersion)
    }

    @Test
    fun `garbage and truncated payloads raise one exception type`() {
        val encoded = ForgeWire.encodeSession(WorkoutSession("s1", startTime = start), emptyList())
        for (bad in listOf("not json".toByteArray(), encoded.copyOf(encoded.size / 2), ByteArray(0))) {
            assertFalse(assertThrows<WireFormatException> { ForgeWire.decodeSession(bad) }.fromNewerVersion)
        }
    }

    @Test
    fun `session items live under one prefix the phone can sweep`() {
        assertEquals("/forge/session/s1", ForgeWire.sessionPath("s1"))
        assertTrue(ForgeWire.sessionPath("s1").startsWith(ForgeWire.SESSION_PATH_PREFIX))
    }
}
