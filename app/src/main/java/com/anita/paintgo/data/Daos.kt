package com.anita.paintgo.data

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
