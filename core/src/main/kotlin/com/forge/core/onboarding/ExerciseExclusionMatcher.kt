package com.forge.core.onboarding

import com.forge.core.model.Exercise

/** The outcome of matching one exercise against the active onboarding exclusions. */
data class ExerciseExclusionMatch(
    val exclusion: ExerciseExclusion,
    /**
     * What decided the match: the structured restriction's plain-language
     * description for a tag match, or the excluded movement's quoted name for
     * a name-fallback match. This wording is carried into user-facing reasons
     * and regeneration prompts, so it is kept exactly as the session engine
     * has always produced it.
     */
    val matchedDescription: String,
)

/**
 * The one exclusion matcher for Forge (blueprint 3.0). The live session
 * engine and the generated-plan validator both run through here so a movement
 * can never be judged excluded in a workout the user performs but acceptable
 * in a plan the AI generated — or the reverse.
 *
 * Matching semantics (identical to the session engine's original rules):
 * 1. **Tags first.** When an exercise carries tags, each exclusion's
 *    structured [com.forge.core.model.MovementRestriction]s are checked
 *    against its movement pattern and loading traits. This is what separates
 *    a barbell row (unsupported) from a chest-supported row — same pattern,
 *    different traits, opposite verdicts.
 * 2. **Name fallback.** Whether or not tags matched, the exclusion's
 *    plain-language movement names are then checked for that same exclusion:
 *    an exercise counts as name-excluded when either name's word set is a
 *    subset of the other's ("Squat" matches "Deep squat"; "Barbell Row"
 *    deliberately does not match "Heavy unsupported row" — names alone
 *    cannot tell a supported row from an unsupported one).
 *
 * Exclusions are considered in the gate's order, and the first match wins.
 */
object ExerciseExclusionMatcher {

    fun match(
        exercise: Exercise,
        constraints: FunctionalConstraints,
    ): ExerciseExclusionMatch? {
        for (exclusion in constraints.exerciseExclusions) {
            val tags = exercise.tags
            if (tags != null) {
                val rule = exclusion.restrictions.firstOrNull { it.matches(tags) }
                if (rule != null) {
                    return ExerciseExclusionMatch(exclusion, rule.description)
                }
            }
            val movement = exclusion.excludedMovements.firstOrNull { nameMatches(exercise.name, it) }
            if (movement != null) {
                return ExerciseExclusionMatch(exclusion, "'$movement'")
            }
        }
        return null
    }

    private fun nameMatches(exerciseName: String, movementName: String): Boolean {
        val exerciseTokens = nameTokens(exerciseName)
        val movementTokens = nameTokens(movementName)
        if (exerciseTokens.isEmpty() || movementTokens.isEmpty()) return false
        return exerciseTokens.containsAll(movementTokens) || movementTokens.containsAll(exerciseTokens)
    }

    private fun nameTokens(name: String): Set<String> =
        name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
}
