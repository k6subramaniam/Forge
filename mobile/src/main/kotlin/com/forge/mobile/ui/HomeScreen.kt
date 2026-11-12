package com.forge.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.forge.core.model.HealthProfile
import com.forge.core.onboarding.OnboardingGate
import com.forge.core.sync.PlanLookup
import com.forge.mobile.data.ForgeRepository
import com.forge.mobile.health.HealthConnectWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Today (blueprint Phase 1 phone scope): the scheduled session, a readiness
 * placeholder, and the active health constraints — all read through :core logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: ForgeRepository,
    healthConnect: HealthConnectWriter,
    onOpenHistory: () -> Unit,
    onHealthConnectChanged: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    // The same plan the watch is sent, so phone and watch never disagree on
    // weights. Rebuilt whenever history changes: a session arriving from the
    // watch moves the progression, here as on the watch.
    val todaysPlan by produceState<PlanLookup?>(initialValue = null, repository, today) {
        repository.observeHistory().collect {
            value = repository.plans(today, ZoneId.systemDefault(), Instant.now()).lookup(today)
        }
    }
    val healthProfile by produceState<HealthProfile?>(initialValue = null, repository) {
        value = repository.healthProfile()
    }
    val gate = remember(healthProfile) { healthProfile?.let { OnboardingGate.evaluate(it) } }

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
                    when (val lookup = todaysPlan) {
                        null -> Unit
                        is PlanLookup.Scheduled -> {
                            Text(lookup.plan.routineName, style = MaterialTheme.typography.headlineSmall)
                            lookup.plan.exercises.forEach { planned ->
                                val p = planned.prescription
                                Text(
                                    "• ${p.exercise.name} — ${p.targetSets} × ${p.repRange.first}–${p.repRange.last}" +
                                        " @ ${formatKg(p.targetWeightKg)} kg",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                "Start this session on your watch. It comes back here when you finish.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PlanLookup.Unscheduled, PlanLookup.OutOfDate -> Text(
                            "Nothing scheduled today. You can still start a freeform session on your watch.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        is PlanLookup.Blocked -> Text(lookup.reason, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            HealthConnectCard(healthConnect, onHealthConnectChanged)

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

/**
 * Health Connect status and the permission request (blueprint 3.6). Hidden on
 * devices without Health Connect. Workouts are stored in Forge either way.
 */
@Composable
private fun HealthConnectCard(healthConnect: HealthConnectWriter, onChanged: () -> Unit) {
    if (!remember { healthConnect.isAvailable() }) return
    var refresh by remember { mutableIntStateOf(0) }
    val granted by produceState<Boolean?>(initialValue = null, refresh) { value = healthConnect.hasPermissions() }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refresh++
        // Workouts stored before the permission are waiting to be written.
        onChanged()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            when (granted) {
                null -> Unit
                true -> Text(
                    "Finished workouts are written to Health Connect, so Samsung Health sees them too.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                false -> {
                    Text(
                        "Allow Forge to write workouts so they show up in Samsung Health.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { request.launch(HealthConnectWriter.REQUIRED_PERMISSIONS) }) {
                        Text("Connect Health Connect")
                    }
                }
            }
        }
    }
}

private fun formatKg(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString()
