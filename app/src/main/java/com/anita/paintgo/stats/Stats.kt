package com.anita.paintgo.stats

import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.data.FOG_COVERAGE_RADIUS_M
import com.anita.paintgo.regions.Region
import com.anita.paintgo.regions.RegionStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class WalkStats(
    val totalKm: Double,
    val coveredAreaM2: Double,
    val regionCoverage: List<RegionStat>,
) {
    companion object {
        val EMPTY = WalkStats(0.0, 0.0, emptyList())
    }
}

private const val EARTH_RADIUS_KM = 6371.0088
private const val METERS_PER_DEG_LAT = 111_320.0
private const val CELL_SIZE_M = 10.0
private const val CELL_AREA_M2 = CELL_SIZE_M * CELL_SIZE_M
private const val CELL_LAT_DEG = CELL_SIZE_M / METERS_PER_DEG_LAT

suspend fun computeStats(
    db: AppDatabase,
    regions: List<Region> = emptyList(),
): WalkStats = withContext(Dispatchers.Default) {
    val points = db.locationPointDao().allForSelfOnce()
    if (points.isEmpty()) return@withContext WalkStats.EMPTY

    var totalKm = 0.0
    var prev = points[0]
    for (i in 1 until points.size) {
        val curr = points[i]
        if (curr.sessionId == prev.sessionId) {
            totalKm += haversineKm(prev.lat, prev.lng, curr.lat, curr.lng)
        }
        prev = curr
    }

    // "Covered" matches what the fog clears: a 10m cell counts as covered iff its
    // center sits within FOG_COVERAGE_RADIUS_M of some recorded point. This is the
    // cheap proxy for "area of the fog union" — accurate enough that reported m²
    // roughly equals the white-on-map, and immune to stationary GPS jitter (no
    // single point's coverage halo extends past one cell unless it actually moves).
    val cells = HashSet<Long>()
    val radiusM2 = FOG_COVERAGE_RADIUS_M * FOG_COVERAGE_RADIUS_M
    val cellRange = ceil(FOG_COVERAGE_RADIUS_M / CELL_SIZE_M).toInt()
    for (p in points) {
        val cosLat = cos(Math.toRadians(p.lat))
        val mPerDegLng = METERS_PER_DEG_LAT * cosLat
        val cellLngDeg = CELL_SIZE_M / mPerDegLng
        val cy0 = floor(p.lat / CELL_LAT_DEG).toLong()
        val cx0 = floor(p.lng / cellLngDeg).toLong()
        for (dy in -cellRange..cellRange) {
            for (dx in -cellRange..cellRange) {
                val cy = cy0 + dy
                val cx = cx0 + dx
                val centerLat = cy * CELL_LAT_DEG + CELL_LAT_DEG / 2.0
                val centerLng = cx * cellLngDeg + cellLngDeg / 2.0
                val dLatM = (centerLat - p.lat) * METERS_PER_DEG_LAT
                val dLngM = (centerLng - p.lng) * mPerDegLng
                if (dLatM * dLatM + dLngM * dLngM <= radiusM2) {
                    cells.add((cy shl 32) or (cx and 0xFFFFFFFFL))
                }
            }
        }
    }

    WalkStats(
        totalKm = totalKm,
        coveredAreaM2 = cells.size * CELL_AREA_M2,
        regionCoverage = computeRegionCoverage(cells, regions),
    )
}

// Brute force: bbox-prefilter each cell against each region, PIP on survivors.
// PreparedGeometry makes PIP ~µs; with NYC-scale data (≲100k cells × ≲10 regions)
// this is sub-second. Optimize when it isn't.
private fun computeRegionCoverage(
    cells: Set<Long>,
    regions: List<Region>,
): List<RegionStat> {
    if (regions.isEmpty() || cells.isEmpty()) return emptyList()
    val factory = GeometryFactory()
    val out = mutableListOf<RegionStat>()
    for (region in regions) {
        var hit = 0
        for (key in cells) {
            val (lat, lng) = unpackCellCenter(key)
            if (lat < region.bboxS || lat > region.bboxN ||
                lng < region.bboxW || lng > region.bboxE) continue
            val pt = factory.createPoint(Coordinate(lng, lat))
            if (region.prepared.contains(pt)) hit++
        }
        if (hit == 0) continue
        val pct = if (region.areaM2 > 0) hit * CELL_AREA_M2 / region.areaM2 * 100.0 else 0.0
        out += RegionStat(region.key, region.kind, region.name, pct)
    }
    return out.sortedWith(compareBy({ it.kind.ordinal }, { -it.percentCovered }))
}

// Pack a 10m cell index as a single Long. cos(lat) is per-point — at the scale of
// a single user's walks the lng-cell width barely varies, so neighboring points
// at the same physical spot always hash to the same cell. Physical area per cell
// stays 100 m² by construction regardless of latitude.
private fun cellKey(lat: Double, lng: Double): Long {
    val cellLngDeg = CELL_SIZE_M / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)))
    val cy = floor(lat / CELL_LAT_DEG).toLong()
    val cx = floor(lng / cellLngDeg).toLong()
    return (cy shl 32) or (cx and 0xFFFFFFFFL)
}

private fun unpackCellCenter(key: Long): Pair<Double, Double> {
    val cy = (key shr 32).toInt()
    val cx = key.toInt() // lower 32 bits, sign-extended — inverse of (cx and 0xFFFFFFFFL)
    val lat = cy * CELL_LAT_DEG + CELL_LAT_DEG / 2.0
    val cellLngDeg = CELL_SIZE_M / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)))
    val lng = cx * cellLngDeg + cellLngDeg / 2.0
    return lat to lng
}

private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val dφ = Math.toRadians(lat2 - lat1)
    val dλ = Math.toRadians(lng2 - lng1)
    val a = sin(dφ / 2) * sin(dφ / 2) +
        cos(φ1) * cos(φ2) * sin(dλ / 2) * sin(dλ / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_KM * c
}
