package com.anita.paintgo.regions

import android.content.Context
import android.util.Log
import org.locationtech.jts.operation.union.UnaryUnionOp

// One-call cache for parsed regions. Sources listed in [defaultSources] all load on
// first access; failures (missing asset, parse error) are logged and skipped so a
// missing data file doesn't break the stats screen.
object RegionRegistry {
    @Volatile private var cache: List<Region>? = null

    suspend fun regions(context: Context): List<Region> {
        cache?.let { return it }
        val sources = defaultSources(context.applicationContext)
        val loaded = sources.flatMap { src ->
            runCatching { src.loadAll() }
                .onFailure { Log.w("RegionRegistry", "source ${src.sourceKey} failed: $it") }
                .getOrDefault(emptyList())
        }
        val finalList = loaded + synthesizeNycCity(loaded)
        cache = finalList
        return finalList
    }

    // Fast path for the stats screen: load geometry only for regions [keep] allows,
    // skipping the WKB parse for everything else. nyc-boroughs are always loaded so
    // the synthesized NYC city stays geometrically correct when any borough is
    // visited. If the in-memory cache is already warm, filter that instead of
    // touching disk. Sources whose disk cache is cold (first launch / after APK
    // update) contribute nothing here — the slow [regions] call warms them later.
    suspend fun regionsFromCache(
        context: Context,
        keep: (String, String) -> Boolean,
    ): List<Region> {
        cache?.let { full ->
            return full.filter {
                it.sourceKey == "nyc-boroughs" ||
                    it.sourceKey == "synthetic-nyc" ||
                    keep(it.sourceKey, it.externalId)
            }
        }
        val effective: (String, String) -> Boolean = { s, e ->
            s == "nyc-boroughs" || keep(s, e)
        }
        val sources = defaultSources(context.applicationContext)
        val loaded = sources.flatMap { src ->
            runCatching { src.loadFromCache(effective) }
                .onFailure { Log.w("RegionRegistry", "source ${src.sourceKey} cache read failed: $it") }
                .getOrDefault(emptyList())
        }
        return loaded + synthesizeNycCity(loaded)
    }

    private fun defaultSources(context: Context): List<RegionSource> = listOf(
        BundledGeoJsonRegionSource(
            context = context,
            sourceKey = "natural-earth-countries",
            kind = RegionKind.COUNTRY,
            assetPath = "regions/countries.geojson",
            nameKey = "name",
            idKey = "ISO3166-1-Alpha-3",
        ),
        BundledGeoJsonRegionSource(
            context = context,
            sourceKey = "us-states",
            kind = RegionKind.STATE,
            assetPath = "regions/us-states.geojson",
            nameKey = "name",
            idKey = "name",
        ),
        BundledGeoJsonRegionSource(
            context = context,
            sourceKey = "osm-cities",
            kind = RegionKind.CITY,
            assetPath = "regions/cities.geojson",
            nameKey = "name",
            idKey = "osm_id",
        ),
        BundledGeoJsonRegionSource(
            context = context,
            sourceKey = "nyc-boroughs",
            kind = RegionKind.NEIGHBORHOOD,
            assetPath = "regions/nyc-boroughs.geojson",
            nameKey = "BoroName",
            idKey = "BoroCode",
        ),
    )

    // NYC is intentionally absent from the OSM cities bundle — its "city" is by
    // definition the union of the five boroughs we already load. Area is the sum
    // of borough areas (no overlap, so the sum is exact).
    private fun synthesizeNycCity(loaded: List<Region>): List<Region> {
        val boroughs = loaded.filter { it.sourceKey == "nyc-boroughs" }
        if (boroughs.isEmpty()) return emptyList()
        val union = UnaryUnionOp.union(boroughs.map { it.geometry }) ?: return emptyList()
        if (union.isEmpty) return emptyList()
        val env = union.envelopeInternal
        return listOf(
            Region(
                sourceKey = "synthetic-nyc",
                externalId = "NYC",
                kind = RegionKind.CITY,
                name = "New York City",
                geometry = union,
                areaM2 = boroughs.sumOf { it.areaM2 },
                bboxW = env.minX,
                bboxE = env.maxX,
                bboxS = env.minY,
                bboxN = env.maxY,
            )
        )
    }
}
