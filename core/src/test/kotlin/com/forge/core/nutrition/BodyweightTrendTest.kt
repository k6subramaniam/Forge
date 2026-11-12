package com.forge.core.nutrition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.abs

class BodyweightTrendTest {

    private val start: LocalDate = LocalDate.of(2026, 1, 1)

    /** Daily weigh-ins from [firstKg], changing by [stepPerDay] each day. */
    private fun dailySeries(firstKg: Double, stepPerDay: Double, days: Int): List<WeighIn> =
        (0 until days).map { WeighIn(start.plusDays(it.toLong()), firstKg + stepPerDay * it) }

    @Test
    fun `steady loss gives the exact weekly rate`() {
        // 90.0 kg falling exactly 0.1 kg/day for 15 days: least squares over the
        // raw points recovers −0.1/day exactly → −0.7 kg/week.
        val trend = BodyweightTrend.analyse(dailySeries(90.0, -0.1, 15))
        assertEquals(15, trend.weighInCount)
        assertEquals(14L, trend.spanDays)
        assertEquals(-0.7, trend.weeklyRateKgPerWeek!!, 0.01)
    }

    @Test
    fun `trend level smooths and lags the raw weight`() {
        // Same steady loss: the EMA (10-day constant) trails the scale — the
        // last raw weight is 88.6 but the trend level is still above it,
        // which is the smoothing doing its job on the displayed level.
        val trend = BodyweightTrend.analyse(dailySeries(90.0, -0.1, 15))
        val latest = trend.latestTrendKg!!
        assertTrue(latest > 88.6, "trend $latest should lag above the raw 88.6")
        assertTrue(latest < 89.6, "trend $latest should be well below the 90.0 start")
        // Trend points carry both raw and smoothed values, in date order.
        assertEquals(90.0, trend.points.first().weightKg, 0.001)
        assertEquals(90.0, trend.points.first().trendKg, 0.001)
        assertEquals(88.6, trend.points.last().weightKg, 0.001)
    }

    @Test
    fun `noisy stable weight gives a near zero rate`() {
        // Alternating 80.0 / 80.6 daily for 15 days (symmetric around day 7):
        // the least-squares numerator cancels exactly → slope 0.
        val weighIns = (0 until 15).map {
            WeighIn(start.plusDays(it.toLong()), if (it % 2 == 0) 80.0 else 80.6)
        }
        val trend = BodyweightTrend.analyse(weighIns)
        assertTrue(abs(trend.weeklyRateKgPerWeek!!) < 0.05)
        // And the trend level sits between the two alternating values.
        val latest = trend.latestTrendKg!!
        assertTrue(latest in 80.1..80.5, "trend level $latest should sit inside the noise band")
    }

    @Test
    fun `irregular spacing still recovers the rate`() {
        // Weekly weigh-ins only: day 0 → 90.0, day 7 → 89.3, day 14 → 88.6.
        // Slope: mean y = 89.3; numerator (−7)(0.7) + 0 + (7)(−0.7) = −9.8 over
        // denominator 98 → −0.1/day → −0.7/week, spacing handled exactly.
        val weighIns = listOf(
            WeighIn(start, 90.0),
            WeighIn(start.plusDays(7), 89.3),
            WeighIn(start.plusDays(14), 88.6),
        )
        val trend = BodyweightTrend.analyse(weighIns)
        assertEquals(-0.7, trend.weeklyRateKgPerWeek!!, 0.01)
        assertEquals(14L, trend.spanDays)
        // The time-weighted EMA crosses the 7-day gaps smoothly (α = 1 − e^−0.7 ≈ 0.503):
        // trend after gap 1 = 90 + 0.503×(−0.7) ≈ 89.65; after gap 2 ≈ 89.12.
        assertTrue(trend.latestTrendKg!! in 89.0..89.3)
    }

    @Test
    fun `single weigh-in has a level but no rate`() {
        val trend = BodyweightTrend.analyse(listOf(WeighIn(start, 82.5)))
        assertEquals(82.5, trend.latestTrendKg!!, 0.001)
        assertNull(trend.weeklyRateKgPerWeek)
        assertEquals(0L, trend.spanDays)
    }

    @Test
    fun `two weigh-ins on one day have no rate`() {
        val trend = BodyweightTrend.analyse(listOf(WeighIn(start, 82.5), WeighIn(start, 82.9)))
        assertNull(trend.weeklyRateKgPerWeek)
        assertEquals(0L, trend.spanDays)
    }

    @Test
    fun `empty history is honestly empty`() {
        val trend = BodyweightTrend.analyse(emptyList())
        assertEquals(0, trend.weighInCount)
        assertTrue(trend.points.isEmpty())
        assertNull(trend.latestTrendKg)
        assertNull(trend.weeklyRateKgPerWeek)
    }

    @Test
    fun `unsorted input is analysed in date order`() {
        val weighIns = dailySeries(90.0, -0.1, 15).reversed()
        val trend = BodyweightTrend.analyse(weighIns)
        assertEquals(-0.7, trend.weeklyRateKgPerWeek!!, 0.01)
        assertEquals(start, trend.points.first().date)
    }
}
