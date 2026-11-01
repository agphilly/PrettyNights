package com.example.tuning

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.config.AppConfig
import com.example.statemachine.StateMachineConfig

@Composable
fun TuningParameterSections(
    config: AppConfig,
    sm: StateMachineConfig,
    controlsEnabled: Boolean,
    isMinMaxValid: Boolean,
    isSpeedValid: Boolean,
    isBrakingMinMaxValid: Boolean,
    onConfigSave: (AppConfig) -> Unit
) {
    // --- VALIDATION ERROR WARNINGS ---
    if (!isMinMaxValid || !isSpeedValid || !isBrakingMinMaxValid) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("validation_warning_card")
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Validation Warnings",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (!isMinMaxValid) {
                    Text(
                        "• globalMinBrightness must be ≤ globalMaxBrightness",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (!isSpeedValid) {
                    Text(
                        "• idleEnterSpeedKmh must be ≤ idleExitSpeedKmh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (!isBrakingMinMaxValid) {
                    Text(
                        "• brakingMinBrightness must be ≤ brakingMaxBrightness",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // --- GROUP 1: GLOBAL BRIGHTNESS ENVELOPE ---
    TuningSectionHeader(title = "Global brightness envelope")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningParameterSlider(
                label = "globalMinBrightness",
                description = "Output floor; all looks map into [min, max]",
                value = sm.globalMinBrightness,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(globalMinBrightness = it))) },
                valueRange = 0.0f..1.0f,
                step = 0.01f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "globalMaxBrightness",
                description = "Ambient-state ceiling (idle/cruising/hold-fade)",
                value = sm.globalMaxBrightness,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(globalMaxBrightness = it))) },
                valueRange = 0.0f..1.0f,
                step = 0.01f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSwitch(
                label = "brakingIgnoresGlobalMax",
                description = "If true, active braking maps to 1.0 instead of the ceiling",
                checked = sm.brakingIgnoresGlobalMax,
                onCheckedChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingIgnoresGlobalMax = it))) },
                enabled = controlsEnabled
            )
        }
    }

    // --- GROUP 2: TRANSITIONS (SHARED) ---
    TuningSectionHeader(title = "Transitions (shared)")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningParameterSlider(
                label = "idleEnterSpeedKmh",
                description = "At or below this speed, eligible for idle",
                value = sm.idleEnterSpeedKmh,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleEnterSpeedKmh = it))) },
                valueRange = 0.0f..20.0f,
                step = 0.5f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "idleExitSpeedKmh",
                description = "Must exceed this to leave idle / cancel a stop hold",
                value = sm.idleExitSpeedKmh,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleExitSpeedKmh = it))) },
                valueRange = 0.0f..20.0f,
                step = 0.5f,
                enabled = controlsEnabled
            )
        }
    }

    // --- GROUP 3: IDLE ---
    TuningSectionHeader(title = "Idle")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningParameterSlider(
                label = "idleEntryFadeS",
                description = "Fade duration when entering idle (seconds)",
                value = sm.idleEntryFadeS,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleEntryFadeS = it))) },
                valueRange = 0.0f..5.0f,
                step = 0.1f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "idleBrightness",
                description = "Static idle brightness (pre-envelope)",
                value = sm.idleBrightness,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleBrightness = it))) },
                valueRange = 0.0f..1.0f,
                step = 0.01f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSwitch(
                label = "requireBoschIdleConfirm",
                description = "Defer idle entry if Bosch explicitly says still driving",
                checked = sm.requireBoschIdleConfirm,
                onCheckedChange = { onConfigSave(config.copy(stateMachine = sm.copy(requireBoschIdleConfirm = it))) },
                enabled = controlsEnabled
            )
        }
    }

    // --- GROUP 4: CRUISING ---
    TuningSectionHeader(title = "Cruising")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningParameterSlider(
                label = "cruisingEntryFadeS",
                description = "Fade duration when entering cruising (seconds)",
                value = sm.cruisingEntryFadeS,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(cruisingEntryFadeS = it))) },
                valueRange = 0.0f..5.0f,
                step = 0.1f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "cruisingBrightness",
                description = "Static cruising brightness (pre-envelope)",
                value = sm.cruisingBrightness,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(cruisingBrightness = it))) },
                valueRange = 0.0f..1.0f,
                step = 0.01f,
                enabled = controlsEnabled
            )
        }
    }

    // --- GROUP 5: BRAKING ---
    TuningSectionHeader(title = "Braking")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningParameterSlider(
                label = "brakingEntryFadeS",
                description = "Fade when entering braking; 0 = instant (for visibility)",
                value = sm.brakingEntryFadeS,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingEntryFadeS = it))) },
                valueRange = 0.0f..5.0f,
                step = 0.1f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "brakingDecelThreshold",
                description = "Deceleration (m/s²) past which braking is asserted. More negative = harder",
                value = sm.brakingDecelThreshold,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingDecelThreshold = it))) },
                valueRange = -10.0f..0.0f,
                step = 0.1f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterLongSlider(
                label = "brakingDebounceMs",
                description = "Decel must persist this long before braking asserts (rejects potholes)",
                value = sm.brakingDebounceMs,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingDebounceMs = it))) },
                valueRange = 0f..1000f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterLongSlider(
                label = "brakingCooldownMs",
                description = "Braking stays active through easings shorter than this (rejects brake-pumping)",
                value = sm.brakingCooldownMs,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingCooldownMs = it))) },
                valueRange = 0f..2000f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "brakingMinBrightness",
                description = "Brightness at brake onset",
                value = sm.brakingMinBrightness,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingMinBrightness = it))) },
                valueRange = 0.0f..1.0f,
                step = 0.01f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "brakingMaxBrightness",
                description = "Brightness as speed approaches zero",
                value = sm.brakingMaxBrightness,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingMaxBrightness = it))) },
                valueRange = 0.0f..1.0f,
                step = 0.01f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "brakingGamma",
                description = "Curve shape of the ramp; >1 pushes intensity toward the stop",
                value = sm.brakingGamma,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingGamma = it))) },
                valueRange = 0.2f..4.0f,
                step = 0.1f,
                enabled = controlsEnabled
            )
        }
    }

    // --- GROUP 6: BRAKING HOLD / FADE ---
    TuningSectionHeader(title = "Braking hold / fade")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningParameterSlider(
                label = "brakingHoldDurationS",
                description = "Hold the stop brightness this long before fading (seconds)",
                value = sm.brakingHoldDurationS,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingHoldDurationS = it))) },
                valueRange = 0.0f..10.0f,
                step = 0.1f,
                enabled = controlsEnabled
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            TuningParameterSlider(
                label = "brakingFadeDurationS",
                description = "Fade from held brightness toward idle over this long (seconds)",
                value = sm.brakingFadeDurationS,
                onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingFadeDurationS = it))) },
                valueRange = 0.0f..10.0f,
                step = 0.1f,
                enabled = controlsEnabled
            )
        }
    }

    // --- GROUP 7: CORROBORATION ---
    TuningSectionHeader(title = "Corroboration")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningParameterSwitch(
                label = "useBoschCorroboration",
                description = "Master switch for using Bosch data as soft confirmation",
                checked = sm.useBoschCorroboration,
                onCheckedChange = { onConfigSave(config.copy(stateMachine = sm.copy(useBoschCorroboration = it))) },
                enabled = controlsEnabled
            )
        }
    }
}
