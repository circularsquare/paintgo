package com.anita.paintgo.data

import kotlin.math.cos
import kotlin.math.floor

// Banded dedup grid.
//
// We split the world into latitude bands of POINT_GRID_BAND_DEG. Inside each band,
// cells are sized to be ~square in meters: lat step is fixed (POINT_GRID_STEP_LAT_DEG,
// ~5m); lng step is the lat step divided by cos(midLatitude of the band), which is the
// band's representative E–W stretch factor. A point's cell is then (band, cellX, cellY)
// where cellX comes from lat (band-independent) and cellY comes from lng with the
// band's lng step.
//
// The cos floor at near-polar bands keeps the lng step from blowing up to infinity
// at the poles; nobody walks there, but the math has to be defined.

const val POINT_GRID_BAND_DEG = 5.0
const val POINT_GRID_STEP_LAT_DEG = 4.5e-5

private const val MIN_COS_LAT = 0.05  // ~ cos(87°); caps band lng step at ~20× lat step.

fun bandOf(lat: Double): Int = floor(lat / POINT_GRID_BAND_DEG).toInt()

fun bandMidLatDeg(band: Int): Double = (band + 0.5) * POINT_GRID_BAND_DEG

fun lngStepDeg(band: Int): Double {
    val midLat = bandMidLatDeg(band)
    val c = cos(Math.toRadians(midLat))
    return POINT_GRID_STEP_LAT_DEG / (if (c < MIN_COS_LAT) MIN_COS_LAT else c)
}

fun cellXOf(lat: Double): Int = floor(lat / POINT_GRID_STEP_LAT_DEG).toInt()

fun cellYOf(lng: Double, band: Int): Int = floor(lng / lngStepDeg(band)).toInt()

// Canonical "you've covered this spot" radius. Drives both the fog visualization
// (each point clears a disc of this radius) and the area-coverage stat (a 10m cell
// counts as covered iff its center sits within this distance of some recorded
// point). Keep these in sync so the reported m² matches the white-on-map.
const val FOG_COVERAGE_RADIUS_M = 50.0
