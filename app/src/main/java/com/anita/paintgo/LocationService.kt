package com.anita.paintgo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        Log.d("PaintGo", "LocationService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d("PaintGo", "LocationService stopping")
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
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("PaintGo", "LocationService onDestroy")
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

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

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
