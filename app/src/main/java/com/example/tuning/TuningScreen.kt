package com.example.tuning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.config.AppConfig
import com.example.logging.SessionEntity
import com.example.statemachine.RideState
import com.example.statemachine.StateMachineConfig
import com.example.statemachine.StateMachineTelemetry

@Composable
fun TuningScreen(
    config: AppConfig? = null,
    onConfigSave: ((AppConfig) -> Unit)? = null,
    onBack: () -> Unit,
    vm: TuningViewModel = viewModel(factory = TuningViewModel.Factory)
) {
    val vmConfig by vm.config.collectAsStateWithLifecycle()
    val isLocked by vm.isLocked.collectAsStateWithLifecycle()
    val isRideRunning by vm.isRideRunning.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val overrideState by vm.overrideState.collectAsStateWithLifecycle()
    val overrideBrightness by vm.overrideBrightness.collectAsStateWithLifecycle()
    val wledUnreachableError by vm.wledUnreachableError.collectAsStateWithLifecycle()
    val replayResult by vm.replayResult.collectAsStateWithLifecycle()
    val isRunningReplay by vm.isRunningReplay.collectAsStateWithLifecycle()

    TuningContent(
        config = config ?: vmConfig,
        isLocked = isLocked,
        isRideRunning = isRideRunning,
        telemetry = telemetry,
        sessions = sessions,
        overrideState = overrideState,
        overrideBrightness = overrideBrightness,
        wledUnreachableError = wledUnreachableError,
        replayResult = replayResult,
        isRunningReplay = isRunningReplay,
        onSelectOverride = vm::selectOverride,
        onBrightnessChange = vm::setOverrideBrightness,
        onRunReplay = vm::runReplay,
        onConfigSave = onConfigSave ?: vm::saveConfig,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningContent(
    config: AppConfig,
    isLocked: Boolean = false,
    isRideRunning: Boolean = false,
    telemetry: StateMachineTelemetry? = null,
    sessions: List<SessionEntity> = emptyList(),
    overrideState: RideState? = null,
    overrideBrightness: Float = 1.0f,
    wledUnreachableError: Boolean = false,
    replayResult: ReplayComparisonResult? = null,
    isRunningReplay: Boolean = false,
    onSelectOverride: (RideState?) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onRunReplay: (Long, StateMachineConfig, String) -> Unit = { _, _, _ -> },
    onConfigSave: (AppConfig) -> Unit = {},
    onBack: () -> Unit = {}
) {
    BackHandler(onBack = onBack)

    var showResetDialog by remember { mutableStateOf(false) }

    val sm = config.stateMachine

    // Validation checks for input constraints
    val isMinMaxValid = sm.globalMinBrightness <= sm.globalMaxBrightness
    val isSpeedValid = sm.idleEnterSpeedKmh <= sm.idleExitSpeedKmh
    val isBrakingMinMaxValid = sm.brakingMinBrightness <= sm.brakingMaxBrightness
    val controlsEnabled = !isLocked

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

                // 1. Live Telemetry Panel
                TuningTelemetryCard(
                    telemetry = telemetry,
                    isRideRunning = isRideRunning
                )

                // 2. Manual Look Override Testing
                TuningManualOverrideCard(
                    isRideRunning = isRideRunning,
                    overrideState = overrideState,
                    overrideBrightness = overrideBrightness,
                    wledUnreachableError = wledUnreachableError,
                    onSelectOverride = onSelectOverride,
                    onBrightnessChange = onBrightnessChange
                )

                // 2b. In-App Ride Replay Simulator (Recorded 50 Hz IMU + GPS + Bosch Live Data V1.0)
                TuningReplayCard(
                    currentStateMachineConfig = sm,
                    sessions = sessions,
                    replayResult = replayResult,
                    isRunningReplay = isRunningReplay,
                    onRunReplay = onRunReplay
                )

                // 2c. Web Workbench Config Sync (JSON / Kotlin Round-Trip)
                TuningConfigSyncCard(
                    config = config,
                    controlsEnabled = controlsEnabled,
                    onConfigSave = onConfigSave
                )

                // 3. Safety Lock Notice
                if (isLocked) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("safety_lock_notice")
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

                // 4. Parameter Slider Sections
                TuningParameterSections(
                    config = config,
                    sm = sm,
                    controlsEnabled = controlsEnabled,
                    isMinMaxValid = isMinMaxValid,
                    isSpeedValid = isSpeedValid,
                    isBrakingMinMaxValid = isBrakingMinMaxValid,
                    onConfigSave = onConfigSave
                )

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
