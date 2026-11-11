package com.forge.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.core.onboarding.OnboardingGate
import com.forge.mobile.data.ForgeRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Today (blueprint Phase 1 phone scope): the scheduled session, a readiness
 * placeholder, and the active health constraints — all read through :core logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: ForgeRepository,
    onOpenHistory: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val routine = remember(today) { repository.routineFor(today) }
    val gate = remember(repository.healthProfile) {
        repository.healthProfile?.let { OnboardingGate.evaluate(it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Forge") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Readiness placeholder — the score itself needs Health Connect reads.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Readiness", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "—",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Sleep, HRV and resting HR arrive via Health Connect reads in Phase 2; " +
                            "the score is computed in :core against your own baseline (blueprint 3.8/3.15).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // TODO(Phase 2): repository.heartTrends() -> core ReadinessScore -> render dial.
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Today's session", style = MaterialTheme.typography.titleMedium)
                    if (routine == null) {
                        Text(
                            "Rest day — nothing scheduled.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(routine.name, style = MaterialTheme.typography.headlineSmall)
                        routine.exercises.forEach { prescribed ->
                            Text(
                                "• ${prescribed.exercise.name} — ${prescribed.targetSets} × " +
                                    "${prescribed.repRange.first}–${prescribed.repRange.last} @ ${prescribed.targetWeightKg} kg",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            "Start and log this session on your watch; it syncs back here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (gate != null && (gate.constraints.exerciseExclusions.isNotEmpty() || gate.constraints.intensityMayOnlyScaleDown)) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Active constraints", style = MaterialTheme.typography.titleMedium)
                        gate.constraints.exerciseExclusions.forEach { exclusion ->
                            Text(
                                "• ${exclusion.area.name.lowercase().replace('_', ' ')}: avoid " +
                                    "${exclusion.excludedMovements.joinToString(", ").lowercase()} — " +
                                    "using ${exclusion.substitutions.first().lowercase()} instead",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (gate.constraints.intensityMayOnlyScaleDown) {
                            Text(
                                "• Working heart rate capped at ${gate.constraints.heartRateCapPercentOfMax}% of max; intensity scales down only.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Button(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("View workout history")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
