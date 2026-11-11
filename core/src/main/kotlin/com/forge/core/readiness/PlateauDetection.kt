package com.forge.core.readiness

import com.forge.core.training.CompletedSession
import com.forge.core.training.ExercisePerformancePoint
import com.forge.core.training.HistoryReadModels
import java.time.ZoneId
import kotlin.math.sqrt

/** Where an exercise's performance trend stands. */
enum class PlateauStatus {
    /** The fitted improvement across the window exceeds the noise threshold. */
    PROGRESSING,

    /** Trained enough to judge, and the improvement does not exceed the noise. */
    PLATEAU,

    /** Fewer sessions than the window needs — no verdict is offered. */
    NOT_ENOUGH_DATA,
}

/**
 * One exercise's plateau verdict (blueprint 3.15: "statistical plateau
 * detection over rolling performance windows rather than two bad sessions").
 * The numbers ride along so the coach quotes them instead of paraphrasing a
 * black box ("bench has moved 0.8 kg in six sessions; it takes ~4 kg to beat
 * your session-to-session noise").
 */
data class PlateauAssessment(
    val exerciseId: String,
    val exerciseName: String,
    val status: PlateauStatus,
    /** Sessions in the judged window (the most recent [PlateauDetection.WINDOW_SESSIONS]). */
    val sessionsConsidered: Int,
    /** Least-squares slope of best-set estimated 1RM, kg per session; null when not judged. */
    val slopeKgPerSession: Double?,
    /** Fitted change across the window: slope × (sessions − 1), kg. */
    val fittedChangeKg: Double?,
    /** Best observed gain inside the window: peak best-set 1RM minus the window's first, kg. */
    val peakGainKg: Double?,
    /** Residual scatter of the series around its fitted line, kg. */
    val residualSdKg: Double?,
    /**
     * Least Significant Change for this series: a change must exceed this to
     * count as real (A20, Evidence Base v1: LSC = 1.96·√2·σ ≈ 2.77·σ, with σ
     * the series' own session-to-session scatter).
     */
    val leastSignificantChangeKg: Double?,
)

/**
 * Plateau detection over the slice-3 performance series (one point per
 * session the exercise was trained, best set by estimated 1RM).
 *
 * Method: take the most recent [WINDOW_SESSIONS] points. Fit a least-squares
 * line of best-set estimated 1RM against session order. The verdict turns on
 * whether the *fitted* change across the window exceeds the Least Significant
 * Change — LSC = 1.96·√2·σ with σ the residuals' SD — the gating the Athletic
 * Insights mining adopted as A20 (Evidence Base v1) so the coach "only
 * celebrates/corrects changes beyond LSC; kills noise-chasing". Using the
 * fitted (not raw first-to-last) change keeps one freak session at either end
 * from manufacturing or hiding a trend.
 *
 * Fewer than [WINDOW_SESSIONS] trained sessions is NOT_ENOUGH_DATA, never a
 * plateau: an exercise trained three times hasn't stalled, it has barely
 * started (blueprint 3.15: detection over a window, "rather than two bad
 * sessions"). The window length is a documented choice — six sessions is
 * roughly three weeks of a lift trained twice a week, the shortest span over
 * which "stalled" is a fair word in the coach's own example (3.8: "bench has
 * stalled 3 weeks at 80 kg").
 */
object PlateauDetection {

    /** Sessions of the exercise inside the judged window. */
    const val WINDOW_SESSIONS = 6

    /** 1.96·√2 — the 95% least-significant-change factor (A20, Evidence Base v1). */
    const val LSC_FACTOR = 2.771858582309347

    /**
     * Judge [series] (chronological, as [HistoryReadModels.performanceSeries]
     * returns it — one point per trained session).
     */
    fun assess(
        exerciseId: String,
        exerciseName: String,
        series: List<ExercisePerformancePoint>,
    ): PlateauAssessment {
        val window = series.takeLast(WINDOW_SESSIONS)
        if (window.size < WINDOW_SESSIONS) {
            return PlateauAssessment(
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                status = PlateauStatus.NOT_ENOUGH_DATA,
                sessionsConsidered = window.size,
                slopeKgPerSession = null,
                fittedChangeKg = null,
                peakGainKg = null,
                residualSdKg = null,
                leastSignificantChangeKg = null,
            )
        }

        val n = window.size
        val ys = window.map { it.bestEstimated1RmKg }
        val xMean = (n - 1) / 2.0
        val yMean = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (i - xMean) * (ys[i] - yMean)
            sxx += (i - xMean) * (i - xMean)
        }
        val slope = sxy / sxx
        val intercept = yMean - slope * xMean
        val residuals = ys.mapIndexed { i, y -> y - (intercept + slope * i) }
        // n − 2 degrees of freedom: the line's two parameters were estimated.
        val residualSd = sqrt(residuals.sumOf { it * it } / (n - 2))
        val lsc = LSC_FACTOR * residualSd
        val fittedChange = slope * (n - 1)
        val peakGain = ys.max() - ys.first()

        return PlateauAssessment(
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            status = if (fittedChange > lsc) PlateauStatus.PROGRESSING else PlateauStatus.PLATEAU,
            sessionsConsidered = n,
            slopeKgPerSession = slope,
            fittedChangeKg = fittedChange,
            peakGainKg = peakGain,
            residualSdKg = residualSd,
            leastSignificantChangeKg = lsc,
        )
    }

    /** Convenience: build the performance series from raw history, then judge it. */
    fun assessExercise(
        history: List<CompletedSession>,
        exerciseId: String,
        zone: ZoneId,
    ): PlateauAssessment {
        val series = HistoryReadModels.performanceSeries(history, exerciseId, zone)
        val name = history.asSequence()
            .flatMap { it.sets.asSequence() }
            .firstOrNull { it.exerciseId == exerciseId }
            ?.exerciseName ?: exerciseId
        return assess(exerciseId, name, series)
    }
}
