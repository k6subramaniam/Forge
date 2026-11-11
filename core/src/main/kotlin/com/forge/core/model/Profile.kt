package com.forge.core.model

enum class GoalType {
    BUILD_MUSCLE,
    LOSE_FAT,
    RECOMP,
    MAINTAIN,
}

enum class ExperienceLevel {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
}

enum class EquipmentAccess {
    FULL_GYM,
    DUMBBELLS_ONLY,
    HOME_MINIMAL,
}

enum class ActivityLevel {
    SEDENTARY,
    LIGHT,
    MODERATE,
    HIGH,
    ATHLETE,
}

enum class Sex {
    MALE,
    FEMALE,
}

/**
 * Blueprint §5: the goal profile collected in onboarding steps 1–2 and 5 —
 * goal, body stats, calorie/macro targets, dietary preferences and restrictions.
 * Calorie/protein targets are computed from formulas in later phases (blueprint 3.7);
 * in Phase 1 they are carried as optional, user-confirmed values.
 */
data class GoalProfile(
    val goal: GoalType,
    val ageYears: Int,
    val sex: Sex,
    val heightCm: Double,
    val weightKg: Double,
    val trainingDaysPerWeek: Int,
    val experienceLevel: ExperienceLevel,
    val equipmentAccess: EquipmentAccess,
    val activityLevel: ActivityLevel,
    val calorieTargetKcal: Int? = null,
    val proteinTargetGrams: Int? = null,
    val carbTargetGrams: Int? = null,
    val fatTargetGrams: Int? = null,
    /** e.g. "vegetarian". Free text in v1, matching the blueprint's diet screen. */
    val dietaryPattern: String? = null,
    val dislikes: List<String> = emptyList(),
    /** Hard constraints — generated meal plans must never include these (blueprint 3.0). */
    val allergies: List<String> = emptyList(),
)

/** Joint/bone problem areas asked about on the PAR-Q-style health screen (blueprint 3.0). */
enum class JointArea {
    KNEE,
    SHOULDER,
    LOWER_BACK,
    ELBOW,
    WRIST,
    HIP,
    ANKLE,
    NECK,
}

/** Red-flag clearance lifecycle: a red-flag profile is REQUIRED to get clearance, then OBTAINED. */
enum class ClearanceState {
    NOT_REQUIRED,
    REQUIRED,
    OBTAINED,
}

/**
 * Blueprint §5: the onboarding health screen. Stored encrypted on-device only in the
 * real app (TODO marker lives in the mobile repository stub); the onboarding-complete
 * flag gates all plan generation (see com.forge.core.onboarding.OnboardingGate).
 */
data class HealthProfile(
    val hasDiagnosedHeartCondition: Boolean = false,
    val hasHighBloodPressure: Boolean = false,
    val hasExertionalChestPain: Boolean = false,
    val doctorAdvisedAgainstExercise: Boolean = false,
    val hasAsthma: Boolean = false,
    val hasDiabetes: Boolean = false,
    /** e.g. beta blockers — caps suggested HR zones (blueprint 3.0). */
    val takesHeartRateAffectingMedication: Boolean = false,
    val jointLimitations: Set<JointArea> = emptySet(),
    val injuriesOrSurgeries: String = "",
    val freeTextLimitations: String = "",
    val clearanceState: ClearanceState = ClearanceState.NOT_REQUIRED,
    val onboardingComplete: Boolean = false,
)
