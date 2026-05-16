package com.anita.paintgo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.stats.WalkStats
import com.anita.paintgo.stats.computeStats
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isRecording by LocationService.running.collectAsState()
    // Recompute on entry, and again when recording stops so a just-finished session is reflected.
    var stats by remember { mutableStateOf<WalkStats?>(null) }
    LaunchedEffect(isRecording) {
        stats = computeStats(AppDatabase.get(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val s = stats
        if (s == null) {
            Text("Loading…", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Row("Total distance", formatKm(s.totalKm))
        HorizontalDivider()
        Row("Sessions", s.sessionCount.toString())
        HorizontalDivider()
        Row("Longest session", formatKm(s.longestSessionKm))
        HorizontalDivider()
        Row("Total time", formatDuration(s.totalDurationMs))
        HorizontalDivider()
        Row("Points recorded", s.totalPoints.toString())
        if (isRecording) {
            HorizontalDivider()
            Text(
                "Recording in progress — stats update when it ends.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatKm(km: Double): String =
    if (km < 1.0) "${"%.0f".format(km * 1000)} m"
    else "${"%.2f".format(km)} km"

private fun formatDuration(ms: Long): String {
    val d = ms.milliseconds
    val h = d.inWholeHours
    val m = d.inWholeMinutes % 60
    val s = d.inWholeSeconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
