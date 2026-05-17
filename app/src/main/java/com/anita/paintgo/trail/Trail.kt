package com.anita.paintgo.trail

import com.anita.paintgo.data.LocationPoint
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point as GjPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max

/**
 * Per-segment line features (and isolated-point fallback features) for the Speed
 * and Recency view modes. Color is precomputed in Kotlin and stored on each
 * feature as a hex string under [TRAIL_COLOR_PROP], so the MapLibre layer is just
 * `lineColor(get("color"))` — no MapLibre interpolate expression needed.
 *
 * Caller passes points pre-filtered to the rendering viewport (expanded by at
 * least [MAX_RUN_SEGMENT_M] so cross-edge segments still draw end-to-end).
 */

private const val EARTH_RADIUS_M = 6_378_137.0
private const val METERS_PER_DEG = EARTH_RADIUS_M * PI / 180.0

// Mirror the fog renderer's run-break heuristics so the two modes draw segments
// over the same point pairs. Kept private to this module — fog has its own copy
// for its own JTS pipeline and we don't want a cross-module dependency on a
// constant whose meaning is identical but use-site different.
private const val MAX_RUN_GAP_MS = 150_000L
internal const val MAX_RUN_SEGMENT_M = 3200.0

const val TRAIL_COLOR_PROP = "color"

enum class TrailMode { Speed, Recency }

data class TrailFeatures(val lines: Array<Feature>, val isolatedPoints: Array<Feature>)

fun buildTrailFeatures(
    points: List<LocationPoint>,
    mode: TrailMode,
    nowMs: Long,
): TrailFeatures {
    val lines = ArrayList<Feature>(points.size)
    val isolated = ArrayList<Feature>()

    for ((_, sessionPoints) in points.groupBy { it.sessionId }) {
        for (i in sessionPoints.indices) {
            val curr = sessionPoints[i]
            val forward = if (i + 1 < sessionPoints.size) segmentBetween(curr, sessionPoints[i + 1]) else null
            val backward = if (i > 0) segmentBetween(sessionPoints[i - 1], curr) else null

            if (forward != null) {
                val next = sessionPoints[i + 1]
                val color = colorForSegment(curr, next, forward.distM, forward.dtMs, mode, nowMs)
                lines.add(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            listOf(
                                GjPoint.fromLngLat(curr.lng, curr.lat),
                                GjPoint.fromLngLat(next.lng, next.lat),
                            )
                        )
                    ).apply { addStringProperty(TRAIL_COLOR_PROP, color) }
                )
            }

            // Only draw a dot for points that aren't an endpoint of any drawn segment.
            // Otherwise the segment endpoints already convey position and color, and a
            // dot overlay just makes the trail look chunky.
            if (forward == null && backward == null) {
                val color = colorForIsolatedPoint(curr, mode, nowMs)
                isolated.add(
                    Feature.fromGeometry(GjPoint.fromLngLat(curr.lng, curr.lat))
                        .apply { addStringProperty(TRAIL_COLOR_PROP, color) }
                )
            }
        }
    }
    return TrailFeatures(lines.toTypedArray(), isolated.toTypedArray())
}

private data class SegmentInfo(val distM: Double, val dtMs: Long)

private fun segmentBetween(a: LocationPoint, b: LocationPoint): SegmentInfo? {
    val dt = b.timestamp - a.timestamp
    if (dt <= 0L || dt > MAX_RUN_GAP_MS) return null
    val midLatRad = Math.toRadians((a.lat + b.lat) / 2.0)
    val dx = (b.lng - a.lng) * METERS_PER_DEG * cos(midLatRad)
    val dy = (b.lat - a.lat) * METERS_PER_DEG
    val d = hypot(dx, dy)
    if (d > MAX_RUN_SEGMENT_M) return null
    return SegmentInfo(d, dt)
}

private fun colorForSegment(
    a: LocationPoint, b: LocationPoint,
    distM: Double, dtMs: Long,
    mode: TrailMode, nowMs: Long,
): String = when (mode) {
    TrailMode.Speed -> speedColor(distM / (dtMs / 1000.0))
    TrailMode.Recency -> recencyColor(nowMs - (a.timestamp + b.timestamp) / 2L)
}

private fun colorForIsolatedPoint(p: LocationPoint, mode: TrailMode, nowMs: Long): String = when (mode) {
    // Speed is unknown for an isolated fix — render in a neutral grey so the dot
    // still reads as recorded but isn't misleadingly bucketed at 0 m/s.
    TrailMode.Speed -> ISOLATED_SPEED_HEX
    TrailMode.Recency -> recencyColor(nowMs - p.timestamp)
}

private const val ISOLATED_SPEED_HEX = "#9E9E9E"

// Color-stops table: key = scalar (speed m/s, or log10(age_s + 1)), then RGB.
private data class ColorStop(val key: Double, val r: Int, val g: Int, val b: Int)

// Targeted at the natural transit modes we care about: standing, walking,
// running, biking, driving. Saturated stops so neighboring stops are visually
// distinct after interpolation.
private val SPEED_STOPS = listOf(
    ColorStop(0.0, 0x15, 0x65, 0xC0),   // deep blue — stopped (< 0.5 m/s)
    ColorStop(1.4, 0x00, 0xBC, 0xD4),   // cyan — walking pace
    ColorStop(4.0, 0x4C, 0xAF, 0x50),   // green — jogging / running
    ColorStop(8.0, 0xFB, 0xC0, 0x2D),   // yellow — biking
    ColorStop(15.0, 0xF5, 0x7C, 0x00),  // orange — city driving
    ColorStop(25.0, 0xC6, 0x28, 0x28),  // red — highway
)

// Key is log10(ageSeconds + 1) so a recent fix maps near 0 and old fixes stretch
// out logarithmically — "today" reads as bright, "weeks ago" as muted.
private val RECENCY_LOG_STOPS = listOf(
    ColorStop(0.0, 0xFF, 0x57, 0x22),   // hot orange — just now (~1 s)
    ColorStop(1.78, 0xFF, 0xC1, 0x07),  // amber — ~1 minute
    ColorStop(3.56, 0x4C, 0xAF, 0x50),  // green — ~1 hour
    ColorStop(4.94, 0x21, 0x96, 0xF3),  // blue — ~1 day
    ColorStop(5.78, 0x67, 0x3A, 0xB7),  // purple — ~1 week
    ColorStop(6.42, 0x42, 0x42, 0x42),  // dark grey — ≥1 month
)

internal fun speedColor(mPerSec: Double): String = lerpStops(SPEED_STOPS, mPerSec)

internal fun recencyColor(ageMs: Long): String {
    val sec = max(ageMs, 0L) / 1000.0
    return lerpStops(RECENCY_LOG_STOPS, log10(sec + 1.0))
}

private fun lerpStops(stops: List<ColorStop>, key: Double): String {
    if (key.isNaN() || key <= stops.first().key) return hex(stops.first())
    if (key >= stops.last().key) return hex(stops.last())
    for (i in 0 until stops.size - 1) {
        val a = stops[i]
        val b = stops[i + 1]
        if (key < b.key) {
            val t = ((key - a.key) / (b.key - a.key)).coerceIn(0.0, 1.0)
            val r = (a.r + (b.r - a.r) * t).toInt()
            val g = (a.g + (b.g - a.g) * t).toInt()
            val bl = (a.b + (b.b - a.b) * t).toInt()
            return "#%02X%02X%02X".format(r, g, bl)
        }
    }
    return hex(stops.last())
}

private fun hex(s: ColorStop): String = "#%02X%02X%02X".format(s.r, s.g, s.b)
