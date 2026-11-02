package com.example.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.config.AppConfig
import com.example.config.WledHardwareConfig
import com.example.wled.WledHardwareLiveInfo
import com.example.wled.WledTransportState

@Composable
fun SettingsScreen(
    config: AppConfig? = null,
    onConfigSave: ((AppConfig) -> Unit)? = null,
    onNavigateToTuning: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val vmConfig by vm.config.collectAsStateWithLifecycle()
    val isLocked by vm.isLocked.collectAsStateWithLifecycle()
    val transportState by vm.transportState.collectAsStateWithLifecycle()
    val testConnectionResult by vm.testConnectionResult.collectAsStateWithLifecycle()
    val liveHardwareInfo by vm.liveHardwareInfo.collectAsStateWithLifecycle()
    val isSyncingHardware by vm.isSyncingHardware.collectAsStateWithLifecycle()
    val hardwareSyncMessage by vm.hardwareSyncMessage.collectAsStateWithLifecycle()
    val hardwareSyncIsError by vm.hardwareSyncIsError.collectAsStateWithLifecycle()

    val effectiveConfig = config ?: vmConfig
    val effectiveSave = onConfigSave ?: vm::saveConfig

    SettingsContent(
        config = effectiveConfig,
        isLocked = isLocked,
        transportState = transportState,
        testConnectionResult = testConnectionResult,
        liveHardwareInfo = liveHardwareInfo,
        isSyncingHardware = isSyncingHardware,
        hardwareSyncMessage = hardwareSyncMessage,
        hardwareSyncIsError = hardwareSyncIsError,
        onRefreshTransport = vm::refreshTransport,
        onTestConnection = vm::testConnection,
        onReadHardwareInfo = { vm.readHardwareInfo(effectiveConfig.wledHost) },
        onPushHardwareAndSegments = { hw -> vm.pushHardwareAndSegments(effectiveConfig.wledHost, hw) },
        onConfigSave = effectiveSave,
        onNavigateToTuning = onNavigateToTuning
    )
}

@Composable
fun SettingsContent(
    config: AppConfig,
    isLocked: Boolean,
    transportState: WledTransportState = WledTransportState(),
    testConnectionResult: String? = null,
    liveHardwareInfo: WledHardwareLiveInfo? = null,
    isSyncingHardware: Boolean = false,
    hardwareSyncMessage: String? = null,
    hardwareSyncIsError: Boolean = false,
    onRefreshTransport: () -> Unit = {},
    onTestConnection: (String) -> Unit = {},
    onReadHardwareInfo: () -> Unit = {},
    onPushHardwareAndSegments: (WledHardwareConfig) -> Unit = {},
    onConfigSave: (AppConfig) -> Unit,
    onNavigateToTuning: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- SAFETY LOCK OUT WARNING BANNER ---
            if (isLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                        .padding(12.dp)
                        .testTag("ride_active_lock_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked icon",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Locked while riding — Settings cannot be modified",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // --- HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "System & Hardware Config",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // --- DEVELOPER / TUNING ENTRY PANEL ---
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isLocked) { onNavigateToTuning() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isLocked) 0.05f else 0.15f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Developer / Tuning Dashboard",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Tune deep variables backing pure ride transitions with live telemetry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Navigate to developer dashboard",
                        tint = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 1. WLED Network & Target Settings
            WledNetworkSettingsCard(
                config = config,
                isLocked = isLocked,
                transportState = transportState,
                testResult = testConnectionResult,
                onRefreshTransport = onRefreshTransport,
                onTestConnection = onTestConnection,
                onConfigSave = onConfigSave
            )

            // 2. NaveiTech NV-LC616WL Hardware, I/O & Multi-Segment Configuration
            WledHardwareConfigCard(
                config = config,
                isLocked = isLocked,
                liveInfo = liveHardwareInfo,
                isSyncing = isSyncingHardware,
                syncStatusMessage = hardwareSyncMessage,
                syncIsError = hardwareSyncIsError,
                onReadHardwareInfo = onReadHardwareInfo,
                onPushHardwareAndSegments = onPushHardwareAndSegments,
                onConfigSave = onConfigSave
            )

            // 3. Speed Source Preference
            SpeedSourceSettingsCard(
                config = config,
                isLocked = isLocked,
                onConfigSave = onConfigSave
            )

            // 4. Sensor Orientation & Polarity
            SensorCalibrationCard(
                config = config,
                isLocked = isLocked,
                onConfigSave = onConfigSave
            )

            Spacer(modifier = Modifier.height(64.dp))
        }

        // --- SAFETY LOCKOUT OVERLAY ---
        AnimatedVisibility(
            visible = isLocked,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onErrorContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Text(
                            text = "Safety Lock Active",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Pretty Nights blocks settings modifications while the bike is in active state. Please stop to configure.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
