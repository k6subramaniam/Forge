package com.forge.core.nutrition

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp

/** One bodyweight log entry (blueprint 3.7: weight is a raw measure, logged as-is). */
data class WeighIn(
    val date: LocalDate,
    val weightKg: Double,
)

/** One weigh-in with its smoothed trend value (see [BodyweightTrend]). */
data class TrendPoint(
    val date: LocalDate,
    val weightKg: Double,
    val trendKg: Double,
)

/**
 * The analysed bodyweight trend over a set of weigh-ins.
 *
 * [weeklyRateKgPerWeek] is null when no rate can honestly be stated — fewer
 * than two weigh-ins, or all of them on one day. Callers must treat null as
 * "not enough data", never as zero (blueprint 3.15: report the estimate with
 * its quality, don't invent precision).
 */
data class WeightTrend(
    /** Weigh-ins in date order, each with its trend value. */
    val points: List<TrendPoint>,
    val weighInCount: Int,
    /** Days between the first and last weigh-in (0 when fewer than two). */
    val spanDays: Long,
    /** Trend weight at the most recent weigh-in, or null with no weigh-ins. */
    val latestTrendKg: Double?,
    /** Least-squares rate of the raw weigh-ins, kg per week; null when unknowable. */
    val weeklyRateKgPerWeek: Double?,
)

/**
 * Bodyweight trend analysis (blueprint 3.7/3.8: calorie decisions are made
 * from the trend, never from single weigh-ins).
 *
 * Raw scale weight swings by a kilogram or more day to day with water, gut
 * content and glycogen; the trend level is an exponentially weighted moving
 * average (EMA smoothing is the trend-noise handling adopted in the evidence
 * base from the Athletic Insights framework, v2) with a 10-day time
 * constant, time-weighted so irregular weigh-in spacing is handled exactly:
 * each step uses α = 1 − exp(−Δdays / 10).
 *
 * The weekly rate is a separate estimator: the least-squares slope of the
 * raw weigh-ins × 7. It is deliberately NOT read off the EMA — the EMA
 * starts exactly at the first weigh-in and its lag grows in over the first
 * ~two weeks, which would bias any trend-based slope over exactly the
 * 2–3 week windows blueprint 3.15 works with. Least squares over the raw
 * points is unbiased for a steady trend, and over weeks of weigh-ins a
 * single odd reading moves it very little.
 */
object BodyweightTrend {

    /** EMA time constant in days: the trend's effective memory is about ten days. */
    const val SMOOTHING_TIME_CONSTANT_DAYS = 10.0

    fun analyse(weighIns: List<WeighIn>): WeightTrend {
        val sorted = weighIns.sortedBy { it.date }
        if (sorted.isEmpty()) {
            return WeightTrend(
                points = emptyList(),
                weighInCount = 0,
                spanDays = 0,
                latestTrendKg = null,
                weeklyRateKgPerWeek = null,
            )
        }
        val points = ArrayList<TrendPoint>(sorted.size)
        var trend = sorted.first().weightKg
        var previousDate = sorted.first().date
        for (weighIn in sorted) {
            val gapDays = ChronoUnit.DAYS.between(previousDate, weighIn.date).toDouble()
            if (gapDays > 0.0) {
                val alpha = 1.0 - exp(-gapDays / SMOOTHING_TIME_CONSTANT_DAYS)
                trend += alpha * (weighIn.weightKg - trend)
            }
            points += TrendPoint(weighIn.date, weighIn.weightKg, trend)
            previousDate = weighIn.date
        }
        val spanDays = ChronoUnit.DAYS.between(sorted.first().date, sorted.last().date)
        return WeightTrend(
            points = points,
            weighInCount = sorted.size,
            spanDays = spanDays,
            latestTrendKg = points.last().trendKg,
            weeklyRateKgPerWeek = weeklyRate(points),
        )
    }

    /**
     * Least-squares slope of raw weight vs time, in kg/week (see the class
     * doc for why the raw points, not the EMA). Null unless at least two
     * weigh-ins fall on different dates (a zero time spread has no slope).
     */
    private fun weeklyRate(points: List<TrendPoint>): Double? {
        if (points.size < 2) return null
        val origin = points.first().date.toEpochDay().toDouble()
        val xs = points.map { it.date.toEpochDay().toDouble() - origin }
        if (xs.max() <= 0.0) return null
        val ys = points.map { it.weightKg }
        val meanX = xs.average()
        val meanY = ys.average()
        var numerator = 0.0
        var denominator = 0.0
        for (i in points.indices) {
            numerator += (xs[i] - meanX) * (ys[i] - meanY)
            denominator += (xs[i] - meanX) * (xs[i] - meanX)
        }
        if (denominator == 0.0) return null
        return numerator / denominator * 7.0
    }
}
