package com.example.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.statemachine.StateMachineConfig
import com.squareup.moshi.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prettynights_config")

data class WledLook(
    val presetId: Int? = null,
    val effectId: Int = 0, // Solid = 0, Rainbow = 9, Chase = 11, Pulse = 12, Flow = 20...
    val paletteId: Int? = null, // Default = none
    val colors: List<String> = listOf("#FF7800"), // RGB colors
    val speed: Int? = null, // WLED ranges from 0-255, default none
    val intensity: Int? = null, // 0-255, default none
    val transitionMs: Int = 300 // transition is configured in milliseconds
)

data class AppConfig(
    val wledHost: String = "prettylights.local",
    val audioSyncPort: Int = 11988,
    val lockToLocalNetwork: Boolean = true,
    val usbSerialFallbackEnabled: Boolean = true,
    val logOnlyMode: Boolean = false,
    val speedSource: SpeedSourceMode = SpeedSourceMode.GPS_ONLY,
    val sensorAxis: SensorAxis = SensorAxis.Y,
    val sensorSign: Int = -1, // 1 or -1
    val stateMachine: StateMachineConfig = StateMachineConfig(),
    @Json(name = "channels")
    val channels: List<ChannelConfig> = emptyList(),

    // Default Looks
    val idleLook: WledLook = WledLook(
        effectId = 0,
        paletteId = null,
        colors = listOf("#FF7800"), // Amber
        speed = null,
        intensity = null,
        transitionMs = 400
    ),
    val cruisingLook: WledLook = WledLook(
        effectId = 0,
        paletteId = null,
        colors = listOf("#00B4FF"), // Cyan
        speed = null,
        intensity = null,
        transitionMs = 300
    ),
    val brakingLook: WledLook = WledLook(
        effectId = 0,
        paletteId = null,
        colors = listOf("#FF0000"), // Red
        speed = null,
        intensity = null,
        transitionMs = 0
    ),
    val brakingHoldLook: WledLook = WledLook(
        effectId = 0,
        paletteId = null,
        colors = listOf("#FF0000"), // Red
        speed = null,
        intensity = null,
        transitionMs = 0
    )
)

open class ConfigManager(private val context: Context? = null) {
    private val adapter = AppJson.moshi.adapter(AppConfig::class.java)

    private val configKey = stringPreferencesKey("app_config_json")

    open val configFlow: Flow<AppConfig> = context?.dataStore?.data
        ?.map { preferences ->
            val json = preferences[configKey]
            if (json == null) {
                AppConfig()
            } else {
                try {
                    val parsed = adapter.fromJson(json) ?: AppConfig()
                    parsed.copy(channels = parsed.channels.withSequentialSegmentIds())
                } catch (_: Exception) {
                    AppConfig()
                }
            }
        } ?: flowOf(AppConfig())

    open suspend fun getConfigSnapshot(): AppConfig {
        return configFlow.first()
    }

    open suspend fun updateConfig(update: (AppConfig) -> AppConfig) {
        val ctx = context ?: return
        ctx.dataStore.edit { preferences ->
            val current = preferences[configKey]?.let {
                try { adapter.fromJson(it) } catch (_: Exception) { null }
            } ?: AppConfig()
            val updated = update(current)
            val normalized = updated.copy(channels = updated.channels.withSequentialSegmentIds())
            preferences[configKey] = adapter.toJson(normalized)
        }
    }
}
