package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.config.ConfigManager
import com.example.logging.LoggingDatabase
import com.example.logging.LogRepository
import com.example.ride.BoschSpeedProvider
import com.example.ride.FusedSpeedProvider
import com.example.ride.GpsSpeedProvider
import com.example.ride.RideOrchestrator
import com.example.safety.LockController
import com.example.wled.WledController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PrettyNightsApp : Application() {

    lateinit var configManager: ConfigManager
    lateinit var lockController: LockController
    lateinit var database: LoggingDatabase
    lateinit var logRepository: LogRepository
    lateinit var gpsSpeedProvider: GpsSpeedProvider
    lateinit var boschSpeedProvider: BoschSpeedProvider
    lateinit var fusedSpeedProvider: FusedSpeedProvider
    lateinit var wledController: WledController
    lateinit var orchestrator: RideOrchestrator

    // Caching preference to prevent blocking calls on sensor thread
    @Volatile
    var cachedSpeedSource: String = "GPS_ONLY"

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Database & Logging Room Setup
        database = LoggingDatabase.getDatabase(this)
        logRepository = LogRepository(database)

        // 2. Config & Safety Locks
        configManager = ConfigManager(this)
        lockController = LockController()

        // Prefetch configuration snapshot for cache or update when safe
        CoroutineScope(Dispatchers.IO).launch {
            configManager.configFlow.collect { config ->
                val isRunning = if (this@PrettyNightsApp::orchestrator.isInitialized) {
                    orchestrator.isRunning
                } else false
                if (!isRunning) {
                     cachedSpeedSource = config.speedSource
                }
            }
        }

        // 3. Sensoring & Speed Providers
        gpsSpeedProvider = GpsSpeedProvider(this)
        boschSpeedProvider = BoschSpeedProvider(this)
        fusedSpeedProvider = FusedSpeedProvider(gpsSpeedProvider, boschSpeedProvider) {
            cachedSpeedSource
        }

        // 4. WLED Connection
        wledController = WledController()

        // 5. Build Orchestrator
        orchestrator = RideOrchestrator(
            context = this,
            configManager = configManager,
            lockController = lockController,
            logRepository = logRepository,
            speedProvider = fusedSpeedProvider,
            wledController = wledController
        )

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ride Companion Live",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays live speedometer and reactive lighting stats during active bike rides."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "prettynights_ride_channel"
        lateinit var instance: PrettyNightsApp
            private set
    }
}
