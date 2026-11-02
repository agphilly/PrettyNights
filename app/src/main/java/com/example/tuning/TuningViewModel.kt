package com.example.tuning

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.config.ConfigManager
import com.example.logging.LogRepository
import com.example.logging.SessionEntity
import com.example.ride.RideOrchestrator
import com.example.safety.LockController
import com.example.statemachine.RideState
import com.example.statemachine.StateMachineConfig
import com.example.statemachine.StateMachineTelemetry
import com.example.wled.WledController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class TuningViewModel(
    private val configManager: ConfigManager,
    private val lockController: LockController,
    private val orchestrator: RideOrchestrator,
    private val wledController: WledController,
    private val logRepository: LogRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    val config: StateFlow<AppConfig> = configManager.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppConfig())

    val isLocked: StateFlow<Boolean> = lockController.isLocked

    val isRideRunning: StateFlow<Boolean> = orchestrator.isRunningFlow

    val telemetry: StateFlow<StateMachineTelemetry?> = orchestrator.telemetryFlow
        .sample(150L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sessions: StateFlow<List<SessionEntity>> = logRepository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _overrideState = MutableStateFlow<RideState?>(null)
    val overrideState: StateFlow<RideState?> = _overrideState.asStateFlow()

    private val _overrideBrightness = MutableStateFlow(1.0f)
    val overrideBrightness: StateFlow<Float> = _overrideBrightness.asStateFlow()

    private val _wledUnreachableError = MutableStateFlow(false)
    val wledUnreachableError: StateFlow<Boolean> = _wledUnreachableError.asStateFlow()

    private val _replayResult = MutableStateFlow<ReplayComparisonResult?>(null)
    val replayResult: StateFlow<ReplayComparisonResult?> = _replayResult.asStateFlow()

    private val _isRunningReplay = MutableStateFlow(false)
    val isRunningReplay: StateFlow<Boolean> = _isRunningReplay.asStateFlow()

    private val sendChannel = Channel<Pair<RideState, Float>>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            orchestrator.isRunningFlow.collect { running ->
                if (running) {
                    _overrideState.value = null
                }
            }
        }

        viewModelScope.launch {
            var lastSentTime = 0L
            for ((state, brightness) in sendChannel) {
                val now = SystemClock.elapsedRealtime()
                val timeSinceLast = now - lastSentTime
                if (timeSinceLast in 1..99) {
                    delay(100 - timeSinceLast)
                }
                val currentConfig = config.value
                val currentLook = when (state) {
                    RideState.IDLE -> currentConfig.idleLook
                    RideState.CRUISING -> currentConfig.cruisingLook
                    RideState.BRAKING -> currentConfig.brakingLook
                    else -> null
                }
                if (currentLook != null) {
                    try {
                        val success = withContext(ioDispatcher) {
                            wledController.sendState(
                                currentConfig.wledHost,
                                currentLook,
                                brightness
                            )
                        }
                        _wledUnreachableError.value = !success
                    } catch (_: Exception) {
                        _wledUnreachableError.value = true
                    }
                    lastSentTime = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    fun saveConfig(updated: AppConfig) {
        viewModelScope.launch {
            configManager.updateConfig { updated }
        }
    }

    fun selectOverride(newState: RideState?) {
        _overrideState.value = newState
        if (newState != null) {
            sendChannel.trySend(newState to _overrideBrightness.value)
        }
    }

    fun setOverrideBrightness(newBrightness: Float) {
        _overrideBrightness.value = newBrightness
        val st = _overrideState.value
        if (st != null) {
            sendChannel.trySend(st to newBrightness)
        }
    }

    fun runReplay(sessionId: Long, stateMachineConfig: StateMachineConfig, replaySpeedSource: String) {
        if (_isRunningReplay.value) return
        _isRunningReplay.value = true
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                val samples = logRepository.getImuSamples(sessionId).first()
                val markers = logRepository.getRideMarkers(sessionId).first()
                RideReplaySimulator.runReplay(
                    imuSamples = samples,
                    markers = markers,
                    config = stateMachineConfig,
                    replaySpeedSource = replaySpeedSource
                )
            }
            _replayResult.value = result
            _isRunningReplay.value = false
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as PrettyNightsApp
                val c = app.container
                TuningViewModel(
                    configManager = c.configManager,
                    lockController = c.lockController,
                    orchestrator = c.orchestrator,
                    wledController = c.wledController,
                    logRepository = c.logRepository
                )
            }
        }
    }
}
