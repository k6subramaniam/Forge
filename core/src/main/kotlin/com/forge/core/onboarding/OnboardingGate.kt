package com.forge.core.onboarding

import com.forge.core.model.ClearanceState
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea

/**
 * A movement pattern removed from generated plans because of a limitation, together
 * with the safer variants that replace it (blueprint 3.0: swaps are shown and explained,
 * and a per-swap override is possible in the UI — the override is a UI concern, not a gate one).
 */
data class ExerciseExclusion(
    val area: JointArea,
    val excludedMovements: List<String>,
    val substitutions: List<String>,
    val reason: String,
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
    ) = ExerciseExclusion(
        area = area,
        excludedMovements = excluded,
        substitutions = substitutions,
        reason = "Declared ${area.name.lowercase().replace('_', ' ')} limitation in the onboarding health screen.",
    )

    private val EXCLUSIONS: Map<JointArea, ExerciseExclusion> = listOf(
        exclusion(
            JointArea.KNEE,
            excluded = listOf("Deep squat", "Lunge", "Jumping movements"),
            substitutions = listOf("Box squat to comfortable depth", "Leg press", "Step-up"),
        ),
        exclusion(
            JointArea.SHOULDER,
            excluded = listOf("Overhead press", "Behind-neck press", "Upright row"),
            substitutions = listOf("Landmine press", "Neutral-grip dumbbell press", "Cable lateral raise"),
        ),
        exclusion(
            JointArea.LOWER_BACK,
            excluded = listOf("Max-effort conventional deadlift", "Good morning", "Heavy unsupported row"),
            substitutions = listOf("Trap-bar deadlift", "Hip thrust", "Chest-supported row"),
        ),
        exclusion(
            JointArea.ELBOW,
            excluded = listOf("Skull crusher", "Heavy close-grip press", "Straight-bar curl"),
            substitutions = listOf("Cable pushdown", "EZ-bar curl", "Hammer curl"),
        ),
        exclusion(
            JointArea.WRIST,
            excluded = listOf("Straight-bar curl", "Barbell front rack position", "Wrist-loaded pressing"),
            substitutions = listOf("EZ-bar curl", "Dumbbell press", "Safety-bar squat"),
        ),
        exclusion(
            JointArea.HIP,
            excluded = listOf("Deep hip-flexion squat", "Sumo deadlift at max load"),
            substitutions = listOf("Box squat above parallel", "Hip thrust", "Step-up"),
        ),
        exclusion(
            JointArea.ANKLE,
            excluded = listOf("Jumping movements", "Deep-stance lunge"),
            substitutions = listOf("Heel-elevated squat", "Leg press", "Seated calf raise"),
        ),
        exclusion(
            JointArea.NECK,
            excluded = listOf("Behind-neck press", "Heavy shrug", "Neck harness work"),
            substitutions = listOf("Front press", "Cable lateral raise", "Farmer's carry"),
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
