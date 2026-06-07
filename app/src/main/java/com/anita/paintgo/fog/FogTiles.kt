package com.anita.paintgo.fog

import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.data.ChunkFog
import com.anita.paintgo.data.ChunkId
import com.anita.paintgo.data.FOG_COVERAGE_RADIUS_M
import com.anita.paintgo.data.chunkBbox
import com.anita.paintgo.data.ensureChunkBackfill
import com.anita.paintgo.data.forSelfInViewport
import com.anita.paintgo.data.tilesInViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.Geometry
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.cos

// Render-side fog tile cache. Replaces the old "union every viewport point each frame"
// with "assemble cached per-chunk cleared geometries, recomputing only the dirty visible
// tiles." Recompute cost is bounded by one chunk's local point density (not total
// history); the zoomed-out whole-city view just unions a few hundred cached polygons.
object FogTiles {

    private const val METERS_PER_DEG_LAT = 111_320.0

    /**
     * Gather the cleared geometries (lat/lng) for all tiles intersecting [bounds],
     * recomputing any that are dirty (and persisting the result). Off-screen dirty tiles
     * are left alone — they recompute lazily the first time they're viewed.
     */
    suspend fun clearedTilesForViewport(
        context: android.content.Context,
        bounds: LatLngBounds,
    ): List<Geometry> = withContext(Dispatchers.Default) {
        val db = AppDatabase.get(context)
        // Seed the chunk caches from all history on first run (covers area walked before
        // the cache existed) — runs once, no-op thereafter.
        db.ensureChunkBackfill(context)
        val fogDao = db.chunkFogDao()
        val tiles = fogDao.tilesInViewport(
            latSouth = bounds.latitudeSouth,
            latNorth = bounds.latitudeNorth,
            lngWest = bounds.longitudeWest,
            lngEast = bounds.longitudeEast,
        )
        val out = ArrayList<Geometry>(tiles.size)
        for (t in tiles) {
            val geom = if (t.dirty) {
                recomputeTile(db, ChunkId(t.band, t.chunkX, t.chunkY))
            } else {
                t.clearedWkb?.let { wkbToGeometry(it) }
            }
            if (geom != null) out.add(geom)
        }
        out
    }

    /** Recompute one tile's cleared union from its points + halo, persist it, return it. */
    private suspend fun recomputeTile(db: AppDatabase, id: ChunkId): Geometry? {
        val bbox = chunkBbox(id)
        // Halo = just the coverage radius: only points within FOG_COVERAGE_RADIUS_M of the
        // rect can clear area inside it. Keeping this tight (not + MAX_RUN_SEGMENT_M) is
        // what makes a tile recompute cheap — otherwise a 2km tile pulls a ~8.5km box of
        // points and the tiling buys nothing. Trade-off: a long transit segment that
        // crosses the tile with both endpoints outside the rect won't paint here.
        val bufferM = FOG_COVERAGE_RADIUS_M
        val cosLat = cos(Math.toRadians((bbox.latS + bbox.latN) / 2.0)).coerceAtLeast(0.01)
        val latBuf = bufferM / METERS_PER_DEG_LAT
        val lngBuf = bufferM / (METERS_PER_DEG_LAT * cosLat)
        val points = db.locationPointDao().forSelfInViewport(
            latSouth = bbox.latS - latBuf,
            latNorth = bbox.latN + latBuf,
            lngWest = bbox.lngW - lngBuf,
            lngEast = bbox.lngE + lngBuf,
        )
        val chunkBounds = LatLngBounds.from(bbox.latN, bbox.lngE, bbox.latS, bbox.lngW)
        val cleared = computeChunkClearedUnion(
            points = points,
            chunkBounds = chunkBounds,
            radiusMeters = FOG_COVERAGE_RADIUS_M,
            segmentsPerQuadrant = FOG_TILE_SEGMENTS_PER_QUADRANT,
            simplifyToleranceDeg = FOG_TILE_SIMPLIFY_DEG,
        )
        db.chunkFogDao().upsert(
            ChunkFog(id.band, id.chunkX, id.chunkY, cleared?.let { geometryToWkb(it) }, dirty = false)
        )
        return cleared
    }
}
