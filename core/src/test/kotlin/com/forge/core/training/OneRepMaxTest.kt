package com.forge.core.training

import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.model.SetEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate

class OneRepMaxTest {

    @Test
    fun `a single rep is the weight itself`() {
        assertEquals(140.0, OneRepMax.estimateEpley(140.0, 1), 0.0)
    }

    @Test
    fun `epley formula for 100 kg x 10`() {
        // 100 * (1 + 10/30) = 133.33...
        assertEquals(133.333, OneRepMax.estimateEpley(100.0, 10), 0.01)
    }

    @Test
    fun `epley formula for 60 kg x 8`() {
        // 60 * (1 + 8/30) = 76.0
        assertEquals(76.0, OneRepMax.estimateEpley(60.0, 8), 0.001)
    }

    @Test
    fun `rejects zero reps and negative weight`() {
        assertThrows<IllegalArgumentException> { OneRepMax.estimateEpley(100.0, 0) }
        assertThrows<IllegalArgumentException> { OneRepMax.estimateEpley(-5.0, 5) }
    }
}

class PrDetectorTest {

    private val date: LocalDate = LocalDate.of(2026, 11, 10)

    private fun set(number: Int, weight: Double, reps: Int, completed: Boolean = true) = SetEntry(
        id = "set-$number-$weight-$reps",
        sessionId = "session-1",
        exerciseId = "ex-bench-press",
        exerciseName = "Bench Press",
        setNumber = number,
        weightKg = weight,
        reps = reps,
        completed = completed,
        timestamp = Instant.parse("2026-11-10T15:00:00Z"),
    )

    private fun existing(type: PrType, value: Double) = PRRecord(
        exerciseId = "ex-bench-press",
        exerciseName = "Bench Press",
        type = type,
        value = value,
        date = date.minusWeeks(1),
    )

    @Test
    fun `first ever session records all three PR types`() {
        val prs = PrDetector.detectNewPrs(listOf(set(1, 60.0, 8)), emptyList(), date)
        assertEquals(3, prs.size)
        assertEquals(setOf(PrType.MAX_WEIGHT, PrType.MAX_VOLUME, PrType.ESTIMATED_1RM), prs.map { it.type }.toSet())
        val oneRm = prs.first { it.type == PrType.ESTIMATED_1RM }
        assertEquals(76.0, oneRm.value, 0.001)
    }

    @Test
    fun `no PR when the set is below every existing best`() {
        val existing = listOf(
            existing(PrType.MAX_WEIGHT, 80.0),
            existing(PrType.MAX_VOLUME, 700.0),
            existing(PrType.ESTIMATED_1RM, 100.0),
        )
        val prs = PrDetector.detectNewPrs(listOf(set(1, 60.0, 8)), existing, date)
        assertTrue(prs.isEmpty())
    }

    @Test
    fun `only the beaten record types are reported`() {
        val existing = listOf(
            existing(PrType.MAX_WEIGHT, 80.0), // 60 kg does not beat this
            existing(PrType.MAX_VOLUME, 400.0), // 60x8 = 480 beats this
            existing(PrType.ESTIMATED_1RM, 100.0), // 76 does not beat this
        )
        val prs = PrDetector.detectNewPrs(listOf(set(1, 60.0, 8)), existing, date)
        assertEquals(listOf(PrType.MAX_VOLUME), prs.map { it.type })
        assertEquals(480.0, prs.single().value, 0.0)
    }

    @Test
    fun `progressively heavier sets in one session each register`() {
        val sets = listOf(set(1, 70.0, 5), set(2, 75.0, 5))
        val prs = PrDetector.detectNewPrs(sets, emptyList(), date)
        val weightPrs = prs.filter { it.type == PrType.MAX_WEIGHT }
        assertEquals(2, weightPrs.size)
        assertEquals(70.0, weightPrs[0].value, 0.0)
        assertEquals(75.0, weightPrs[1].value, 0.0)
    }

    @Test
    fun `incomplete sets never produce PRs`() {
        val prs = PrDetector.detectNewPrs(listOf(set(1, 200.0, 1, completed = false)), emptyList(), date)
        assertTrue(prs.isEmpty())
    }

    @Test
    fun `empty input produces no PRs`() {
        assertTrue(PrDetector.detectNewPrs(emptyList(), emptyList(), date).isEmpty())
    }

    @Test
    fun `PRs belonging to other exercises are ignored`() {
        val squatPr = PRRecord("ex-squat", "Squat", PrType.MAX_WEIGHT, 500.0, date.minusWeeks(1))
        val prs = PrDetector.detectNewPrs(listOf(set(1, 60.0, 8)), listOf(squatPr), date)
        assertEquals(3, prs.size) // treated as first-time bests for bench
    }
}
