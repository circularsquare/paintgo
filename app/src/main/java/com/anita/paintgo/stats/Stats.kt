package com.anita.paintgo.stats

import com.anita.paintgo.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class WalkStats(
    val totalKm: Double,
    val sessionCount: Int,
    val longestSessionKm: Double,
    val totalDurationMs: Long,
    val totalPoints: Int,
) {
    companion object {
        val EMPTY = WalkStats(0.0, 0, 0.0, 0L, 0)
    }
}

suspend fun computeStats(db: AppDatabase): WalkStats = withContext(Dispatchers.Default) {
    val sessions = db.sessionDao().allForSelf()
    if (sessions.isEmpty()) return@withContext WalkStats.EMPTY

    val pointsBySession = db.locationPointDao().allForSelfOnce().groupBy { it.sessionId }

    var totalKm = 0.0
    var longestKm = 0.0
    var totalDuration = 0L
    var totalPoints = 0
    val now = System.currentTimeMillis()

    for (s in sessions) {
        val pts = pointsBySession[s.id].orEmpty()
        totalPoints += pts.size

        var sessionKm = 0.0
        for (i in 1 until pts.size) {
            sessionKm += haversineKm(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
        }
        totalKm += sessionKm
        if (sessionKm > longestKm) longestKm = sessionKm

        // Open sessions (still recording) count up to now.
        val end = s.endTime ?: now
        totalDuration += (end - s.startTime).coerceAtLeast(0)
    }

    WalkStats(
        totalKm = totalKm,
        sessionCount = sessions.size,
        longestSessionKm = longestKm,
        totalDurationMs = totalDuration,
        totalPoints = totalPoints,
    )
}

private const val EARTH_RADIUS_KM = 6371.0088

private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val dφ = Math.toRadians(lat2 - lat1)
    val dλ = Math.toRadians(lng2 - lng1)
    val a = sin(dφ / 2) * sin(dφ / 2) +
        cos(φ1) * cos(φ2) * sin(dλ / 2) * sin(dλ / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_KM * c
}
