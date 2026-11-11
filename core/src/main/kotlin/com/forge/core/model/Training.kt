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
