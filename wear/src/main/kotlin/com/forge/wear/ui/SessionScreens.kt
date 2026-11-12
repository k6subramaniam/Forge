package com.forge.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.forge.core.model.SetEntry
import com.forge.core.sync.MenuExercise
import com.forge.core.sync.PlanLookup
import com.forge.core.sync.WorkoutPlan
import com.forge.core.training.SessionSummary

/**
 * What the watch offers with no session running: today's scheduled routine,
 * a freeform session, or why there's nothing (blueprint 3.0: when the phone's
 * onboarding gate blocks plans, the watch has nothing to start).
 */
@Composable
fun StartScreen(
    lookup: PlanLookup?,
    canStartFreeform: Boolean,
    onStart: (WorkoutPlan?) -> Unit,
) {
    ListScreen {
        when (lookup) {
            null -> item { Centered("Open Forge on your phone to sync your plans.", MaterialTheme.typography.body1) }
            is PlanLookup.Blocked -> item { Centered(lookup.reason, MaterialTheme.typography.body1) }
            is PlanLookup.Scheduled -> {
                item { Centered("Today", MaterialTheme.typography.caption1, secondary = true) }
                item { Centered(lookup.plan.routineName, MaterialTheme.typography.title2) }
                item {
                    Centered(
                        lookup.plan.exercises.joinToString(" · ") { it.prescription.exercise.name },
                        MaterialTheme.typography.body2,
                        secondary = true,
                    )
                }
                item { WideChip("Start", onClick = { onStart(lookup.plan) }) }
            }
            PlanLookup.Unscheduled -> item { Centered("Nothing scheduled today.", MaterialTheme.typography.title3) }
            PlanLookup.OutOfDate -> item {
                Centered("Your plan is out of date. Open Forge on your phone to refresh it.", MaterialTheme.typography.body1)
            }
        }
        if (canStartFreeform) {
            val label = if (lookup is PlanLookup.Scheduled) "Freeform instead" else "Start freeform"
            item { WideChip(label, onClick = { onStart(null) }, secondary = lookup is PlanLookup.Scheduled) }
        }
    }
}

/** Adds an exercise: only those the user's limitations allow are on the menu. */
@Composable
fun ExercisePickerScreen(
    menu: List<MenuExercise>,
    onPick: (exerciseId: String) -> Unit,
    onCancel: () -> Unit,
) {
    ListScreen {
        item { Centered("Add exercise", MaterialTheme.typography.title3) }
        items(menu) { item ->
            WideChip(
                label = item.exercise.name,
                secondaryLabel = item.lastTopSet?.let(::describe),
                onClick = { onPick(item.exercise.id) },
                secondary = true,
            )
        }
        item { WideChip("Cancel", onClick = onCancel, secondary = true) }
    }
}

/** The session's totals, PRs and pain reports, and whether it's on its way to the phone. */
@Composable
fun SummaryScreen(
    summary: SessionSummary,
    handedOver: Boolean,
    onDone: () -> Unit,
) {
    ListScreen {
        item { Centered("Workout done", MaterialTheme.typography.title2) }
        item {
            Centered(
                "${summary.totalSets} sets · ${formatKg(summary.totalVolumeKg)} kg · ${summary.durationSeconds / 60} min",
                MaterialTheme.typography.body1,
            )
        }
        if (summary.newPrs.isNotEmpty()) {
            val names = summary.newPrs.map { it.exerciseName }.distinct()
            item { Centered("New PRs: ${names.joinToString()}", MaterialTheme.typography.body1) }
        }
        if (summary.painExercises.isNotEmpty()) {
            item {
                Centered(
                    "Pain reported: ${summary.painExercises.joinToString()}. Review it in Forge on your phone.",
                    MaterialTheme.typography.body2,
                    secondary = true,
                )
            }
        }
        item {
            Centered(
                if (handedOver) "Syncing to your phone." else "Saved on the watch.",
                MaterialTheme.typography.caption1,
                secondary = true,
            )
        }
        item { WideChip("Done", onClick = onDone) }
    }
}

@Composable
private fun ListScreen(content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(state = rememberScalingLazyListState(), modifier = Modifier.fillMaxSize(), content = content)
    }
}

internal fun describe(set: SetEntry): String = "${formatKg(set.weightKg)} kg × ${set.reps}"
