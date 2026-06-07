package com.anita.paintgo.stats

import android.content.Context
import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.data.ChunkCoverage
import com.anita.paintgo.data.ChunkCoverageDao
import com.anita.paintgo.data.ChunkId
import com.anita.paintgo.data.ChunkRegionCoverage
import com.anita.paintgo.data.ChunkRegionCoverageDao
import com.anita.paintgo.data.FOG_COVERAGE_RADIUS_M
import com.anita.paintgo.data.LocationPoint
import com.anita.paintgo.data.LocationPointDao
import com.anita.paintgo.data.RegionCellTotal
import com.anita.paintgo.data.SessionStat
import com.anita.paintgo.data.chunkBbox
import com.anita.paintgo.data.chunkIdOfLatLng
import com.anita.paintgo.data.forSelfInViewport
import com.anita.paintgo.data.ensureChunkBackfill
import com.anita.paintgo.regions.Region
import com.anita.paintgo.regions.RegionRegistry
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

// Incremental stats engine backed by the spatial chunk cache (ChunkCoverage /
// ChunkRegionCoverage / SessionStat, see Chunk.kt + Entities.kt). Replaces the old
// O(all points) computeStats: each refresh recomputes only the chunks (and sessions)
// marked dirty since the last pass, then sums the cached rows. The covered-cell and
// region-PIP math mirrors the retired computeStats so reported numbers are unchanged.
object StatsEngine {

    private const val EARTH_RADIUS_KM = 6371.0088
    internal const val METERS_PER_DEG_LAT = 111_320.0
    internal const val CELL_SIZE_M = 10.0
    internal const val CELL_AREA_M2 = CELL_SIZE_M * CELL_SIZE_M
    internal const val CELL_LAT_DEG = CELL_SIZE_M / METERS_PER_DEG_LAT

    suspend fun refresh(context: Context, db: AppDatabase): WalkStats =
        withContext(Dispatchers.Default) {
            val cDao = db.chunkCoverageDao()
            val crDao = db.chunkRegionCoverageDao()
            val sDao = db.sessionStatDao()
            val lpDao = db.locationPointDao()

            db.ensureChunkBackfill(context)

            // Load region geometry once and reuse it for both tallying and display. This
            // may re-parse bundled GeoJSON the first time after an app update (cache is
            // versionCode-keyed); until it succeeds, regionsReady is false and we compute
            // everything *except* region tallies, leaving those chunks dirty so a later
            // refresh retries — region %s are never silently lost, just deferred.
            val regions = RegionRegistry.regions(context)
            val regionsReady = regions.isNotEmpty()

            for (cc in cDao.dirtyChunks()) {
                recomputeChunk(
                    lpDao, cDao, crDao,
                    ChunkId(cc.band, cc.chunkX, cc.chunkY), regions, regionsReady,
                )
            }

            for (ss in sDao.dirtySessions()) {
                val km = sessionDistanceKm(lpDao.bySession(ss.sessionId))
                sDao.upsert(SessionStat(ss.sessionId, km, dirty = false))
            }

            WalkStats(
                totalKm = sDao.totalDistanceKm(),
                coveredAreaM2 = cDao.totalCoveredCells() * CELL_AREA_M2,
                regionCoverage = buildRegionStats(regions, crDao.regionTotals()),
            )
        }

