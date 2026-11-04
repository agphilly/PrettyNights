package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.config.AppConfig
import com.example.ui.theme.appColors
import com.example.wled.WledLinkType
import com.example.wled.WledTransportState

fun cleanHost(input: String): Pair<String, String?> {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return Pair("", null)
    }
    var cleaned = trimmed
    var correction: String? = null

    if (cleaned.startsWith("http://", ignoreCase = true)) {
        cleaned = cleaned.substring("http://".length)
        correction = "Scheme prefix 'http://' is not allowed and has been stripped."
    } else if (cleaned.startsWith("https://", ignoreCase = true)) {
        cleaned = cleaned.substring("https://".length)
        correction = "Scheme 'https://' is not allowed and has been stripped."
    }

    val slashIndex = cleaned.indexOf("/")
    val qIndex = cleaned.indexOf("?")
    val cutIndex = when {
        slashIndex != -1 && qIndex != -1 -> minOf(slashIndex, qIndex)
        slashIndex != -1 -> slashIndex
        else -> qIndex
    }
    if (cutIndex != -1) {
        cleaned = cleaned.substring(0, cutIndex)
        correction = if (correction == null) {
            "Path/query segments are not allowed and have been stripped."
        } else {
            "$correction Path/query stripped."
        }
    }

    if (cleaned.contains(":")) {
        cleaned = cleaned.substringBefore(":")
        correction = if (correction == null) {
            "Port is not allowed and has been stripped."
        } else {
            "$correction Port stripped."
        }
    }

    return Pair(cleaned.trim(), correction)
}

