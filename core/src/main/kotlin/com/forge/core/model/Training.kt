package com.forge.core.model

/** Muscle groups for the exercise library (blueprint 3.2). */
enum class MuscleGroup {
    CHEST,
    BACK,
    SHOULDERS,
    BICEPS,
    TRICEPS,
    QUADS,
    HAMSTRINGS,
    GLUTES,
    CALVES,
    CORE,
    FULL_BODY;

    /** Lower-body groups get the +5 kg overload increment; everything else +2.5 kg (blueprint 3.2). */
    val isLowerBody: Boolean
        get() = this == QUADS || this == HAMSTRINGS || this == GLUTES || this == CALVES
}

enum class Equipment {
    BARBELL,
    DUMBBELL,
    MACHINE,
    CABLE,
    KETTLEBELL,
    BODYWEIGHT,
}

/** Blueprint §5: Exercise — name, muscle group, equipment, custom flag. */
data class Exercise(
    val id: String,
    val name: String,
    val muscleGroup: MuscleGroup,
    val equipment: Equipment,
    val isCustom: Boolean = false,
    /** Muscles worked beyond the primary [muscleGroup] (blueprint 3.12: "muscles worked"). */
    val secondaryMuscleGroups: Set<MuscleGroup> = emptySet(),
    /**
     * Structured movement metadata (pattern, joints loaded, risk traits) that
     * constraint matching runs on (blueprint 3.0/3.2). Null for exercises that
     * have not been tagged — user-created exercises start untagged and fall back
     * to name-based exclusion matching in the session engine.
     */
    val tags: ExerciseTags? = null,
)

/** Blueprint §5: an exercise as prescribed inside a routine. */
data class RoutineExercise(
    val exercise: Exercise,
    val targetSets: Int,
    val repRange: IntRange,
    val targetWeightKg: Double,
    val restSeconds: Int = DEFAULT_REST_SECONDS,
) {
    companion object {
        const val DEFAULT_REST_SECONDS = 90
    }
}

/** Blueprint §5: Routine — name + ordered list of prescribed exercises. */
data class Routine(
    val id: String,
    val name: String,
    val exercises: List<RoutineExercise>,
)
