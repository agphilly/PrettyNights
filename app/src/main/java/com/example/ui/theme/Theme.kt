package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.config.RideMarkerType
import com.example.statemachine.RideState

@Immutable
data class AppSemanticColors(
  // Status & Readiness
  val statusPassBadge: Color = StatusPassBadge,
  val statusPassText: Color = StatusPassText,
  val statusSuccess: Color = StatusSuccess,
  val statusWarning: Color = StatusWarning,
  val statusCheckingBadge: Color = StatusCheckingBadge,
  val statusDanger: Color = StatusDanger,
  val statusReadyBadge: Color = StatusReadyBadge,
  val statusStandbyBadge: Color = StatusStandbyBadge,
  val statusLiveDot: Color = StatusLiveDot,

  // Ride States
  val rideStateIdle: Color = RideStateIdle,
  val rideStateCruising: Color = RideStateCruising,
  val rideStateBraking: Color = RideStateBraking,
  val rideStateBrakingHold: Color = RideStateBrakingHold,

  // Event Markers & Mode Badges
  val markerHardBrake: Color = MarkerHardBrake,
  val markerLightBrake: Color = MarkerLightBrake,
  val markerRoadBump: Color = MarkerRoadBump,
  val markerBoschGlitch: Color = MarkerBoschGlitch,
  val logOnlyBadge: Color = LogOnlyBadge,

  // Transport & Hardware
  val linkEthernetWired: Color = LinkEthernetWired,
  val linkWifiBound: Color = LinkWifiBound,
  val linkUsbSerial: Color = LinkUsbSerial,
  val hardwareGpioBadge: Color = HardwareGpioBadge,

  // Visualizer & Audio Spectrum
  val spectrumLow: Color = SpectrumLow,
  val spectrumMid: Color = SpectrumMid,
  val spectrumHigh: Color = SpectrumHigh,
  val musicStreamingAccent: Color = MusicStreamingAccent,
  val visualizerSurface: Color = VisualizerSurface,
  val visualizerGrid: Color = VisualizerGrid,
  val visualizerFrame: Color = VisualizerFrame,

  // Neutral / Utility
  val mutedText: Color = MutedText,
  val onBadgeText: Color = OnBadgeText,
) {
  fun colorForRideState(state: RideState): Color =
    when (state) {
      RideState.IDLE -> rideStateIdle
      RideState.CRUISING -> rideStateCruising
      RideState.BRAKING -> rideStateBraking
      RideState.BRAKING_HOLD -> rideStateBrakingHold
    }

  fun colorForRideStateName(stateName: String): Color =
    when (stateName) {
      "BRAKING" -> rideStateBraking
      "BRAKING_HOLD" -> rideStateBrakingHold
      "CRUISING" -> rideStateCruising
      else -> rideStateIdle
    }

  fun colorForMarker(marker: RideMarkerType): Color =
    when (marker) {
      RideMarkerType.HARD_BRAKE -> markerHardBrake
      RideMarkerType.LIGHT_BRAKE -> markerLightBrake
      RideMarkerType.ROAD_BUMP -> markerRoadBump
      RideMarkerType.BOSCH_GLITCH -> markerBoschGlitch
    }
}

val LocalAppColors = staticCompositionLocalOf { AppSemanticColors() }

val MaterialTheme.appColors: AppSemanticColors
  @Composable @ReadOnlyComposable get() = LocalAppColors.current

private val DarkColorScheme =
  darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  semanticColors: AppSemanticColors = AppSemanticColors(),
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  CompositionLocalProvider(LocalAppColors provides semanticColors) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
