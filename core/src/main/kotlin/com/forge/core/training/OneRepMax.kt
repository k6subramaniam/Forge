package com.forge.core.training

import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.model.SetEntry
import java.time.LocalDate

/** Estimated one-rep max (blueprint 3.3: PR board uses the Epley formula). */
object OneRepMax {

    /**
     * Epley: 1RM ≈ w × (1 + reps/30). A true single is the weight itself.
     * Note the blueprint's honesty rule (3.15): estimates from high-rep sets are
     * unreliable — callers should treat values from sets above ~10 reps with care.
     */
    fun estimateEpley(weightKg: Double, reps: Int): Double {
        require(weightKg >= 0) { "Weight cannot be negative, was $weightKg" }
        require(reps >= 1) { "Reps must be at least 1, was $reps" }
        return if (reps == 1) weightKg else weightKg * (1.0 + reps / 30.0)
    }
}

/**
 * Detects new personal records in a batch of freshly logged sets (blueprint 3.1/3.3).
 *
 * Three PR types are tracked per exercise: heaviest weight, best single-set volume,
 * and best Epley estimated 1RM. Existing bests seed the comparison, and bests are
 * updated as the batch is scanned, so two improving sets in one session each count.
 */
object PrDetector {

    private const val EPSILON = 1e-9

    fun detectNewPrs(
        exerciseSets: List<SetEntry>,
        existingPrs: List<PRRecord>,
        date: LocalDate,
    ): List<PRRecord> {
        if (exerciseSets.isEmpty()) return emptyList()

        val exerciseId = exerciseSets.first().exerciseId
        val exerciseName = exerciseSets.first().exerciseName

        val bests = existingPrs
            .filter { it.exerciseId == exerciseId }
            .groupBy { it.type }
            .mapValues { (_, records) -> records.maxOf { it.value } }
            .toMutableMap()

        val newPrs = mutableListOf<PRRecord>()
        for (set in exerciseSets) {
            if (!set.completed) continue
            val candidates: List<Pair<PrType, Double>> = listOf(
                PrType.MAX_WEIGHT to set.weightKg,
                PrType.MAX_VOLUME to set.volumeKg,
                PrType.ESTIMATED_1RM to OneRepMax.estimateEpley(set.weightKg, set.reps),
            )
            for ((type, value) in candidates) {
                val currentBest = bests[type]
                if (currentBest == null || value > currentBest + EPSILON) {
                    newPrs += PRRecord(
                        exerciseId = exerciseId,
                        exerciseName = exerciseName,
                        type = type,
                        value = value,
                        date = date,
                        sourceSet = set,
                    )
                    bests[type] = value
                }
            }
        }
        return newPrs
    }
}
