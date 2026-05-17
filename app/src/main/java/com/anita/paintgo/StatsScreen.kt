package com.anita.paintgo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.log10
import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.regions.RegionKind
import com.anita.paintgo.regions.RegionRegistry
import com.anita.paintgo.regions.RegionStat
import com.anita.paintgo.regions.VisitedRegions
import com.anita.paintgo.stats.StatsCache
import com.anita.paintgo.stats.WalkStats
import com.anita.paintgo.stats.computeStats

@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Live updates: same selfPointCount Flow the map's fog uses. Each new fix bumps
    // the count, this effect re-runs, stats recompute. computeStats runs on
    // Dispatchers.Default so the per-tick cost (~1s at NYC scale) doesn't block UI.
    val pointTickFlow = remember(context) {
        AppDatabase.get(context).locationPointDao().selfPointCount()
    }
    val pointTick by pointTickFlow.collectAsState(initial = 0L)
    // Seed from the on-disk cache so the screen renders last-known values on the
    // first frame. Fast pass + full pass below overwrite this with fresh numbers
    // as they finish.
    var stats by remember { mutableStateOf<WalkStats?>(StatsCache.loadBlocking(context)) }
    // Visited region keys, loaded once from prefs and updated by the background full
    // scan. Held in state so the fast pass re-runs whenever the set grows.
    var visited by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) {
        visited = VisitedRegions.load(context)
    }

    // Fast pass: only loads geometry + PIPs the regions we already know are visited
    // (plus nyc-boroughs, force-loaded by RegionRegistry for synthesis). Re-runs on
    // every new point and whenever the background scan grows [visited].
    LaunchedEffect(pointTick, visited) {
        val v = visited ?: return@LaunchedEffect
        val db = AppDatabase.get(context)
        val fastRegions = RegionRegistry.regionsFromCache(context) { src, eid ->
            VisitedRegions.key(src, eid) in v
        }
        val fresh = computeStats(db, fastRegions)
        stats = fresh
        StatsCache.save(context, fresh)
    }

    // Background full scan: walks every bundled region once per screen open to catch
    // regions the user has newly entered. Updates the persistent set so the next fast
    // pass picks them up. Only one slow scan per screen open — debounced by keying on
    // Unit, not pointTick, so we don't re-scan all 250 countries on every GPS fix.
    LaunchedEffect(Unit) {
        val db = AppDatabase.get(context)
        val full = RegionRegistry.regions(context)
        if (full.isEmpty()) return@LaunchedEffect
        val fullStats = computeStats(db, full)
        stats = fullStats
        StatsCache.save(context, fullStats)
        val touched = fullStats.regionCoverage.mapTo(HashSet()) { it.key }
        val current = visited ?: VisitedRegions.load(context)
        if (current != touched) {
            VisitedRegions.save(context, touched)
            visited = touched
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
        Row("Area covered", formatArea(s.coveredAreaM2))

        // Group region coverage by kind. Already sorted (kind asc, pct desc) in computeStats.
        val byKind = s.regionCoverage.groupBy { it.kind }
        RegionKind.entries.forEach { kind ->
            val items = byKind[kind].orEmpty()
            if (items.isEmpty()) return@forEach
            Text(
                sectionLabel(kind),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            items.forEach { rs ->
                HorizontalDivider()
                RegionRow(rs)
            }
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

@Composable
private fun RegionRow(rs: RegionStat) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(rs.name, style = MaterialTheme.typography.bodyLarge)
            Text(formatPercent(rs.percentCovered), style = MaterialTheme.typography.titleMedium)
        }
        // Hand-rolled bar so very-small / very-near-full percentages render truthfully.
        // M3's LinearProgressIndicator has a stop-indicator dot and a minimum visible
        // thumb that bias the rendering at the extremes.
        val fraction = (rs.percentCovered / 100.0).coerceIn(0.0, 1.0).toFloat()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFFE8E8E8))
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(Color(0xFF7AB87A))
                )
            }
        }
    }
}

private fun sectionLabel(kind: RegionKind): String = when (kind) {
    RegionKind.COUNTRY -> "Countries"
    RegionKind.STATE -> "States"
    RegionKind.CITY -> "Cities"
    RegionKind.NEIGHBORHOOD -> "Neighborhoods"
}

private fun formatKm(km: Double): String =
    if (km < 1.0) "${"%.0f".format(km * 1000)} m"
    else "${"%.2f".format(km)} km"

private fun formatArea(m2: Double): String =
    if (m2 < 10_000.0) "${"%.0f".format(m2)} m²"
    else "${"%.3f".format(m2 / 1_000_000.0)} km²"

// Adaptive decimal precision, never scientific — show all the leading zeros.
// For p ≈ 3.1e-9 this prints "0.0000000031%": leading zeros = floor(-log10(p)),
// plus 2 significant digits.
private fun formatPercent(p: Double): String = when {
    p >= 1.0 -> "%.2f%%".format(p)
    p >= 0.01 -> "%.3f%%".format(p)
    p <= 0.0 -> "0%"
    else -> {
        val decimals = (-floor(log10(p))).toInt() + 1
        "%.${decimals}f%%".format(p)
    }
}
