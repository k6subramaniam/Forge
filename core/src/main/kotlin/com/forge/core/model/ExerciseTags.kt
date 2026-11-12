package com.forge.core.model

/**
 * Structured exercise metadata (blueprint 3.2/3.11/3.12): what an exercise *is*
 * mechanically, independent of what it is called. Slice 1 matched onboarding
 * exclusions by exercise-name strings because exercises carried no tags; this
 * model is the fix — constraints can now speak in patterns, joints, and loading
 * traits, and the name matcher remains only as a fallback for untagged
 * (e.g. user-created) exercises.
 */

/** The fundamental movement pattern an exercise trains (blueprint 3.2). */
enum class MovementPattern {
    SQUAT,
    HINGE,
    HORIZONTAL_PRESS,
    VERTICAL_PRESS,
    HORIZONTAL_ROW,
    VERTICAL_PULL,
    LUNGE,
    CARRY,
    ISOLATION,
}

/**
 * Loading traits that make an exercise risky for a specific limitation — the
 * vocabulary restrictions are written in (blueprint 3.0: "avoid deep knee
 * flexion; no overhead pressing"). A flag describes how the load travels, never
 * a diagnosis.
 */
enum class ExerciseFlag {
    /** Knee flexes well past 90° under load — deep squats, deep lunges. */
    DEEP_KNEE_FLEXION,

    /** Hip flexes well past 90° under load — deep squats. */
    DEEP_HIP_FLEXION,

    /** Large ankle dorsiflexion demanded under load — deep-stance lunges. */
    DEEP_ANKLE_DORSIFLEXION,

    /** Jumping / plyometric landing forces. */
    IMPACT,

    /** Load travels overhead through shoulder flexion — presses, jerks. */
    OVERHEAD_PRESSING,

    /** Loaded shoulder elevation with internal rotation — the upright-row pattern. */
    IMPINGEMENT_PRONE,

    /**
     * Rowing while the trunk is held in a hinge without external support — the
     * trait that separates a barbell row from a chest-supported row, which a
     * name alone cannot do.
     */
    UNSUPPORTED_ROW,

    /** Heavy load creating a large spinal moment — max deadlifts, good mornings. */
    HEAVY_SPINAL_LOADING,

    /** Wide-stance pulling that loads the hip joint heavily — sumo deadlift. */
    WIDE_STANCE_HIP_LOAD,

    /** Elbow flexes deeply against load — skull-crusher style extension work. */
    DEEP_ELBOW_FLEXION_UNDER_LOAD,

    /** Close-grip pressing, which shifts load onto the elbow joint. */
    CLOSE_GRIP_PRESSING,

    /**
     * A fixed, fully supinated grip forced by a straight bar — stresses elbow
     * and wrist in a way EZ-bar and dumbbell variants do not.
     */
    FIXED_SUPINATED_GRIP,

    /** Wrist loaded in extension — barbell front-rack positions. */
    WRIST_UNDER_LOAD,

    /** Heavy direct loading of the neck/trap complex — heavy shrugs. */
    HEAVY_NECK_LOAD,
}

/**
 * The tags carried by a library exercise. [jointsLoaded] reuses the onboarding
 * [JointArea] vocabulary so a limitation area and the joints an exercise loads
 * are directly comparable (JointArea.LOWER_BACK is the spinal entry).
 */
data class ExerciseTags(
    val pattern: MovementPattern,
    val jointsLoaded: Set<JointArea>,
    val flags: Set<ExerciseFlag> = emptySet(),
)

/**
 * One structured restriction inside an onboarding exclusion (blueprint 3.0):
 * an exercise is restricted when its pattern is in [patterns] or it carries
 * any of [flags]. [description] is the plain-language functional statement —
 * the same wording that is safe to embed in AI Coach prompts.
 */
data class MovementRestriction(
    val patterns: Set<MovementPattern> = emptySet(),
    val flags: Set<ExerciseFlag> = emptySet(),
    val description: String,
) {
    fun matches(tags: ExerciseTags): Boolean =
        tags.pattern in patterns || tags.flags.any { it in flags }
}
