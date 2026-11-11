package com.forge.core.coach

import com.forge.core.training.WeightConverter
import com.forge.core.training.WeightUnit
import java.util.Locale

/**
 * A typed, compact summary of deterministic `:core` outputs, ready to hand to
 * the AI Coach (blueprint 3.8: the coach "works from structured summaries of
 * his own data — totals, trends, PRs, adherence — not raw data dumps").
 *
 * Privacy contract (blueprint 3.0, propagation rule 3): a context is built
 * ONLY from derived numbers, enums, and plain functional statements produced
 * by the deterministic engines (readiness, prescriptions, summaries, diet
 * aggregates). Raw health-profile fields, diagnoses, and the user's name can
 * never appear here, because no builder in this package accepts them — the
 * coach package does not import the health profile at all.
 */
interface CoachContext {

    /**
     * The plain-text block that goes into a coach prompt, with weights
     * rendered in [unit] (the user's display preference; :core stores kg).
     */
    fun render(unit: WeightUnit): String
}

/** A stored kg weight rendered in the user's unit: "132.3 lb" / "60 kg". */
internal fun formatWeight(weightKg: Double, unit: WeightUnit): String {
    val converted = WeightConverter.convert(weightKg, WeightUnit.KG, unit)
    return "${formatNumber(converted)} ${if (unit == WeightUnit.LB) "lb" else "kg"}"
}

/** A signed kg change rendered in the user's unit, sign always shown: "+0.8 kg". */
internal fun formatSignedWeight(weightKg: Double, unit: WeightUnit): String {
    val converted = WeightConverter.convert(weightKg, WeightUnit.KG, unit)
    val sign = if (converted > 0.0) "+" else ""
    return "$sign${formatNumber(converted)} ${if (unit == WeightUnit.LB) "lb" else "kg"}"
}

/** Integral values without decimals ("60"), others with one decimal ("62.5"). Locale-stable. */
internal fun formatNumber(value: Double): String =
    if (value == value.toInt().toDouble()) {
        value.toInt().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }

/** Whole-number percentage ("13%"). */
internal fun formatPercent(percent: Double): String =
    String.format(Locale.ROOT, "%.0f", percent)

/** A duration in seconds as "47 min" / "1 h 05 min". */
internal fun formatDuration(durationSeconds: Long): String {
    val totalMinutes = durationSeconds / 60
    return if (totalMinutes < 60) {
        "$totalMinutes min"
    } else {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        "$hours h ${minutes.toString().padStart(2, '0')} min"
    }
}