    // Recompute one chunk's covered-cell count + per-region tallies from its points plus
    // a FOG_COVERAGE_RADIUS_M halo of neighbor points (so cells near the chunk edge see
    // coverage from across the boundary). A 10m cell is counted iff its center lands in
    // *this* chunk (unique ownership → summing chunk counts never double-counts).
    private suspend fun recomputeChunk(
        lpDao: LocationPointDao,
        cDao: ChunkCoverageDao,
        crDao: ChunkRegionCoverageDao,
        id: ChunkId,
        regions: List<Region>,
        regionsReady: Boolean,
    ) {
        val bbox = chunkBbox(id)
        val cosLat = cos(Math.toRadians((bbox.latS + bbox.latN) / 2.0)).coerceAtLeast(0.01)
        val latBuf = FOG_COVERAGE_RADIUS_M / METERS_PER_DEG_LAT
        val lngBuf = FOG_COVERAGE_RADIUS_M / (METERS_PER_DEG_LAT * cosLat)
        val points = lpDao.forSelfInViewport(
            latSouth = bbox.latS - latBuf,
            latNorth = bbox.latN + latBuf,
            lngWest = bbox.lngW - lngBuf,
            lngEast = bbox.lngE + lngBuf,
        )

        val cells = HashSet<Long>()
        val radiusM2 = FOG_COVERAGE_RADIUS_M * FOG_COVERAGE_RADIUS_M
        val cellRange = ceil(FOG_COVERAGE_RADIUS_M / CELL_SIZE_M).toInt()
        for (p in points) {
            val cosLatP = cos(Math.toRadians(p.lat))
            val mPerDegLng = METERS_PER_DEG_LAT * cosLatP
            val cellLngDeg = CELL_SIZE_M / mPerDegLng
            val cy0 = floor(p.lat / CELL_LAT_DEG).toLong()
            val cx0 = floor(p.lng / cellLngDeg).toLong()
            for (dy in -cellRange..cellRange) {
                for (dx in -cellRange..cellRange) {
                    val cyc = cy0 + dy
                    val cxc = cx0 + dx
                    val centerLat = cyc * CELL_LAT_DEG + CELL_LAT_DEG / 2.0
                    val centerLng = cxc * cellLngDeg + cellLngDeg / 2.0
                    val dLatM = (centerLat - p.lat) * METERS_PER_DEG_LAT
                    val dLngM = (centerLng - p.lng) * mPerDegLng
                    if (dLatM * dLatM + dLngM * dLngM > radiusM2) continue
                    // Unique ownership: skip cells whose center belongs to another chunk.
                    if (chunkIdOfLatLng(centerLat, centerLng) != id) continue
                    cells.add((cyc shl 32) or (cxc and 0xFFFFFFFFL))
                }
            }
        }

        // Coverage (area) needs no geometry, so always write it. Keep the chunk dirty
        // when region geometry isn't loaded yet, so its region tally gets retried — and
        // leave its existing region rows untouched (last-known %s stay) rather than
        // wiping them to empty.
        cDao.upsert(ChunkCoverage(id.band, id.chunkX, id.chunkY, cells.size, dirty = !regionsReady))
        if (!regionsReady) return

        // Region tallies: PIP each owned cell center against regions whose bbox overlaps
        // the chunk. Replaces this chunk's rows wholesale so a shrink (after deletes) is
        // reflected. A region with any row here counts as "visited".
        crDao.deleteForChunk(id.band, id.chunkX, id.chunkY)
        if (cells.isEmpty()) return
        val candidates = regions.filter { r ->
            !(r.bboxN < bbox.latS || r.bboxS > bbox.latN ||
                r.bboxE < bbox.lngW || r.bboxW > bbox.lngE)
        }
        if (candidates.isEmpty()) return
        val factory = GeometryFactory()
        val counts = HashMap<String, Int>()
        for (key in cells) {
            val (lat, lng) = unpackCellCenter(key)
            for (r in candidates) {
                if (lat < r.bboxS || lat > r.bboxN || lng < r.bboxW || lng > r.bboxE) continue
                val pt = factory.createPoint(Coordinate(lng, lat))
                if (r.prepared.contains(pt)) counts[r.key] = (counts[r.key] ?: 0) + 1
            }
        }
        if (counts.isNotEmpty()) {
            crDao.insertAll(
                counts.map { (k, v) -> ChunkRegionCoverage(id.band, id.chunkX, id.chunkY, k, v) }
            )
        }
    }

    private fun buildRegionStats(
        regions: List<Region>,
        totals: List<RegionCellTotal>,
    ): List<RegionStat> {
        if (totals.isEmpty() || regions.isEmpty()) return emptyList()
        val byKey = regions.associateBy { it.key }
        val out = ArrayList<RegionStat>(totals.size)
        for (t in totals) {
            val r = byKey[t.regionKey] ?: continue
            val pct = if (r.areaM2 > 0) t.cells * CELL_AREA_M2 / r.areaM2 * 100.0 else 0.0
            out += RegionStat(r.key, r.kind, r.name, pct)
        }
        return out.sortedWith(compareBy({ it.kind.ordinal }, { -it.percentCovered }))
    }

    private fun sessionDistanceKm(points: List<LocationPoint>): Double {
        if (points.size < 2) return 0.0
        var km = 0.0
        for (i in 1 until points.size) {
            km += haversineKm(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
        }
        return km
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
}
