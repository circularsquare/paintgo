package com.anita.paintgo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.data.FOG_COVERAGE_RADIUS_M
import com.anita.paintgo.data.LocationPoint
import com.anita.paintgo.data.POINT_GRID_STEP_LAT_DEG
import com.anita.paintgo.data.forSelfInViewport
import com.anita.paintgo.data.lngStepDeg
import com.anita.paintgo.fog.MAX_RUN_GAP_MS
import com.anita.paintgo.fog.MAX_RUN_SEGMENT_M
import com.anita.paintgo.fog.computeFogRings
import com.anita.paintgo.trail.TRAIL_COLOR_PROP
import com.anita.paintgo.trail.TrailMode
import com.anita.paintgo.trail.buildTrailFeatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon as GjPolygon
import java.net.URI
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// OpenFreeMap — free, no API key. Alternatives: liberty, bright, dark.
private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
private val DEFAULT_CENTER = LatLng(40.7128, -74.0060) // NYC
private const val DEFAULT_ZOOM = 11.0
private const val USER_ZOOM = 15.0
private const val USER_LOC_SOURCE_ID = "user-location-source"
private const val USER_LOC_LAYER_ID = "user-location-layer"
private const val USER_ACCURACY_SOURCE_ID = "user-accuracy-source"
private const val USER_ACCURACY_LAYER_ID = "user-accuracy-layer"
private const val USER_BEARING_SOURCE_ID = "user-bearing-source"
private const val USER_BEARING_LAYER_ID = "user-bearing-layer"
private const val BEARING_ICON_ID = "bearing-arrow-icon"
private const val BEARING_PROP = "bearing"
private const val LOCATION_TIMEOUT_MS = 15_000L
// Foreground "live dot" cadence. The recording service still persists at 5s for
// battery; this is just for visible-on-screen smoothness, so we ask for the fastest
// FusedLocation will give us at HIGH_ACCURACY.
private const val LIVE_INTERVAL_MS = 1_000L
private const val LIVE_MIN_INTERVAL_MS = 500L
private const val ACCURACY_CIRCLE_VERTICES = 48
private const val DOT_RADIUS_PX = 8f
// Bearing arrow: a small isoceles right triangle (45-45-90). The 90° apex points in
// the bearing direction; base angles are 45° each, so height = baseWidth / 2.
private const val ARROW_APEX_HALF_ANGLE_DEG = 45.0
private const val ARROW_BASE_WIDTH_PX = 24f
// Draw the bearing-arrow bitmap at this multiple of its rendered size, then scale it
// back down via SymbolLayer.iconSize. The triangle is small; supersampling keeps the
// edges and white stroke crisp instead of pixel-mush on hi-DPI screens.
private const val ARROW_SUPERSAMPLE = 4f
// Distance from the source point to the triangle's base, along the bearing axis, in
// rendered pixels. Must exceed DOT_RADIUS_PX + dot stroke half-width (~9 px) for the
// triangle to sit entirely outside the dot.
private const val ARROW_OUTWARD_OFFSET_PX = 12f

// Fog: a single fill layer. Each recorded point clears a disc of FOG_COVERAGE_RADIUS_M
// from the viewport polygon. Hard edge (no fade) — keeps the visual definition of
// "covered" identical to the area-coverage stat.
private const val FOG_SOURCE_ID = "fog-source"
private const val FOG_LAYER_ID = "fog-layer"
// Outer fog: a world-spanning rectangle with a hole punched out where the
// detail fog renders. Paints uniform fog over anywhere we haven't computed
// clears for, so zooming/panning past the rendered area still shows fog
// instead of the bare basemap.
private const val FOG_OUTER_SOURCE_ID = "fog-outer-source"
private const val FOG_OUTER_LAYER_ID = "fog-outer-layer"
// Latitude clamp for the world fog polygon. Web Mercator can't represent the
// poles; ±85° is the standard cap (matches MapLibre's own world bounds).
private const val FOG_WORLD_LAT_LIMIT = 85.0
// Initial fillOpacity used until the LaunchedEffect pushes the user's setting.
// The real value lives in SettingsStore.fogOpacity and is driven by    the alpha
// slider in the custom color picker.
private const val FOG_INITIAL_OPACITY = 0.45f

// Signature used to skip fog recomputes when nothing visibly changed: the cell range
// covers the viewport, the count covers in-bbox inserts. Two different point sets of
// equal size in the same cell range can't both occur in practice (we don't edit/
// delete points), so equal sig ↔ identical input.
private data class FogSig(
    val latSouth: Double,
    val latNorth: Double,
    val lngWest: Double,
    val lngEast: Double,
    val pointCount: Int,
    val segmentsPerQuadrant: Int,
    val simplifyToleranceM: Double,
)

// Web Mercator ground resolution at the equator at zoom 0. m/px at latitude lat,
// zoom z = MERCATOR_M_PER_PX_AT_EQUATOR_Z0 * cos(lat) / 2^z.
private const val MERCATOR_M_PER_PX_AT_EQUATOR_Z0 = 156_543.03392
// Target on-screen length of a single buffer-arc segment, and the Douglas-Peucker
// simplification tolerance, in pixels. ~2 px/segment makes 50m circles look round
// at any zoom; ~0.5 px tolerance keeps the simplified outline from visibly shifting
// between recomputes.
private const val FOG_TARGET_ARC_PX = 2.0
private const val FOG_TARGET_SIMPLIFY_PX = 0.5
// Floors and ceilings on the adaptive precision — keep low-zoom work cheap and
// high-zoom work bounded.
private const val FOG_MIN_SEGMENTS_PER_QUADRANT = 4
private const val FOG_MAX_SEGMENTS_PER_QUADRANT = 48
private const val FOG_MIN_SIMPLIFY_M = 0.1
private const val FOG_MAX_SIMPLIFY_M = 4.0
// Render fog over the visible viewport expanded by this fraction on each side, so
// the polygon extends past the screen edge. Small pan/zoom-out gestures stay
// covered by previously-rendered fog until the next camera-idle recompute lands,
// instead of briefly showing the underlying basemap as "uncovered".
private const val FOG_VIEWPORT_MARGIN_FRAC = 0.20

// Trail overlay (Speed / Recency view modes). Lines for connected segments, circles
// for isolated fixes. Both layers read their fill color from a per-feature property
// set in Kotlin, so the same layers serve both modes — only the property values
// differ. Layers sit just above the fog layer so the user marker still wins.
private const val TRAIL_LINE_SOURCE_ID = "trail-line-source"
private const val TRAIL_LINE_LAYER_ID = "trail-line-layer"
private const val TRAIL_POINT_SOURCE_ID = "trail-point-source"
private const val TRAIL_POINT_LAYER_ID = "trail-point-layer"
private const val TRAIL_LINE_WIDTH_DP = 5f
private const val TRAIL_LINE_OPACITY = 0.85f
private const val TRAIL_POINT_RADIUS_PX = 3.5f

