package com.forge.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.mobile.data.ForgeRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Session history (blueprint 3.3): every past session with set count and total volume. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    repository: ForgeRepository,
    onBack: () -> Unit,
) {
    // null until the first read from the database lands, so the empty state
    // never flashes before real history appears.
    val historyFlow = remember(repository) { repository.observeHistory() }
    val history by historyFlow.collectAsState(initial = null)

    Scaffold(
        topBar = { TopAppBar(title = { Text("History") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Back to Today")
            }
            if (history?.isEmpty() == true) {
                Text(
                    "No sessions yet — your first workout will appear here.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(history.orEmpty(), key = { it.session.id }) { item ->
                    val session = item.session
                    val sets = item.sets
                    val completedSets = sets.count { it.completed }
                    val totalVolume = sets.filter { it.completed }.sumOf { it.volumeKg }
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                session.routineName ?: "Freeform session",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                formatInstant(session.startTime),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "$completedSets sets · ${"%,.0f".format(totalVolume)} kg total volume",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            session.maxHeartRateBpm?.let { maxHr ->
                                Text(
                                    "Max HR $maxHr bpm" +
                                        (session.avgHeartRateBpm?.let { " · avg $it bpm" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")

private fun formatInstant(instant: Instant): String =
    DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()))
