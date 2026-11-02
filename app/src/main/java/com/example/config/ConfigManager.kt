package com.example.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.statemachine.StateMachineConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prettynights_config")

data class WledHardwareConfig(
    val maxCurrentMa: Int = 2500, // 2500mA safe default for USB-C 5V 3A; up to 15000mA on main terminals
    val ch1LedCount: Int = 60,
    val ch1StripType: Int = 22, // 22 = WS281x RGB, 30 = SK6812 RGBW
    val ch1ColorOrder: Int = 0, // 0 = GRB, 1 = RGB, 2 = BRG
    val ch1Reversed: Boolean = false,
    val ch1ZoneName: String = "Main Frame (IO16)",
    val ch2Enabled: Boolean = true,
    val ch2LedCount: Int = 30,
    val ch2StripType: Int = 22,
    val ch2ColorOrder: Int = 0,
    val ch2Reversed: Boolean = false,
    val ch2ZoneName: String = "Rear Tail (IO2)",
    val io13Role: String = "PUSHBUTTON", // "PUSHBUTTON", "REED_SWITCH", "STRIP_CH3", "DISABLED"
    val ch3LedCount: Int = 20
)

data class WledLook(
    val presetId: Int? = null,
    val effectId: Int = 0, // Solid = 0, Rainbow = 9, Chase = 11, Pulse = 12, Flow = 20...
    val paletteId: Int? = null, // Default = none
    val colors: List<String> = listOf("#FF7800"), // RGB colors
    val speed: Int? = null, // WLED ranges from 0-255, default none
    val intensity: Int? = null, // 0-255, default none
    val transitionMs: Int = 300, // transition is configured in milliseconds
    val targetSegments: String = "BOTH" // "BOTH", "SEG_0_IO16", "SEG_1_IO2"
)

data class AppConfig(
    val wledHost: String = "prettylights.local",
    val audioSyncPort: Int = 11988,
    val lockToLocalNetwork: Boolean = true,
    val usbSerialFallbackEnabled: Boolean = true,
    val logOnlyMode: Boolean = false,
    val speedSource: String = "GPS_ONLY", // "GPS_ONLY", "BOSCH_PREFERRED", "BOSCH_ONLY"
    val sensorAxis: String = "Y", // "X", "Y", "Z"
    val sensorSign: Int = -1, // 1 or -1
    val stateMachine: StateMachineConfig = StateMachineConfig(),
    val hardwareConfig: WledHardwareConfig = WledHardwareConfig(),
    
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

class ConfigManager(private val context: Context) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(AppConfig::class.java)
    
    private val configKey = stringPreferencesKey("app_config_json")

    val configFlow: Flow<AppConfig> = context.dataStore.data
        .map { preferences ->
            val json = preferences[configKey]
            if (json == null) {
                AppConfig()
            } else {
                try {
                    adapter.fromJson(json) ?: AppConfig()
                } catch (e: Exception) {
                    AppConfig()
                }
            }
        }

    suspend fun getConfigSnapshot(): AppConfig {
        return configFlow.first()
    }

    suspend fun updateConfig(update: (AppConfig) -> AppConfig) {
        context.dataStore.edit { preferences ->
            val current = preferences[configKey]?.let {
                try { adapter.fromJson(it) } catch (e: Exception) { null }
            } ?: AppConfig()
            val updated = update(current)
            preferences[configKey] = adapter.toJson(updated)
        }
    }
}
