package com.forge.core.data

import com.forge.core.model.Equipment
import com.forge.core.model.Exercise
import com.forge.core.model.MuscleGroup
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import java.time.DayOfWeek

/**
 * Built-in starter content (blueprint 3.2): a small exercise library and Push/Pull/Legs
 * templates so day one isn't a blank page. Shared by the phone and watch apps.
 * TODO(Phase 2): move the full library + templates (Upper/Lower, 5×5) into the routine
 * builder's seed data once Room lands.
 */
object SampleData {

    val benchPress = Exercise("ex-bench-press", "Bench Press", MuscleGroup.CHEST, Equipment.BARBELL)
    val overheadPress = Exercise("ex-overhead-press", "Overhead Press", MuscleGroup.SHOULDERS, Equipment.BARBELL)
    val tricepPushdown = Exercise("ex-tricep-pushdown", "Tricep Pushdown", MuscleGroup.TRICEPS, Equipment.CABLE)
    val barbellRow = Exercise("ex-barbell-row", "Barbell Row", MuscleGroup.BACK, Equipment.BARBELL)
    val pullUp = Exercise("ex-pull-up", "Pull-Up", MuscleGroup.BACK, Equipment.BODYWEIGHT)
    val bicepCurl = Exercise("ex-bicep-curl", "Bicep Curl", MuscleGroup.BICEPS, Equipment.DUMBBELL)
    val squat = Exercise("ex-squat", "Squat", MuscleGroup.QUADS, Equipment.BARBELL)
    val deadlift = Exercise("ex-deadlift", "Deadlift", MuscleGroup.HAMSTRINGS, Equipment.BARBELL)
    val legPress = Exercise("ex-leg-press", "Leg Press", MuscleGroup.QUADS, Equipment.MACHINE)
    val calfRaise = Exercise("ex-calf-raise", "Calf Raise", MuscleGroup.CALVES, Equipment.MACHINE)

    val exercises: List<Exercise> = listOf(
        benchPress, overheadPress, tricepPushdown,
        barbellRow, pullUp, bicepCurl,
        squat, deadlift, legPress, calfRaise,
    )

    val pushA = Routine(
        id = "routine-push-a",
        name = "Push A",
        exercises = listOf(
            RoutineExercise(benchPress, targetSets = 4, repRange = 6..8, targetWeightKg = 60.0, restSeconds = 120),
            RoutineExercise(overheadPress, targetSets = 3, repRange = 8..10, targetWeightKg = 40.0, restSeconds = 90),
            RoutineExercise(tricepPushdown, targetSets = 3, repRange = 10..12, targetWeightKg = 25.0, restSeconds = 60),
        ),
    )

    val pullA = Routine(
        id = "routine-pull-a",
        name = "Pull A",
        exercises = listOf(
            RoutineExercise(barbellRow, targetSets = 4, repRange = 6..8, targetWeightKg = 60.0, restSeconds = 120),
            RoutineExercise(pullUp, targetSets = 3, repRange = 5..8, targetWeightKg = 0.0, restSeconds = 90),
            RoutineExercise(bicepCurl, targetSets = 3, repRange = 10..12, targetWeightKg = 12.5, restSeconds = 60),
        ),
    )

    val legsA = Routine(
        id = "routine-legs-a",
        name = "Legs A",
        exercises = listOf(
            RoutineExercise(squat, targetSets = 4, repRange = 5..6, targetWeightKg = 80.0, restSeconds = 150),
            RoutineExercise(deadlift, targetSets = 3, repRange = 5..5, targetWeightKg = 100.0, restSeconds = 150),
            RoutineExercise(legPress, targetSets = 3, repRange = 10..12, targetWeightKg = 120.0, restSeconds = 90),
            RoutineExercise(calfRaise, targetSets = 3, repRange = 12..15, targetWeightKg = 40.0, restSeconds = 60),
        ),
    )

    val routines: List<Routine> = listOf(pushA, pullA, legsA)

    /** Default weekly schedule (blueprint 3.2): weekday → routine id. */
    val schedule: Map<DayOfWeek, String> = mapOf(
        DayOfWeek.MONDAY to pushA.id,
        DayOfWeek.WEDNESDAY to pullA.id,
        DayOfWeek.FRIDAY to legsA.id,
    )
}