// Debug grid overlay: a translucent square per hit 5m cell, plus a small dot per
// raw recorded point. Off by default; toggled via the Settings sheet. Useful for
// eyeballing dedup behavior and accuracy clusters.
private const val DEBUG_CELLS_SOURCE_ID = "debug-cells-source"
private const val DEBUG_CELLS_LAYER_ID = "debug-cells-layer"
private const val DEBUG_POINTS_SOURCE_ID = "debug-points-source"
private const val DEBUG_POINTS_LAYER_ID = "debug-points-layer"

// Delete-mode overlay: one dot per recorded point in the viewport. Tap toggles
// selection; styling switches via the data-driven "selected" property.
private const val DELETE_POINTS_SOURCE_ID = "delete-points-source"
private const val DELETE_POINTS_LAYER_ID = "delete-points-layer"
private const val DELETE_POINT_ID_PROP = "id"
private const val DELETE_POINT_SELECTED_PROP = "selected"
// Snap-to-nearest radius for taps in delete mode. ~24 dp of slack so users don't
// have to land directly on a 6px dot.
private const val DELETE_TAP_RADIUS_PX = 36f
// Preview of segments that would disappear if the selection were deleted — the two
// in-run neighbors of each selected point. Sits below DELETE_POINTS so the dots stay
// on top.
private const val DELETE_SEGMENTS_SOURCE_ID = "delete-segments-source"
private const val DELETE_SEGMENTS_LAYER_ID = "delete-segments-layer"

// Region outlines, loaded directly from bundled GeoJSON via the asset:// scheme so
// MapLibre tiles the geometry on its worker thread rather than us re-parsing. Tier-
// graded so nested borders read clearly: thicker/darker = higher administrative level.
private data class BoundaryLayerSpec(
    val sourceId: String,
    val layerId: String,
    val assetUri: String,
    val color: String,
    val widthDp: Float,
    val opacity: Float,
)

