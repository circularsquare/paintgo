package com.anita.paintgo.fog

import com.anita.paintgo.data.LocationPoint
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateFilter
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKBReader
import org.locationtech.jts.io.WKBWriter
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

private const val EARTH_RADIUS_M = 6_378_137.0
private const val METERS_PER_DEG = EARTH_RADIUS_M * PI / 180.0

// Split a session's fixes into separate runs whenever the time gap or jump distance
// exceeds these. ~2 miles of jump is roughly a subway hop — we want to draw the line
// through it so transit travel still paints. Past that, it's likely a real GPS glitch.
internal const val MAX_RUN_GAP_MS = 150_000L
internal const val MAX_RUN_SEGMENT_M = 3200.0

// Precision a fog tile's cleared union is baked at — fixed (zoom-independent) so a stored
// tile serves every zoom level; the assembly step applies a zoom-scaled simplify on top.
// 16 segments/quadrant = 64-sided discs (smooth for a translucent fill); 1m simplify keeps
// stored WKB compact without visible shift at street zoom.
const val FOG_TILE_SEGMENTS_PER_QUADRANT = 16
const val FOG_TILE_SIMPLIFY_DEG = 1.0 / METERS_PER_DEG

// ---- shared run tessellation -------------------------------------------------

/** Split one session's time-ordered fixes into runs, breaking at large time/distance
 *  gaps so a dropout or GPS glitch doesn't paint a stripe across the void. [xScale] is the
 *  local E–W meters-per-degree used to measure jump distance. */
private fun splitSessionIntoRuns(
    sessionPoints: List<LocationPoint>,
    xScale: Double,
): List<List<LocationPoint>> {
    val runs = ArrayList<List<LocationPoint>>()
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
        runs.add(sessionPoints.subList(runStart, runEnd))
        runStart = runEnd
    }
    return runs
}

/** A run becomes a LineString (so the painted stripe fills between fixes) or, for an
 *  isolated fix, a Point that buffers to a disc. */
private fun runToGeometry(
    run: List<LocationPoint>,
    project: (Double, Double) -> Coordinate,
    factory: GeometryFactory,
): Geometry {
    val coords = run.map { project(it.lat, it.lng) }
    return if (coords.size == 1) factory.createPoint(coords[0])
    else factory.createLineString(coords.toTypedArray())
}

// ---- per-chunk fog tile ------------------------------------------------------

/**
 * Cleared-area geometry for one fog tile: the union of [radiusMeters] buffers around the
 * chunk's traveled runs, clipped to the chunk rectangle, returned in lat/lng (WGS84
 * degrees). [points] must be the chunk's own fixes plus a halo of neighbor fixes whose
 * discs / segments reach into the rect (the caller queries chunkBbox expanded by
 * FOG_COVERAGE_RADIUS_M + MAX_RUN_SEGMENT_M). Clipping to the rect makes adjacent tiles
 * abut without overlap, so they tile seamlessly at render.
 *
 * Buffering happens in a local equirectangular metric projection; the result is then
 * unprojected to lat/lng so tiles computed under different chunk centers still combine
 * correctly. Returns null when the chunk has no traveled geometry (fully fogged).
 */
