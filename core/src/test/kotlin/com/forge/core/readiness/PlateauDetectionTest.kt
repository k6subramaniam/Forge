package com.forge.core.readiness

import com.forge.core.training.ExercisePerformancePoint
import com.forge.core.model.SetEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class PlateauDetectionTest {

    private fun series(estimated1Rms: List<Double>): List<ExercisePerformancePoint> =
        estimated1Rms.mapIndexed { i, e1rm ->
            ExercisePerformancePoint(
                date = LocalDate.of(2026, 10, 1).plusDays(i * 3L),
                sessionId = "s$i",
                bestSet = SetEntry(
                    id = "set$i", sessionId = "s$i", exerciseId = "ex-bench",
                    exerciseName = "Bench Press", setNumber = 1,
                    weightKg = e1rm, reps = 1, timestamp = Instant.EPOCH,
                ),
                bestEstimated1RmKg = e1rm,
                totalSets = 4,
                totalVolumeKg = e1rm * 20,
            )
        }

    @Test
    fun `flat series is a plateau with zero change and zero noise`() {
        val result = PlateauDetection.assess("ex-bench", "Bench Press", series(List(6) { 100.0 }))
        assertEquals(PlateauStatus.PLATEAU, result.status)
        assertEquals(6, result.sessionsConsidered)
        assertEquals(0.0, result.slopeKgPerSession!!, 1e-9)
        assertEquals(0.0, result.fittedChangeKg!!, 1e-9)
        assertEquals(0.0, result.leastSignificantChangeKg!!, 1e-9)
    }

    @Test
    fun `steadily rising series is progressing`() {
        // +2 kg per session, perfectly linear: residuals 0, LSC 0, fitted change +10.
        val result = PlateauDetection.assess(
            "ex-bench", "Bench Press",
            series(listOf(100.0, 102.0, 104.0, 106.0, 108.0, 110.0)),
        )
        assertEquals(PlateauStatus.PROGRESSING, result.status)
        assertEquals(2.0, result.slopeKgPerSession!!, 1e-9)
        assertEquals(10.0, result.fittedChangeKg!!, 1e-9)
        assertEquals(10.0, result.peakGainKg!!, 1e-9)
    }

    @Test
    fun `noisy flat series is a plateau - gains inside the noise do not count`() {
        // Wobbles ±2 kg around 100 with no trend: residual SD ≈ 1.9, so the
        // LSC is ≈ 5.3 kg — far above the fitted change of ~0.
        val result = PlateauDetection.assess(
            "ex-bench", "Bench Press",
            series(listOf(100.0, 102.0, 98.0, 101.0, 99.0, 100.0)),
        )
        assertEquals(PlateauStatus.PLATEAU, result.status)
        assertTrue(result.leastSignificantChangeKg!! > 4.0, "LSC was ${result.leastSignificantChangeKg}")
        assertTrue(kotlin.math.abs(result.fittedChangeKg!!) < result.leastSignificantChangeKg!!)
    }

    @Test
    fun `noisy but genuinely rising series beats its LSC`() {
        // +3 kg per session under ±1 kg of noise: fitted change ≈ +15 against
        // an LSC of ≈ 2.8 × ~0.9 ≈ 2.5.
        val result = PlateauDetection.assess(
            "ex-bench", "Bench Press",
            series(listOf(100.0, 104.0, 105.0, 110.0, 111.0, 116.0)),
        )
        assertEquals(PlateauStatus.PROGRESSING, result.status)
    }

    @Test
    fun `too few sessions is not enough data, never a plateau`() {
        val result = PlateauDetection.assess(
            "ex-bench", "Bench Press",
            series(listOf(100.0, 100.0, 100.0)),
        )
        assertEquals(PlateauStatus.NOT_ENOUGH_DATA, result.status)
        assertEquals(3, result.sessionsConsidered)
        assertNull(result.slopeKgPerSession)
        assertNull(result.leastSignificantChangeKg)
    }

    @Test
    fun `only the most recent window is judged`() {
        // Nine sessions: early progress, then a flat last six — the verdict
        // must be PLATEAU, from the last six, not PROGRESSING from the rise.
        val result = PlateauDetection.assess(
            "ex-bench", "Bench Press",
            series(listOf(80.0, 90.0, 100.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0)),
        )
        assertEquals(6, result.sessionsConsidered)
        assertEquals(PlateauStatus.PLATEAU, result.status)
    }

    @Test
    fun `declining series is reported as a plateau verdict with a negative slope`() {
        val result = PlateauDetection.assess(
            "ex-bench", "Bench Press",
            series(listOf(110.0, 108.0, 106.0, 104.0, 102.0, 100.0)),
        )
        assertEquals(PlateauStatus.PLATEAU, result.status)
        assertEquals(-2.0, result.slopeKgPerSession!!, 1e-9)
    }
}
