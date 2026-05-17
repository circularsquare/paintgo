package com.anita.paintgo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide user-tunable settings. Singleton wrapper around SharedPreferences that
 * surfaces each value as a [StateFlow] so the UI and the recording service can
 * react to changes without polling.
 */
/**
 * What overlay to draw on top of the basemap.
 *
 * - [Fog]: viewport-minus-points polygon — the default reveal-the-world view.
 * - [Speed]: per-segment line colored by m/s between consecutive fixes.
 * - [Recency]: per-segment line colored by log-scaled age of the fixes.
 */
enum class ViewMode { Fog, Speed, Recency }

class SettingsStore private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // View mode is intentionally NOT persisted — Fog is the canonical view, Speed
    // and Recency are diagnostic / momentary. Reopening the app drops back to Fog.
    private val _viewMode = MutableStateFlow(ViewMode.Fog)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _showBoundaries =
        MutableStateFlow(prefs.getBoolean(KEY_SHOW_BOUNDARIES, false))
    val showBoundaries: StateFlow<Boolean> = _showBoundaries.asStateFlow()

    private val _showDebugGrid =
        MutableStateFlow(prefs.getBoolean(KEY_SHOW_DEBUG_GRID, false))
    val showDebugGrid: StateFlow<Boolean> = _showDebugGrid.asStateFlow()

    private val _sampleIntervalMs =
        MutableStateFlow(prefs.getLong(KEY_SAMPLE_INTERVAL_MS, DEFAULT_SAMPLE_INTERVAL_MS))
    val sampleIntervalMs: StateFlow<Long> = _sampleIntervalMs.asStateFlow()

    private val _fogColor =
        MutableStateFlow(prefs.getString(KEY_FOG_COLOR, DEFAULT_FOG_COLOR) ?: DEFAULT_FOG_COLOR)
    val fogColor: StateFlow<String> = _fogColor.asStateFlow()

    private val _fogOpacity =
        MutableStateFlow(prefs.getFloat(KEY_FOG_OPACITY, DEFAULT_FOG_OPACITY))
    val fogOpacity: StateFlow<Float> = _fogOpacity.asStateFlow()

    fun setViewMode(value: ViewMode) {
        _viewMode.value = value
    }

    fun setShowBoundaries(value: Boolean) {
        _showBoundaries.value = value
        prefs.edit().putBoolean(KEY_SHOW_BOUNDARIES, value).apply()
    }

    fun setShowDebugGrid(value: Boolean) {
        _showDebugGrid.value = value
        prefs.edit().putBoolean(KEY_SHOW_DEBUG_GRID, value).apply()
    }

    fun setSampleIntervalMs(value: Long) {
        _sampleIntervalMs.value = value
        prefs.edit().putLong(KEY_SAMPLE_INTERVAL_MS, value).apply()
    }

    fun setFogColor(value: String) {
        _fogColor.value = value
        prefs.edit().putString(KEY_FOG_COLOR, value).apply()
    }

    fun setFogOpacity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _fogOpacity.value = clamped
        prefs.edit().putFloat(KEY_FOG_OPACITY, clamped).apply()
    }

    companion object {
        private const val PREFS_NAME = "paintgo_prefs"
        private const val KEY_SHOW_BOUNDARIES = "show_region_boundaries"
        private const val KEY_SHOW_DEBUG_GRID = "show_debug_grid"
        private const val KEY_SAMPLE_INTERVAL_MS = "sample_interval_ms"
        private const val KEY_FOG_COLOR = "fog_color"
        private const val KEY_FOG_OPACITY = "fog_opacity"

        const val DEFAULT_FOG_OPACITY = 0.45f

        const val DEFAULT_SAMPLE_INTERVAL_MS = 5_000L
        val SAMPLE_INTERVAL_CHOICES_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L)

        const val DEFAULT_FOG_COLOR = "#3F51B5"
        // Curated palette — muted/saturated tones that read clearly as "unexplored" without
        // clashing with the positron basemap. Add to this list rather than exposing a free
        // color picker; the chip layout assumes a small fixed set.
        val FOG_COLOR_CHOICES = listOf(
            "#3F51B5", // indigo (default)
            "#607D8B", // slate
            "#424242", // charcoal
            "#7E57C2", // plum
            "#009688", // teal
            "#689F38", // sage
            "#EC407A", // rose
        )

        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
