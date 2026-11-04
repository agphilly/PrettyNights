package com.example.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.config.ChannelConfig
import com.example.config.ConfigManager
import com.example.config.withSequentialSegmentIds
import com.example.safety.LockController
import com.example.wled.WledController
import com.example.wled.WledHardwareLiveInfo
import com.example.wled.WledTransportState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val configManager: ConfigManager,
    private val lockController: LockController,
    private val wledController: WledController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    val config: StateFlow<AppConfig> = configManager.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppConfig())

    val isLocked: StateFlow<Boolean> = lockController.isLocked

    val transportState: StateFlow<WledTransportState> = wledController.transportState

    private val _testConnectionResult = MutableStateFlow<String?>(null)
    val testConnectionResult: StateFlow<String?> = _testConnectionResult.asStateFlow()

    private val _liveHardwareInfo = MutableStateFlow<WledHardwareLiveInfo?>(null)
    val liveHardwareInfo: StateFlow<WledHardwareLiveInfo?> = _liveHardwareInfo.asStateFlow()

    private val _isSyncingHardware = MutableStateFlow(false)
    val isSyncingHardware: StateFlow<Boolean> = _isSyncingHardware.asStateFlow()

    private val _hardwareSyncMessage = MutableStateFlow<String?>(null)
    val hardwareSyncMessage: StateFlow<String?> = _hardwareSyncMessage.asStateFlow()

    private val _hardwareSyncIsError = MutableStateFlow(false)
    val hardwareSyncIsError: StateFlow<Boolean> = _hardwareSyncIsError.asStateFlow()

    fun saveConfig(updated: AppConfig) {
        val normalized = updated.copy(channels = updated.channels.withSequentialSegmentIds())
        viewModelScope.launch {
            configManager.updateConfig { normalized }
        }
    }

    fun refreshTransport() {
        wledController.refreshTransport()
    }

    fun testConnection(hostInput: String) {
        val (cleaned, _) = cleanHost(hostInput)
        if (cleaned.isEmpty()) {
            _testConnectionResult.value = "Unreachable"
            return
        }
        _testConnectionResult.value = "Checking..."
        viewModelScope.launch {
            val ok = withContext(ioDispatcher) {
                wledController.ping(cleaned)
            }
            val latency = wledController.transportState.value.lastPingLatencyMs
            _testConnectionResult.value = if (ok) {
                if (latency != null) "Reachable (${latency} ms)" else "Reachable"
            } else {
                "Unreachable"
            }
        }
    }

    fun readHardwareInfo(host: String) {
        _isSyncingHardware.value = true
        _hardwareSyncMessage.value = "Reading /json/info..."
        _hardwareSyncIsError.value = false
        viewModelScope.launch {
            val info = withContext(ioDispatcher) {
                wledController.fetchHardwareInfo(host)
            }
            _isSyncingHardware.value = false
            _liveHardwareInfo.value = info
            if (info != null) {
                _hardwareSyncIsError.value = false
                _hardwareSyncMessage.value = "Connected: WLED v${info.version} (${info.ledCount} LEDs, ${info.maxPowerMa} mA cap)"
            } else {
                _hardwareSyncIsError.value = true
                _hardwareSyncMessage.value = "Could not read /json/info from $host"
            }
        }
    }

    fun pushHardwareAndSegments(host: String, channels: List<ChannelConfig>) {
        val normalizedChannels = channels.withSequentialSegmentIds()
        _isSyncingHardware.value = true
        _hardwareSyncMessage.value = "Pushing /json/cfg & /json/state to $host..."
        _hardwareSyncIsError.value = false
        viewModelScope.launch {
            val (ok, refreshedInfo) = withContext(ioDispatcher) {
                val pushed = wledController.pushHardwareAndSegments(host, normalizedChannels)
                val info = if (pushed) wledController.fetchHardwareInfo(host) else null
                pushed to info
            }
            _isSyncingHardware.value = false
            if (refreshedInfo != null) {
                _liveHardwareInfo.value = refreshedInfo
            }
            if (ok) {
                _hardwareSyncIsError.value = false
                val totalSegments = normalizedChannels.sumOf { it.segments.size }
                _hardwareSyncMessage.value = "Provisioned ${normalizedChannels.size} channel(s) & $totalSegments segment(s) on WLED!"
            } else {
                _hardwareSyncIsError.value = true
                _hardwareSyncMessage.value = "WLED unreachable at $host — settings saved locally."
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as PrettyNightsApp
                val c = app.container
                SettingsViewModel(
                    configManager = c.configManager,
                    lockController = c.lockController,
                    wledController = c.wledController
                )
            }
        }
    }
}
