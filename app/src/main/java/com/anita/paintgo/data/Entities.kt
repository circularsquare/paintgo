package com.anita.paintgo.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity
data class Owner(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val isSelf: Boolean,
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Owner::class,
            parentColumns = ["id"],
            childColumns = ["ownerId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("ownerId")],
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerId: Long,
    val startTime: Long,
    val endTime: Long? = null,
    val name: String? = null,
    val importedAt: Long? = null,
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("sessionId"),
        // Dedup within a session at ~5m resolution. Stationary fixes get squashed;
        // revisits in later sessions still record (intentional — useful for stats).
        // band is part of the key because cellY's meters-per-unit depends on the band's
        // representative latitude; a point at lat 49.9 and a point at lat 50.1 are in
        // different cells even if their lng matches.
        Index(value = ["sessionId", "band", "cellX", "cellY"], unique = true),
        // Non-unique spatial index: turns the per-chunk / viewport bbox cell-range
        // queries (forSelfInBandCellRange) from a table scan into an indexed range.
        Index(value = ["band", "cellX", "cellY"]),
    ],
)
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val accuracy: Float,
    val band: Int,
    val cellX: Int,
    val cellY: Int,
)

// ---- Spatial chunk cache (see Chunk.kt) ----
//
// Per-chunk derived stats, recomputed only for chunks whose points changed. coveredArea
// = SUM(coveredCellCount) across rows; region % = per-region SUM(cellCount) from
// ChunkRegionCoverage. `dirty` marks a chunk whose points changed since its last
// recompute (also set true for every chunk during the one-time backfill). All values are
// derived from LocationPoint — safe to drop and rebuild.

@Entity(primaryKeys = ["band", "chunkX", "chunkY"])
data class ChunkCoverage(
    val band: Int,
    val chunkX: Int,
    val chunkY: Int,
    val coveredCellCount: Int,
    val dirty: Boolean,
)

// One row per (chunk, region) the chunk's covered cells fall inside. A region is
// "visited" iff it has any row here, which subsumes the old VisitedRegions discovery.
@Entity(primaryKeys = ["band", "chunkX", "chunkY", "regionKey"])
data class ChunkRegionCoverage(
    val band: Int,
    val chunkX: Int,
    val chunkY: Int,
    val regionKey: String,
    val cellCount: Int,
)

// Per-chunk cached fog geometry: the cleared-area union (union of FOG_COVERAGE_RADIUS_M
// buffers around the chunk's traveled runs), clipped to the chunk rectangle, stored as
// JTS WKB in lat/lng so tiles from different chunks combine seamlessly at render. Null
// clearedWkb = nothing cleared in this chunk (fully fogged). `dirty` is SEPARATE from
// ChunkCoverage.dirty because fog tiles are recomputed lazily at render (only visible
// chunks) while stats recompute all dirty chunks on refresh — the two flags clear on
// different schedules. (ByteArray breaks data-class equals(), but ChunkFog is never
// compared by value.)
@Entity(primaryKeys = ["band", "chunkX", "chunkY"])
data class ChunkFog(
    val band: Int,
    val chunkX: Int,
    val chunkY: Int,
    val clearedWkb: ByteArray?,
    val dirty: Boolean,
)

// Cached per-session walked distance — distance isn't spatial, so it's keyed by session
// rather than chunk. Only changed sessions (in practice just the active one) get
// recomputed; totalKm = SUM(distanceKm).
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class SessionStat(
    @PrimaryKey val sessionId: Long,
    val distanceKm: Double,
    val dirty: Boolean,
)