private val BOUNDARY_LAYERS = listOf(
    BoundaryLayerSpec(
        sourceId = "boundary-countries-source",
        layerId = "boundary-countries-layer",
        assetUri = "asset://regions/countries.geojson",
        color = "#333333",
        widthDp = 1.2f,
        opacity = 0.55f,
    ),
    BoundaryLayerSpec(
        sourceId = "boundary-states-source",
        layerId = "boundary-states-layer",
        assetUri = "asset://regions/us-states.geojson",
        color = "#555555",
        widthDp = 0.9f,
        opacity = 0.5f,
    ),
    BoundaryLayerSpec(
        sourceId = "boundary-cities-source",
        layerId = "boundary-cities-layer",
        assetUri = "asset://regions/cities.geojson",
        color = "#666666",
        widthDp = 0.8f,
        opacity = 0.5f,
    ),
    BoundaryLayerSpec(
        sourceId = "boundary-boroughs-source",
        layerId = "boundary-boroughs-layer",
        assetUri = "asset://regions/nyc-boroughs.geojson",
        color = "#777777",
        widthDp = 0.7f,
        opacity = 0.5f,
    ),
)

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(
    showBoundaries: Boolean,
    showDebugGrid: Boolean,
    fogColor: String,
    fogOpacity: Float,
    viewMode: ViewMode,
    centerOnUserInitially: Boolean,
    onInitialCenterConsumed: () -> Unit,
    deleteMode: Boolean,
    onDeleteModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var hasBackgroundPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var backgroundDeclined by remember { mutableStateOf(false) }
    var userFix by remember { mutableStateOf<LiveFix?>(null) }
    val userLatLng: LatLng? = userFix?.let { LatLng(it.lat, it.lng) }
    var compassHeading by remember { mutableStateOf<Float?>(null) }
    // Effective heading: prefer GPS bearing while moving, fall back to compass when
    // the provider stops reporting one (i.e. you're stationary).
    val targetBearing: Float? = userFix?.bearing ?: compassHeading
    val animatedBearing = remember { Animatable(0f) }
    var bearingShown by remember { mutableStateOf(false) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var fetchTrigger by remember { mutableIntStateOf(0) }
    // Initial value comes from the parent so re-entering Map from Stats doesn't
    // re-trigger an auto-recenter. FAB taps still set it true locally.
    var centerOnUser by remember { mutableStateOf(centerOnUserInitially) }
    var viewportBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    var lastFogSig by remember { mutableStateOf<FogSig?>(null) }
    // Delete mode is hoisted to the parent so Settings can trigger it. The local-only
    // bits — the viewport-scoped point set and the tap selection — stay here since
    // nothing outside MapScreen needs to read them.
    var deletePoints by remember { mutableStateOf<List<LocationPoint>>(emptyList()) }
    var selectedDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    val isRecording by LocationService.running.collectAsState()
    val liveLocation by LocationService.liveLocation.collectAsState()
    // Lightweight tick — just a row count. Only the bbox-filtered query inside the
    // fog effect ever materializes actual points, so we don't need the full list here.
    val pointTickFlow = remember(context) {
        AppDatabase.get(context).locationPointDao().selfPointCount()
    }
    val pointTick by pointTickFlow.collectAsState(initial = 0L)

    // Up-front permissions: bundle fine-location + notifications into a single system
    // dialog. Background location is intentionally NOT in this bundle — Android 11+
    // rejects requests that mix it with foreground perms and forces a separate prompt,
    // which the BackgroundPermissionPrompt handles after fine-location is granted.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        hasPermission = granted
        if (!granted && activity != null) {
            permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    val backgroundPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBackgroundPermission = granted
        if (!granted) backgroundDeclined = true
    }

    fun requestInitialPermissions() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    fun toggleRecording() {
        if (isRecording) LocationService.stop(context) else LocationService.start(context)
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                mapRef = map
                map.cameraPosition = CameraPosition.Builder()
                    .target(DEFAULT_CENTER)
                    .zoom(DEFAULT_ZOOM)
                    .build()
                map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                    style.layers.filterIsInstance<FillExtrusionLayer>().forEach {
                        it.setProperties(PropertyFactory.visibility(Property.NONE))
                    }
                    // Fog first (back-to-front) so user marker renders on top. The
                    // fillColor is re-pushed by a LaunchedEffect whenever the user
                    // picks a new fog color in Settings.
                    //
                    // Two stacked layers, same paint properties: the inner FOG_LAYER
                    // covers the rendered viewport (with cleared holes around tracks);
                    // the outer FOG_OUTER_LAYER fills the rest of the world. They
                    // never overlap by construction, so the user sees a single uniform
                    // fog tone everywhere except cleared discs. The outer source is
                    // seeded with a full-world fill so the world looks fogged even
                    // before the first camera-idle fires — the inner fog will paint
                    // its viewport on top and the outer will get a hole punched out
                    // at the same moment (atomic update in the fog effect).
                    style.addSource(
                        GeoJsonSource(FOG_OUTER_SOURCE_ID, buildOuterFogFeature(null))
                    )
                    style.addLayer(
                        FillLayer(FOG_OUTER_LAYER_ID, FOG_OUTER_SOURCE_ID).withProperties(
                            PropertyFactory.fillColor(fogColor),
                            PropertyFactory.fillOpacity(FOG_INITIAL_OPACITY),
                            PropertyFactory.fillAntialias(true),
                        )
                    )
                    style.addSource(GeoJsonSource(FOG_SOURCE_ID))
                    style.addLayer(
                        FillLayer(FOG_LAYER_ID, FOG_SOURCE_ID).withProperties(
                            PropertyFactory.fillColor(fogColor),
                            PropertyFactory.fillOpacity(FOG_INITIAL_OPACITY),
                            PropertyFactory.fillAntialias(true),
                        )
                    )
                    // Region outlines sit above fog (so they remain visible inside cleared
                    // areas) but below the user dot/accuracy halo. Initial visibility NONE —
                    // the showBoundaries LaunchedEffect flips it once style is ready.
                    BOUNDARY_LAYERS.forEach { b ->
                        style.addSource(GeoJsonSource(b.sourceId, URI(b.assetUri)))
                        style.addLayer(
                            LineLayer(b.layerId, b.sourceId).withProperties(
                                PropertyFactory.lineColor(b.color),
                                PropertyFactory.lineWidth(b.widthDp),
                                PropertyFactory.lineOpacity(b.opacity),
                                PropertyFactory.visibility(Property.NONE),
                            )
                        )
                    }
                    // Debug grid overlay sits above fog/borders but below the user
                    // marker. Sources start empty; populated only while the toggle is on.
                    style.addSource(GeoJsonSource(DEBUG_CELLS_SOURCE_ID))
                    style.addLayer(
                        FillLayer(DEBUG_CELLS_LAYER_ID, DEBUG_CELLS_SOURCE_ID).withProperties(
                            PropertyFactory.fillColor("#FF1744"),
                            PropertyFactory.fillOpacity(0.25f),
                            PropertyFactory.fillOutlineColor("#B71C1C"),
                            PropertyFactory.visibility(Property.NONE),
                        )
                    )
                    style.addSource(GeoJsonSource(DEBUG_POINTS_SOURCE_ID))
                    style.addLayer(
                        CircleLayer(DEBUG_POINTS_LAYER_ID, DEBUG_POINTS_SOURCE_ID).withProperties(
                            PropertyFactory.circleColor("#212121"),
                            PropertyFactory.circleRadius(2.5f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(0.5f),
                            PropertyFactory.visibility(Property.NONE),
                        )
                    )
                    // Trail overlay for Speed/Recency view modes. Per-feature `color`
                    // property carries the precomputed hex; both layers read it via
                    // get() so switching modes just re-pushes features, no restyle.
                    style.addSource(GeoJsonSource(TRAIL_LINE_SOURCE_ID))
                    style.addLayer(
                        LineLayer(TRAIL_LINE_LAYER_ID, TRAIL_LINE_SOURCE_ID).withProperties(
                            PropertyFactory.lineColor(Expression.get(TRAIL_COLOR_PROP)),
                            PropertyFactory.lineWidth(TRAIL_LINE_WIDTH_DP),
                            PropertyFactory.lineOpacity(TRAIL_LINE_OPACITY),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                            PropertyFactory.visibility(Property.NONE),
                        )
                    )
                    style.addSource(GeoJsonSource(TRAIL_POINT_SOURCE_ID))
                    style.addLayer(
                        CircleLayer(TRAIL_POINT_LAYER_ID, TRAIL_POINT_SOURCE_ID).withProperties(
                            PropertyFactory.circleColor(Expression.get(TRAIL_COLOR_PROP)),
                            PropertyFactory.circleRadius(TRAIL_POINT_RADIUS_PX),
                            PropertyFactory.circleOpacity(TRAIL_LINE_OPACITY),
                            PropertyFactory.visibility(Property.NONE),
                        )
                    )
                    // Accuracy halo — translucent blue disc sized in meters via a
                    // geometry polygon (Maplibre CircleLayer only sizes in pixels).
                    style.addSource(GeoJsonSource(USER_ACCURACY_SOURCE_ID))
                    style.addLayer(
                        FillLayer(USER_ACCURACY_LAYER_ID, USER_ACCURACY_SOURCE_ID).withProperties(
                            PropertyFactory.fillColor("#2196F3"),
                            PropertyFactory.fillOpacity(0.15f),
                            PropertyFactory.fillAntialias(true),
                        )
                    )
                    // Bearing arrow under the dot — the dot sits on the arrow's center,
                    // so the triangle pokes out in the direction of travel.
                    style.addImage(BEARING_ICON_ID, createBearingArrowBitmap(context))
                    style.addSource(GeoJsonSource(USER_BEARING_SOURCE_ID))
                    style.addLayer(
                        SymbolLayer(USER_BEARING_LAYER_ID, USER_BEARING_SOURCE_ID).withProperties(
                            PropertyFactory.iconImage(BEARING_ICON_ID),
                            PropertyFactory.iconRotate(Expression.get(BEARING_PROP)),
                            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                            PropertyFactory.iconSize(1f / ARROW_SUPERSAMPLE),
                            // iconOffset is in bitmap (pre-iconSize) pixels and is also
                            // rotated with the icon, so a negative-Y shift pushes the
                            // arrow along the bearing axis away from the dot center.
                            PropertyFactory.iconOffset(
                                arrayOf(0f, -ARROW_OUTWARD_OFFSET_PX * ARROW_SUPERSAMPLE)
                            ),
                        )
                    )
                    style.addSource(GeoJsonSource(USER_LOC_SOURCE_ID))
                    style.addLayer(
                        CircleLayer(USER_LOC_LAYER_ID, USER_LOC_SOURCE_ID).withProperties(
                            PropertyFactory.circleColor("#2196F3"),
                            PropertyFactory.circleRadius(DOT_RADIUS_PX),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2f),
                        )
                    )
                    // Segment preview: red lines connecting each selected point to its
                    // in-run neighbors. Added before the dots so dots stay on top.
                    // Hidden by default — visibility tracks delete mode.
                    style.addSource(GeoJsonSource(DELETE_SEGMENTS_SOURCE_ID))
                    style.addLayer(
                        LineLayer(DELETE_SEGMENTS_LAYER_ID, DELETE_SEGMENTS_SOURCE_ID).withProperties(
                            PropertyFactory.lineColor("#F44336"),
                            PropertyFactory.lineWidth(3f),
                            PropertyFactory.lineOpacity(0.7f),
                            PropertyFactory.visibility(Property.NONE),
                        )
                    )
                    // Delete-mode dots — added last so they render above everything.
                    // Hidden by default; visibility flipped by the delete-mode effect.
                    // Selected dots get red + larger radius via data-driven expressions
                    // so toggling selection doesn't require re-pushing layer properties.
                    style.addSource(GeoJsonSource(DELETE_POINTS_SOURCE_ID))
                    style.addLayer(
                        CircleLayer(DELETE_POINTS_LAYER_ID, DELETE_POINTS_SOURCE_ID).withProperties(
                            PropertyFactory.circleColor(
                                Expression.switchCase(
                                    Expression.get(DELETE_POINT_SELECTED_PROP),
                                    Expression.literal("#F44336"),
                                    Expression.literal("#616161"),
                                )
                            ),
                            PropertyFactory.circleRadius(
                                Expression.switchCase(
                                    Expression.get(DELETE_POINT_SELECTED_PROP),
                                    Expression.literal(8f),
                                    Expression.literal(6f),
                                )
                            ),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(1.5f),
                            PropertyFactory.visibility(Property.NONE),
                        )
                    )
                    styleReady = true
                }
                map.addOnCameraIdleListener {
                    viewportBounds = map.projection.visibleRegion.latLngBounds
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    hasPermission = granted
                    if (granted) permanentlyDenied = false
                    val bgGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    hasBackgroundPermission = bgGranted
                    if (bgGranted) backgroundDeclined = false
                }
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(hasPermission, fetchTrigger) {
        Log.d("PaintGo", "Fetch effect; hasPermission=$hasPermission trigger=$fetchTrigger")
        if (hasPermission) {
            val loc = fetchCurrentLocation(context)
            Log.d("PaintGo", "Fetch returned: $loc")
            if (loc != null) userFix = loc.toLiveFix()
        }
    }

    LaunchedEffect(liveLocation) {
        userFix = liveLocation ?: return@LaunchedEffect
    }

    // Compass heading from the fused TYPE_ROTATION_VECTOR sensor. Drives the arrow when
    // GPS bearing is absent (stationary). Lifecycle-scoped: only listens while resumed,
    // so it doesn't drain battery in the background.
    DisposableEffect(lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            // No rotation vector (very old / unusual device) — leave compassHeading null.
            return@DisposableEffect onDispose { }
        }
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthDeg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                compassHeading = azimuthDeg
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        var registered = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!registered) {
                    sensorManager.registerListener(
                        listener, rotationSensor, SensorManager.SENSOR_DELAY_UI
                    )
                    registered = true
                }
                Lifecycle.Event.ON_PAUSE -> if (registered) {
                    sensorManager.unregisterListener(listener)
                    registered = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (registered) sensorManager.unregisterListener(listener)
        }
    }

    // Smoothly rotate the arrow toward the target. Snap on first show; otherwise tween
    // over 200ms, taking the short way around the 0/360 wrap so 350° → 10° rotates
    // forward 20° rather than backward 340°.
    LaunchedEffect(targetBearing) {
        val target = targetBearing
        if (target == null) {
            bearingShown = false
            return@LaunchedEffect
        }
        if (!bearingShown) {
            animatedBearing.snapTo(target)
            bearingShown = true
        } else {
            val current = animatedBearing.value
            val delta = target - current
            val adjustedCurrent = when {
                delta > 180f -> current + 360f
                delta < -180f -> current - 360f
                else -> current
            }
            if (adjustedCurrent != current) animatedBearing.snapTo(adjustedCurrent)
            animatedBearing.animateTo(target, animationSpec = tween(durationMillis = 200))
        }
    }

    // Foreground live updates: keep the dot fresh whenever the screen is resumed.
    // The recording service has its own (denser, persisted) subscription; both can coexist —
    // Fused dedupes underneath. Disposes on PAUSE so we don't burn battery in the background.
    DisposableEffect(lifecycleOwner, hasPermission) {
        if (!hasPermission) {
            return@DisposableEffect onDispose { }
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                userFix = loc.toLiveFix()
            }
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LIVE_INTERVAL_MS,
        ).setMinUpdateIntervalMillis(LIVE_MIN_INTERVAL_MS).build()
        var subscribed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!subscribed) {
                        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                        subscribed = true
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (subscribed) {
                        client.removeLocationUpdates(callback)
                        subscribed = false
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (subscribed) client.removeLocationUpdates(callback)
        }
    }

    // Dot + accuracy halo — update only when the fix itself changes.
    LaunchedEffect(userFix, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        val fix = userFix ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect

        style.getSourceAs<GeoJsonSource>(USER_LOC_SOURCE_ID)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(fix.lng, fix.lat)))

        style.getSourceAs<GeoJsonSource>(USER_ACCURACY_SOURCE_ID)?.setGeoJson(
            Feature.fromGeometry(accuracyPolygon(fix.lat, fix.lng, fix.accuracyMeters.toDouble()))
        )
    }

    // Bearing arrow — runs on every animation frame of animatedBearing too, so the
    // rotation lerps smoothly without re-pushing the dot/halo on each tick.
    LaunchedEffect(userFix, bearingShown, animatedBearing.value, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(USER_BEARING_SOURCE_ID)
            ?: return@LaunchedEffect
        val fix = userFix
        if (!bearingShown || fix == null) {
            source.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyArray()))
        } else {
            val feature = Feature.fromGeometry(Point.fromLngLat(fix.lng, fix.lat)).apply {
                addNumberProperty(BEARING_PROP, animatedBearing.value)
            }
            source.setGeoJson(feature)
        }
    }

    LaunchedEffect(fogColor, fogOpacity, styleReady, mapRef) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        val props = arrayOf(
            PropertyFactory.fillColor(fogColor),
            PropertyFactory.fillOpacity(fogOpacity),
        )
        (style.getLayer(FOG_LAYER_ID) as? FillLayer)?.setProperties(*props)
        (style.getLayer(FOG_OUTER_LAYER_ID) as? FillLayer)?.setProperties(*props)
    }

    // View-mode visibility: exactly one of the fog layer / trail layers is visible
    // at a time. The fog effect keeps populating its source in the background even
    // when hidden, so toggling back to Fog mode is instant.
    LaunchedEffect(viewMode, styleReady, mapRef) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        val fogVis = if (viewMode == ViewMode.Fog) Property.VISIBLE else Property.NONE
        val trailVis = if (viewMode == ViewMode.Fog) Property.NONE else Property.VISIBLE
        style.getLayer(FOG_LAYER_ID)?.setProperties(PropertyFactory.visibility(fogVis))
        style.getLayer(FOG_OUTER_LAYER_ID)?.setProperties(PropertyFactory.visibility(fogVis))
        style.getLayer(TRAIL_LINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(trailVis))
        style.getLayer(TRAIL_POINT_LAYER_ID)?.setProperties(PropertyFactory.visibility(trailVis))
        // Drop trail features when leaving Speed/Recency so we're not holding a
        // viewport's worth of LineStrings in memory while invisible.
        if (viewMode == ViewMode.Fog) {
            val empty = FeatureCollection.fromFeatures(emptyArray())
            style.getSourceAs<GeoJsonSource>(TRAIL_LINE_SOURCE_ID)?.setGeoJson(empty)
            style.getSourceAs<GeoJsonSource>(TRAIL_POINT_SOURCE_ID)?.setGeoJson(empty)
        }
    }

    // Trail (Speed / Recency) feature build. Same bbox scope as fog, expanded by
    // MAX_RUN_SEGMENT_M on each side so segments crossing the viewport edge still
    // draw end-to-end. Re-runs on point inserts, viewport changes, and mode switches.
    // For the Recency view we'd also want a clock-tick refresh so old segments
    // gradually fade — skipped for v1; the colors update whenever any other key
    // re-emits, which in practice is whenever you move.
    LaunchedEffect(viewMode, pointTick, viewportBounds, styleReady) {
        if (!styleReady) return@LaunchedEffect
        if (viewMode == ViewMode.Fog) return@LaunchedEffect
        val bounds = viewportBounds ?: return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect

        val centerLat = (bounds.latitudeNorth + bounds.latitudeSouth) / 2.0
        val cosCenterLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val bufferM = MAX_RUN_SEGMENT_M
        val latBufDeg = bufferM / 111_320.0
        val lngBufDeg = bufferM / (111_320.0 * cosCenterLat)
        val viewportPoints = AppDatabase.get(context).locationPointDao().forSelfInViewport(
            latSouth = bounds.latitudeSouth - latBufDeg,
            latNorth = bounds.latitudeNorth + latBufDeg,
            lngWest = bounds.longitudeWest - lngBufDeg,
            lngEast = bounds.longitudeEast + lngBufDeg,
        )

        val mode = when (viewMode) {
            ViewMode.Speed -> TrailMode.Speed
            ViewMode.Recency -> TrailMode.Recency
            ViewMode.Fog -> return@LaunchedEffect
        }
        val nowMs = System.currentTimeMillis()
        val trail = withContext(Dispatchers.Default) {
            buildTrailFeatures(viewportPoints, mode, nowMs)
        }
        style.getSourceAs<GeoJsonSource>(TRAIL_LINE_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(trail.lines))
        style.getSourceAs<GeoJsonSource>(TRAIL_POINT_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(trail.isolatedPoints))
    }

    LaunchedEffect(showBoundaries, styleReady, mapRef) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        val visibility = if (showBoundaries) Property.VISIBLE else Property.NONE
        BOUNDARY_LAYERS.forEach { b ->
            style.getLayer(b.layerId)?.setProperties(PropertyFactory.visibility(visibility))
        }
    }

    LaunchedEffect(showDebugGrid, styleReady, mapRef) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        val visibility = if (showDebugGrid) Property.VISIBLE else Property.NONE
        style.getLayer(DEBUG_CELLS_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(DEBUG_POINTS_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        // When turning off, drop the source data so we're not holding a viewport of
        // features in memory while invisible.
        if (!showDebugGrid) {
            val empty = FeatureCollection.fromFeatures(emptyArray())
            style.getSourceAs<GeoJsonSource>(DEBUG_CELLS_SOURCE_ID)?.setGeoJson(empty)
            style.getSourceAs<GeoJsonSource>(DEBUG_POINTS_SOURCE_ID)?.setGeoJson(empty)
        }
    }

    // Debug grid: same viewport scope as the fog effect, but emits one square per
    // unique (band, cellX, cellY) hit plus a dot per raw point. Only runs while the
    // toggle is on.
    LaunchedEffect(pointTick, viewportBounds, styleReady, showDebugGrid) {
        if (!showDebugGrid) return@LaunchedEffect
        val bounds = viewportBounds ?: return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect

        val points = AppDatabase.get(context).locationPointDao().forSelfInViewport(
            latSouth = bounds.latitudeSouth,
            latNorth = bounds.latitudeNorth,
            lngWest = bounds.longitudeWest,
            lngEast = bounds.longitudeEast,
        )

        val (cellFeatures, pointFeatures) = withContext(Dispatchers.Default) {
            buildDebugGridFeatures(points)
        }
        style.getSourceAs<GeoJsonSource>(DEBUG_CELLS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(cellFeatures))
        style.getSourceAs<GeoJsonSource>(DEBUG_POINTS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(pointFeatures))
    }

    LaunchedEffect(centerOnUser, userLatLng, mapRef, styleReady) {
        if (!centerOnUser) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val target = userLatLng ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, USER_ZOOM))
        centerOnUser = false
        onInitialCenterConsumed()
    }

    // Delete-mode overlay: load viewport points while the mode is on. Refreshes on
    // viewport pan/zoom and whenever pointTick changes (e.g. after a delete shrinks
    // the set). When the mode flips off we drop the source data so we're not holding
    // a viewport's worth of features in memory while invisible.
    LaunchedEffect(deleteMode, viewportBounds, pointTick, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        if (!deleteMode) {
            deletePoints = emptyList()
            val empty = FeatureCollection.fromFeatures(emptyArray())
            style.getSourceAs<GeoJsonSource>(DELETE_POINTS_SOURCE_ID)?.setGeoJson(empty)
            style.getSourceAs<GeoJsonSource>(DELETE_SEGMENTS_SOURCE_ID)?.setGeoJson(empty)
            return@LaunchedEffect
        }
        val bounds = viewportBounds ?: return@LaunchedEffect
        val points = AppDatabase.get(context).locationPointDao().forSelfInViewport(
            latSouth = bounds.latitudeSouth,
            latNorth = bounds.latitudeNorth,
            lngWest = bounds.longitudeWest,
            lngEast = bounds.longitudeEast,
        )
        deletePoints = points
        // Selection set may reference points outside the new viewport — keep them in
        // memory but only push features for what's currently visible.
        val features = Array(points.size) { i ->
            val p = points[i]
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addNumberProperty(DELETE_POINT_ID_PROP, p.id)
                addBooleanProperty(DELETE_POINT_SELECTED_PROP, p.id in selectedDeleteIds)
            }
        }
        style.getSourceAs<GeoJsonSource>(DELETE_POINTS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Toggle the layer's visibility separately so we don't have to re-push features
    // every time the mode flips.
    LaunchedEffect(deleteMode, styleReady, mapRef) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        val visibility = if (deleteMode) Property.VISIBLE else Property.NONE
        style.getLayer(DELETE_POINTS_LAYER_ID)
            ?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(DELETE_SEGMENTS_LAYER_ID)
            ?.setProperties(PropertyFactory.visibility(visibility))
    }

    // Re-push features when the selection set changes (without re-querying the DB).
    // The data-driven circleColor/circleRadius expressions read the per-feature
    // "selected" property, so updating it is enough to restyle in place. Same
    // trigger rebuilds the segment-preview lines: the two in-run neighbors of each
    // selected point, drawn iff they're within fog's run-break thresholds (so we
    // don't preview a segment the fog renderer wouldn't have drawn anyway).
    LaunchedEffect(selectedDeleteIds, deletePoints, styleReady, mapRef) {
        if (!styleReady || !deleteMode) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        val features = Array(deletePoints.size) { i ->
            val p = deletePoints[i]
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addNumberProperty(DELETE_POINT_ID_PROP, p.id)
                addBooleanProperty(DELETE_POINT_SELECTED_PROP, p.id in selectedDeleteIds)
            }
        }
        style.getSourceAs<GeoJsonSource>(DELETE_POINTS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))

        val segmentFeatures = computeAdjacentSegments(
            AppDatabase.get(context).locationPointDao(),
            selectedDeleteIds,
        )
        style.getSourceAs<GeoJsonSource>(DELETE_SEGMENTS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(segmentFeatures))
    }

    // Tap handling for delete mode. Snaps to the nearest viewport point within
    // DELETE_TAP_RADIUS_PX so users don't have to land precisely on a 6px dot;
    // returns true (consumed) only when a tap actually lands on a point, so map
    // taps in normal mode (and missed taps in delete mode) still pass through.
    // [deleteMode] is a function parameter (captured-by-value), so the click-listener
    // closure would freeze on the value it had at registration time. Snapshot it
    // through a State so the listener reads the latest value on each tap.
    val deleteModeLatest by rememberUpdatedState(deleteMode)
    DisposableEffect(mapRef) {
        val map = mapRef ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            if (!deleteModeLatest) return@OnMapClickListener false
            val tapScreen = map.projection.toScreenLocation(latLng)
            var closestId: Long? = null
            var closestDist = Float.MAX_VALUE
            for (p in deletePoints) {
                val s = map.projection.toScreenLocation(LatLng(p.lat, p.lng))
                val dx = s.x - tapScreen.x
                val dy = s.y - tapScreen.y
                val d = sqrt(dx * dx + dy * dy)
                if (d < closestDist) {
                    closestDist = d
                    closestId = p.id
                }
            }
            val hit = closestId
            if (hit != null && closestDist <= DELETE_TAP_RADIUS_PX) {
                selectedDeleteIds = if (hit in selectedDeleteIds) {
                    selectedDeleteIds - hit
                } else {
                    selectedDeleteIds + hit
                }
                true
            } else {
                false
            }
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }

    // Fog effect: re-runs when the points flow emits (a fix landed) OR the camera
    // settles on new bounds. We query only the points whose 5m cell intersects the
    // visible viewport expanded by FOG_COVERAGE_RADIUS_M + max-run-segment — anything
    // farther out can't visibly draw inside the viewport. The signature dedup skips
    // the JTS work when an insert lands off-screen, which is the common case while
    // recording: a new fix near you doesn't change the fog in Brooklyn.
    LaunchedEffect(pointTick, viewportBounds, styleReady, viewMode) {
        val bounds = viewportBounds ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        // Skip JTS work entirely when the fog layer isn't being shown. The previously
        // computed fog source data is kept, so flipping back to Fog mode shows the
        // last-good polygon immediately; the next pointTick/viewport change recomputes.
        if (viewMode != ViewMode.Fog) return@LaunchedEffect

        // Expand visible bounds by FOG_VIEWPORT_MARGIN_FRAC on each side so the
        // rendered fog polygon extends past the screen edge. Clamps lat to ±90;
        // lng is left unclamped (LatLngBounds tolerates extreme zoom-out wraps
        // and the JTS projection is local-equirectangular anyway).
        val visLatSpan = bounds.latitudeNorth - bounds.latitudeSouth
        val visLngSpan = bounds.longitudeEast - bounds.longitudeWest
        val marginLat = visLatSpan * FOG_VIEWPORT_MARGIN_FRAC
        val marginLng = visLngSpan * FOG_VIEWPORT_MARGIN_FRAC
        val renderLatSouth = (bounds.latitudeSouth - marginLat).coerceAtLeast(-90.0)
        val renderLatNorth = (bounds.latitudeNorth + marginLat).coerceAtMost(90.0)
        val renderLngWest = bounds.longitudeWest - marginLng
        val renderLngEast = bounds.longitudeEast + marginLng
        val renderBounds = LatLngBounds.from(
            renderLatNorth, renderLngEast, renderLatSouth, renderLngWest,
        )

        val bufferM = FOG_COVERAGE_RADIUS_M + MAX_RUN_SEGMENT_M
        val centerLat = (renderLatNorth + renderLatSouth) / 2.0
        val cosCenterLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val latBufDeg = bufferM / 111_320.0
        val lngBufDeg = bufferM / (111_320.0 * cosCenterLat)
        val latSouth = renderLatSouth - latBufDeg
        val latNorth = renderLatNorth + latBufDeg
        val lngWest = renderLngWest - lngBufDeg
        val lngEast = renderLngEast + lngBufDeg

        val viewportPoints = AppDatabase.get(context).locationPointDao()
            .forSelfInViewport(latSouth, latNorth, lngWest, lngEast)

        // Pick polygon precision so the rendered fog edges stay sub-faceted at the
        // current zoom. mPerPx is the on-screen scale at the viewport center; the
        // segment count and simplify tolerance both target a constant pixel size, so
        // the circle looks equally smooth whether you're at zoom 11 or 20.
        val zoom = map.cameraPosition.zoom
        val mPerPx = MERCATOR_M_PER_PX_AT_EQUATOR_Z0 * cosCenterLat / 2.0.pow(zoom)
        val targetArcM = FOG_TARGET_ARC_PX * mPerPx
        val segmentsPerQuadrant = (PI * FOG_COVERAGE_RADIUS_M / (4.0 * targetArcM))
            .toInt()
            .coerceIn(FOG_MIN_SEGMENTS_PER_QUADRANT, FOG_MAX_SEGMENTS_PER_QUADRANT)
        val simplifyToleranceM = (FOG_TARGET_SIMPLIFY_PX * mPerPx)
            .coerceIn(FOG_MIN_SIMPLIFY_M, FOG_MAX_SIMPLIFY_M)

        val sig = FogSig(
            latSouth, latNorth, lngWest, lngEast, viewportPoints.size,
            segmentsPerQuadrant, simplifyToleranceM,
        )
        if (sig == lastFogSig) return@LaunchedEffect
        lastFogSig = sig

        val features = withContext(Dispatchers.Default) {
            computeFogRings(
                points = viewportPoints,
                bounds = renderBounds,
                radiiMeters = listOf(FOG_COVERAGE_RADIUS_M),
                bufferSegmentsPerQuadrant = segmentsPerQuadrant,
                simplifyToleranceM = simplifyToleranceM,
            )
        } ?: return@LaunchedEffect
        val feature = features.firstOrNull() ?: return@LaunchedEffect
        // Push both layers together so the outer fog gets its hole punched out
        // at the same render frame the inner fog appears — no momentary
        // double-opacity overlap.
        val outerFeature = buildOuterFogFeature(renderBounds)
        map.style?.let { style ->
            style.getSourceAs<GeoJsonSource>(FOG_OUTER_SOURCE_ID)?.setGeoJson(outerFeature)
            style.getSourceAs<GeoJsonSource>(FOG_SOURCE_ID)?.setGeoJson(feature)
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        when {
            !hasPermission -> {
                PermissionPrompt(
                    permanentlyDenied = permanentlyDenied,
                    onRequest = { requestInitialPermissions() },
                    onOpenSettings = { openAppSettings(context) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
            !hasBackgroundPermission && !backgroundDeclined -> {
                BackgroundPermissionPrompt(
                    onAllow = { backgroundPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    onDecline = { backgroundDeclined = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
            else -> {
                if (deleteMode) {
                    DeleteModeBar(
                        selectedCount = selectedDeleteIds.size,
                        onCancel = {
                            onDeleteModeChange(false)
                            selectedDeleteIds = emptySet()
                        },
                        onDelete = { deleteConfirmOpen = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FloatingActionButton(
                            onClick = {
                                centerOnUser = true
                                if (!isRecording) fetchTrigger++
                            }
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = "Recenter on my location")
                        }
                        FloatingActionButton(onClick = ::toggleRecording) {
                            if (isRecording) {
                                Icon(Icons.Filled.Close, contentDescription = "Stop recording")
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Start recording")
                            }
                        }
                    }
                }
            }
        }
        if (deleteConfirmOpen) {
            val count = selectedDeleteIds.size
            AlertDialog(
                onDismissRequest = { deleteConfirmOpen = false },
                title = { Text("Delete $count point${if (count == 1) "" else "s"}?") },
                text = { Text("This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        val ids = selectedDeleteIds.toList()
                        deleteConfirmOpen = false
                        onDeleteModeChange(false)
                        selectedDeleteIds = emptySet()
                        scope.launch(Dispatchers.IO) {
                            AppDatabase.get(context).locationPointDao().deleteByIds(ids)
                        }
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirmOpen = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun DeleteModeBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectedCount == 0) "Tap points to select"
                    else "$selectedCount selected",
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = onDelete,
                enabled = selectedCount > 0,
            ) { Text("Delete") }
        }
    }
}

@Composable
private fun PermissionPrompt(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (permanentlyDenied) {
                Text("Location permission is off. Enable it in system settings to center the map on you.")
                Button(onClick = onOpenSettings) { Text("Open settings") }
            } else {
                Text(
                    "PaintGo needs your location to show where you've been, plus permission " +
                        "to post a notification while it's recording. You'll be asked once."
                )
                Button(onClick = onRequest) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun BackgroundPermissionPrompt(
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Let PaintGo track your walks with the screen off? " +
                    "Choose “Allow all the time” so your route keeps recording when your phone is in your pocket."
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDecline) { Text("Not now") }
                Button(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun fetchCurrentLocation(context: Context): Location? {
    val client = LocationServices.getFusedLocationProviderClient(context)

    val fresh = suspendCancellableCoroutine<Location?> { cont ->
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener {
                Log.d("PaintGo", "getCurrentLocation: $it")
                cont.resume(it)
            }
            .addOnFailureListener {
                Log.w("PaintGo", "getCurrentLocation failed", it)
                cont.resume(null)
            }
    }
    if (fresh != null) return fresh

    Log.d("PaintGo", "Falling back to requestLocationUpdates")
    val updated = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
        suspendCancellableCoroutine<Location?> { cont ->
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation
                    Log.d("PaintGo", "requestLocationUpdates fired: $loc")
                    if (loc != null) {
                        client.removeLocationUpdates(this)
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMaxUpdates(1)
                .build()
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            cont.invokeOnCancellation { client.removeLocationUpdates(callback) }
        }
    }
    if (updated != null) return updated

    Log.d("PaintGo", "Falling back to lastLocation")
    return suspendCancellableCoroutine { cont ->
        client.lastLocation
            .addOnSuccessListener {
                Log.d("PaintGo", "lastLocation: $it")
                cont.resume(it)
            }
            .addOnFailureListener {
                Log.w("PaintGo", "lastLocation failed", it)
                cont.resume(null)
            }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun Location.toLiveFix(): LiveFix = LiveFix(
    lat = latitude,
    lng = longitude,
    accuracyMeters = accuracy,
    bearing = if (hasBearing()) bearing else null,
)

// Builds the debug grid overlay features from the raw points returned by the bbox query.
// cellX is in POINT_GRID_STEP_LAT_DEG units (band-independent); cellY is in band-specific
// lngStepDeg(band) units, so each hit cell becomes an axis-aligned rectangle whose lng
// width depends on the band's representative latitude.
// Segments adjacent to selected points — one LineString per (prev↔selected) /
// (selected↔next) pair that's same-session and within the fog renderer's run
// thresholds. Fetches neighbors directly from the DB rather than relying on the
// viewport-loaded point set: the common case is zooming in on a glitch whose
// true neighbor sits far off-screen, and we want the line drawn across the
// viewport edge so the user can see "this connects to somewhere out there".
private suspend fun computeAdjacentSegments(
    dao: com.anita.paintgo.data.LocationPointDao,
    selectedIds: Set<Long>,
): Array<Feature> {
    if (selectedIds.isEmpty()) return emptyArray()
    val selected = dao.byIds(selectedIds.toList())
    val out = ArrayList<Feature>(selected.size * 2)
    val seen = HashSet<Long>()  // dedupe shared edges between two adjacent selections
    for (p in selected) {
        val prev = dao.prevInSession(p.sessionId, p.timestamp)
        if (prev != null && isAdjacentInRun(prev, p) && seen.add(edgeKey(prev.id, p.id))) {
            out.add(segmentFeature(prev, p))
        }
        val next = dao.nextInSession(p.sessionId, p.timestamp)
        if (next != null && isAdjacentInRun(p, next) && seen.add(edgeKey(p.id, next.id))) {
            out.add(segmentFeature(p, next))
        }
    }
    return out.toTypedArray()
}

private fun edgeKey(a: Long, b: Long): Long {
    val lo = minOf(a, b)
    val hi = maxOf(a, b)
    return (lo shl 32) xor hi
}

private fun isAdjacentInRun(a: LocationPoint, b: LocationPoint): Boolean {
    if (a.sessionId != b.sessionId) return false
    if (b.timestamp - a.timestamp > MAX_RUN_GAP_MS) return false
    val cosLat = cos(Math.toRadians((a.lat + b.lat) / 2.0))
    val dx = (b.lng - a.lng) * 111_320.0 * cosLat
    val dy = (b.lat - a.lat) * 111_320.0
    return dx * dx + dy * dy <= MAX_RUN_SEGMENT_M * MAX_RUN_SEGMENT_M
}

private fun segmentFeature(a: LocationPoint, b: LocationPoint): Feature =
    Feature.fromGeometry(
        org.maplibre.geojson.LineString.fromLngLats(
            listOf(
                Point.fromLngLat(a.lng, a.lat),
                Point.fromLngLat(b.lng, b.lat),
            )
        )
    )

private data class DebugCellKey(val band: Int, val cellX: Int, val cellY: Int)
private fun buildDebugGridFeatures(
    points: List<LocationPoint>,
): Pair<Array<Feature>, Array<Feature>> {
    val cells = HashSet<DebugCellKey>(points.size)
    val cellFeatures = ArrayList<Feature>()
    for (p in points) {
        if (!cells.add(DebugCellKey(p.band, p.cellX, p.cellY))) continue
        val lngStep = lngStepDeg(p.band)
        val latLo = p.cellX * POINT_GRID_STEP_LAT_DEG
        val latHi = (p.cellX + 1) * POINT_GRID_STEP_LAT_DEG
        val lngLo = p.cellY * lngStep
        val lngHi = (p.cellY + 1) * lngStep
        val ring = listOf(
            Point.fromLngLat(lngLo, latLo),
            Point.fromLngLat(lngHi, latLo),
            Point.fromLngLat(lngHi, latHi),
            Point.fromLngLat(lngLo, latHi),
            Point.fromLngLat(lngLo, latLo),
        )
        cellFeatures.add(Feature.fromGeometry(GjPolygon.fromLngLats(listOf(ring))))
    }
    val pointFeatures = Array(points.size) { i ->
        val p = points[i]
        Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat))
    }
    return cellFeatures.toTypedArray() to pointFeatures
}

// Outer fog: a world rectangle, optionally with the rendered viewport bounds
// punched out as a hole. Passing null gives the full-world fill used to seed
// the source before the first camera-idle fog computation runs. Ring winding
// follows the GeoJSON right-hand rule (exterior CCW, holes CW) so MapLibre
// fills the area between them.
private fun buildOuterFogFeature(hole: LatLngBounds?): Feature {
    val n = FOG_WORLD_LAT_LIMIT
    val outer = listOf(
        Point.fromLngLat(-180.0, -n),
        Point.fromLngLat(180.0, -n),
        Point.fromLngLat(180.0, n),
        Point.fromLngLat(-180.0, n),
        Point.fromLngLat(-180.0, -n),
    )
    val rings = if (hole == null) {
        listOf(outer)
    } else {
        // Clamp the hole's lng to ±180 in case extreme zoom-out + 20% margin
        // pushed renderBounds past the antimeridian. Outside that range MapLibre's
        // tile boundaries would do unpredictable things with the hole anyway.
        val w = hole.longitudeWest.coerceIn(-180.0, 180.0)
        val e = hole.longitudeEast.coerceIn(-180.0, 180.0)
        val s = hole.latitudeSouth.coerceIn(-n, n)
        val nLat = hole.latitudeNorth.coerceIn(-n, n)
        val inner = listOf(
            Point.fromLngLat(w, s),
            Point.fromLngLat(w, nLat),
            Point.fromLngLat(e, nLat),
            Point.fromLngLat(e, s),
            Point.fromLngLat(w, s),
        )
        listOf(outer, inner)
    }
    return Feature.fromGeometry(GjPolygon.fromLngLats(rings))
}

// Polygon approximation of a circle of [radiusMeters] around (lat, lng). Equirectangular
// stretch in longitude handled via cos(lat) — accurate within a few cm at city scales,
// which is well under GPS noise.
private fun accuracyPolygon(lat: Double, lng: Double, radiusMeters: Double): GjPolygon {
    val metersPerDegLat = 111_320.0
    val metersPerDegLng = metersPerDegLat * cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
    val ring = (0..ACCURACY_CIRCLE_VERTICES).map { i ->
        val theta = 2.0 * PI * i / ACCURACY_CIRCLE_VERTICES
        val dLat = radiusMeters * sin(theta) / metersPerDegLat
        val dLng = radiusMeters * cos(theta) / metersPerDegLng
        Point.fromLngLat(lng + dLng, lat + dLat)
    }
    return GjPolygon.fromLngLats(listOf(ring))
}

// Isoceles triangle pointing up the bitmap's +Y axis. With ARROW_APEX_HALF_ANGLE_DEG=45
// this is a 45-45-90 right triangle (apex angle 90°), so height = baseWidth / 2.
// Drawn at ARROW_SUPERSAMPLE× scale; SymbolLayer.iconSize scales it back down. Layout:
//
//   y = padding             : apex
//   y = padding + triHeight : base (cx ± triBase/2)
//   y = padding + triHeight + padding (= H) : bitmap bottom
//
// iconOffset on the SymbolLayer pushes the icon outward along the bearing axis so the
// triangle sits entirely past the dot — see ARROW_OUTWARD_OFFSET_PX.
private fun createBearingArrowBitmap(@Suppress("UNUSED_PARAMETER") context: Context): Bitmap {
    val triBase = ARROW_BASE_WIDTH_PX * ARROW_SUPERSAMPLE
    val halfAngleRad = Math.toRadians(ARROW_APEX_HALF_ANGLE_DEG)
    val triHeight = (triBase / 2f) / tan(halfAngleRad).toFloat()
    val padding = ARROW_SUPERSAMPLE  // ~1 rendered px after iconSize scaling

    val w = (triBase + 2 * padding).toInt().coerceAtLeast(1)
    val h = (triHeight + 2 * padding).toInt().coerceAtLeast(1)
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bm)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt()
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * ARROW_SUPERSAMPLE  // matches the dot's 2px white outline
        strokeJoin = Paint.Join.ROUND
    }
    val cx = w / 2f
    val baseY = padding + triHeight
    val path = Path().apply {
        moveTo(cx, padding)                       // apex
        lineTo(cx + triBase / 2f, baseY)          // base right
        lineTo(cx - triBase / 2f, baseY)          // base left
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return bm
}
