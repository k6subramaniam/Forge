package com.forge.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.forge.core.data.SampleData
import com.forge.core.training.RestTimerLogic
import com.forge.core.training.RestTimerState
import com.forge.wear.theme.ForgeWearTheme
import com.forge.wear.ui.RestTimerScreen
import com.forge.wear.ui.SetLoggerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForgeWearTheme {
                WearWorkoutApp()
            }
        }
    }
}

private enum class WearScreen { LOGGER, REST }

/**
 * Phase 1 watch flow: log sets for the first exercise of Push A, resting between
 * sets via :core's rest-timer state machine.
 *
 * TODO(Phase 1): drive this from a real WorkoutSession (start/freeform, exercise
 * progression, session summary with PRs from :core's PrDetector), persist sets to
 * Room on the watch, and sync to the phone over the Data Layer (blueprint 3.6).
 * The ghost target below becomes the phone's last-performance record for the lift.
 */
@Composable
fun WearWorkoutApp() {
    val prescribed = remember { SampleData.pushA.exercises.first() }
    // Ghost target (blueprint 3.1): "last time: 60 kg × 8" for the demo exercise.
    val ghostTarget = remember { "Last time: 60 kg × 8" }

    var screen by remember { mutableStateOf(WearScreen.LOGGER) }
    var setNumber by remember { mutableIntStateOf(1) }
    var weightKg by remember { mutableStateOf(prescribed.targetWeightKg) }
    var reps by remember { mutableIntStateOf(prescribed.repRange.last) }
    var restState by remember { mutableStateOf(RestTimerLogic.startFor(prescribed)) }

    when (screen) {
        WearScreen.LOGGER -> SetLoggerScreen(
            exerciseName = prescribed.exercise.name,
            ghostTarget = ghostTarget,
            setNumber = setNumber,
            targetSets = prescribed.targetSets,
            weightKg = weightKg,
            reps = reps,
            onWeightChange = { weightKg = it },
            onRepsChange = { reps = it },
            onCompleteSet = {
                restState = RestTimerLogic.startFor(prescribed)
                screen = WearScreen.REST
            },
        )

        WearScreen.REST -> RestTimerScreen(
            initialState = restState,
            nextSetNumber = setNumber + 1,
            onRestFinished = {
                setNumber += 1
                screen = WearScreen.LOGGER
            },
        )
    }
}
