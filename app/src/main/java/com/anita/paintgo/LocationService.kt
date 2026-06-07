package com.anita.paintgo

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.data.LocationPoint
import com.anita.paintgo.data.Session
import com.anita.paintgo.data.bandOf
import com.anita.paintgo.data.cellXOf
import com.anita.paintgo.data.cellYOf
import com.anita.paintgo.data.markPointsDirty
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/** Latest fix surfaced for the live UI — position plus the bits used to draw the
 *  user marker (accuracy disc, bearing arrow). Bearing is null when the provider
 *  doesn't report one (e.g. stationary). */
data class LiveFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float,
    val bearing: Float?,
)

class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient

    @Volatile private var currentSessionId: Long? = null
    private var intervalJob: Job? = null
    @Volatile private var subscribed = false

    // Adaptive-polling state. All access goes through synchronized(adaptiveLock) — the
    // location callback fires on the main looper, the settings-flow collector fires on
    // IO, and the lifecycle observer fires on main, so reads/writes overlap.
    private val adaptiveLock = Any()
    private val recentFixes = ArrayDeque<Location>()
    private var stationarySinceMs: Long? = null
    private var isAppForeground = false
    private var baseIntervalMs: Long = SettingsStore.DEFAULT_SAMPLE_INTERVAL_MS
    private var currentTargetIntervalMs: Long = 0L
    // -1 = "no request live yet" so the first evaluate always resubscribes (0 is a
    // valid delay — foreground/no-batching — and would otherwise look unchanged).
    private var currentMaxDelayMs: Long = -1L

    // Dormancy: when deeply stationary + backgrounded we drop GPS entirely and sleep on
    // the hardware significant-motion trigger, with a periodic safety poll as a backstop.
    private lateinit var sensorManager: SensorManager
    private var sigMotionSensor: Sensor? = null
    private var sigMotionAvailable = false
    @Volatile private var sigMotionArmed = false
    @Volatile private var dormant = false
    @Volatile private var dormantAnchor: Location? = null
    private var safetyPollJob: Job? = null

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                synchronized(adaptiveLock) { isAppForeground = true }
                evaluateInterval()
            }
            Lifecycle.Event.ON_STOP -> {
                synchronized(adaptiveLock) { isAppForeground = false }
                evaluateInterval()
            }
            else -> {}
        }
    }

    /** Fired once by the hardware significant-motion sensor while we're dormant. The
     *  sensor disarms itself on fire, so we just resume and re-arm if we sleep again. */
    private val motionTriggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Log.d("PaintGo", "Significant motion — resuming from dormant")
            sigMotionArmed = false
            exitDormant()
            evaluateInterval()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Live dot: surface the latest fix at any accuracy so the visible marker
            // stays responsive when GPS is shaky. The accuracy halo grows to match,
            // so the user can read the uncertainty visually. Recording / fog writes
            // below still filter on MAX_ACCURACY_METERS, so noisy fixes don't
            // reveal fog or pollute the DB.
            result.lastLocation?.let { updateLiveDot(it) }
            ingestFixes(result.locations)
        }
    }

    /** Push a fix to the live UI marker, regardless of accuracy. */
    private fun updateLiveDot(loc: Location) {
        _liveLocation.value = LiveFix(
            lat = loc.latitude,
            lng = loc.longitude,
            accuracyMeters = loc.accuracy,
            bearing = if (loc.hasBearing()) loc.bearing else null,
        )
    }

    /** Filter, persist, and paint the given fixes for the active session, then fold them
     *  into the adaptive-polling window. Shared by the continuous stream and the manual
     *  one-shot poll. No-op when not recording or when nothing clears the accuracy gate. */
    private fun ingestFixes(locations: List<Location>) {
        val sessionId = currentSessionId ?: return

        // Batched delivery (setMaxUpdateDelayMillis) can hand back fixes out of order;
        // non-batched delivery is monotonic. Sort by the monotonic boot clock before
        // we fold them into the movement window, which assumes chronological order.
        val ordered = locations.sortedBy { it.elapsedRealtimeNanos }

        val goodFixes = ordered.filter { loc ->
            val ok = loc.accuracy <= MAX_ACCURACY_METERS
            if (!ok) Log.d("PaintGo", "Skipping low-accuracy fix: ${loc.accuracy}m")
            ok
        }
        if (goodFixes.isEmpty()) return

        val points = goodFixes.map { loc ->
            val band = bandOf(loc.latitude)
            LocationPoint(
                sessionId = sessionId,
                lat = loc.latitude,
                lng = loc.longitude,
                timestamp = loc.time,
                accuracy = loc.accuracy,
                band = band,
                cellX = cellXOf(loc.latitude),
                cellY = cellYOf(loc.longitude, band),
            )
        }
        Log.d("PaintGo", "Inserting ${points.size} point(s) into session $sessionId")
        scope.launch {
            val db = AppDatabase.get(applicationContext)
            db.locationPointDao().insertAll(points)
            // Mark the touched chunks + session dirty so the next StatsEngine refresh
            // recomputes only them. Dup-cell inserts that IGNORE still mark dirty —
            // harmless, the recompute just re-derives the same count.
            db.markPointsDirty(points)
        }

        synchronized(adaptiveLock) {
            for (loc in goodFixes) {
                recentFixes.addLast(loc)
                while (recentFixes.size > WINDOW_SIZE) recentFixes.removeFirst()
            }
            updateStationaryStateLocked()
        }
        evaluateInterval()
    }

    /** One-shot high-accuracy fix on top of the running stream — fired when the user taps
     *  the recenter pin while recording. Forces the GPS chip harder than the stream's
     *  balanced-power request, so it can drop a sharper point on demand instead of waiting
     *  for the next scheduled sample. Same accuracy gate as the stream, so a bad fix still
     *  only moves the dot. */
    @SuppressLint("MissingPermission")
    private fun pollNow() {
        if (currentSessionId == null) return
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                Log.d("PaintGo", "Manual poll fix: $loc")
                if (loc != null) {
                    updateLiveDot(loc)
                    ingestFixes(listOf(loc))
                }
            }
            .addOnFailureListener { Log.w("PaintGo", "Manual poll failed", it) }
    }

    /** Caller must hold [adaptiveLock]. Marks the start of a stationary streak (or
     *  clears it if we're moving). Anchoring to the earliest fix in the window means
     *  we don't have to wait an extra window-length to ramp down. */
    private fun updateStationaryStateLocked() {
        if (isMovingByWindowLocked()) {
            stationarySinceMs = null
        } else if (stationarySinceMs == null) {
            stationarySinceMs = recentFixes.firstOrNull()?.time ?: System.currentTimeMillis()
        }
    }

    /** Caller must hold [adaptiveLock]. True when we don't have enough data to judge,
     *  or when total displacement over the window exceeds the stationary threshold. */
    private fun isMovingByWindowLocked(): Boolean {
        if (recentFixes.size < 2) return true
        val first = recentFixes.first()
        val last = recentFixes.last()
        val timeSecs = (last.time - first.time) / 1000.0
        if (timeSecs < MIN_WINDOW_SECS) return true
        var dist = 0f
        var prev = first
        for (i in 1 until recentFixes.size) {
            val cur = recentFixes.elementAt(i)
            dist += prev.distanceTo(cur)
            prev = cur
        }
        val avgSpeedMps = dist / timeSecs.toFloat()
        return avgSpeedMps >= STATIONARY_SPEED_MPS
    }

    /** Recompute the desired sample interval from current state; resubscribe iff it
     *  changed. Cheap to call repeatedly — it's a no-op when the target is stable. */
    private fun evaluateInterval() {
        if (currentSessionId == null) return

        // Deepest rung of the ramp: backgrounded + long-stationary + the sensor exists →
        // drop GPS entirely and wait for a significant-motion wake instead of polling.
        if (synchronized(adaptiveLock) { shouldBeDormantLocked() }) {
            enterDormant()
            return
        }
        // Not (or no longer) dormant. If we were, wake up first — exitDormant() resets
        // the movement window so the interval below comes from fresh post-wake fixes.
        if (dormant) exitDormant()

        val target: Long
        val maxDelay: Long
        val shouldResubscribe: Boolean
        synchronized(adaptiveLock) {
            target = computeTargetIntervalLocked()
            maxDelay = computeMaxDelayLocked(target)
            shouldResubscribe = target != currentTargetIntervalMs || maxDelay != currentMaxDelayMs
            if (shouldResubscribe) {
                currentTargetIntervalMs = target
                currentMaxDelayMs = maxDelay
            }
        }
        if (shouldResubscribe) doResubscribe(target, maxDelay)
    }

    /** Caller must hold [adaptiveLock]. How long FusedLocation may buffer fixes before
     *  delivering them in one batched callback. Foreground → 0: deliver each fix
     *  immediately so the live dot tracks. Backgrounded → at least
     *  BACKGROUND_BATCH_WINDOW_MS, so the chip buffers several fixes and the CPU wakes
     *  in bursts instead of once per fix. The sampling rate ([intervalMs]) is unchanged
     *  either way — only delivery timing differs. */
    private fun computeMaxDelayLocked(intervalMs: Long): Long {
        if (isAppForeground) return 0L
        return max(intervalMs, BACKGROUND_BATCH_WINDOW_MS)
    }

    private fun computeTargetIntervalLocked(): Long {
        val base = baseIntervalMs
        // No movement baseline yet → sample at the user's chosen rate.
        val since = stationarySinceMs ?: return base
        val stationaryMs = System.currentTimeMillis() - since
        // Foreground keeps a faster floor than background: the live dot is on-screen, so
        // ramp down only as far as FOREGROUND_MAX_INTERVAL_MS instead of all the way to
        // MAX_INTERVAL_MS. Backgrounded + stationary can go fully slow — nobody's watching.
        // base always wins if the user picked a coarser rate than the ramp.
        val cap = if (isAppForeground) FOREGROUND_MAX_INTERVAL_MS else MAX_INTERVAL_MS
        val ramped = when {
            stationaryMs < 60_000 -> base
            stationaryMs < 180_000 -> 15_000L
            stationaryMs < 360_000 -> 30_000L
            else -> MAX_INTERVAL_MS
        }
        return max(base, ramped.coerceAtMost(cap))
    }

    /** Caller must hold [adaptiveLock]. True when we should drop GPS and sleep on the
     *  motion sensor: backgrounded, stationary past the threshold, and the hardware
     *  trigger exists. Foreground stays live; no sensor → we fall back to slow polling. */
    private fun shouldBeDormantLocked(): Boolean {
        if (!sigMotionAvailable || isAppForeground) return false
        val since = stationarySinceMs ?: return false
        return System.currentTimeMillis() - since >= DORMANT_AFTER_MS
    }

    /** Stop GPS, arm the one-shot significant-motion trigger, and start the safety-poll
     *  backstop. Idempotent. The radio stays off until motion wakes us or a safety poll
     *  detects displacement the inertial sensor missed (e.g. a smoothly-cruising train). */
    private fun enterDormant() {
        synchronized(adaptiveLock) {
            if (dormant) return
            dormant = true
            dormantAnchor = recentFixes.lastOrNull()
            currentTargetIntervalMs = 0L
            currentMaxDelayMs = -1L
        }
        if (subscribed) {
            fusedClient.removeLocationUpdates(locationCallback)
            subscribed = false
        }
        armSignificantMotion()
        startSafetyPoll()
        updateNotification(dormant = true)
        Log.d("PaintGo", "Entering dormant — GPS off, sig-motion armed")
    }

    /** Resume from dormancy: disarm the trigger, stop the safety poll, and clear the
     *  movement window so stale pre-sleep fixes don't poison the speed estimate. The
     *  caller re-subscribes GPS (via evaluateInterval). Idempotent. */
    private fun exitDormant() {
        synchronized(adaptiveLock) {
            if (!dormant) return
            dormant = false
            dormantAnchor = null
            recentFixes.clear()
            stationarySinceMs = null
        }
        disarmSignificantMotion()
        stopSafetyPoll()
        updateNotification(dormant = false)
        Log.d("PaintGo", "Exiting dormant — resuming GPS")
    }

    private fun armSignificantMotion() {
        val sensor = sigMotionSensor ?: return
        if (sigMotionArmed) return
        sigMotionArmed = sensorManager.requestTriggerSensor(motionTriggerListener, sensor)
        Log.d("PaintGo", "Sig-motion arm requested: $sigMotionArmed")
    }

    private fun disarmSignificantMotion() {
        val sensor = sigMotionSensor ?: return
        if (!sigMotionArmed) return
        sensorManager.cancelTriggerSensor(motionTriggerListener, sensor)
        sigMotionArmed = false
    }

    /** While dormant, take a coarse fix every [SAFETY_POLL_MS] as a backstop: if we've
     *  displaced past [DORMANT_WAKE_DISTANCE_M] from where we parked, the motion sensor
     *  missed real travel (e.g. a smooth vehicle), so wake and resume normal recording. */
    private fun startSafetyPoll() {
        safetyPollJob?.cancel()
        safetyPollJob = scope.launch {
            while (dormant) {
                delay(SAFETY_POLL_MS)
                if (!dormant) break
                safetyPollOnce()
            }
        }
    }

    private fun stopSafetyPoll() {
        safetyPollJob?.cancel()
        safetyPollJob = null
    }

    @SuppressLint("MissingPermission")
    private fun safetyPollOnce() {
        if (!dormant) return
        fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null || !dormant) return@addOnSuccessListener
                val anchor = dormantAnchor
                if (anchor != null && loc.distanceTo(anchor) >= DORMANT_WAKE_DISTANCE_M) {
                    Log.d("PaintGo", "Safety poll: moved ${loc.distanceTo(anchor)}m while dormant — waking")
                    exitDormant()
                    evaluateInterval()
                } else {
                    // Still parked (or GPS jitter); re-anchor and keep sleeping.
                    dormantAnchor = loc
                }
            }
            .addOnFailureListener { Log.w("PaintGo", "Safety poll failed", it) }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SensorManager::class.java)
        sigMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        sigMotionAvailable = sigMotionSensor != null
        Log.d("PaintGo", "LocationService onCreate (sigMotion=$sigMotionAvailable)")
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
            ACTION_POLL_NOW -> {
                // On-demand high-accuracy fix. Only meaningful mid-recording; pollNow()
                // no-ops when there's no active session, so a stray trigger can't start
                // a non-foregrounded service doing background location work.
                Log.d("PaintGo", "Manual poll requested")
                pollNow()
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
        // Lifecycle observer must be added on main; onStartCommand → here runs on main.
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        synchronized(adaptiveLock) {
            isAppForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            recentFixes.clear()
            stationarySinceMs = null
            currentTargetIntervalMs = 0L
            currentMaxDelayMs = -1L
        }
        dormant = false
        dormantAnchor = null
        lifecycle.addObserver(lifecycleObserver)

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

            // Track the user-chosen base interval. evaluateInterval() then decides the
            // actual subscription rate based on app foreground + recent movement, so a
            // mid-recording settings change just adjusts the floor.
            intervalJob?.cancel()
            intervalJob = scope.launch {
                SettingsStore.get(applicationContext).sampleIntervalMs
                    .collect { intervalMs ->
                        synchronized(adaptiveLock) { baseIntervalMs = intervalMs }
                        evaluateInterval()
                    }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun doResubscribe(intervalMs: Long, maxDelayMs: Long) {
        if (subscribed) fusedClient.removeLocationUpdates(locationCallback)
        // maxUpdateDelay > interval lets FusedLocation batch fixes and deliver them in
        // bursts (fewer CPU wakeups when backgrounded). 0 = no batching (foreground).
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMs,
        ).setMaxUpdateDelayMillis(maxDelayMs).build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        subscribed = true
        val (fg, stationaryFor) = synchronized(adaptiveLock) {
            isAppForeground to stationarySinceMs?.let { System.currentTimeMillis() - it }
        }
        Log.d("PaintGo", "Location updates @ ${intervalMs}ms (maxDelay=${maxDelayMs}ms, fg=$fg, stationaryFor=${stationaryFor}ms)")
    }

    private fun stopRecording() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        intervalJob?.cancel()
        intervalJob = null
        disarmSignificantMotion()
        stopSafetyPoll()
        dormant = false
        dormantAnchor = null
        if (subscribed) {
            fusedClient.removeLocationUpdates(locationCallback)
            subscribed = false
        }
        synchronized(adaptiveLock) {
            recentFixes.clear()
            stationarySinceMs = null
            currentTargetIntervalMs = 0L
            currentMaxDelayMs = -1L
        }
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

    private fun buildNotification(dormant: Boolean = false): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PaintGo")
            .setContentText(if (dormant) "Paused — waiting for movement" else "Recording your walk")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /** Refresh the ongoing notification text in place (same NOTIF_ID) without
     *  re-entering startForeground. */
    private fun updateNotification(dormant: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(dormant))
    }

    companion object {
        private const val CHANNEL_ID = "paintgo_recording"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.anita.paintgo.action.STOP"
        private const val ACTION_POLL_NOW = "com.anita.paintgo.action.POLL_NOW"
        // 100m is liberal — emulator fixes are flat 100m, and city GPS can occasionally
        // land here. Revisit once we have real-device traces.
        private const val MAX_ACCURACY_METERS = 100f

        // Adaptive polling. Hold the last few fixes, judge speed from total displacement
        // over the window, and slow the request rate when stationary while backgrounded.
        private const val WINDOW_SIZE = 5
        private const val MIN_WINDOW_SECS = 20.0
        private const val STATIONARY_SPEED_MPS = 0.5f
        private const val MAX_INTERVAL_MS = 60_000L
        // Foreground ramp-down floor: stationary + on-screen slows to this (vs MAX_INTERVAL_MS
        // backgrounded) so the live dot stays reasonably responsive. Tune freely.
        private const val FOREGROUND_MAX_INTERVAL_MS = 15_000L
        // Backgrounded batching window: FusedLocation may buffer fixes up to this long and
        // deliver them in one callback, so the CPU wakes in bursts. Bounds fog latency to
        // ~this while backgrounded; foreground uses 0 (no batching).
        private const val BACKGROUND_BATCH_WINDOW_MS = 30_000L
        // Dormancy: backgrounded + stationary this long → drop GPS and sleep on the
        // significant-motion sensor (matches the deepest polling rung it replaces).
        private const val DORMANT_AFTER_MS = 360_000L
        // Backstop while dormant: poll a coarse fix this often and wake if we've moved
        // past DORMANT_WAKE_DISTANCE_M — covers smooth-vehicle motion the inertial sensor
        // can miss, plus device-to-device sensor-sensitivity variance.
        private const val SAFETY_POLL_MS = 600_000L
        private const val DORMANT_WAKE_DISTANCE_M = 100f
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _liveLocation = MutableStateFlow<LiveFix?>(null)
        val liveLocation: StateFlow<LiveFix?> = _liveLocation.asStateFlow()

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

        /** Ask the running service for an immediate high-accuracy fix. No-op if the
         *  service isn't recording (pollNow guards on the active session). */
        fun pollNow(context: Context) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_POLL_NOW
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
