package com.anita.paintgo.data

import kotlin.math.ceil

// Spatial chunk grid — the coarse tiling that the stats cache (and, later, the fog
// tiles) are keyed on. A chunk is just a CHUNK_CELLS × CHUNK_CELLS block of the banded
// ~5m dedup cells already stored on every LocationPoint (band, cellX, cellY), so chunk
// identity is a pure function of columns we already have: no new point columns, no
// rewrite. See Grid.kt for the underlying banded cell math.
//
// chunkX comes from cellX (band-independent, derived from lat); chunkY comes from cellY
// (whose meters-per-unit depends on the band). The band is part of the identity because
// cellY's physical width is per-band — same as the dedup unique index.

// 400 dedup cells ≈ 400 × 4.5e-5° lat ≈ 0.018° ≈ 2.0 km per side. Single tunable knob:
// smaller = cheaper per-chunk recompute but more tiles to assemble; larger = the reverse.
const val CHUNK_CELLS = 400

// How far (in dedup cells) a point's influence reaches past a chunk edge: a recorded
// point clears / counts coverage out to FOG_COVERAGE_RADIUS_M, so a point within this
// many cells of a chunk boundary also affects the neighbor chunk. +1 for safety margin.
val HALO_CELLS: Int =
    ceil(FOG_COVERAGE_RADIUS_M / (POINT_GRID_STEP_LAT_DEG * 111_320.0)).toInt() + 1

data class ChunkId(val band: Int, val chunkX: Int, val chunkY: Int)

fun chunkXOf(cellX: Int): Int = Math.floorDiv(cellX, CHUNK_CELLS)
fun chunkYOf(cellY: Int): Int = Math.floorDiv(cellY, CHUNK_CELLS)

fun chunkIdOf(band: Int, cellX: Int, cellY: Int): ChunkId =
    ChunkId(band, chunkXOf(cellX), chunkYOf(cellY))

// The chunk that owns a given lat/lng — routed through the dedup grid so it matches the
// chunk a stored point at the same spot lands in. Used to assign a 10m stat-cell center
// to exactly one chunk (unique ownership → summing per-chunk counts never double-counts).
fun chunkIdOfLatLng(lat: Double, lng: Double): ChunkId {
    val band = bandOf(lat)
    return ChunkId(band, chunkXOf(cellXOf(lat)), chunkYOf(cellYOf(lng, band)))
}

/** Lat/lng bounding box of a chunk's rectangle (no halo). Lng width uses the chunk's
 *  band step. A chunk straddling a 5° band boundary is split into two partial chunks by
 *  band id; its bbox here may overhang slightly, which only ever pulls in extra halo
 *  points (harmless — cell ownership is decided exactly by [chunkIdOfLatLng]). */
data class ChunkBbox(val latS: Double, val latN: Double, val lngW: Double, val lngE: Double)

fun chunkBbox(id: ChunkId): ChunkBbox {
    val latS = id.chunkX.toLong() * CHUNK_CELLS * POINT_GRID_STEP_LAT_DEG
    val latN = (id.chunkX.toLong() + 1) * CHUNK_CELLS * POINT_GRID_STEP_LAT_DEG
    val step = lngStepDeg(id.band)
    val lngW = id.chunkY.toLong() * CHUNK_CELLS * step
    val lngE = (id.chunkY.toLong() + 1) * CHUNK_CELLS * step
    return ChunkBbox(latS, latN, lngW, lngE)
}

/** Every chunk a single point's write/delete invalidates: its own chunk, plus any
 *  neighbor whose shared edge/corner is within [HALO_CELLS] of the point. Neighbors are
 *  kept in the point's own band — a neighbor across a 5° band edge is an astronomically
 *  rare case (within 50m of an exact 5° latitude) and self-heals on a full recompute. */
fun affectedChunks(band: Int, cellX: Int, cellY: Int): Set<ChunkId> {
    val cx = chunkXOf(cellX)
    val cy = chunkYOf(cellY)
    val ox = cellX - cx * CHUNK_CELLS
    val oy = cellY - cy * CHUNK_CELLS
    val xs = buildList {
        add(cx)
        if (ox < HALO_CELLS) add(cx - 1)
        if (ox >= CHUNK_CELLS - HALO_CELLS) add(cx + 1)
    }
    val ys = buildList {
        add(cy)
        if (oy < HALO_CELLS) add(cy - 1)
        if (oy >= CHUNK_CELLS - HALO_CELLS) add(cy + 1)
    }
    val out = HashSet<ChunkId>(xs.size * ys.size)
    for (x in xs) for (y in ys) out.add(ChunkId(band, x, y))
    return out
}
