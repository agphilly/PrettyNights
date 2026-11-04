package com.example.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.config.AppConfig
import com.example.ride.BoschLiveDataState
import com.example.ui.theme.appColors
import com.example.wled.WledLinkType
import com.example.wled.WledTransportState

@Composable
fun HomeReadinessCard(
    config: AppConfig,
    selectedMode: String,
    locationGranted: Boolean,
    microphoneGranted: Boolean,
    permissionPermanentlyDenied: Boolean,
    wledStatus: ScreenWledStatus,
    wledTransportState: WledTransportState = WledTransportState(),
    boschState: BoschLiveDataState = BoschLiveDataState(),
    bluetoothGranted: Boolean = true,
    context: Context,
    onGrantLocation: () -> Unit,
    onGrantMic: () -> Unit,
    onCheckWled: () -> Unit
) {
    val isModeStandard = selectedMode == "STANDARD"
    val wledBypassed = isModeStandard && config.logOnlyMode

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("readiness_indicators_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Ride Readiness Handshake",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Phone sensors / Microphone indicator
            if (isModeStandard) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Phone sensors (location)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (locationGranted) "Ready to measure 50 Hz IMU & GPS speed" else "Access required for ride analytics",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.appColors.mutedText
                            )
                        }
                    }

                    // Status Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (locationGranted) MaterialTheme.appColors.statusPassBadge else MaterialTheme.appColors.statusDanger)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (locationGranted) "PASS" else "FAIL",
                            color = MaterialTheme.appColors.onBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                // Bosch Live Data V1.0 BLE Telemetry indicator (always recorded alongside GPS)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Bosch Live Data V1.0 (eb20/eb21)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    boschState.isConnected -> "Connected (${boschState.connectedDeviceName ?: "eBike"}, MTU=${boschState.negotiatedMtu})"
                                    !bluetoothGranted -> "Always logged when BLE permitted; GPS active"
                                    else -> "Always logged alongside GPS (Service Solicitation eb20)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.appColors.mutedText
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    boschState.isConnected -> MaterialTheme.appColors.statusPassBadge
                                    bluetoothGranted -> MaterialTheme.appColors.statusReadyBadge
                                    else -> MaterialTheme.appColors.statusStandbyBadge
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when {
                                boschState.isConnected -> "LIVE"
                                bluetoothGranted -> "READY"
                                else -> "STANDBY"
                            },
                            color = MaterialTheme.appColors.onBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mic_readiness_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Microphone input",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (microphoneGranted) "Ready to capture internal audio" else "Access required for music reactions",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.appColors.mutedText
                            )
                        }
                    }

                    // Status Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (microphoneGranted) MaterialTheme.appColors.statusPassBadge else MaterialTheme.appColors.statusDanger)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (microphoneGranted) "PASS" else "FAIL",
                            color = MaterialTheme.appColors.onBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            // WLED controller indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (wledTransportState.linkType) {
                                WledLinkType.ETHERNET_WIRED -> Icons.Default.SettingsEthernet
                                WledLinkType.USB_SERIAL_OTG -> Icons.Default.Usb
                                else -> Icons.Default.Wifi
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    val linkBadge = wledTransportState.linkType.badgeText
                    val rttSuffix = wledTransportState.lastPingLatencyMs?.let { " • ${it}ms" } ?: ""
                    Column {
                        Text(
                            text = "WLED controller ($linkBadge)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (wledBypassed) {
                                "Bypassed in Log Only Mode (WLED not required)"
                            } else {
                                when (wledStatus) {
                                    ScreenWledStatus.CHECKING -> "Checking ${config.wledHost} via ${wledTransportState.linkType.label}..."
                                    ScreenWledStatus.REACHABLE -> "Connected to ${config.wledHost} (${wledTransportState.linkType.label}$rttSuffix)"
                                    else -> "Offline at ${config.wledHost} (${wledTransportState.linkType.label})"
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.appColors.mutedText
                        )
                    }
                }

                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (wledBypassed) {
                                MaterialTheme.appColors.statusPassBadge
                            } else {
                                when (wledStatus) {
                                    ScreenWledStatus.REACHABLE -> MaterialTheme.appColors.statusPassBadge
                                    ScreenWledStatus.CHECKING -> MaterialTheme.appColors.statusCheckingBadge
                                    else -> MaterialTheme.appColors.statusDanger
                                }
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (wledBypassed) {
                            "LOG ONLY"
                        } else {
                            when (wledStatus) {
                                ScreenWledStatus.REACHABLE -> "PASS"
                                ScreenWledStatus.CHECKING -> "PING"
                                else -> "FAIL"
                            }
                        },
                        color = MaterialTheme.appColors.onBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Contextual Action Area
    val currentPermissionGranted = if (isModeStandard) locationGranted else microphoneGranted

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("contextual_actions_panel"),
        contentAlignment = Alignment.Center
    ) {
        if (!currentPermissionGranted) {
            if (isModeStandard) {
                // Location NOT GRANTED Case
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (permissionPermanentlyDenied) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Permission Blocked",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Location is required and has been blocked. Enable it in Settings to ride.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("blocked_message")
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_open_settings"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Settings", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Pretty Nights uses your location to measure ride speed, which drives the light effects.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.testTag("rationale_text")
                                )
                            }
                        }

                        Button(
                            onClick = onGrantLocation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_grant_location"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grant location", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Microphone NOT GRANTED Case (Music Mode)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Pretty Nights needs record audio permission to capture internal audio, allowing your lights to pulse and react in real-time.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.testTag("rationale_text_mic")
                            )
                        }
                    }

                    Button(
                        onClick = onGrantMic,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_grant_mic"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant microphone permission", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (!wledBypassed && wledStatus != ScreenWledStatus.REACHABLE) {
            // Permission IS GRANTED, but WLED unreachable (and not in Log-Only mode)
            Button(
                onClick = onCheckWled,
                enabled = wledStatus != ScreenWledStatus.CHECKING,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_recheck_wled"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                if (wledStatus == ScreenWledStatus.CHECKING) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pinging Controller...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Re-check WLED", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // Ready (either WLED reachable OR Log-Only mode active)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.appColors.statusPassBadge.copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.appColors.statusPassText)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            wledBypassed -> "Log Only Mode ready — recording IMU, GPS & Bosch Live Data V1.0!"
                            isModeStandard -> "Systems operating normally. Ready to ride!"
                            else -> "Systems operating normally. Ready to stream!"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.appColors.statusPassText
                    )
                }
            }
        }
    }
}
