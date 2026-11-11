package com.forge.core.data

import com.forge.core.model.Equipment
import com.forge.core.model.Exercise
import com.forge.core.model.ExerciseFlag
import com.forge.core.model.ExerciseTags
import com.forge.core.model.JointArea
import com.forge.core.model.MovementPattern
import com.forge.core.model.MuscleGroup

/**
 * The built-in exercise library (blueprint 3.2/3.11): every exercise fully
 * tagged with its movement pattern, the joints it loads, and its risk traits,
 * so onboarding constraints match on mechanics instead of names (blueprint 3.0).
 * The substitutes the constraint system reaches for — Landmine press, Box squat,
 * Leg press, Chest-supported row, Trap-bar deadlift — are first-class entries
 * here, not generated stand-ins.
 *
 * [SampleData] draws its ten starter exercises from this library (ids stable
 * since the scaffold); routines and templates stay in SampleData.
 */
object ExerciseLibrary {

    // -- Pressing ------------------------------------------------------------

    val benchPress = Exercise(
        id = "ex-bench-press", name = "Bench Press",
        muscleGroup = MuscleGroup.CHEST, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS),
        tags = ExerciseTags(
            pattern = MovementPattern.HORIZONTAL_PRESS,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
        ),
    )

    val inclineDumbbellPress = Exercise(
        id = "ex-incline-db-press", name = "Incline Dumbbell Press",
        muscleGroup = MuscleGroup.CHEST, equipment = Equipment.DUMBBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.HORIZONTAL_PRESS,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
        ),
    )

    val neutralGripDumbbellPress = Exercise(
        id = "ex-neutral-grip-db-press", name = "Neutral-Grip Dumbbell Press",
        muscleGroup = MuscleGroup.CHEST, equipment = Equipment.DUMBBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.TRICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.HORIZONTAL_PRESS,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
        ),
    )

    val overheadPress = Exercise(
        id = "ex-overhead-press", name = "Overhead Press",
        muscleGroup = MuscleGroup.SHOULDERS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.TRICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.VERTICAL_PRESS,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
            flags = setOf(ExerciseFlag.OVERHEAD_PRESSING),
        ),
    )

    val landminePress = Exercise(
        id = "ex-landmine-press", name = "Landmine Press",
        muscleGroup = MuscleGroup.SHOULDERS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.VERTICAL_PRESS,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
            // Angled arc, never fully overhead — that is exactly why the
            // shoulder constraint substitutes it for the overhead press.
            flags = emptySet(),
        ),
    )

    val closeGripBenchPress = Exercise(
        id = "ex-close-grip-bench-press", name = "Close-Grip Bench Press",
        muscleGroup = MuscleGroup.TRICEPS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.CHEST),
        tags = ExerciseTags(
            pattern = MovementPattern.HORIZONTAL_PRESS,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW, JointArea.WRIST),
            flags = setOf(ExerciseFlag.CLOSE_GRIP_PRESSING),
        ),
    )

    val tricepPushdown = Exercise(
        id = "ex-tricep-pushdown", name = "Tricep Pushdown",
        muscleGroup = MuscleGroup.TRICEPS, equipment = Equipment.CABLE,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.ELBOW),
        ),
    )

    val skullCrusher = Exercise(
        id = "ex-skull-crusher", name = "Skull Crusher",
        muscleGroup = MuscleGroup.TRICEPS, equipment = Equipment.BARBELL,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.ELBOW),
            flags = setOf(ExerciseFlag.DEEP_ELBOW_FLEXION_UNDER_LOAD),
        ),
    )

    val cableLateralRaise = Exercise(
        id = "ex-cable-lateral-raise", name = "Cable Lateral Raise",
        muscleGroup = MuscleGroup.SHOULDERS, equipment = Equipment.CABLE,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.SHOULDER),
        ),
    )

    val uprightRow = Exercise(
        id = "ex-upright-row", name = "Upright Row",
        muscleGroup = MuscleGroup.SHOULDERS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.BICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
            flags = setOf(ExerciseFlag.IMPINGEMENT_PRONE),
        ),
    )

    // -- Pulling ---------------------------------------------------------------

    val barbellRow = Exercise(
        id = "ex-barbell-row", name = "Barbell Row",
        muscleGroup = MuscleGroup.BACK, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.BICEPS, MuscleGroup.HAMSTRINGS),
        tags = ExerciseTags(
            pattern = MovementPattern.HORIZONTAL_ROW,
            jointsLoaded = setOf(JointArea.LOWER_BACK, JointArea.SHOULDER, JointArea.ELBOW, JointArea.HIP),
            // The trunk is held in a hinge with no external support — the trait
            // the lower-back restriction targets, and the reason a
            // chest-supported row is the safe counterpart (blueprint 3.0).
            flags = setOf(ExerciseFlag.UNSUPPORTED_ROW),
        ),
    )

    val chestSupportedRow = Exercise(
        id = "ex-chest-supported-row", name = "Chest-Supported Row",
        muscleGroup = MuscleGroup.BACK, equipment = Equipment.MACHINE,
        secondaryMuscleGroups = setOf(MuscleGroup.BICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.HORIZONTAL_ROW,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
            // Same pattern as the barbell row, but supported: no UNSUPPORTED_ROW.
            flags = emptySet(),
        ),
    )

    val seatedCableRow = Exercise(
        id = "ex-seated-cable-row", name = "Seated Cable Row",
        muscleGroup = MuscleGroup.BACK, equipment = Equipment.CABLE,
        secondaryMuscleGroups = setOf(MuscleGroup.BICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.HORIZONTAL_ROW,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
        ),
    )

    val pullUp = Exercise(
        id = "ex-pull-up", name = "Pull-Up",
        muscleGroup = MuscleGroup.BACK, equipment = Equipment.BODYWEIGHT,
        secondaryMuscleGroups = setOf(MuscleGroup.BICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.VERTICAL_PULL,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
        ),
    )

    val latPulldown = Exercise(
        id = "ex-lat-pulldown", name = "Lat Pulldown",
        muscleGroup = MuscleGroup.BACK, equipment = Equipment.CABLE,
        secondaryMuscleGroups = setOf(MuscleGroup.BICEPS),
        tags = ExerciseTags(
            pattern = MovementPattern.VERTICAL_PULL,
            jointsLoaded = setOf(JointArea.SHOULDER, JointArea.ELBOW),
        ),
    )

    val bicepCurl = Exercise(
        id = "ex-bicep-curl", name = "Bicep Curl",
        muscleGroup = MuscleGroup.BICEPS, equipment = Equipment.DUMBBELL,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.ELBOW),
        ),
    )

    val barbellCurl = Exercise(
        id = "ex-barbell-curl", name = "Barbell Curl",
        muscleGroup = MuscleGroup.BICEPS, equipment = Equipment.BARBELL,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.ELBOW, JointArea.WRIST),
            flags = setOf(ExerciseFlag.FIXED_SUPINATED_GRIP),
        ),
    )

    val ezBarCurl = Exercise(
        id = "ex-ez-bar-curl", name = "EZ-Bar Curl",
        muscleGroup = MuscleGroup.BICEPS, equipment = Equipment.BARBELL,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.ELBOW),
        ),
    )

    val hammerCurl = Exercise(
        id = "ex-hammer-curl", name = "Hammer Curl",
        muscleGroup = MuscleGroup.BICEPS, equipment = Equipment.DUMBBELL,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.ELBOW),
        ),
    )

    // -- Legs ------------------------------------------------------------------

    val squat = Exercise(
        id = "ex-squat", name = "Squat",
        muscleGroup = MuscleGroup.QUADS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS),
        tags = ExerciseTags(
            pattern = MovementPattern.SQUAT,
            jointsLoaded = setOf(JointArea.KNEE, JointArea.HIP, JointArea.ANKLE, JointArea.LOWER_BACK),
            flags = setOf(ExerciseFlag.DEEP_KNEE_FLEXION, ExerciseFlag.DEEP_HIP_FLEXION),
        ),
    )

    val boxSquat = Exercise(
        id = "ex-box-squat", name = "Box Squat",
        muscleGroup = MuscleGroup.QUADS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES),
        tags = ExerciseTags(
            pattern = MovementPattern.SQUAT,
            jointsLoaded = setOf(JointArea.KNEE, JointArea.HIP),
            // The box caps depth at a comfortable range — no deep-flexion flags,
            // which is why the knee/hip constraints substitute it in.
            flags = emptySet(),
        ),
    )

    val frontSquat = Exercise(
        id = "ex-front-squat", name = "Front Squat",
        muscleGroup = MuscleGroup.QUADS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES, MuscleGroup.CORE),
        tags = ExerciseTags(
            pattern = MovementPattern.SQUAT,
            jointsLoaded = setOf(JointArea.KNEE, JointArea.HIP, JointArea.WRIST),
            flags = setOf(
                ExerciseFlag.DEEP_KNEE_FLEXION,
                ExerciseFlag.DEEP_HIP_FLEXION,
                ExerciseFlag.WRIST_UNDER_LOAD,
            ),
        ),
    )

    val legPress = Exercise(
        id = "ex-leg-press", name = "Leg Press",
        muscleGroup = MuscleGroup.QUADS, equipment = Equipment.MACHINE,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS),
        tags = ExerciseTags(
            pattern = MovementPattern.SQUAT,
            jointsLoaded = setOf(JointArea.KNEE, JointArea.HIP),
        ),
    )

    val deadlift = Exercise(
        id = "ex-deadlift", name = "Deadlift",
        muscleGroup = MuscleGroup.HAMSTRINGS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES, MuscleGroup.BACK),
        tags = ExerciseTags(
            pattern = MovementPattern.HINGE,
            jointsLoaded = setOf(JointArea.HIP, JointArea.KNEE, JointArea.LOWER_BACK),
            flags = setOf(ExerciseFlag.HEAVY_SPINAL_LOADING),
        ),
    )

    val trapBarDeadlift = Exercise(
        id = "ex-trap-bar-deadlift", name = "Trap-Bar Deadlift",
        muscleGroup = MuscleGroup.HAMSTRINGS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.QUADS, MuscleGroup.GLUTES),
        tags = ExerciseTags(
            pattern = MovementPattern.HINGE,
            jointsLoaded = setOf(JointArea.HIP, JointArea.KNEE),
            // Upright torso and neutral handles cut the spinal moment — the
            // lower-back constraint's named substitute for the deadlift.
            flags = emptySet(),
        ),
    )

    val romanianDeadlift = Exercise(
        id = "ex-romanian-deadlift", name = "Romanian Deadlift",
        muscleGroup = MuscleGroup.HAMSTRINGS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES),
        tags = ExerciseTags(
            pattern = MovementPattern.HINGE,
            jointsLoaded = setOf(JointArea.HIP, JointArea.LOWER_BACK),
        ),
    )

    val goodMorning = Exercise(
        id = "ex-good-morning", name = "Good Morning",
        muscleGroup = MuscleGroup.HAMSTRINGS, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES),
        tags = ExerciseTags(
            pattern = MovementPattern.HINGE,
            jointsLoaded = setOf(JointArea.HIP, JointArea.LOWER_BACK),
            flags = setOf(ExerciseFlag.HEAVY_SPINAL_LOADING),
        ),
    )

    val sumoDeadlift = Exercise(
        id = "ex-sumo-deadlift", name = "Sumo Deadlift",
        muscleGroup = MuscleGroup.GLUTES, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.HAMSTRINGS, MuscleGroup.QUADS),
        tags = ExerciseTags(
            pattern = MovementPattern.HINGE,
            jointsLoaded = setOf(JointArea.HIP, JointArea.KNEE),
            flags = setOf(ExerciseFlag.HEAVY_SPINAL_LOADING, ExerciseFlag.WIDE_STANCE_HIP_LOAD),
        ),
    )

    val hipThrust = Exercise(
        id = "ex-hip-thrust", name = "Hip Thrust",
        muscleGroup = MuscleGroup.GLUTES, equipment = Equipment.BARBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.HAMSTRINGS),
        tags = ExerciseTags(
            pattern = MovementPattern.HINGE,
            jointsLoaded = setOf(JointArea.HIP),
        ),
    )

    val walkingLunge = Exercise(
        id = "ex-walking-lunge", name = "Walking Lunge",
        muscleGroup = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES),
        tags = ExerciseTags(
            pattern = MovementPattern.LUNGE,
            jointsLoaded = setOf(JointArea.KNEE, JointArea.HIP, JointArea.ANKLE),
            flags = setOf(ExerciseFlag.DEEP_KNEE_FLEXION, ExerciseFlag.DEEP_ANKLE_DORSIFLEXION),
        ),
    )

    val stepUp = Exercise(
        id = "ex-step-up", name = "Step-Up",
        muscleGroup = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.GLUTES),
        tags = ExerciseTags(
            pattern = MovementPattern.LUNGE,
            jointsLoaded = setOf(JointArea.KNEE, JointArea.HIP),
            // Controlled box height keeps knee flexion in a comfortable range —
            // no deep-flexion flags, which is why the knee constraint names it
            // as a substitute even though it shares the lunge pattern.
            flags = emptySet(),
        ),
    )

    val calfRaise = Exercise(
        id = "ex-calf-raise", name = "Calf Raise",
        muscleGroup = MuscleGroup.CALVES, equipment = Equipment.MACHINE,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.ANKLE),
        ),
    )

    val seatedCalfRaise = Exercise(
        id = "ex-seated-calf-raise", name = "Seated Calf Raise",
        muscleGroup = MuscleGroup.CALVES, equipment = Equipment.MACHINE,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.ANKLE, JointArea.KNEE),
        ),
    )

    // -- Carries and neck --------------------------------------------------------

    val farmersCarry = Exercise(
        id = "ex-farmers-carry", name = "Farmer's Carry",
        muscleGroup = MuscleGroup.FULL_BODY, equipment = Equipment.DUMBBELL,
        secondaryMuscleGroups = setOf(MuscleGroup.SHOULDERS, MuscleGroup.CORE),
        tags = ExerciseTags(
            pattern = MovementPattern.CARRY,
            jointsLoaded = setOf(JointArea.WRIST, JointArea.SHOULDER),
        ),
    )

    val barbellShrug = Exercise(
        id = "ex-barbell-shrug", name = "Barbell Shrug",
        muscleGroup = MuscleGroup.SHOULDERS, equipment = Equipment.BARBELL,
        tags = ExerciseTags(
            pattern = MovementPattern.ISOLATION,
            jointsLoaded = setOf(JointArea.NECK, JointArea.SHOULDER),
            flags = setOf(ExerciseFlag.HEAVY_NECK_LOAD),
        ),
    )

    /** Every exercise in the library, in a stable browsing order (blueprint 3.11). */
    val all: List<Exercise> = listOf(
        benchPress, inclineDumbbellPress, neutralGripDumbbellPress,
        overheadPress, landminePress, closeGripBenchPress,
        tricepPushdown, skullCrusher, cableLateralRaise, uprightRow,
        barbellRow, chestSupportedRow, seatedCableRow,
        pullUp, latPulldown,
        bicepCurl, barbellCurl, ezBarCurl, hammerCurl,
        squat, boxSquat, frontSquat, legPress,
        deadlift, trapBarDeadlift, romanianDeadlift, goodMorning, sumoDeadlift, hipThrust,
        walkingLunge, stepUp,
        calfRaise, seatedCalfRaise,
        farmersCarry, barbellShrug,
    )

    fun byId(id: String): Exercise? = all.firstOrNull { it.id == id }

    fun byPattern(pattern: MovementPattern): List<Exercise> = all.filter { it.tags?.pattern == pattern }
}
