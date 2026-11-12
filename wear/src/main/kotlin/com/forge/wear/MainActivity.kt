package com.forge.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forge.wear.theme.ForgeWearTheme
import com.forge.wear.ui.ExercisePickerScreen
import com.forge.wear.ui.LoggerExercise
import com.forge.wear.ui.RestTimerScreen
import com.forge.wear.ui.SetLoggerScreen
import com.forge.wear.ui.StartScreen
import com.forge.wear.ui.SummaryScreen
import com.forge.wear.ui.describe
import com.forge.wear.workout.WatchUi
import com.forge.wear.workout.WorkoutViewModel

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

/**
 * The watch flow (blueprint 3.1): start today's plan or a freeform session,
 * log sets with rest between them, finish, and the session goes to the phone.
 * Screens are state in, events out; [WorkoutViewModel] holds the session.
 */
@Composable
fun WearWorkoutApp(viewModel: WorkoutViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()
    var picking by remember { mutableStateOf(false) }

    when (val screen = ui) {
        WatchUi.Loading -> Unit
        is WatchUi.Idle -> StartScreen(
            lookup = screen.lookup,
            canStartFreeform = screen.canStartFreeform,
            onStart = viewModel::startSession,
        )
        is WatchUi.Active -> {
            val current = screen.current
            val rest = screen.rest
            when {
                // One rest screen per logged set, so each starts fresh.
                rest != null -> key(screen.state.setHistory.size) {
                    RestTimerScreen(
                        initialState = rest,
                        nextLabel = current?.let { "Next: ${it.exercise.name} · set ${it.loggedSets.size + 1}" }
                            ?: "Next: finish or add an exercise",
                        onRestFinished = viewModel::restFinished,
                    )
                }
                picking -> ExercisePickerScreen(
                    menu = screen.menu,
                    onPick = { id ->
                        picking = false
                        viewModel.addExercise(id)
                    },
                    onCancel = { picking = false },
                )
                else -> SetLoggerScreen(
                    exercise = current?.let {
                        LoggerExercise(
                            name = it.exercise.name,
                            ghostTarget = screen.ghost?.let { set -> "Last time: ${describe(set)}" },
                            setNumber = it.loggedSets.size + 1,
                            targetSets = it.prescription.targetSets,
                        )
                    },
                    weightKg = screen.weightKg,
                    reps = screen.reps,
                    hasLoggedSets = screen.hasLoggedSets,
                    canAddExercise = screen.menu.isNotEmpty(),
                    onWeightChange = viewModel::adjustWeight,
                    onRepsChange = viewModel::adjustReps,
                    onCompleteSet = { viewModel.completeSet() },
                    onPainSet = { viewModel.completeSet(painReported = true) },
                    onUndo = viewModel::undoLastSet,
                    onSkip = viewModel::skipExercise,
                    onAddExercise = { picking = true },
                    onFinish = viewModel::finishSession,
                )
            }
        }
        is WatchUi.Finished -> SummaryScreen(
            summary = screen.summary,
            handedOver = screen.handedOver,
            onDone = viewModel::dismissSummary,
        )
    }
}
