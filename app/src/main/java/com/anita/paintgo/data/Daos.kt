package com.anita.paintgo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OwnerDao {
    @Query("SELECT * FROM Owner WHERE isSelf = 1 LIMIT 1")
    suspend fun getSelf(): Owner?

    @Query("SELECT * FROM Owner ORDER BY id ASC")
    fun all(): Flow<List<Owner>>

    @Insert
    suspend fun insert(owner: Owner): Long
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Query("SELECT * FROM Session WHERE id = :id")
    suspend fun getById(id: Long): Session?

    @Query("SELECT * FROM Session WHERE ownerId = :ownerId ORDER BY startTime DESC")
    fun byOwner(ownerId: Long): Flow<List<Session>>

    @Query("""
        SELECT s.* FROM Session s
        JOIN Owner o ON s.ownerId = o.id
        WHERE o.isSelf = 1
        ORDER BY s.startTime ASC
    """)
    suspend fun allForSelf(): List<Session>
}

@Dao
interface LocationPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: LocationPoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<LocationPoint>)

    @Query("SELECT * FROM LocationPoint WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun bySession(sessionId: Long): List<LocationPoint>

    @Query("""
        SELECT COUNT(*) FROM LocationPoint lp
        JOIN Session s ON lp.sessionId = s.id
        JOIN Owner o ON s.ownerId = o.id
        WHERE o.isSelf = 1
    """)
    fun selfPointCount(): Flow<Long>

    @Query("""
        SELECT lp.* FROM LocationPoint lp
        JOIN Session s ON lp.sessionId = s.id
        JOIN Owner o ON s.ownerId = o.id
        WHERE o.isSelf = 1
        ORDER BY lp.sessionId ASC, lp.timestamp ASC
    """)
    suspend fun allForSelfOnce(): List<LocationPoint>

    // Cell-range bbox query, scoped to one latitude band. cellY's meters-per-unit
    // depends on the band, so callers issue one of these per band intersecting the
    // viewport (see forSelfInViewport). No dedicated index covers (band, cellX, cellY)
    // yet, so this scans LocationPoint — fast for hundreds-of-thousands of rows;
    // revisit if it starts to drag.
    @Query("""
        SELECT lp.* FROM LocationPoint lp
        JOIN Session s ON lp.sessionId = s.id
        JOIN Owner o ON s.ownerId = o.id
        WHERE o.isSelf = 1
          AND lp.band = :band
          AND lp.cellX BETWEEN :cellXMin AND :cellXMax
          AND lp.cellY BETWEEN :cellYMin AND :cellYMax
        ORDER BY lp.sessionId ASC, lp.timestamp ASC
    """)
    suspend fun forSelfInBandCellRange(
        band: Int,
        cellXMin: Int,
        cellXMax: Int,
        cellYMin: Int,
        cellYMax: Int,
    ): List<LocationPoint>

    @Query("DELETE FROM LocationPoint WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM LocationPoint WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<LocationPoint>

    // Neighbor lookups for the delete-mode segment preview. Index on sessionId is
    // already present; the timestamp comparison + LIMIT 1 keeps each call to a
    // bounded handful of rows.
    @Query("""
        SELECT * FROM LocationPoint
        WHERE sessionId = :sessionId AND timestamp < :timestamp
        ORDER BY timestamp DESC LIMIT 1
    """)
    suspend fun prevInSession(sessionId: Long, timestamp: Long): LocationPoint?

    @Query("""
        SELECT * FROM LocationPoint
        WHERE sessionId = :sessionId AND timestamp > :timestamp
        ORDER BY timestamp ASC LIMIT 1
    """)
    suspend fun nextInSession(sessionId: Long, timestamp: Long): LocationPoint?
}

@Dao
interface ChunkCoverageDao {
    // Used by recompute to write the final count and clear dirty.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(coverage: ChunkCoverage)

    // Used by dirty-marking: create the row if absent without clobbering an existing
    // count (IGNORE keeps the existing row); a following markDirty flips its flag.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(coverage: ChunkCoverage)

    @Query("UPDATE ChunkCoverage SET dirty = 1 WHERE band = :band AND chunkX = :chunkX AND chunkY = :chunkY")
    suspend fun markDirty(band: Int, chunkX: Int, chunkY: Int)

    @Query("SELECT * FROM ChunkCoverage WHERE dirty = 1")
    suspend fun dirtyChunks(): List<ChunkCoverage>

    @Query("SELECT COALESCE(SUM(coveredCellCount), 0) FROM ChunkCoverage")
    suspend fun totalCoveredCells(): Long
}

class RegionCellTotal(val regionKey: String, val cells: Long)

@Dao
interface ChunkRegionCoverageDao {
    @Query("DELETE FROM ChunkRegionCoverage WHERE band = :band AND chunkX = :chunkX AND chunkY = :chunkY")
    suspend fun deleteForChunk(band: Int, chunkX: Int, chunkY: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ChunkRegionCoverage>)

    @Query("SELECT regionKey, SUM(cellCount) AS cells FROM ChunkRegionCoverage GROUP BY regionKey")
    suspend fun regionTotals(): List<RegionCellTotal>
}

@Dao
interface ChunkFogDao {
    // Written by a tile recompute: stores the cleared geometry and clears dirty.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fog: ChunkFog)

    // Dirty-marking: create the row if absent (keeps any existing clearedWkb on conflict);
    // a following markDirty flips its flag.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(fog: ChunkFog)

    @Query("UPDATE ChunkFog SET dirty = 1 WHERE band = :band AND chunkX = :chunkX AND chunkY = :chunkY")
    suspend fun markDirty(band: Int, chunkX: Int, chunkY: Int)

    @Query("""
        SELECT * FROM ChunkFog
        WHERE band = :band
          AND chunkX BETWEEN :chunkXMin AND :chunkXMax
          AND chunkY BETWEEN :chunkYMin AND :chunkYMax
    """)
    suspend fun inBandRange(
        band: Int,
        chunkXMin: Int,
        chunkXMax: Int,
        chunkYMin: Int,
        chunkYMax: Int,
    ): List<ChunkFog>

    // Cheap dirty check (no blobs) so the render path can skip re-assembling the fog when
    // nothing visible changed.
    @Query("""
        SELECT COUNT(*) FROM ChunkFog
        WHERE dirty = 1 AND band = :band
          AND chunkX BETWEEN :chunkXMin AND :chunkXMax
          AND chunkY BETWEEN :chunkYMin AND :chunkYMax
    """)
    suspend fun dirtyCountInBandRange(
        band: Int,
        chunkXMin: Int,
        chunkXMax: Int,
        chunkYMin: Int,
        chunkYMax: Int,
    ): Int
}

// Fog tiles intersecting a lat/lng viewport. Mirrors forSelfInViewport's band fan-out:
// chunkX comes from lat (band-independent), chunkY from lng with the band's chunk step.
suspend fun ChunkFogDao.tilesInViewport(
    latSouth: Double,
    latNorth: Double,
    lngWest: Double,
    lngEast: Double,
): List<ChunkFog> {
    val chunkXMin = chunkXOf(cellXOf(latSouth))
    val chunkXMax = chunkXOf(cellXOf(latNorth))
    val bandMin = bandOf(latSouth)
    val bandMax = bandOf(latNorth)
    val out = ArrayList<ChunkFog>()
    for (band in bandMin..bandMax) {
        val chunkYMin = chunkYOf(cellYOf(lngWest, band))
        val chunkYMax = chunkYOf(cellYOf(lngEast, band))
        out += inBandRange(band, chunkXMin, chunkXMax, chunkYMin, chunkYMax)
    }
    return out
}

// Number of dirty fog tiles intersecting a viewport — drives the render-path skip.
suspend fun ChunkFogDao.dirtyCountInViewport(
    latSouth: Double,
    latNorth: Double,
    lngWest: Double,
    lngEast: Double,
): Int {
    val chunkXMin = chunkXOf(cellXOf(latSouth))
    val chunkXMax = chunkXOf(cellXOf(latNorth))
    val bandMin = bandOf(latSouth)
    val bandMax = bandOf(latNorth)
    var total = 0
    for (band in bandMin..bandMax) {
        val chunkYMin = chunkYOf(cellYOf(lngWest, band))
        val chunkYMax = chunkYOf(cellYOf(lngEast, band))
        total += dirtyCountInBandRange(band, chunkXMin, chunkXMax, chunkYMin, chunkYMax)
    }
    return total
}

@Dao
interface SessionStatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: SessionStat)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(stat: SessionStat)

    @Query("UPDATE SessionStat SET dirty = 1 WHERE sessionId = :sessionId")
    suspend fun markDirty(sessionId: Long)

    @Query("SELECT * FROM SessionStat WHERE dirty = 1")
    suspend fun dirtySessions(): List<SessionStat>

    @Query("SELECT COALESCE(SUM(distanceKm), 0) FROM SessionStat")
    suspend fun totalDistanceKm(): Double
}