fun computeChunkClearedUnion(
    points: List<LocationPoint>,
    chunkBounds: LatLngBounds,
    radiusMeters: Double,
    segmentsPerQuadrant: Int,
    simplifyToleranceDeg: Double,
): Geometry? {
    if (points.isEmpty()) return null

    val centerLat = (chunkBounds.latitudeNorth + chunkBounds.latitudeSouth) / 2.0
    val centerLng = (chunkBounds.longitudeEast + chunkBounds.longitudeWest) / 2.0
    val cosCenterLat = cos(Math.toRadians(centerLat))
    if (cosCenterLat <= 0.0) return null
    val xScale = cosCenterLat * METERS_PER_DEG

    val factory = GeometryFactory()
    val project = { lat: Double, lng: Double ->
        Coordinate((lng - centerLng) * xScale, (lat - centerLat) * METERS_PER_DEG)
    }

    val runGeometries = ArrayList<Geometry>()
    for ((_, sessionPoints) in points.groupBy { it.sessionId }) {
        for (run in splitSessionIntoRuns(sessionPoints, xScale)) {
            runGeometries.add(runToGeometry(run, project, factory))
        }
    }
    if (runGeometries.isEmpty()) return null

    val buffered = runGeometries.map { it.buffer(radiusMeters, segmentsPerQuadrant) }
    val union = UnaryUnionOp.union(buffered) ?: return null
    if (union.isEmpty) return null

    // Unproject meters → lat/lng in place (the union is a throwaway temp).
    union.apply(object : CoordinateFilter {
        override fun filter(c: Coordinate) {
            c.x = centerLng + c.x / xScale
            c.y = centerLat + c.y / METERS_PER_DEG
        }
    })
    union.geometryChanged()

    val clipped = union.intersection(latLngRect(chunkBounds, factory))
    if (clipped.isEmpty) return null
    val simplified =
        if (simplifyToleranceDeg > 0) DouglasPeuckerSimplifier.simplify(clipped, simplifyToleranceDeg)
        else clipped
    return if (simplified.isEmpty) null else simplified
}

// Web Mercator can't represent the poles; ±85° is the standard world-fog cap.
private const val FOG_WORLD_LAT_LIMIT = 85.0

/**
 * Assemble the fog fill as ONE world-spanning polygon with the visible tiles' cleared areas
 * punched out as holes, simplified by [simplifyToleranceDeg] (scaled to the current zoom so
 * low-zoom assemblies stay light). A single world-extent fill means there's no viewport-
 * rectangle boundary to leave a seam, and the whole thing is one source updated atomically
 * (no inner/outer hole-punch race / flash). Off-screen cleared areas simply aren't holes
 * yet — they become holes once their tiles are in view. Returns null only if the result is
 * empty (caller pushes an empty source).
 */
fun assembleWorldFog(
    clearedTiles: List<Geometry>,
    simplifyToleranceDeg: Double,
): Feature? {
    val factory = GeometryFactory()
    val n = FOG_WORLD_LAT_LIMIT
    val world = factory.createPolygon(
        arrayOf(
            Coordinate(-180.0, -n),
            Coordinate(180.0, -n),
            Coordinate(180.0, n),
            Coordinate(-180.0, n),
            Coordinate(-180.0, -n),
        )
    )
    val cleared = if (clearedTiles.isEmpty()) null else UnaryUnionOp.union(clearedTiles)
    val fog = if (cleared == null || cleared.isEmpty) world else world.difference(cleared)
    if (fog.isEmpty) return null
    val simplified =
        if (simplifyToleranceDeg > 0) DouglasPeuckerSimplifier.simplify(fog, simplifyToleranceDeg)
        else fog
    if (simplified.isEmpty) return null
    return jtsToGeoJsonFeature(simplified) { c -> GjPoint.fromLngLat(c.x, c.y) }
}

fun geometryToWkb(geom: Geometry): ByteArray = WKBWriter().write(geom)

fun wkbToGeometry(bytes: ByteArray): Geometry = WKBReader().read(bytes)

private fun latLngRect(bounds: LatLngBounds, factory: GeometryFactory): Polygon =
    factory.createPolygon(
        arrayOf(
            Coordinate(bounds.longitudeWest, bounds.latitudeSouth),
            Coordinate(bounds.longitudeEast, bounds.latitudeSouth),
            Coordinate(bounds.longitudeEast, bounds.latitudeNorth),
            Coordinate(bounds.longitudeWest, bounds.latitudeNorth),
            Coordinate(bounds.longitudeWest, bounds.latitudeSouth),
        )
    )

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
