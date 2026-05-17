package com.anita.paintgo.regions

import android.content.Context

// Persistent set of regions the user has been observed inside, keyed by
// "sourceKey|externalId". Lets the stats screen take a fast path that only loads
// geometry + PIPs the regions we already know are interesting, instead of every
// country / state / city on every recompute.
object VisitedRegions {
    private const val PREFS = "visited_regions"
    private const val KEY = "ids"

    fun load(context: Context): Set<String> =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            ?.toSet()
            ?: emptySet()

    fun save(context: Context, ids: Set<String>) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, ids)
            .apply()
    }

    fun key(sourceKey: String, externalId: String) = "$sourceKey|$externalId"
}
