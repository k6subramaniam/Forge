package com.forge.core.testing

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.Exercise
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import java.time.DayOfWeek

/**
 * Test-only fixtures: the Push/Pull/Legs routines and schedule that tests run
 * against, built from [ExerciseLibrary] entries. Production code carries no
 * sample routines, target weights, or schedules — fixtures like these live
 * under `src/test` only. Target weights and the weekly split mirror what the
 * behavioural tests were written against, so their expectations are unchanged.
 */
object TestRoutines {

    val benchPress = ExerciseLibrary.benchPress
    val overheadPress = ExerciseLibrary.overheadPress
    val tricepPushdown = ExerciseLibrary.tricepPushdown
    val barbellRow = ExerciseLibrary.barbellRow
    val pullUp = ExerciseLibrary.pullUp
    val bicepCurl = ExerciseLibrary.bicepCurl
    val squat = ExerciseLibrary.squat
    val deadlift = ExerciseLibrary.deadlift
    val legPress = ExerciseLibrary.legPress
    val calfRaise = ExerciseLibrary.calfRaise

    /** The ten exercises the tests treat as the "starter set" library. */
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

    /** Weekly schedule the tests assume: weekday → routine id. */
    val schedule: Map<DayOfWeek, String> = mapOf(
        DayOfWeek.MONDAY to pushA.id,
        DayOfWeek.WEDNESDAY to pullA.id,
        DayOfWeek.FRIDAY to legsA.id,
    )
}