// Single dirty-marking entry point shared by the insert path (LocationService) and the
// delete path (delete mode). Given the points written or removed, marks every chunk they
// touch (own chunk + halo neighbors) and their sessions dirty, so the next StatsEngine
// refresh recomputes only those. Cheap: a fix batch normally touches one chunk → a
// couple of tiny writes. For deletes, pass the points fetched before deletion.
suspend fun AppDatabase.markPointsDirty(points: List<LocationPoint>) {
    if (points.isEmpty()) return
    val chunks = HashSet<ChunkId>()
    val sessions = HashSet<Long>()
    for (p in points) {
        chunks += affectedChunks(p.band, p.cellX, p.cellY)
        sessions += p.sessionId
    }
    val cDao = chunkCoverageDao()
    val cfDao = chunkFogDao()
    for (c in chunks) {
        cDao.insertIgnore(ChunkCoverage(c.band, c.chunkX, c.chunkY, 0, dirty = true))
        cDao.markDirty(c.band, c.chunkX, c.chunkY)
        cfDao.insertIgnore(ChunkFog(c.band, c.chunkX, c.chunkY, null, dirty = true))
        cfDao.markDirty(c.band, c.chunkX, c.chunkY)
    }
    val sDao = sessionStatDao()
    for (s in sessions) {
        sDao.insertIgnore(SessionStat(s, 0.0, dirty = true))
        sDao.markDirty(s)
    }
}

