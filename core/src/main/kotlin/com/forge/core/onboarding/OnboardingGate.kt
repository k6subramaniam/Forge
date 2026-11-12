package com.forge.core.onboarding

import com.forge.core.model.ClearanceState
import com.forge.core.model.ExerciseFlag
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.MovementRestriction

/**
 * A movement pattern removed from generated plans because of a limitation, together
 * with the safer variants that replace it (blueprint 3.0: swaps are shown and explained,
 * and a per-swap override is possible in the UI — the override is a UI concern, not a gate one).
 *
 * The exclusion is expressed twice, deliberately:
 * - [excludedMovements] names movements in plain language. This is the form the
 *   prompt constraints and the UI explanations use, and it is the only form that
 *   can match an untagged (e.g. user-created) exercise.
 * - [restrictions] is the structured form — movement patterns and loading
 *   traits — that matching runs on whenever an exercise carries tags. It can
 *   tell apart exercises that names cannot (a barbell row vs a chest-supported
 *   row), which is why it takes precedence in the session engine.
 */
data class ExerciseExclusion(
    val area: JointArea,
    val excludedMovements: List<String>,
    val substitutions: List<String>,
    val reason: String,
    val restrictions: List<MovementRestriction> = emptyList(),
)

/**
 * The functional (non-diagnostic) constraints derived from a [HealthProfile].
 * These are what get embedded into AI Coach prompts — never the diagnoses themselves
 * (blueprint 3.0, propagation rule 3).
 */
data class FunctionalConstraints(
    val exerciseExclusions: List<ExerciseExclusion>,
    /**
     * Cap on suggested working heart rate as a percentage of max HR, or null when uncapped.
     * When set, plans and readiness may only scale intensity DOWN from the prescription,
     * never up (blueprint 3.0, propagation rule 2).
     */
    val heartRateCapPercentOfMax: Int?,
    val intensityMayOnlyScaleDown: Boolean,
    /** Plain-language functional limitation statements, safe to send to the AI Coach. */
    val promptConstraints: List<String>,
)

data class OnboardingGateResult(
    /** Human-readable names of the red flags that fired (empty when none). */
    val redFlagsPresent: List<String>,
    /** True while a red flag exists and medical clearance has not been recorded. */
    val planGenerationBlocked: Boolean,
    /** The single gate every plan/workout/diet generator must check first (blueprint 3.0). */
    val canGeneratePlans: Boolean,
    val constraints: FunctionalConstraints,
)

/**
 * Blueprint 3.0 as executable rules. Pure functions over [HealthProfile] so the safety
 * gate is unit-tested independently of any UI.
 */
object OnboardingGate {

    /** Working-HR ceiling applied when any cardiovascular flag is present. */
    const val CARDIO_HR_CAP_PERCENT_OF_MAX = 85

    private fun exclusion(
        area: JointArea,
        excluded: List<String>,
        substitutions: List<String>,
        restrictions: List<MovementRestriction> = emptyList(),
    ) = ExerciseExclusion(
        area = area,
        excludedMovements = excluded,
        substitutions = substitutions,
        reason = "Declared ${area.name.lowercase().replace('_', ' ')} limitation in the onboarding health screen.",
        restrictions = restrictions,
    )

    private fun restricted(flag: ExerciseFlag, description: String) =
        MovementRestriction(flags = setOf(flag), description = description)