@Composable
fun WledNetworkSettingsCard(
    config: AppConfig,
    isLocked: Boolean,
    transportState: WledTransportState = WledTransportState(),
    testResult: String? = null,
    onRefreshTransport: () -> Unit = {},
    onTestConnection: (String) -> Unit = {},
    onConfigSave: (AppConfig) -> Unit
) {
    var wledHostInput by remember(config.wledHost) { mutableStateOf(config.wledHost) }
    var audioSyncPortInput by remember(config.audioSyncPort) { mutableStateOf(config.audioSyncPort.toString()) }

    val (_, correctionNote) = remember(wledHostInput) { cleanHost(wledHostInput) }

    LaunchedEffect(Unit) {
        onRefreshTransport()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (transportState.linkType == WledLinkType.ETHERNET_WIRED) {
                        Icons.Default.SettingsEthernet
                    } else {
                        Icons.Default.Wifi
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "LED Controller Target & Dual-Mode Transport",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // --- LIVE DETECTED PHYSICAL LINK BANNER ---
            val linkColor = when (transportState.linkType) {
                WledLinkType.ETHERNET_WIRED -> MaterialTheme.appColors.linkEthernetWired
                WledLinkType.WIFI_BOUND -> MaterialTheme.appColors.linkWifiBound
                WledLinkType.USB_SERIAL_OTG -> MaterialTheme.appColors.linkUsbSerial
                WledLinkType.SYSTEM_DEFAULT -> MaterialTheme.colorScheme.secondary
                WledLinkType.OFFLINE -> MaterialTheme.colorScheme.error
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wled_active_transport_banner"),
                shape = RoundedCornerShape(12.dp),
                color = linkColor.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = when (transportState.linkType) {
                                WledLinkType.ETHERNET_WIRED -> Icons.Default.SettingsEthernet
                                WledLinkType.WIFI_BOUND -> Icons.Default.WifiLock
                                WledLinkType.USB_SERIAL_OTG -> Icons.Default.Usb
                                else -> Icons.Default.Router
                            },
                            contentDescription = null,
                            tint = linkColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Active Link: ${transportState.linkType.label}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = linkColor
                            )
                            val ifaceDetails = buildString {
                                if (!transportState.interfaceName.isNullOrBlank()) {
                                    append(transportState.interfaceName)
                                }
                                if (!transportState.localIpAddress.isNullOrBlank()) {
                                    if (isNotEmpty()) append(" • ")
                                    append(transportState.localIpAddress)
                                }
                                if (transportState.lastPingLatencyMs != null && transportState.lastPingSuccess == true) {
                                    if (isNotEmpty()) append(" • ")
                                    append("${transportState.lastPingLatencyMs} ms RTT")
                                }
                                if (isEmpty()) {
                                    append(
                                        when (transportState.linkType) {
                                            WledLinkType.ETHERNET_WIRED -> "Wired USB-C Ethernet priority active"
                                            WledLinkType.WIFI_BOUND -> "Wi-Fi socket bound (5G/LTE cellular fallback blocked)"
                                            WledLinkType.USB_SERIAL_OTG -> "USB-C Serial 115200 baud active"
                                            else -> "Plug in USB-C Ethernet (charged) or connect Wi-Fi (charging)"
                                        }
                                    )
                                }
                            }
                            Text(
                                text = ifaceDetails,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(linkColor.copy(alpha = 0.22f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = transportState.linkType.badgeText,
                            color = linkColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // --- DUAL-USE WORKFLOW TOGGLE: LOCK TO LOCAL ETHERNET / WI-FI ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.wled_lock_local_network_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.wled_lock_local_network_desc),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }
                Switch(
                    checked = config.lockToLocalNetwork,
                    onCheckedChange = { enabled ->
                        if (!isLocked) {
                            onConfigSave(config.copy(lockToLocalNetwork = enabled))
                        }
                    },
                    enabled = !isLocked,
                    modifier = Modifier.testTag("switch_lock_local_network")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

            // --- QUICK HOST PRESETS FOR CHARGED (USB-C ETHERNET) vs CHARGING (WI-FI) ---
            Text(
                text = "Quick Host Presets (Charged Wired vs. Charging Wi-Fi):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        if (!isLocked) {
                            wledHostInput = "4.3.2.1"
                            onConfigSave(config.copy(wledHost = "4.3.2.1"))
                        }
                    },
                    enabled = !isLocked,
                    label = { Text("WLED-AP (4.3.2.1)", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    modifier = Modifier.testTag("preset_chip_wled_ap")
                )

                AssistChip(
                    onClick = {
                        if (!isLocked) {
                            wledHostInput = "192.168.1.50"
                            onConfigSave(config.copy(wledHost = "192.168.1.50"))
                        }
                    },
                    enabled = !isLocked,
                    label = { Text("Wired IP", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.SettingsEthernet,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    modifier = Modifier.testTag("preset_chip_wired_ip")
                )

                AssistChip(
                    onClick = {
                        if (!isLocked) {
                            wledHostInput = "prettylights.local"
                            onConfigSave(config.copy(wledHost = "prettylights.local"))
                        }
                    },
                    enabled = !isLocked,
                    label = { Text("mDNS", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    modifier = Modifier.testTag("preset_chip_mdns")
                )
            }

            OutlinedTextField(
                value = wledHostInput,
                onValueChange = {
                    if (!isLocked) {
                        wledHostInput = it
                        val (cleaned, _) = cleanHost(it)
                        if (cleaned.isNotEmpty()) {
                            onConfigSave(config.copy(wledHost = cleaned))
                        }
                    }
                },
                enabled = !isLocked,
                label = { Text("WLED Host (Hostname or IP)") },
                leadingIcon = { Icon(Icons.Default.SettingsInputComponent, null) },
                placeholder = { Text("prettylights.local") },
                supportingText = {
                    Column {
                        if (correctionNote != null) {
                            Text(
                                text = correctionNote,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Text(
                                text = "Use 4.3.2.1 when on WLED-AP Wi-Fi (charging), a static IP for USB-C Ethernet (charged), or prettylights.local.",
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wled_host_input"),
                singleLine = true
            )

            val isPortValid = audioSyncPortInput.toIntOrNull()?.let { it in 1..65535 } ?: false

            OutlinedTextField(
                value = audioSyncPortInput,
                onValueChange = {
                    if (!isLocked) {
                        audioSyncPortInput = it
                        val parsed = it.toIntOrNull()
                        if (parsed != null && parsed in 1..65535) {
                            onConfigSave(config.copy(audioSyncPort = parsed))
                        }
                    }
                },
                enabled = !isLocked,
                label = { Text("WLED AudioReactive UDP Sync Port") },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                placeholder = { Text("11988") },
                supportingText = {
                    Text(
                        text = if (!isPortValid) "Port must be an integer between 1 and 65535" else "Must match the UDP port in WLED's AudioReactive settings (default 11988).",
                        color = if (!isPortValid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("audio_sync_port_input"),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onTestConnection(wledHostInput) },
                    enabled = !isLocked,
                    modifier = Modifier.testTag("btn_test_connection")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Connection")
                }

                testResult?.let { result ->
                    val isReachable = result.startsWith("Reachable")
                    val color = when {
                        isReachable -> MaterialTheme.appColors.statusSuccess
                        result == "Unreachable" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("test_connection_result")
                    ) {
                        if (result == "Checking...") {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                                color = color
                            )
                        } else {
                            Icon(
                                imageVector = if (isReachable) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = result,
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // --- DUAL-WORKFLOW HARDWARE TIPS ---
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Rider Connection Workflows",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "• Charged Phone (USB-C to Ethernet): Plug in a USB-C Ethernet adapter wired to an Ethernet WLED controller (e.g., WT32-ETH01 / QuinLED-Dig Ethernet). Pretty Nights automatically prioritizes the wired link.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Charging Phone (Wi-Fi): Connect to WLED-AP (4.3.2.1) and enable 'Disable WiFi sleep' in WLED Config → WiFi Setup. Pretty Nights binds sockets directly to Wi-Fi so Android never falls back to 5G.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
