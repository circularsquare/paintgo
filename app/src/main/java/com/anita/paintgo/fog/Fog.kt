package com.anita.paintgo.fog

import com.anita.paintgo.data.LocationPoint
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.Feature
import org.maplibre.geojson.MultiPolygon as GjMultiPolygon
import org.maplibre.geojson.Point as GjPoint
import org.maplibre.geojson.Polygon as GjPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

private const val EARTH_RADIUS_M = 6_378_137.0
private const val METERS_PER_DEG = EARTH_RADIUS_M * PI / 180.0

// Split a session's fixes into separate runs whenever the time gap or jump distance
// exceeds these. ~2 miles of jump is roughly a subway hop — we want to draw the line
// through it so transit travel still paints. Past that, it's likely a real GPS glitch.
internal const val MAX_RUN_GAP_MS = 150_000L
internal const val MAX_RUN_SEGMENT_M = 3200.0

/**
 * Build one fog feature per clear-radius in [radiiMeters]. Each feature = viewport
 * polygon minus the union of radius-r buffers around every traveled segment.
 * Caller stacks the features as translucent fill layers to produce a faded edge.
 *
 * Within a session, consecutive in-time, in-range fixes are joined into a LineString
 * so the painted stripe fills in between fixes — important at biking/driving speeds
 * where 5s-interval point circles barely overlap. Isolated fixes (or runs broken by
 * a dropout) become Points and buffer to circles, same as before.
 *
 * Uses a local equirectangular projection centered on the viewport so all JTS ops
 * happen in (approximate) metric Cartesian space. The smallest-radius union is
 * computed once, then iteratively buffered outward by the delta to each successive
 * radius — much cheaper than independent unions per ring.
 *
 * [bufferSegmentsPerQuadrant] controls how circular the disc edges look (number of
 * vertices per 90° arc). [simplifyToleranceM] is the Douglas-Peucker tolerance for
 * the final viewport-minus-union polygon. Both should scale with map zoom: low at
 * high zoom (sub-pixel detail), high at low zoom (cheap when discs are tiny).
 *
 * [points] must be ordered (sessionId asc, timestamp asc) as the DAO returns them.
 * [radiiMeters] must be non-empty and strictly ascending. Returns null if the
 * viewport is degenerate.
 */
