package com.forge.core.coach

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.Exercise
import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.nutrition.AdjustmentReason
import com.forge.core.nutrition.DailyTotals
import com.forge.core.nutrition.DietLog
import com.forge.core.nutrition.MacroComparison
import com.forge.core.nutrition.MicroCoverage
import com.forge.core.nutrition.MicroGapReport
import com.forge.core.nutrition.MicroStatus
import com.forge.core.nutrition.MicronutrientCoverage
import com.forge.core.nutrition.NutrientComparison
import com.forge.core.nutrition.WeeklyAdjustment
import com.forge.core.nutrition.WeightTrend
import com.forge.core.readiness.LoadAdjustment
import com.forge.core.readiness.MuscleGroupVolume
import com.forge.core.readiness.PlateauAssessment
import com.forge.core.readiness.PlateauStatus
import com.forge.core.readiness.ReadinessAssessment
import com.forge.core.readiness.ReadinessStatus
import com.forge.core.readiness.VolumeBand
import com.forge.core.readiness.VolumeLandmarks
import com.forge.core.training.CompletedSession
import com.forge.core.training.FinishedSession
import com.forge.core.training.HistoryReadModels
import com.forge.core.training.NextSessionPrescription
import com.forge.core.training.WeightUnit
import java.time.LocalDate
import java.time.ZoneId

/**
 * The morning briefing's inputs (blueprint 3.8 daily rhythm): today's
 * readiness, the session the read-models prescribed, the deterministic load
 * adjustment, and any lifts the plateau detector has flagged.
 */
data class MorningBriefContext(
    val date: LocalDate,
    val readiness: ReadinessAssessment,
    /** Null on a rest day / when no routine is set — the brief still renders. */
    val prescription: NextSessionPrescription?,
    val adjustment: LoadAdjustment,
    /** Only [PlateauStatus.PLATEAU] verdicts — the flags, not the full report. */
    val plateauFlags: List<PlateauAssessment>,
) : CoachContext {

    override fun render(unit: WeightUnit): String = buildString {
        appendLine("MORNING BRIEF — $date")
        if (readiness.status == ReadinessStatus.SCORED) {
            appendLine("Readiness: ${readiness.band} (score ${readiness.score}) — ${readiness.headline}")
        } else {
            appendLine("Readiness: not scored — ${readiness.headline}")
        }
        val plan = prescription
        if (plan == null) {
            appendLine("Today's session: none planned.")
        } else {
            appendLine("Today's session: ${plan.routineName}")
            for (target in plan.exercises) {
                val p = target.prescription
                val replaced = target.substitutedFor?.let { " (replaces ${it.name})" } ?: ""
                appendLine(
                    "- ${target.exercise.name}$replaced: ${p.targetSets} sets × " +
                        "${p.repRange.first}–${p.repRange.last} @ ${formatWeight(target.suggestedWeightKg, unit)}" +
                        " — ${target.reason}",
                )
                target.adjustmentReason?.let { appendLine("  Substitution: $it") }
            }
            for (dropped in plan.droppedExercises) {
                appendLine("- Dropped by your limitations: ${dropped.exercise.name} — ${dropped.reason}")
            }
        }
        appendLine("Adjustment: ${adjustment.kind} — ${adjustment.reason}")
        if (plateauFlags.isNotEmpty()) {
            appendLine("Stalled lifts (plateau detection):")
            for (flag in plateauFlags) {
                appendLine(
                    "- ${flag.exerciseName}: fitted change " +
                        "${formatSignedWeight(flag.fittedChangeKg ?: 0.0, unit)} of estimated 1RM over the last " +
                        "${flag.sessionsConsidered} sessions; it takes about " +
                        "${formatWeight(flag.leastSignificantChangeKg ?: 0.0, unit)} to beat session-to-session noise.",
                )
            }
        }
    }.trimEnd()
}

/**
 * The post-workout debrief's inputs (blueprint 3.8): what got done versus the
 * summary the session engine produced at finish — sets, volume, bests, PRs,
 * substitutions, and any pain reported (which the coach must treat as a
 * back-off signal, never something to push through).
 */
