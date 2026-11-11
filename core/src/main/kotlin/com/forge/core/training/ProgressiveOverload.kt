package com.forge.core.training

import com.forge.core.model.Exercise
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry

/** A "suggested next weight" from the overload rule. Suggestion only — the user confirms (blueprint 3.2). */
data class OverloadSuggestion(
    val exerciseId: String,
    val exerciseName: String,
    val currentTargetWeightKg: Double,
    val suggestedWeightKg: Double,
    val incrementKg: Double,
    val reason: String,
)

/**
 * Progressive overload rule (blueprint 3.2): when all prescribed sets of an exercise
 * hit the top of the rep range, suggest +2.5 kg (upper body) / +5 kg (lower body)
 * for the next session.
 */
object ProgressiveOverload {

    const val UPPER_INCREMENT_KG = 2.5
    const val LOWER_INCREMENT_KG = 5.0

    fun incrementFor(exercise: Exercise): Double =
        if (exercise.muscleGroup.isLowerBody) LOWER_INCREMENT_KG else UPPER_INCREMENT_KG

    /**
     * @param performedSets the sets logged for [exercise] in the session being judged
     * (sets belonging to other exercises, and incomplete sets, are ignored).
     * @return the suggestion, or null when the rule doesn't fire (too few sets, or any
     * completed set fell short of the top of the rep range).
     */
    fun suggest(exercise: Exercise, target: RoutineExercise, performedSets: List<SetEntry>): OverloadSuggestion? {
        val completed = performedSets.filter { it.completed && it.exerciseId == exercise.id }
        if (completed.size < target.targetSets) return null

        val topOfRange = target.repRange.last
        if (completed.any { it.reps < topOfRange }) return null

        val increment = incrementFor(exercise)
        return OverloadSuggestion(
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            currentTargetWeightKg = target.targetWeightKg,
            suggestedWeightKg = target.targetWeightKg + increment,
            incrementKg = increment,
            reason = "All ${target.targetSets} sets reached $topOfRange reps — add weight next session.",
        )
    }
}