    private val EXCLUSIONS: Map<JointArea, ExerciseExclusion> = listOf(
        exclusion(
            JointArea.KNEE,
            excluded = listOf("Deep squat", "Lunge", "Jumping movements"),
            substitutions = listOf("Box squat to comfortable depth", "Leg press", "Step-up"),
            restrictions = listOf(
                // Lunges are caught by the deep-flexion trait, not their pattern:
                // a controlled step-up shares the lunge pattern but not the trait,
                // and it is one of this exclusion's own substitutes.
                restricted(ExerciseFlag.DEEP_KNEE_FLEXION, "no deep knee flexion under load"),
                restricted(ExerciseFlag.IMPACT, "no jumping or impact movements"),
            ),
        ),
        exclusion(
            JointArea.SHOULDER,
            excluded = listOf("Overhead press", "Behind-neck press", "Upright row"),
            substitutions = listOf("Landmine press", "Neutral-grip dumbbell press", "Cable lateral raise"),
            restrictions = listOf(
                restricted(ExerciseFlag.OVERHEAD_PRESSING, "no overhead pressing"),
                restricted(ExerciseFlag.IMPINGEMENT_PRONE, "no impingement-prone loaded shoulder elevation"),
            ),
        ),
        exclusion(
            JointArea.LOWER_BACK,
            excluded = listOf("Max-effort conventional deadlift", "Good morning", "Heavy unsupported row"),
            substitutions = listOf("Trap-bar deadlift", "Hip thrust", "Chest-supported row"),
            restrictions = listOf(
                restricted(ExerciseFlag.HEAVY_SPINAL_LOADING, "no heavy spinal loading"),
                restricted(ExerciseFlag.UNSUPPORTED_ROW, "no unsupported rowing"),
            ),
        ),
        exclusion(
            JointArea.ELBOW,
            excluded = listOf("Skull crusher", "Heavy close-grip press", "Straight-bar curl"),
            substitutions = listOf("Cable pushdown", "EZ-bar curl", "Hammer curl"),
            restrictions = listOf(
                restricted(ExerciseFlag.DEEP_ELBOW_FLEXION_UNDER_LOAD, "no deep elbow flexion under load"),
                restricted(ExerciseFlag.CLOSE_GRIP_PRESSING, "no heavy close-grip pressing"),
                restricted(ExerciseFlag.FIXED_SUPINATED_GRIP, "no straight-bar curls"),
            ),
        ),
        exclusion(
            JointArea.WRIST,
            excluded = listOf("Straight-bar curl", "Barbell front rack position", "Wrist-loaded pressing"),
            substitutions = listOf("EZ-bar curl", "Dumbbell press", "Safety-bar squat"),
            restrictions = listOf(
                restricted(ExerciseFlag.WRIST_UNDER_LOAD, "no wrist-loaded positions"),
                restricted(ExerciseFlag.FIXED_SUPINATED_GRIP, "no straight-bar curls"),
            ),
        ),
        exclusion(
            JointArea.HIP,
            excluded = listOf("Deep hip-flexion squat", "Sumo deadlift at max load"),
            substitutions = listOf("Box squat above parallel", "Hip thrust", "Step-up"),
            restrictions = listOf(
                restricted(ExerciseFlag.DEEP_HIP_FLEXION, "no deep hip flexion under load"),
                restricted(ExerciseFlag.WIDE_STANCE_HIP_LOAD, "no wide-stance heavy pulling"),
            ),
        ),
        exclusion(
            JointArea.ANKLE,
            excluded = listOf("Jumping movements", "Deep-stance lunge"),
            substitutions = listOf("Heel-elevated squat", "Leg press", "Seated calf raise"),
            restrictions = listOf(
                restricted(ExerciseFlag.IMPACT, "no jumping or impact movements"),
                restricted(ExerciseFlag.DEEP_ANKLE_DORSIFLEXION, "no deep-stance lunging"),
            ),
        ),
        exclusion(
            JointArea.NECK,
            excluded = listOf("Behind-neck press", "Heavy shrug", "Neck harness work"),
            substitutions = listOf("Front press", "Cable lateral raise", "Farmer's carry"),
            restrictions = listOf(
                restricted(ExerciseFlag.HEAVY_NECK_LOAD, "no heavy shrugs or direct neck loading"),
            ),
        ),
    ).associateBy { it.area }

    fun evaluate(profile: HealthProfile): OnboardingGateResult {
        val redFlags = buildList {
            if (profile.hasDiagnosedHeartCondition) add("Diagnosed heart condition")
            if (profile.hasExertionalChestPain) add("Chest pain during activity")
            if (profile.doctorAdvisedAgainstExercise) add("Doctor has advised against exercise")
        }

        // A red flag blocks generation until clearance is recorded as OBTAINED.
        // (The UI sets clearanceState = REQUIRED when a red flag is first answered.)
        val blocked = redFlags.isNotEmpty() && profile.clearanceState != ClearanceState.OBTAINED
        val canGenerate = profile.onboardingComplete && !blocked

        val cardiovascularFlag = profile.hasDiagnosedHeartCondition ||
            profile.hasHighBloodPressure ||
            profile.takesHeartRateAffectingMedication

        val exclusions = profile.jointLimitations.mapNotNull { EXCLUSIONS[it] }

        val promptConstraints = buildList {
            exclusions.forEach { ex ->
                add(
                    "Avoid ${ex.excludedMovements.joinToString(", ").lowercase()} " +
                        "(${ex.area.name.lowercase().replace('_', ' ')} limitation); " +
                        "prefer ${ex.substitutions.joinToString(", ").lowercase()}.",
                )
            }
            if (cardiovascularFlag) {
                add("Keep working heart rate at or below $CARDIO_HR_CAP_PERCENT_OF_MAX% of max; scale intensity down only, never up.")
            }
            if (profile.hasAsthma) {
                add("Breathing limitation declared; favour longer rest intervals and avoid maximal breath-hold efforts.")
            }
            if (profile.freeTextLimitations.isNotBlank()) {
                add("User-declared limitation: ${profile.freeTextLimitations.trim()}")
            }
            if (profile.injuriesOrSurgeries.isNotBlank()) {
                add("Injury/surgery history declared: ${profile.injuriesOrSurgeries.trim()}")
            }
        }

        return OnboardingGateResult(
            redFlagsPresent = redFlags,
            planGenerationBlocked = blocked,
            canGeneratePlans = canGenerate,
            constraints = FunctionalConstraints(
                exerciseExclusions = exclusions,
                heartRateCapPercentOfMax = if (cardiovascularFlag) CARDIO_HR_CAP_PERCENT_OF_MAX else null,
                intensityMayOnlyScaleDown = cardiovascularFlag,
                promptConstraints = promptConstraints,
            ),
        )
    }
}