data class PostWorkoutContext(
    val finished: FinishedSession,
) : CoachContext {

    override fun render(unit: WeightUnit): String {
        val summary = finished.summary
        return buildString {
            appendLine("POST-WORKOUT DEBRIEF")
            appendLine(
                "Session: ${finished.session.routineName ?: "Freeform"} — ${summary.totalSets} sets, " +
                    "${formatWeight(summary.totalVolumeKg, unit)} total volume, ${formatDuration(summary.durationSeconds)}.",
            )
            for (exercise in summary.exercises) {
                val best = exercise.bestSet
                if (best == null) {
                    appendLine("- ${exercise.exerciseName}: no completed sets.")
                } else {
                    appendLine(
                        "- ${exercise.exerciseName}: ${exercise.sets.size} sets, best " +
                            "${formatWeight(best.weightKg, unit)} × ${best.reps}, volume " +
                            "${formatWeight(exercise.totalVolumeKg, unit)}.",
                    )
                }
            }
            if (summary.newPrs.isEmpty()) {
                appendLine("New PRs: none.")
            } else {
                appendLine("New PRs:")
                for (pr in summary.newPrs) {
                    appendLine("- ${pr.exerciseName}: ${prLabel(pr.type)} ${formatWeight(pr.value, unit)} (${pr.date}).")
                }
            }
            for (sub in summary.substitutions) {
                appendLine("Substitution applied: ${sub.originalExerciseName} → ${sub.replacementExerciseName} — ${sub.reason}")
            }
            if (summary.painExercises.isNotEmpty()) {
                appendLine(
                    "Pain reported during: ${summary.painExercises.joinToString(", ")} — " +
                        "back-off rules apply; do not suggest pushing through it.",
                )
            }
        }.trimEnd()
    }

    private fun prLabel(type: PrType): String = when (type) {
        PrType.MAX_WEIGHT -> "max weight"
        PrType.ESTIMATED_1RM -> "estimated 1RM"
        PrType.MAX_VOLUME -> "best set volume"
    }
}

/**
 * The evening check-in's inputs (blueprint 3.8): today's diet totals against
 * targets, the micronutrients that are genuinely LOW on adequately-covered
 * data, any persistent multi-day gaps, and the weekly calorie adjustment the
 * deterministic rule currently suggests.
 */
data class EveningDietContext(
    val totals: DailyTotals,
    val comparison: MacroComparison,
    /** Coverage verdicts with [MicroStatus.LOW] only — UNKNOWNs are data gaps, not findings. */
    val lowMicros: List<MicroCoverage>,
    /** Multi-day persistent gaps only ([MicroGapReport.isPersistentGap]). */
    val persistentGaps: List<MicroGapReport>,
    /** The slice-4 weekly adjustment, when one has been computed. */
    val weeklyAdjustment: WeeklyAdjustment?,
) : CoachContext {

    override fun render(unit: WeightUnit): String = buildString {
        appendLine("EVENING DIET CHECK-IN — ${totals.date}")
        appendLine(nutrientLine("Calories", comparison.calories, "kcal"))
        appendLine(nutrientLine("Protein", comparison.protein, "g"))
        appendLine(nutrientLine("Fat", comparison.fat, "g"))
        appendLine(nutrientLine("Carbs", comparison.carbs, "g"))
        appendLine(nutrientLine("Fibre", comparison.fibre, "g"))
        if (lowMicros.isEmpty()) {
            appendLine("Micronutrients: no low readings on adequately-covered data today.")
        } else {
            appendLine("Low today (on adequately-covered data):")
            for (micro in lowMicros) {
                appendLine(
                    "- ${micro.nutrient.displayName}: ${formatNumber(micro.intake ?: 0.0)} ${micro.nutrient.unit} " +
                        "of ${formatNumber(micro.nutrient.referenceIntake)} ${micro.nutrient.unit} reference " +
                        "(${formatPercent(micro.percentOfReference ?: 0.0)}%).",
                )
            }
        }
        for (gap in persistentGaps) {
            appendLine(
                "Persistent gap: ${gap.nutrient.displayName} — low on ${gap.daysLow} of ${gap.daysCovered} " +
                    "well-covered days (average ${formatPercent(gap.averagePercentOfReference ?: 0.0)}% of reference).",
            )
        }
        weeklyAdjustment?.let { adj ->
            appendLine(
                "Weekly calorie adjustment: ${adj.direction.name.lowercase()} — suggested target " +
                    "${adj.suggestedTargetKcal} kcal (currently ${adj.currentTargetKcal} kcal, " +
                    "change ${if (adj.deltaKcal > 0) "+" else ""}${adj.deltaKcal}); ${adjustmentReason(adj.reason)}.",
            )
        }
    }.trimEnd()

    private fun nutrientLine(label: String, cmp: NutrientComparison, unitLabel: String): String {
        val consumed = cmp.consumed
        return if (consumed == null) {
            "$label: not reported today (target ${cmp.target} $unitLabel)."
        } else {
            val partial = if (cmp.isComplete) "" else " (partial — some entries did not report it)"
            val balance = when {
                (cmp.overBy ?: 0.0) > 0.0 -> "${formatNumber(cmp.overBy ?: 0.0)} $unitLabel over"
                else -> "${formatNumber(cmp.remaining ?: 0.0)} $unitLabel remaining"
            }
            "$label: ${formatNumber(consumed)} of ${cmp.target} $unitLabel — $balance$partial."
        }
    }

    private fun adjustmentReason(reason: AdjustmentReason): String = when (reason) {
        AdjustmentReason.ON_TRACK -> "weight trend is on track for the goal"
        AdjustmentReason.OFF_TARGET -> "weight trend is off the goal's target rate"
        AdjustmentReason.INSUFFICIENT_DATA -> "not enough trend data to judge, so the target holds"
        AdjustmentReason.AT_FLOOR -> "the BMR floor limits any decrease"
    }
}

