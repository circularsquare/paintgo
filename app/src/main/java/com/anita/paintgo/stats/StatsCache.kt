package com.anita.paintgo.stats

import android.content.Context
import android.util.Log
import com.anita.paintgo.regions.RegionKind
import com.anita.paintgo.regions.RegionStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Persists the last computed WalkStats so the stats screen can render values on
// the first frame instead of waiting for the fast / full passes to finish.
// SharedPreferences-backed (single JSON blob) — small enough that the keyed
// load is essentially free, and survives both process death and APK upgrades.
//
// The cache is purely a display aid; recomputes always read from the DB.
// Region rows in the cache can include keys that no longer exist in the bundle
// (renamed asset, removed entry) — they'll be displayed once and then
// overwritten by the next full pass.
object StatsCache {
    private const val PREFS = "stats_cache"
    private const val KEY = "last_stats"
    private const val TAG = "StatsCache"

    fun loadBlocking(context: Context): WalkStats? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return runCatching { parse(raw) }
            .onFailure { Log.w(TAG, "parse failed, dropping cache: $it") }
            .getOrNull()
    }

    suspend fun save(context: Context, stats: WalkStats) = withContext(Dispatchers.IO) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, serialize(stats))
            .apply()
    }

    private fun serialize(stats: WalkStats): String {
        val regions = JSONArray()
        stats.regionCoverage.forEach { r ->
            regions.put(JSONObject().apply {
                put("key", r.key)
                put("kind", r.kind.name)
                put("name", r.name)
                put("pct", r.percentCovered)
            })
        }
        return JSONObject().apply {
            put("totalKm", stats.totalKm)
            put("coveredAreaM2", stats.coveredAreaM2)
            put("regions", regions)
        }.toString()
    }

    private fun parse(raw: String): WalkStats {
        val obj = JSONObject(raw)
        val arr = obj.getJSONArray("regions")
        val regions = ArrayList<RegionStat>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val kind = runCatching { RegionKind.valueOf(r.getString("kind")) }.getOrNull()
                ?: continue
            regions.add(
                RegionStat(
                    key = r.getString("key"),
                    kind = kind,
                    name = r.getString("name"),
                    percentCovered = r.getDouble("pct"),
                )
            )
        }
        return WalkStats(
            totalKm = obj.getDouble("totalKm"),
            coveredAreaM2 = obj.getDouble("coveredAreaM2"),
            regionCoverage = regions,
        )
    }
}
