package com.anita.paintgo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.anita.paintgo.fog.computeFog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.coroutines.resume

// OpenFreeMap — free, no API key. Alternatives: liberty, bright, dark.
private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
private val DEFAULT_CENTER = LatLng(40.7128, -74.0060) // NYC
private const val DEFAULT_ZOOM = 11.0
private const val USER_ZOOM = 15.0
private const val USER_LOC_SOURCE_ID = "user-location-source"
private const val USER_LOC_LAYER_ID = "user-location-layer"
private const val FOG_SOURCE_ID = "fog-source"
private const val FOG_LAYER_ID = "fog-layer"
private const val LOCATION_TIMEOUT_MS = 15_000L
private const val LIVE_INTERVAL_MS = 2_000L

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }

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
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var fetchTrigger by remember { mutableIntStateOf(0) }
    var centerOnUser by remember { mutableStateOf(true) }
    var viewportBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    val isRecording by LocationService.running.collectAsState()
    val liveLocation by LocationService.liveLocation.collectAsState()
    val pointsFlow = remember(context) {
        AppDatabase.get(context).locationPointDao().allForSelf()
    }
    val points by pointsFlow.collectAsState(initial = emptyList())

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
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

    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Start regardless of grant — notification just won't show if denied.
        LocationService.start(context)
    }

    fun toggleRecording() {
        if (isRecording) {
            LocationService.stop(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        LocationService.start(context)
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
                    // Fog first so user-location renders on top.
                    style.addSource(GeoJsonSource(FOG_SOURCE_ID))
                    style.addLayer(
                        FillLayer(FOG_LAYER_ID, FOG_SOURCE_ID).withProperties(
                            PropertyFactory.fillColor("#3F51B5"),
                            PropertyFactory.fillOpacity(0.45f),
                            PropertyFactory.fillAntialias(true),
                        )
                    )
                    style.addSource(GeoJsonSource(USER_LOC_SOURCE_ID))
                    style.addLayer(
                        CircleLayer(USER_LOC_LAYER_ID, USER_LOC_SOURCE_ID).withProperties(
                            PropertyFactory.circleColor("#2196F3"),
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2f),
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
            if (loc != null) userLocation = LatLng(loc.latitude, loc.longitude)
        }
    }

    LaunchedEffect(liveLocation) {
        val ll = liveLocation ?: return@LaunchedEffect
        userLocation = LatLng(ll.first, ll.second)
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
                userLocation = LatLng(loc.latitude, loc.longitude)
            }
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            LIVE_INTERVAL_MS,
        ).build()
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

    LaunchedEffect(userLocation, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        val target = userLocation ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val source = map.style?.getSourceAs<GeoJsonSource>(USER_LOC_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(
                Feature.fromGeometry(Point.fromLngLat(target.longitude, target.latitude))
            )
        } else {
            Log.w("PaintGo", "User-location source not found in style")
        }
    }

    LaunchedEffect(centerOnUser, userLocation, mapRef, styleReady) {
        if (!centerOnUser) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val target = userLocation ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, USER_ZOOM))
        centerOnUser = false
    }

    LaunchedEffect(points, viewportBounds, styleReady) {
        val bounds = viewportBounds ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val feature = withContext(Dispatchers.Default) { computeFog(points, bounds) }
            ?: return@LaunchedEffect
        val source = map.style?.getSourceAs<GeoJsonSource>(FOG_SOURCE_ID) ?: return@LaunchedEffect
        source.setGeoJson(feature)
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        when {
            !hasPermission -> {
                PermissionPrompt(
                    permanentlyDenied = permanentlyDenied,
                    onRequest = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
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
                Text("Show your location on the map?")
                Button(onClick = onRequest) { Text("Use my location") }
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