/**
 * The weekly review's inputs (blueprint 3.8): one ISO week's aggregates —
 * sessions and volume, volume-landmark bands, PRs set inside the week, the
 * bodyweight trend rate, and diet-logging adherence. Empty inputs degrade to
 * explicit no-data markers in the render, never fabricated numbers.
 */
data class WeeklyReviewContext(
    val weekStart: LocalDate,
    val sessionCount: Int,
    val totalSets: Int,
    val totalVolumeKg: Double,
    val volumeByMuscleGroup: List<MuscleGroupVolume>,
    /** Records whose date falls inside the reviewed week. */
    val newPrs: List<PRRecord>,
    /** Bodyweight trend rate, kg/week; null when the trend can't be stated. */
    val trendRateKgPerWeek: Double?,
    val latestTrendKg: Double?,
    val dietDaysLogged: Int,
    val dietDaysInWindow: Int,
) : CoachContext {

    override fun render(unit: WeightUnit): String = buildString {
        appendLine("WEEKLY REVIEW — week of $weekStart")
        if (sessionCount == 0) {
            appendLine("Training: no sessions logged this week.")
            appendLine("Volume landmarks: no data — no sessions this week.")
        } else {
            appendLine(
                "Training: $sessionCount sessions, $totalSets sets, " +
                    "${formatWeight(totalVolumeKg, unit)} total volume.",
            )
            val trained = volumeByMuscleGroup.filter { it.primarySets + it.secondarySets > 0 }
            if (trained.isEmpty()) {
                appendLine("Volume landmarks: no attributable sets this week.")
            } else {
                appendLine("Volume landmarks (effective sets):")
                for (row in trained) {
                    appendLine(
                        "- ${row.muscleGroup.name.lowercase().replace('_', ' ')}: " +
                            "${formatNumber(row.effectiveSets)} — ${bandLabel(row.band)}.",
                    )
                }
            }
        }
        if (newPrs.isEmpty()) {
            appendLine("New PRs this week: none.")
        } else {
            appendLine("New PRs this week:")
            for (pr in newPrs) {
                appendLine("- ${pr.exerciseName}: ${formatWeight(pr.value, unit)} (${pr.date}).")
            }
        }
        val rate = trendRateKgPerWeek
        if (rate == null) {
            appendLine("Bodyweight trend: not enough weigh-ins to state a rate.")
        } else {
            val latest = latestTrendKg?.let { " (latest trend weight ${formatWeight(it, unit)})" } ?: ""
            appendLine("Bodyweight trend: ${formatSignedWeight(rate, unit)}/week$latest.")
        }
        if (dietDaysLogged == 0) {
            appendLine("Diet: nothing logged this week.")
        } else {
            appendLine("Diet logging: $dietDaysLogged of $dietDaysInWindow days logged.")
        }
    }.trimEnd()

    private fun bandLabel(band: VolumeBand): String = when (band) {
        VolumeBand.BELOW_MAINTENANCE -> "below maintenance"
        VolumeBand.MAINTENANCE -> "maintenance"
        VolumeBand.PRODUCTIVE -> "productive"
        VolumeBand.HIGH -> "high"
        VolumeBand.ABOVE_LANDMARKS -> "above landmarks"
    }
}

