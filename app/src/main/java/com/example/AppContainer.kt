package com.example

import android.app.Application
import android.content.Context
import com.example.config.ConfigManager
import com.example.config.SpeedSourceMode
import com.example.logging.LogRepository
import com.example.logging.LoggingDatabase
import com.example.ride.BoschSpeedProvider
import com.example.ride.FusedSpeedProvider
import com.example.ride.GpsSpeedProvider
import com.example.ride.RideOrchestrator
import com.example.safety.LockController
import com.example.wled.WledController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: LoggingDatabase = LoggingDatabase.getDatabase(app)
    val logRepository: LogRepository = LogRepository(database)
    val configManager: ConfigManager = ConfigManager(app)
    val lockController: LockController = LockController()
    val wledController: WledController = WledController(app)

    @Volatile
    var cachedSpeedSource: SpeedSourceMode = SpeedSourceMode.GPS_ONLY

    val gpsSpeedProvider: GpsSpeedProvider = GpsSpeedProvider(app)
    val boschSpeedProvider: BoschSpeedProvider = BoschSpeedProvider(app)
    val fusedSpeedProvider: FusedSpeedProvider = FusedSpeedProvider(
        gpsSpeedProvider,
        boschSpeedProvider
    ) { cachedSpeedSource }

    val orchestrator: RideOrchestrator = RideOrchestrator(
        context = app,
        configManager = configManager,
        lockController = lockController,
        logRepository = logRepository,
        speedProvider = fusedSpeedProvider,
        wledController = wledController,
        onSpeedSourceCached = { cachedSpeedSource = it }
    )

    init {
        appScope.launch {
            combine(configManager.configFlow, orchestrator.isRunningFlow) { cfg, running ->
                cfg to running
            }.collect { (cfg, running) ->
                if (!running) {
                    cachedSpeedSource = cfg.speedSource
                }
                wledController.updateTransportConfig(
                    lockToLocalNetwork = cfg.lockToLocalNetwork,
                    usbSerialFallbackEnabled = cfg.usbSerialFallbackEnabled
                )
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as PrettyNightsApp).container
