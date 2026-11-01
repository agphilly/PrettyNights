package com.example.ride

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.PrettyNightsApp
import com.example.R
import com.example.statemachine.RideState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RideService : Service() {

    private val orchestrator = PrettyNightsApp.instance.orchestrator
    private var lastNotifiedState: RideState? = null
    private var lastNotificationTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RIDE) {
            stopRideSession()
            return START_NOT_STICKY
        }

        // Start Foreground Notification
        val notification = createNotification("Starting Ride...", RideState.IDLE, 0f)
        lastNotifiedState = RideState.IDLE
        lastNotificationTimeMs = android.os.SystemClock.elapsedRealtime()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Hook up diagnostics stream to UI StateFlows (50 Hz) and throttled system notification (1 Hz or state change)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        orchestrator.setDiagnosticsListener { info, state, bright ->
            // Push properties to in-memory StateFlow for UI observing
            _currentSpeedInfo.value = info
            _activeRideState.value = state

            val nowMs = android.os.SystemClock.elapsedRealtime()
            val stateChanged = state != lastNotifiedState
            if (stateChanged || (nowMs - lastNotificationTimeMs) >= 1000L) {
                lastNotifiedState = state
                lastNotificationTimeMs = nowMs
                val updatedNotif = createNotification(info, state, bright)
                manager.notify(NOTIFICATION_ID, updatedNotif)
            }
        }

        // Begin sensor fusion tick
        orchestrator.start()

        return START_STICKY
    }

    private fun stopRideSession() {
        orchestrator.stop()
        _isServiceRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        orchestrator.stop()
        _isServiceRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotification(contentText: String, state: RideState, brightness: Float): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RideService::class.java).apply {
            action = ACTION_STOP_RIDE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stateLabel = when (state) {
            RideState.IDLE -> "PARKED / IDLE"
            RideState.CRUISING -> "CRUISING SENSORS ACTIVE"
            RideState.BRAKING -> "BRAKING TRIGGERED"
            RideState.BRAKING_HOLD -> "BRAKE LIGHT HOLDING"
        }

        // Build elegant notification with action button
        return NotificationCompat.Builder(this, PrettyNightsApp.CHANNEL_ID)
            .setContentTitle("Pretty Nights — $stateLabel")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // fallback system compass icon for stability
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Ride",
                stopPendingIntent
            )
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 250101
        const val ACTION_STOP_RIDE = "com.example.ride.STOP_RIDE"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _currentSpeedInfo = MutableStateFlow("Idle")
        val currentSpeedInfo: StateFlow<String> = _currentSpeedInfo.asStateFlow()

        private val _activeRideState = MutableStateFlow(RideState.IDLE)
        val activeRideState: StateFlow<RideState> = _activeRideState.asStateFlow()
    }
}