/**
 * Builders that assemble [CoachContext]s from the deterministic engines'
 * outputs. These are the only sanctioned way to build a coach summary: each
 * function takes engine outputs (never a health profile), so the privacy
 * contract on [CoachContext] holds by construction.
 */
object CoachContexts {

    /**
     * Morning brief (blueprint 3.8). [plateauAssessments] may carry every
     * exercise's verdict; only the PLATEAU ones become flags.
     */
    fun morning(
        readiness: ReadinessAssessment,
        prescription: NextSessionPrescription?,
        adjustment: LoadAdjustment,
        plateauAssessments: List<PlateauAssessment> = emptyList(),
    ): MorningBriefContext = MorningBriefContext(
        date = readiness.date,
        readiness = readiness,
        prescription = prescription,
        adjustment = adjustment,
        plateauFlags = plateauAssessments.filter { it.status == PlateauStatus.PLATEAU },
    )

    /** Post-workout debrief (blueprint 3.8) from the engine's finish output. */
    fun postWorkout(finished: FinishedSession): PostWorkoutContext = PostWorkoutContext(finished)

    /**
     * Evening check-in (blueprint 3.8). [gapReports] is the multi-day report
     * from [MicronutrientCoverage.gapReport]; only persistent gaps are kept.
     * Coverage defaults to assessing [totals]' own day.
     */
    fun evening(
        totals: DailyTotals,
        comparison: MacroComparison,
        coverage: List<MicroCoverage> = DietLog.microCoverage(totals),
        gapReports: List<MicroGapReport> = emptyList(),
        weeklyAdjustment: WeeklyAdjustment? = null,
    ): EveningDietContext = EveningDietContext(
        totals = totals,
        comparison = comparison,
        lowMicros = coverage.filter { it.status == MicroStatus.LOW },
        persistentGaps = gapReports.filter { it.isPersistentGap },
        weeklyAdjustment = weeklyAdjustment,
    )

    /**
     * Weekly review (blueprint 3.8) for the ISO week containing
     * [anyDateInWeek]. Aggregation runs through the slice-3 read-models and
     * the slice-5 landmarks, so the review can never disagree with the
     * History screen. [dietDays] are the week's per-day totals (as produced
     * by [DietLog.summarise]); adherence counts the days with any entries.
     */
    fun weekly(
        history: List<CompletedSession>,
        anyDateInWeek: LocalDate,
        zone: ZoneId,
        trend: WeightTrend? = null,
        dietDays: List<DailyTotals> = emptyList(),
        library: List<Exercise> = ExerciseLibrary.all,
    ): WeeklyReviewContext {
        val weekStart = VolumeLandmarks.weekStartOf(anyDateInWeek)
        val weekEnd = weekStart.plusDays(6)
        val entries = HistoryReadModels.recentSessions(history, zone)
            .filter { it.date in weekStart..weekEnd }
        val board = HistoryReadModels.prBoard(history, zone)
        val newPrs = board.flatMap { listOf(it.maxWeight, it.bestEstimated1Rm, it.bestSetVolume) }
            .filter { it.date in weekStart..weekEnd }
            .sortedWith(compareBy<PRRecord> { it.date }.thenBy { it.exerciseName })
        return WeeklyReviewContext(
            weekStart = weekStart,
            sessionCount = entries.size,
            totalSets = entries.sumOf { it.totalSets },
            totalVolumeKg = entries.sumOf { it.totalVolumeKg },
            volumeByMuscleGroup = VolumeLandmarks.weeklyVolume(history, anyDateInWeek, zone, library),
            newPrs = newPrs,
            trendRateKgPerWeek = trend?.weeklyRateKgPerWeek,
            latestTrendKg = trend?.latestTrendKg,
            dietDaysLogged = dietDays.count { it.entryCount > 0 }.coerceAtMost(DAYS_IN_WEEK),
            dietDaysInWindow = DAYS_IN_WEEK,
        )
    }

    private const val DAYS_IN_WEEK = 7
}
