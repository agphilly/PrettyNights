package com.example.tuning

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.ride.RideService
import com.example.statemachine.RideState
import com.example.statemachine.StateMachineConfig
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun TuningScreen(
    config: AppConfig,
    onConfigSave: (AppConfig) -> Unit,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val isLocked by PrettyNightsApp.instance.lockController.isLocked.collectAsStateWithLifecycle()
    val isRideRunning by RideService.isServiceRunning.collectAsStateWithLifecycle()

    // Visual throttled telemetry flow (~6.7 Hz / 150ms sample)
    val telemetry by remember {
        PrettyNightsApp.instance.orchestrator.telemetryFlow
            .sample(150L)
    }.collectAsStateWithLifecycle(initialValue = null)

    var showResetDialog by remember { mutableStateOf(false) }

    val sm = config.stateMachine

    // Validation checks for input constraints
    val isMinMaxValid = sm.globalMinBrightness <= sm.globalMaxBrightness
    val isSpeedValid = sm.idleEnterSpeedKmh <= sm.idleExitSpeedKmh
    val isBrakingMinMaxValid = sm.brakingMinBrightness <= sm.brakingMaxBrightness

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer / Tuning", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("btn_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showResetDialog = true },
                        enabled = !isLocked,
                        modifier = Modifier.testTag("btn_reset_defaults")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Defaults")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // --- TELEMETRY PANEL ---
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("telemetry_panel"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Timeline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "State Machine Telemetry",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (isRideRunning && telemetry != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color.Green)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "LIVE",
                                        color = Color.Green,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Text(
                                    "Off",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isRideRunning && telemetry != null) {
                            val activeTel = telemetry!!
                            TelemetryRow(label = "Current state", value = activeTel.state.name)
                            TelemetryRow(label = "Brightness", value = String.format("%.2f", activeTel.brightness))
                            TelemetryRow(label = "Time in state", value = "${activeTel.timeInStateMs} ms")
                            TelemetryRow(label = "Entry speed", value = activeTel.entrySpeedKmh?.let { String.format("%.1f km/h", it) } ?: "n/a")
                            TelemetryRow(label = "Decel active", value = if (activeTel.decelActive) "yes" else "no")
                            TelemetryRow(label = "In entry fade", value = if (activeTel.inEntryFade) "yes" else "no")
                            TelemetryRow(label = "Hold/fade phase", value = activeTel.holdFadePhase)
                        } else {
                            Text(
                                text = "No active ride",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Gray,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TelemetryRow(label = "Current state", value = "—")
                            TelemetryRow(label = "Brightness", value = "—")
                            TelemetryRow(label = "Time in state", value = "—")
                            TelemetryRow(label = "Entry speed", value = "—")
                            TelemetryRow(label = "Decel active", value = "—")
                            TelemetryRow(label = "In entry fade", value = "—")
                            TelemetryRow(label = "Hold/fade phase", value = "—")
                        }
                    }
                }

                // --- SAFETY LOCK NOTICE ---
                if (isLocked) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("safety_lock_notice")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Lock",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Locked while riding (adjustments disabled)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // --- VALIDATION ERROR WARNINGS ---
                if (!isMinMaxValid || !isSpeedValid || !isBrakingMinMaxValid) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("validation_warning_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Validation Warnings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (!isMinMaxValid) {
                                Text("• globalMinBrightness must be ≤ globalMaxBrightness", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (!isSpeedValid) {
                                Text("• idleEnterSpeedKmh must be ≤ idleExitSpeedKmh", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (!isBrakingMinMaxValid) {
                                Text("• brakingMinBrightness must be ≤ brakingMaxBrightness", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                val controlsEnabled = !isLocked

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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                        TuningParameterSlider(
                            label = "globalMaxBrightness",
                            description = "Ambient-state ceiling (idle/cruising/hold-fade)",
                            value = sm.globalMaxBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(globalMaxBrightness = it))) },
                            valueRange = 0.0f..1.0f,
                            step = 0.01f,
                            enabled = controlsEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                        TuningParameterSlider(
                            label = "idleBrightness",
                            description = "Static idle brightness (pre-envelope)",
                            value = sm.idleBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleBrightness = it))) },
                            valueRange = 0.0f..1.0f,
                            step = 0.01f,
                            enabled = controlsEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                        TuningParameterSlider(
                            label = "brakingDecelThreshold",
                            description = "Deceleration (m/s²) past which braking is asserted. More negative = harder",
                            value = sm.brakingDecelThreshold,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingDecelThreshold = it))) },
                            valueRange = -10.0f..0.0f,
                            step = 0.1f,
                            enabled = controlsEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                        TuningParameterLongSlider(
                            label = "brakingDebounceMs",
                            description = "Decel must persist this long before braking asserts (rejects potholes)",
                            value = sm.brakingDebounceMs,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingDebounceMs = it))) },
                            valueRange = 0f..1000f,
                            enabled = controlsEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                        TuningParameterLongSlider(
                            label = "brakingCooldownMs",
                            description = "Braking stays active through easings shorter than this (rejects brake-pumping)",
                            value = sm.brakingCooldownMs,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingCooldownMs = it))) },
                            valueRange = 0f..2000f,
                            enabled = controlsEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                        TuningParameterSlider(
                            label = "brakingMinBrightness",
                            description = "Brightness at brake onset",
                            value = sm.brakingMinBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingMinBrightness = it))) },
                            valueRange = 0.0f..1.0f,
                            step = 0.01f,
                            enabled = controlsEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                        TuningParameterSlider(
                            label = "brakingMaxBrightness",
                            description = "Brightness as speed approaches zero",
                            value = sm.brakingMaxBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingMaxBrightness = it))) },
                            valueRange = 0.0f..1.0f,
                            step = 0.01f,
                            enabled = controlsEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
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

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    // --- RESET CONFIRMATION DIALOG ---
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset to Defaults") },
            text = { Text("Are you sure you want to restore all tuned parameters to their original starting defaults? This will immediately persist and apply live if a ride is active.") },
            confirmButton = {
                Button(
                    onClick = {
                        val resetConfig = config.copy(stateMachine = StateMachineConfig())
                        onConfigSave(resetConfig)
                        showResetDialog = false
                    },
                    modifier = Modifier.testTag("btn_dialog_confirm_reset"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Reset")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetDialog = false },
                    modifier = Modifier.testTag("btn_dialog_cancel_reset")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TuningSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun TuningParameterSlider(
    label: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean
) {
    var textValue by remember(value) { mutableStateOf(String.format("%.2f", value)) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                if (description.isNotEmpty()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            // Small exact numeric text input
            OutlinedTextField(
                value = textValue,
                onValueChange = { newValue ->
                    textValue = newValue
                    val parsed = newValue.toFloatOrNull()
                    if (parsed != null) {
                        val clamped = parsed.coerceIn(valueRange.start, valueRange.endInclusive)
                        onValueChange(clamped)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = enabled,
                modifier = Modifier
                    .width(100.dp)
                    .testTag("input_$label"),
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End, fontSize = 12.sp),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Slider(
            value = value,
            onValueChange = {
                onValueChange(it)
            },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("slider_$label")
        )
    }
}

@Composable
fun TuningParameterLongSlider(
    label: String,
    description: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                if (description.isNotEmpty()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            // Small exact numeric text input
            OutlinedTextField(
                value = textValue,
                onValueChange = { newValue ->
                    textValue = newValue
                    val parsed = newValue.toLongOrNull()
                    if (parsed != null) {
                        val clamped = parsed.coerceIn(valueRange.start.toLong(), valueRange.endInclusive.toLong())
                        onValueChange(clamped)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = enabled,
                modifier = Modifier
                    .width(100.dp)
                    .testTag("input_$label"),
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End, fontSize = 12.sp),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Slider(
            value = value.toFloat(),
            onValueChange = {
                onValueChange(it.toLong())
            },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("slider_$label")
        )
    }
}

@Composable
fun TuningParameterSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag("switch_$label")
        )
    }
}
