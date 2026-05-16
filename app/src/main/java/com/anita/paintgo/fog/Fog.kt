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
import kotlin.math.max

private const val FOG_RADIUS_METERS = 50.0
private const val EARTH_RADIUS_M = 6_378_137.0
private const val METERS_PER_DEG = EARTH_RADIUS_M * PI / 180.0
private const val SIMPLIFY_TOLERANCE_M = 4.0
private const val BUFFER_SEGMENTS_PER_QUADRANT = 12

/**
 * Build a fog feature = viewport polygon minus the union of [FOG_RADIUS_METERS]-radius
 * circles around every point.
 *
 * Uses a local equirectangular projection centered on the viewport so all the JTS ops
 * happen in (approximate) metric Cartesian space. Accurate to <1% for city-scale viewports.
 *
 * Returns null if the viewport is degenerate.
 */
fun computeFog(points: List<LocationPoint>, bounds: LatLngBounds): Feature? {
    val centerLat = (bounds.latitudeNorth + bounds.latitudeSouth) / 2.0
    val centerLng = (bounds.longitudeEast + bounds.longitudeWest) / 2.0
    val cosCenterLat = cos(Math.toRadians(centerLat))
    if (cosCenterLat <= 0.0) return null

    fun project(lat: Double, lng: Double): Coordinate {
        val x = (lng - centerLng) * cosCenterLat * METERS_PER_DEG
        val y = (lat - centerLat) * METERS_PER_DEG
        return Coordinate(x, y)
    }
    fun unprojectToPoint(c: Coordinate): GjPoint {
        val lng = centerLng + c.x / (cosCenterLat * METERS_PER_DEG)
        val lat = centerLat + c.y / METERS_PER_DEG
        return GjPoint.fromLngLat(lng, lat)
    }

    val factory = GeometryFactory()
    val viewportRing = arrayOf(
        project(bounds.latitudeSouth, bounds.longitudeWest),
        project(bounds.latitudeSouth, bounds.longitudeEast),
        project(bounds.latitudeNorth, bounds.longitudeEast),
        project(bounds.latitudeNorth, bounds.longitudeWest),
        project(bounds.latitudeSouth, bounds.longitudeWest),
    )
    val viewportPoly: Polygon = factory.createPolygon(viewportRing)

    // Cull points to bbox + radius buffer in degrees.
    val latBuf = FOG_RADIUS_METERS / METERS_PER_DEG
    val lngBuf = FOG_RADIUS_METERS / (max(cosCenterLat, 0.01) * METERS_PER_DEG)
    val relevant = points.filter {
        it.lat in (bounds.latitudeSouth - latBuf)..(bounds.latitudeNorth + latBuf) &&
            it.lng in (bounds.longitudeWest - lngBuf)..(bounds.longitudeEast + lngBuf)
    }

    val fog: Geometry = if (relevant.isEmpty()) {
        viewportPoly
    } else {
        val circles = relevant.map {
            factory.createPoint(project(it.lat, it.lng))
                .buffer(FOG_RADIUS_METERS, BUFFER_SEGMENTS_PER_QUADRANT)
        }
        val union = UnaryUnionOp.union(circles)
        viewportPoly.difference(union)
    }

    val simplified = DouglasPeuckerSimplifier.simplify(fog, SIMPLIFY_TOLERANCE_M)
    return jtsToGeoJsonFeature(simplified, ::unprojectToPoint)
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
