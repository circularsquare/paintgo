package com.anita.paintgo.regions

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import java.io.FileNotFoundException
import kotlin.math.PI
import kotlin.math.cos

interface RegionSource {
    val sourceKey: String
    val kind: RegionKind
    suspend fun loadAll(): List<Region>

    // Cache-only load. Returns empty list if the cache is cold — the caller is
    // expected to live without this source until [loadAll] warms it.
    suspend fun loadFromCache(keep: (String, String) -> Boolean): List<Region>
}

private const val METERS_PER_DEG = 111_320.0
private const val TAG = "RegionSource"

class BundledGeoJsonRegionSource(
    private val context: Context,
    override val sourceKey: String,
    override val kind: RegionKind,
    private val assetPath: String,
    private val nameKey: String,
    private val idKey: String,
) : RegionSource {

    override suspend fun loadAll(): List<Region> = withContext(Dispatchers.IO) {
        RegionCache.read(context, sourceKey)?.let { return@withContext it }
        val parsed = parseFromAsset()
        if (parsed.isNotEmpty()) RegionCache.write(context, sourceKey, parsed)
        parsed
    }

    override suspend fun loadFromCache(keep: (String, String) -> Boolean): List<Region> =
        withContext(Dispatchers.IO) {
            RegionCache.read(context, sourceKey, keep) ?: emptyList()
        }

    private fun parseFromAsset(): List<Region> {
        val text = try {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "$sourceKey: asset '$assetPath' not found; skipping")
            return emptyList()
        }

        val factory = GeometryFactory()
        val features = JSONObject(text).getJSONArray("features")
        val out = ArrayList<Region>(features.length())
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val props = f.optJSONObject("properties") ?: JSONObject()
            val name = props.optString(nameKey).ifEmpty { "Unnamed" }
            val externalId = props.optString(idKey).ifEmpty { name }
            val geomJson = f.optJSONObject("geometry") ?: continue
            val geom = parseGeometry(geomJson, factory) ?: continue
            if (geom.isEmpty) continue
            val env = geom.envelopeInternal
            out += Region(
                sourceKey = sourceKey,
                externalId = externalId,
                kind = kind,
                name = name,
                geometry = geom,
                areaM2 = localAreaM2(geom),
                bboxW = env.minX,
                bboxE = env.maxX,
                bboxS = env.minY,
                bboxN = env.maxY,
            )
        }
        return out
    }
}

private fun parseGeometry(json: JSONObject, factory: GeometryFactory): Geometry? {
    val coords = json.optJSONArray("coordinates") ?: return null
    return when (json.optString("type")) {
        "Polygon" -> parsePolygon(coords, factory)
        "MultiPolygon" -> {
            val polys = (0 until coords.length()).mapNotNull { i ->
                parsePolygon(coords.getJSONArray(i), factory)
            }
            if (polys.isEmpty()) null else factory.createMultiPolygon(polys.toTypedArray())
        }
        else -> null
    }
}

private fun parsePolygon(coords: JSONArray, factory: GeometryFactory): Polygon? {
    if (coords.length() == 0) return null
    val rings = (0 until coords.length()).map { parseRing(coords.getJSONArray(it), factory) }
    return factory.createPolygon(rings[0], rings.drop(1).toTypedArray())
}

private fun parseRing(ring: JSONArray, factory: GeometryFactory): LinearRing {
    val pts = (0 until ring.length()).map {
        val p = ring.getJSONArray(it)
        Coordinate(p.getDouble(0), p.getDouble(1)) // GeoJSON is [lng, lat]
    }
    return factory.createLinearRing(pts.toTypedArray())
}

// Local equirectangular area in m², centered on the geometry's centroid. Accurate for
// regions spanning a few degrees of latitude (boroughs, cities); loses accuracy on
// country-sized polygons — swap for a true geodesic area calc when we bundle global data.
private fun localAreaM2(geom: Geometry): Double {
    val c = geom.centroid
    val cosLat = cos(c.y * PI / 180.0)
    if (cosLat <= 0.0) return 0.0
    val xs = cosLat * METERS_PER_DEG
    val ys = METERS_PER_DEG
    val factory = GeometryFactory()
    val projected = projectGeom(geom, factory, c.x, c.y, xs, ys) ?: return 0.0
    return projected.area
}

private fun projectGeom(
    geom: Geometry,
    factory: GeometryFactory,
    cLng: Double,
    cLat: Double,
    xs: Double,
    ys: Double,
): Geometry? = when (geom) {
    is Polygon -> projectPolygon(geom, factory, cLng, cLat, xs, ys)
    is MultiPolygon -> factory.createMultiPolygon(
        (0 until geom.numGeometries).map {
            projectPolygon(geom.getGeometryN(it) as Polygon, factory, cLng, cLat, xs, ys)
        }.toTypedArray()
    )
    else -> null
}

private fun projectPolygon(
    poly: Polygon,
    factory: GeometryFactory,
    cLng: Double,
    cLat: Double,
    xs: Double,
    ys: Double,
): Polygon {
    fun projRing(ring: LineString): LinearRing {
        val pts = ring.coordinates.map { Coordinate((it.x - cLng) * xs, (it.y - cLat) * ys) }
        return factory.createLinearRing(pts.toTypedArray())
    }
    val outer = projRing(poly.exteriorRing)
    val holes = (0 until poly.numInteriorRing).map { projRing(poly.getInteriorRingN(it)) }
    return factory.createPolygon(outer, holes.toTypedArray())
}
