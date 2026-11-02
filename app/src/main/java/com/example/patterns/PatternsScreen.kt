package com.example.patterns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.config.AppConfig
import com.example.config.WledLook
import com.example.statemachine.RideState
import com.example.wled.WledPreset
import kotlinx.coroutines.delay

@Composable
fun PatternsScreen(
    config: AppConfig? = null,
    onConfigSave: ((AppConfig) -> Unit)? = null,
    vm: PatternsViewModel = viewModel(factory = PatternsViewModel.Factory)
) {
    val vmConfig by vm.config.collectAsStateWithLifecycle()
    val isLocked by vm.isLocked.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val effects by vm.effects.collectAsStateWithLifecycle()
    val palettes by vm.palettes.collectAsStateWithLifecycle()
    val isFetching by vm.isFetching.collectAsStateWithLifecycle()
    val fetchFailed by vm.fetchFailed.collectAsStateWithLifecycle()
    val previewStatusByState by vm.previewStatusByState.collectAsStateWithLifecycle()

    val effectiveConfig = config ?: vmConfig

    LaunchedEffect(effectiveConfig.wledHost) {
        vm.fetchWledData(effectiveConfig.wledHost)
    }

    PatternsContent(
        config = effectiveConfig,
        isLocked = isLocked,
        presets = presets,
        effects = effects,
        palettes = palettes,
        isFetching = isFetching,
        fetchFailed = fetchFailed,
        previewStatusByState = previewStatusByState,
        onRetryFetch = { vm.fetchWledData(effectiveConfig.wledHost) },
        onPreviewLook = { state, host, look -> vm.previewLook(state, host, look) },
        onClearPreviewStatus = vm::clearPreviewStatus,
        onConfigSave = onConfigSave ?: vm::saveConfig
    )
}

@Composable
fun PatternsContent(
    config: AppConfig,
    isLocked: Boolean = false,
    presets: List<WledPreset>? = null,
    effects: List<String>? = null,
    palettes: List<String>? = null,
    isFetching: Boolean = false,
    fetchFailed: Boolean = false,
    previewStatusByState: Map<RideState, String> = emptyMap(),
    onRetryFetch: () -> Unit = {},
    onPreviewLook: (RideState, String, WledLook) -> Unit = { _, _, _ -> },
    onClearPreviewStatus: (RideState) -> Unit = {},
    onConfigSave: (AppConfig) -> Unit = {}
) {
    var localConfig by remember(config) { mutableStateOf(config) }
    var saveStatusMsg by remember { mutableStateOf<String?>(null) }
    var activeEditingState by remember { mutableStateOf<RideState?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Glow Pattern Assignments",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Map WLED lighting looks, custom palettes, speeds, and transitions for each ride state.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // --- SAFETY LOCK OUT WARNING BANNER ---
            if (isLocked) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ride_active_lock_banner"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        width = 1.dp,
                        brush = SolidColor(MaterialTheme.colorScheme.error)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                "Locked while riding",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Editing controls are disabled for rider safety.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // --- RETRY TRIGGER FOR METADATA ---
            if (fetchFailed) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Couldn't load presets — check the WLED connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = onRetryFetch,
                            modifier = Modifier.testTag("btn_retry_fetch_presets")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Preset Load")
                        }
                    }
                }
            }

            // --- 3 CONFIGURABLE STATE SLOTS (IDLE, CRUISING, BRAKING) ---
            StateLookCard(
                stateLabel = "IDLE (Stationary)",
                state = RideState.IDLE,
                look = localConfig.idleLook,
                presets = presets,
                effects = effects,
                palettes = palettes,
                isFetchingPresets = isFetching,
                fetchFailed = fetchFailed,
                onRetryFetch = onRetryFetch,
                expanded = activeEditingState == RideState.IDLE,
                onHeaderClick = {
                    activeEditingState = if (activeEditingState == RideState.IDLE) null else RideState.IDLE
                },
                onLookChanged = { newLook ->
                    localConfig = localConfig.copy(idleLook = newLook)
                },
                isLocked = isLocked,
                previewStatus = previewStatusByState[RideState.IDLE],
                onPreviewLook = { onPreviewLook(RideState.IDLE, localConfig.wledHost, localConfig.idleLook) },
                onClearPreviewStatus = { onClearPreviewStatus(RideState.IDLE) }
            )

            StateLookCard(
                stateLabel = "CRUISING (In Motion)",
                state = RideState.CRUISING,
                look = localConfig.cruisingLook,
                presets = presets,
                effects = effects,
                palettes = palettes,
                isFetchingPresets = isFetching,
                fetchFailed = fetchFailed,
                onRetryFetch = onRetryFetch,
                expanded = activeEditingState == RideState.CRUISING,
                onHeaderClick = {
                    activeEditingState = if (activeEditingState == RideState.CRUISING) null else RideState.CRUISING
                },
                onLookChanged = { newLook ->
                    localConfig = localConfig.copy(cruisingLook = newLook)
                },
                isLocked = isLocked,
                previewStatus = previewStatusByState[RideState.CRUISING],
                onPreviewLook = { onPreviewLook(RideState.CRUISING, localConfig.wledHost, localConfig.cruisingLook) },
                onClearPreviewStatus = { onClearPreviewStatus(RideState.CRUISING) }
            )

            StateLookCard(
                stateLabel = "BRAKING (Decelerating)",
                state = RideState.BRAKING,
                look = localConfig.brakingLook,
                presets = presets,
                effects = effects,
                palettes = palettes,
                isFetchingPresets = isFetching,
                fetchFailed = fetchFailed,
                onRetryFetch = onRetryFetch,
                expanded = activeEditingState == RideState.BRAKING,
                onHeaderClick = {
                    activeEditingState = if (activeEditingState == RideState.BRAKING) null else RideState.BRAKING
                },
                onLookChanged = { newLook ->
                    localConfig = localConfig.copy(brakingLook = newLook)
                },
                isLocked = isLocked,
                previewStatus = previewStatusByState[RideState.BRAKING],
                onPreviewLook = { onPreviewLook(RideState.BRAKING, localConfig.wledHost, localConfig.brakingLook) },
                onClearPreviewStatus = { onClearPreviewStatus(RideState.BRAKING) }
            )

            // Save Settings status and action
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    onConfigSave(localConfig)
                    saveStatusMsg = "Configuration saved successfully!"
                },
                enabled = !isLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_save_look_config"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Configuration", fontWeight = FontWeight.Bold)
            }

            saveStatusMsg?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_status_text")
                )
                LaunchedEffect(msg) {
                    delay(3000)
                    saveStatusMsg = null
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
