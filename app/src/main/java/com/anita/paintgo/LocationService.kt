package com.anita.paintgo

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.data.LocationPoint
import com.anita.paintgo.data.Session
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.floor

class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient

    @Volatile private var currentSessionId: Long? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val sessionId = currentSessionId ?: return
            val points = result.locations.mapNotNull { loc ->
                if (loc.accuracy > MAX_ACCURACY_METERS) {
                    Log.d("PaintGo", "Skipping low-accuracy fix: ${loc.accuracy}m")
                    null
                } else {
                    LocationPoint(
                        sessionId = sessionId,
                        lat = loc.latitude,
                        lng = loc.longitude,
                        timestamp = loc.time,
                        accuracy = loc.accuracy,
                        cellX = floor(loc.latitude / GRID_STEP_DEG).toInt(),
                        cellY = floor(loc.longitude / GRID_STEP_DEG).toInt(),
                    )
                }
            }
            if (points.isEmpty()) return
            Log.d("PaintGo", "Inserting ${points.size} point(s) into session $sessionId")
            _liveLocation.value = points.last().let { it.lat to it.lng }
            scope.launch {
                AppDatabase.get(applicationContext).locationPointDao().insertAll(points)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        Log.d("PaintGo", "LocationService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d("PaintGo", "LocationService stopping")
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                _running.value = false
                stopSelf()
            }
            else -> {
                Log.d("PaintGo", "LocationService starting foreground")
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
                _running.value = true
                startRecording()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        scope.launch {
            val db = AppDatabase.get(applicationContext)
            val self = db.ownerDao().getSelf()
            if (self == null) {
                Log.e("PaintGo", "No self Owner row; aborting recording")
                return@launch
            }
            val sessionId = db.sessionDao().insert(
                Session(ownerId = self.id, startTime = System.currentTimeMillis())
            )
            currentSessionId = sessionId
            Log.d("PaintGo", "Recording started, session=$sessionId")

            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                LOCATION_INTERVAL_MS,
            ).build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    private fun stopRecording() {
        fusedClient.removeLocationUpdates(locationCallback)
        _liveLocation.value = null
        val sessionId = currentSessionId ?: return
        currentSessionId = null
        scope.launch {
            val db = AppDatabase.get(applicationContext)
            val session = db.sessionDao().getById(sessionId) ?: return@launch
            db.sessionDao().update(session.copy(endTime = System.currentTimeMillis()))
            Log.d("PaintGo", "Recording stopped, session=$sessionId")
        }
    }

    override fun onDestroy() {
        Log.d("PaintGo", "LocationService onDestroy")
        stopRecording()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PaintGo")
            .setContentText("Recording your walk")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val CHANNEL_ID = "paintgo_recording"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.anita.paintgo.action.STOP"
        private const val LOCATION_INTERVAL_MS = 5_000L
        // 100m is liberal — emulator fixes are flat 100m, and city GPS can occasionally
        // land here. Revisit once we have real-device traces.
        private const val MAX_ACCURACY_METERS = 100f
        // ~5m at the equator (constant for lat; ~3.8m E-W at NYC latitude). Cells are
        // session-unique so stationary points collapse to one row.
        private const val GRID_STEP_DEG = 4.5e-5

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _liveLocation = MutableStateFlow<Pair<Double, Double>?>(null)
        val liveLocation: StateFlow<Pair<Double, Double>?> = _liveLocation.asStateFlow()

        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, LocationService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while PaintGo is recording your walk"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
