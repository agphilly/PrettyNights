package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onConfigSave: (AppConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    var wledHostInput by remember(config.wledHost) { mutableStateOf(config.wledHost) }
    var audioSyncPortInput by remember(config.audioSyncPort) { mutableStateOf(config.audioSyncPort.toString()) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val (_, correctionNote) = remember(wledHostInput) { cleanHost(wledHostInput) }

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
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "LED Controller Target",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Recommend a hostname (e.g. prettylights.local) over a raw IP because the controller's IP is typically DHCP-assigned and can change. Raw IP entry still works.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

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
                                text = "Address of the WLED device, e.g. prettylights.local or 192.168.1.50",
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
                    onClick = {
                        val (cleaned, _) = cleanHost(wledHostInput)
                        if (cleaned.isEmpty()) {
                            testResult = "Unreachable"
                        } else {
                            testResult = "Checking..."
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    PrettyNightsApp.instance.wledController.ping(cleaned)
                                }
                                testResult = if (ok) "Reachable" else "Unreachable"
                            }
                        }
                    },
                    enabled = !isLocked,
                    modifier = Modifier.testTag("btn_test_connection")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Connection")
                }

                testResult?.let { result ->
                    val color = when (result) {
                        "Reachable" -> Color(0xFF4CAF50)
                        "Unreachable" -> MaterialTheme.colorScheme.error
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
                                imageVector = if (result == "Reachable") Icons.Default.CheckCircle else Icons.Default.Warning,
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
        }
    }
}
