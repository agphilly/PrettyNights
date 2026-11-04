package com.example.patterns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.config.ConfigManager
import com.example.config.WledLook
import com.example.safety.LockController
import com.example.statemachine.RideState
import com.example.wled.WledController
import com.example.wled.WledPreset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PatternsViewModel(
    private val configManager: ConfigManager,
    private val lockController: LockController,
    private val wledController: WledController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    val config: StateFlow<AppConfig> = configManager.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppConfig())

    val isLocked: StateFlow<Boolean> = lockController.isLocked

    private val _presets = MutableStateFlow<List<WledPreset>?>(null)
    val presets: StateFlow<List<WledPreset>?> = _presets.asStateFlow()

    private val _effects = MutableStateFlow<List<String>?>(WledController.DEFAULT_WLED_EFFECTS)
    val effects: StateFlow<List<String>?> = _effects.asStateFlow()

    private val _palettes = MutableStateFlow<List<String>?>(WledController.DEFAULT_WLED_PALETTES)
    val palettes: StateFlow<List<String>?> = _palettes.asStateFlow()

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    private val _fetchFailed = MutableStateFlow(false)
    val fetchFailed: StateFlow<Boolean> = _fetchFailed.asStateFlow()

    private val _previewStatusByState = MutableStateFlow<Map<RideState, String>>(emptyMap())
    val previewStatusByState: StateFlow<Map<RideState, String>> = _previewStatusByState.asStateFlow()

    fun fetchWledData(host: String) {
        if (host.isBlank()) return
        _isFetching.value = true
        _fetchFailed.value = false
        viewModelScope.launch {
            val (fetchedPresets, fetchedEffects, fetchedPalettes) = withContext(ioDispatcher) {
                val p = wledController.fetchPresets(host)
                val e = if (p != null) wledController.fetchEffects(host) else null
                val pal = if (p != null) wledController.fetchPalettes(host) else null
                Triple(p, e, pal)
            }
            _isFetching.value = false
            if (fetchedPresets == null) {
                _fetchFailed.value = true
                if (_effects.value.isNullOrEmpty()) {
                    _effects.value = WledController.DEFAULT_WLED_EFFECTS
                }
                if (_palettes.value.isNullOrEmpty()) {
                    _palettes.value = WledController.DEFAULT_WLED_PALETTES
                }
            } else {
                _presets.value = fetchedPresets
                _effects.value = fetchedEffects ?: WledController.DEFAULT_WLED_EFFECTS
                _palettes.value = fetchedPalettes ?: WledController.DEFAULT_WLED_PALETTES
                _fetchFailed.value = false
            }
        }
    }

    fun saveConfig(updated: AppConfig) {
        viewModelScope.launch {
            configManager.updateConfig { updated }
        }
    }

    fun previewLook(state: RideState, host: String, look: WledLook) {
        _previewStatusByState.update { it + (state to "Sending preview...") }
        viewModelScope.launch {
            val currentChannels = config.value.channels
            val configuredSegs = currentChannels.flatMap { it.segments }
            val segmentLooks: Map<Int, WledLook> = if (configuredSegs.isNotEmpty()) {
                configuredSegs.associate { it.wledSegmentId to look }
            } else {
                mapOf(0 to look)
            }
            val ok = withContext(ioDispatcher) {
                wledController.sendComposedState(
                    host = host,
                    segmentLooks = segmentLooks,
                    brightness = 1.0f
                )
            }
            val resultText = if (ok) "Sent successfully!" else "WLED unreachable!"
            _previewStatusByState.update { it + (state to resultText) }
        }
    }

    fun clearPreviewStatus(state: RideState) {
        _previewStatusByState.update { it - state }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as PrettyNightsApp
                val c = app.container
                PatternsViewModel(
                    configManager = c.configManager,
                    lockController = c.lockController,
                    wledController = c.wledController
                )
            }
        }
    }
}
