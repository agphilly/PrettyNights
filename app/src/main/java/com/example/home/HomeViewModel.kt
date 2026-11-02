package com.example.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.config.ConfigManager
import com.example.config.RideMarkerType
import com.example.ride.BoschLiveDataState
import com.example.ride.BoschSpeedProvider
import com.example.ride.MusicService
import com.example.ride.RideOrchestrator
import com.example.ride.RideService
import com.example.statemachine.RideState
import com.example.wled.WledController
import com.example.wled.WledTransportState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val configManager: ConfigManager,
    private val orchestrator: RideOrchestrator,
    private val boschSpeedProvider: BoschSpeedProvider,
    private val wledController: WledController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    val config: StateFlow<AppConfig> = configManager.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppConfig())

    val isRunning: StateFlow<Boolean> = RideService.isServiceRunning
    val activeState: StateFlow<RideState> = RideService.activeRideState
    val isMusicRunning: StateFlow<Boolean> = MusicService.isServiceRunning
    val musicBands: StateFlow<FloatArray> = MusicService.fftBands

    val markerCount: StateFlow<Int> = orchestrator.markerCountFlow
    val lastMarkerLabel: StateFlow<String?> = orchestrator.lastMarkerLabelFlow
    val boschState: StateFlow<BoschLiveDataState> = boschSpeedProvider.liveStateFlow
    val wledTransportState: StateFlow<WledTransportState> = wledController.transportState

    private val _wledStatus = MutableStateFlow(ScreenWledStatus.UNKNOWN)
    val wledStatus: StateFlow<ScreenWledStatus> = _wledStatus.asStateFlow()

    fun setWledStatusReachable() {
        _wledStatus.value = ScreenWledStatus.REACHABLE
    }

    fun checkWledReachability(host: String) {
        if (isRunning.value) {
            _wledStatus.value = ScreenWledStatus.REACHABLE
            return
        }
        viewModelScope.launch {
            _wledStatus.value = ScreenWledStatus.CHECKING
            val reachable = withContext(ioDispatcher) {
                wledController.ping(host)
            }
            _wledStatus.value = if (reachable) ScreenWledStatus.REACHABLE else ScreenWledStatus.UNREACHABLE
        }
    }

    fun stampMarker(markerType: RideMarkerType) {
        orchestrator.recordRideMarker(markerType)
    }

    fun toggleLogOnlyMode(enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            configManager.updateConfig { it.copy(logOnlyMode = enabled) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as PrettyNightsApp).container
                HomeViewModel(
                    configManager = c.configManager,
                    orchestrator = c.orchestrator,
                    boschSpeedProvider = c.boschSpeedProvider,
                    wledController = c.wledController
                )
            }
        }
    }
}
