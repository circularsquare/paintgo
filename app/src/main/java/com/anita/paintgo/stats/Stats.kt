package com.anita.paintgo.stats

import com.anita.paintgo.regions.RegionStat

// Display model for the stats screen. Produced by StatsEngine.refresh (incremental,
// chunk-cache backed) and seeded/persisted by StatsCache.
data class WalkStats(
    val totalKm: Double,
    val coveredAreaM2: Double,
    val regionCoverage: List<RegionStat>,
) {
    companion object {
        val EMPTY = WalkStats(0.0, 0.0, emptyList())
    }
}
