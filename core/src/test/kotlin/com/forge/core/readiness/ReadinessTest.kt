package com.forge.core.readiness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.sqrt

class ReadinessTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 10)

    private fun obs(
        date: LocalDate,
        hrv: Double? = null,
        rhr: Int? = null,
        sleepMin: Int? = null,
        quality: Double? = null,
    ) = DailyRecoveryObservation(date, hrv, rhr, sleepMin, quality)

    /** [days] of identical baseline ending the day before [today]. */
    private fun flatBaseline(
        days: Int,
        hrv: Double? = 50.0,
        rhr: Int? = 60,
        sleepMin: Int? = 480,
        quality: Double? = 0.8,
    ): List<DailyRecoveryObservation> =
        (1..days).map { obs(today.minusDays(it.toLong()), hrv, rhr, sleepMin, quality) }

    @Test
    fun `z-score math on a hand-computed HRV baseline`() {
        // Baseline HRV: 40, 45, 50, 55, 60 repeated to 20 observations.
        // mean = 50; squared deviations per cycle = 100+25+0+25+100 = 250;
        // total = 4 × 250 = 1000; sample variance = 1000 / 19; SD = √(1000/19).
        val values = listOf(40.0, 45.0, 50.0, 55.0, 60.0)
        val baseline = (0 until 20).map { obs(today.minusDays((it + 1).toLong()), hrv = values[it % 5]) }
        val sd = sqrt(1000.0 / 19.0)
        // Today 60 → z = (60 − 50) / SD; HRV is the only signal, so weight renormalises to 1.
        val result = Readiness.assess(baseline + obs(today, hrv = 60.0), today)
        assertEquals(ReadinessStatus.SCORED, result.status)
        val signal = result.signals.single()
        assertEquals(RecoverySignal.HRV, signal.signal)
        assertEquals(50.0, signal.baselineMean, 1e-9)
        assertEquals(sd, signal.baselineSd, 1e-9)
        assertEquals(10.0 / sd, signal.favorabilityZ, 1e-9)
        assertEquals(1.0, signal.weight, 1e-9)
        assertEquals(10.0 / sd, result.compositeZ!!, 1e-9)
        assertEquals(ReadinessBand.PRIMED, result.band)
        assertEquals(20, signal.baselineCount)
    }

    @Test
    fun `resting heart rate sign flips - higher is worse`() {
        // Same 5-value cycle as the HRV test: mean 60, SD = √(1000/19) ≈ 7.25.
        // Today 70 → raw z = +10/SD, but for resting HR *up* is bad, so the
        // favourability z must be exactly −10/SD and the band STRAINED.
        val values = listOf(50, 55, 60, 65, 70)
        val baseline = (0 until 20).map { obs(today.minusDays((it + 1).toLong()), rhr = values[it % 5]) }
        val result = Readiness.assess(baseline + obs(today, rhr = 70), today)
        val signal = result.signals.single()
        assertEquals(-10.0 / sqrt(1000.0 / 19.0), signal.favorabilityZ, 1e-9)
        assertEquals(ReadinessBand.STRAINED, result.band)
    }

    @Test
    fun `typical morning scores NORMAL`() {
        val result = Readiness.assess(
            flatBaseline(28) + obs(today, hrv = 50.0, rhr = 60, sleepMin = 480, quality = 0.8),
            today,
        )
        assertEquals(ReadinessStatus.SCORED, result.status)
        assertEquals(ReadinessBand.NORMAL, result.band)
        assertEquals(0.0, result.compositeZ!!, 1e-9)
        assertEquals(50, result.score)
        assertTrue(result.missingSignals.isEmpty())
        assertEquals(28, result.baselineDaysUsed)
    }

    @Test
    fun `suppressed HRV with elevated RHR scores DEPLETED`() {
        // Vary the baseline slightly so SDs are real, then tank both signals.
        val baseline = (1..28).map {
            obs(today.minusDays(it.toLong()), hrv = 50.0 + (it % 3), rhr = 60 + (it % 2), sleepMin = 480, quality = 0.8)
        }
        val result = Readiness.assess(
            baseline + obs(today, hrv = 30.0, rhr = 75, sleepMin = 300, quality = 0.2),
            today,
        )
        assertEquals(ReadinessBand.DEPLETED, result.band)
        // HRV is the biggest contributor and ranks first.
        assertEquals(RecoverySignal.HRV, result.signals.first().signal)
    }

    @Test
    fun `one terrible night alone cannot crater an otherwise fine morning`() {
        // HRV and RHR exactly at baseline; sleep duration and quality are as
        // bad as they can be (both z-scores hit the −2 clamp).
        val baseline = (1..28).map {
            obs(
                today.minusDays(it.toLong()),
                hrv = 50.0 + (it % 3) - 1,
                rhr = 60,
                sleepMin = 480 + ((it % 5) - 2) * 30,
                quality = 0.8 + ((it % 3) - 1) * 0.05,
            )
        }
        val result = Readiness.assess(
            baseline + obs(today, hrv = 50.0, rhr = 60, sleepMin = 240, quality = 0.2),
            today,
        )
        // Sleep carries 0.30 of the weight, so the worst possible night drags
        // the composite to exactly −2 × 0.30 = −0.6: STRAINED, never DEPLETED.
        // The morning HRV — which already reflects the night — is unmoved.
        assertEquals(-0.6, result.compositeZ!!, 1e-9)
        assertEquals(ReadinessBand.STRAINED, result.band)
    }

    @Test
    fun `insufficient baseline is an explicit state, not a score`() {
        val result = Readiness.assess(
            flatBaseline(10) + obs(today, hrv = 50.0, rhr = 60, sleepMin = 480, quality = 0.8),
            today,
        )
        assertEquals(ReadinessStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.band)
        assertNull(result.score)
        assertNull(result.compositeZ)
        assertEquals(RecoverySignal.entries.toSet(), result.missingSignals.toSet())
    }

    @Test
    fun `no observation today is insufficient data`() {
        val result = Readiness.assess(flatBaseline(28), today)
        assertEquals(ReadinessStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.band)
        assertEquals(28, result.baselineDaysUsed)
    }

    @Test
    fun `a signal with too little baseline is excluded while others score`() {
        // 28 days of HRV; sleep appears only in the last 5 baseline days.
        val baseline = (1..28).map {
            obs(
                today.minusDays(it.toLong()),
                hrv = 50.0 + (it % 3) - 1,
                sleepMin = if (it <= 5) 480 else null,
            )
        }
        val result = Readiness.assess(baseline + obs(today, hrv = 50.0, sleepMin = 480), today)
        assertEquals(ReadinessStatus.SCORED, result.status)
        assertEquals(listOf(RecoverySignal.HRV), result.signals.map { it.signal })
        assertTrue(RecoverySignal.SLEEP_DURATION in result.missingSignals)
    }

    @Test
    fun `baseline window excludes older observations`() {
        // 14 fresh HRV days inside the window plus 14 stale days 60 days ago
        // must not combine into a scorable baseline.
        val fresh = (1..14).map { obs(today.minusDays(it.toLong()), hrv = 50.0) }
        val stale = (60..73).map { obs(today.minusDays(it.toLong()), hrv = 90.0) }
        val result = Readiness.assess(fresh + stale + obs(today, hrv = 50.0), today)
        val signal = result.signals.single()
        assertEquals(14, signal.baselineCount)
        assertEquals(50.0, signal.baselineMean, 1e-9)
    }

    @Test
    fun `repeated readings of one day count once toward the baseline`() {
        // Fourteen copies of yesterday's reading are one day of baseline, not fourteen.
        val copies = (1..14).map { obs(today.minusDays(1), hrv = 50.0, rhr = 60) }
        val result = Readiness.assess(copies + obs(today, hrv = 50.0, rhr = 60), today)
        assertEquals(ReadinessStatus.INSUFFICIENT_DATA, result.status)
        assertEquals(1, result.baselineDaysUsed)

        // With a real baseline, a day's later reading replaces its earlier one.
        val days = flatBaseline(20, hrv = 50.0, rhr = null, sleepMin = null, quality = null)
        val corrected = days + obs(today.minusDays(1), hrv = 50.0) + obs(today, hrv = 50.0)
        val withEarlyOutlier = listOf(obs(today.minusDays(1), hrv = 500.0)) + corrected
        assertEquals(
            Readiness.assess(corrected, today).signals.single().baselineMean,
            Readiness.assess(withEarlyOutlier, today).signals.single().baselineMean,
            1e-9,
        )
        assertEquals(20, Readiness.assess(withEarlyOutlier, today).baselineDaysUsed)
    }

    @Test
    fun `the headline says which side of baseline the reading is on, not whether that's good`() {
        // Resting HR well above baseline drives the score down; the text must still say "above".
        val values = listOf(50, 55, 60, 65, 70)
        val baseline = (0 until 20).map { obs(today.minusDays((it + 1).toLong()), rhr = values[it % 5]) }
        val high = Readiness.assess(baseline + obs(today, rhr = 75), today)
        assertTrue(high.headline.contains("resting heart rate") && high.headline.endsWith("above yours."), high.headline)
        val low = Readiness.assess(baseline + obs(today, rhr = 48), today)
        assertTrue(low.headline.endsWith("below yours."), low.headline)
    }
}