// One-time seeding of the chunk caches from ALL existing points — covers history walked
// before the chunk cache existed. Guarded ONLY by a prefs flag, deliberately NOT by
// "are there any chunk rows yet": live recording can create a few rows before this first
// runs, and an "if any rows exist, skip" guard would then skip the historical backfill and
// leave earlier-walked areas with no fog tiles. Idempotent (markPointsDirty just re-marks),
// so the worst case of a redundant run is one extra recompute. Called from both the stats
// refresh and the map fog path so it runs no matter which screen the user opens first.
suspend fun AppDatabase.ensureChunkBackfill(context: Context) {
    val prefs = context.applicationContext
        .getSharedPreferences("chunk_backfill", Context.MODE_PRIVATE)
    if (prefs.getBoolean("done_v1", false)) return
    markPointsDirty(locationPointDao().allForSelfOnce())
    prefs.edit().putBoolean("done_v1", true).apply()
}

// Decompose a lat/lng bbox into per-band cell-range queries and concatenate results.
// Typical viewports sit inside a single band; a viewport that straddles a band edge
// fans out into 2 queries. Caller passes raw lat/lng bounds; this function handles
// the band split + the per-band cellY math.
suspend fun LocationPointDao.forSelfInViewport(
    latSouth: Double,
    latNorth: Double,
    lngWest: Double,
    lngEast: Double,
): List<LocationPoint> {
    val cellXMin = cellXOf(latSouth)
    val cellXMax = cellXOf(latNorth)
    val bandMin = bandOf(latSouth)
    val bandMax = bandOf(latNorth)
    val out = ArrayList<LocationPoint>()
    for (band in bandMin..bandMax) {
        val step = lngStepDeg(band)
        val cellYMin = kotlin.math.floor(lngWest / step).toInt()
        val cellYMax = kotlin.math.floor(lngEast / step).toInt()
        out += forSelfInBandCellRange(band, cellXMin, cellXMax, cellYMin, cellYMax)
    }
    return out
}