fun computeFogRings(
    points: List<LocationPoint>,
    bounds: LatLngBounds,
    radiiMeters: List<Double>,
    bufferSegmentsPerQuadrant: Int,
    simplifyToleranceM: Double,
): List<Feature?>? {
    require(radiiMeters.isNotEmpty()) { "radiiMeters must not be empty" }
    require(radiiMeters.zipWithNext().all { (a, b) -> a < b }) {
        "radiiMeters must be strictly ascending"
    }
    require(bufferSegmentsPerQuadrant >= 1) { "bufferSegmentsPerQuadrant must be >= 1" }
    require(simplifyToleranceM >= 0.0) { "simplifyToleranceM must be >= 0" }

    val centerLat = (bounds.latitudeNorth + bounds.latitudeSouth) / 2.0
    val centerLng = (bounds.longitudeEast + bounds.longitudeWest) / 2.0
    val cosCenterLat = cos(Math.toRadians(centerLat))
    if (cosCenterLat <= 0.0) return null
    val xScale = cosCenterLat * METERS_PER_DEG

    fun project(lat: Double, lng: Double): Coordinate =
        Coordinate((lng - centerLng) * xScale, (lat - centerLat) * METERS_PER_DEG)

    fun unprojectToPoint(c: Coordinate): GjPoint = GjPoint.fromLngLat(
        centerLng + c.x / xScale,
        centerLat + c.y / METERS_PER_DEG,
    )

    val factory = GeometryFactory()
    val viewportRing = arrayOf(
        project(bounds.latitudeSouth, bounds.longitudeWest),
        project(bounds.latitudeSouth, bounds.longitudeEast),
        project(bounds.latitudeNorth, bounds.longitudeEast),
        project(bounds.latitudeNorth, bounds.longitudeWest),
        project(bounds.latitudeSouth, bounds.longitudeWest),
    )
    val viewportPoly: Polygon = factory.createPolygon(viewportRing)

    val maxRadius = radiiMeters.last()
    val latBuf = maxRadius / METERS_PER_DEG
    val lngBuf = maxRadius / (max(cosCenterLat, 0.01) * METERS_PER_DEG)
    val expSouth = bounds.latitudeSouth - latBuf
    val expNorth = bounds.latitudeNorth + latBuf
    val expWest = bounds.longitudeWest - lngBuf
    val expEast = bounds.longitudeEast + lngBuf

    val runGeometries = mutableListOf<Geometry>()
    for ((_, sessionPoints) in points.groupBy { it.sessionId }) {
        var runStart = 0
        while (runStart < sessionPoints.size) {
            var runEnd = runStart + 1
            while (runEnd < sessionPoints.size) {
                val prev = sessionPoints[runEnd - 1]
                val curr = sessionPoints[runEnd]
                if (curr.timestamp - prev.timestamp > MAX_RUN_GAP_MS) break
                val dx = (curr.lng - prev.lng) * xScale
                val dy = (curr.lat - prev.lat) * METERS_PER_DEG
                if (hypot(dx, dy) > MAX_RUN_SEGMENT_M) break
                runEnd++
            }
            val run = sessionPoints.subList(runStart, runEnd)
            // Bbox cull against expanded viewport — skip runs entirely off-screen.
            // Using lat/lng bbox catches segments that pass through the viewport even
            // when both endpoints are off-screen.
            val minLat = run.minOf { it.lat }
            val maxLat = run.maxOf { it.lat }
            val minLng = run.minOf { it.lng }
            val maxLng = run.maxOf { it.lng }
            val onScreen = !(maxLat < expSouth || minLat > expNorth ||
                maxLng < expWest || minLng > expEast)
            if (onScreen) {
                val coords = run.map { project(it.lat, it.lng) }
                val geom: Geometry = if (coords.size == 1) {
                    factory.createPoint(coords[0])
                } else {
                    factory.createLineString(coords.toTypedArray())
                }
                runGeometries.add(geom)
            }
            runStart = runEnd
        }
    }

    if (runGeometries.isEmpty()) {
        val viewportFeature = jtsToGeoJsonFeature(viewportPoly, ::unprojectToPoint)
        return radiiMeters.map { viewportFeature }
    }

    val coreBuffered = runGeometries.map {
        it.buffer(radiiMeters[0], bufferSegmentsPerQuadrant)
    }
    var currentUnion: Geometry = UnaryUnionOp.union(coreBuffered)
    val unions = mutableListOf(currentUnion)
    var prevRadius = radiiMeters[0]
    for (k in 1 until radiiMeters.size) {
        val delta = radiiMeters[k] - prevRadius
        currentUnion = currentUnion.buffer(delta, bufferSegmentsPerQuadrant)
        unions.add(currentUnion)
        prevRadius = radiiMeters[k]
    }

    return unions.map { union ->
        val fog = viewportPoly.difference(union)
        val simplified = DouglasPeuckerSimplifier.simplify(fog, simplifyToleranceM)
        jtsToGeoJsonFeature(simplified, ::unprojectToPoint)
    }
}

private fun jtsToGeoJsonFeature(
    geom: Geometry,
    unproject: (Coordinate) -> GjPoint,
): Feature? = when (geom) {
    is Polygon -> Feature.fromGeometry(GjPolygon.fromLngLats(polygonRings(geom, unproject)))
    is MultiPolygon -> {
        val polys = (0 until geom.numGeometries).map { i ->
            polygonRings(geom.getGeometryN(i) as Polygon, unproject)
        }
        Feature.fromGeometry(GjMultiPolygon.fromLngLats(polys))
    }
    else -> null
}

private fun polygonRings(
    poly: Polygon,
    unproject: (Coordinate) -> GjPoint,
): List<List<GjPoint>> {
    val outer = poly.exteriorRing.coordinates.map(unproject)
    val holes = (0 until poly.numInteriorRing).map { i ->
        poly.getInteriorRingN(i).coordinates.map(unproject)
    }
    return listOf(outer) + holes
}
