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
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.ride.RideService
import com.example.statemachine.RideState
import com.example.statemachine.StateMachineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun TuningScreen(
    config: AppConfig,
    onConfigSave: (AppConfig) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val isLocked by PrettyNightsApp.instance.lockController.isLocked.collectAsStateWithLifecycle()
    val isRideRunning by RideService.isServiceRunning.collectAsStateWithLifecycle()

    var overrideState by remember { mutableStateOf<RideState?>(null) }
    var overrideBrightness by remember { mutableStateOf(1.0f) }
    var wledUnreachableError by remember { mutableStateOf(false) }

    val sendChannel = remember {
        Channel<Pair<RideState, Float>>(Channel.CONFLATED)
    }

    LaunchedEffect(isRideRunning) {
        if (isRideRunning) {
            overrideState = null
        }
    }

    val currentConfig by rememberUpdatedState(config)

    LaunchedEffect(Unit) {
        var lastSentTime = 0L
        for (event in sendChannel) {
            val (state, brightness) = event
            val now = android.os.SystemClock.elapsedRealtime()
            val timeSinceLast = now - lastSentTime
            if (timeSinceLast < 100) {
                delay(100 - timeSinceLast)
            }
            val currentLook = when (state) {
                RideState.IDLE -> currentConfig.idleLook
                RideState.CRUISING -> currentConfig.cruisingLook
                RideState.BRAKING -> currentConfig.brakingLook
                else -> null
            }
            if (currentLook != null) {
                try {
                    val success = withContext(Dispatchers.IO) {
                        PrettyNightsApp.instance.wledController.sendState(
                            currentConfig.wledHost,
                            currentLook,
                            brightness
                        )
                    }
                    wledUnreachableError = !success
                } catch (e: Exception) {
                    wledUnreachableError = true
                }
                lastSentTime = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

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
                    onSelectOverride = { newState ->
                        overrideState = newState
                        newState?.let { sendChannel.trySend(it to overrideBrightness) }
                    },
                    onBrightnessChange = { newBrightness ->
                        overrideBrightness = newBrightness
                        overrideState?.let { sendChannel.trySend(it to newBrightness) }
                    }
                )

                // 2b. In-App Ride Replay Simulator (Recorded 50 Hz IMU + GPS + Bosch Live Data V1.0)
                TuningReplayCard(
                    currentStateMachineConfig = sm
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
