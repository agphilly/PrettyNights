package com.example.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeActionControls(
    selectedMode: String,
    isRunning: Boolean,
    isMusicRunning: Boolean,
    isReady: Boolean,
    logOnlyMode: Boolean = false,
    markerCount: Int = 0,
    lastMarkerLabel: String? = null,
    onSelectMode: (String) -> Unit,
    onStampMarker: (String) -> Unit = {},
    onToggleService: () -> Unit
) {
    val isAnyServiceRunning = isRunning || isMusicRunning
    val isModeStandard = selectedMode == "STANDARD"

    // --- Mode Selection Toggle ---
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(4.dp)
            .testTag("mode_selector_row"),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("STANDARD", "MUSIC").forEach { mode ->
            val isSelected = selectedMode == mode
            val isLockedMode = isAnyServiceRunning && !isSelected
            val targetColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
            val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(targetColor)
                    .clickable(enabled = !isAnyServiceRunning) {
                        onSelectMode(mode)
                    }
                    .padding(vertical = 10.dp)
                    .testTag("mode_toggle_$mode"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (mode == "STANDARD") "STANDARD MODE" else "MUSIC MODE",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = if (isLockedMode) 0.38f else 1.0f)
                )
            }
        }
    }

    // --- Main Start / Stop Button ---
    val buttonEnabled = isRunning || isMusicRunning || isReady

    Button(
        onClick = onToggleService,
        enabled = buttonEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("btn_ride_toggle"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning || isMusicRunning) Color(0xFFC62828) else MaterialTheme.colorScheme.primary,
            disabledContainerColor = Color.Gray.copy(alpha = 0.25f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isRunning || isMusicRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning || isMusicRunning) {
                    if (isRunning) "STOP RIDE" else "STOP MUSIC"
                } else {
                    if (isModeStandard) {
                        if (logOnlyMode) "START LOG-ONLY RIDE" else "START RIDE"
                    } else {
                        "START MUSIC"
                    }
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // --- Live In-Ride Event Marker Bar (Active during both WLED-enabled and Log-Only rides) ---
    if (isRunning) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("in_ride_marker_bar"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Flag,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Stamp Live Event Marker",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = if (lastMarkerLabel != null) {
                            "Stamped: $lastMarkerLabel ($markerCount)"
                        } else {
                            "Markers: $markerCount"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarkerStampButton(
                        label = "Hard Brake",
                        markerType = "HARD_BRAKE",
                        color = Color(0xFFC62828),
                        modifier = Modifier.weight(1f),
                        onStamp = onStampMarker
                    )
                    MarkerStampButton(
                        label = "Light Brake",
                        markerType = "LIGHT_BRAKE",
                        color = Color(0xFFEF6C00),
                        modifier = Modifier.weight(1f),
                        onStamp = onStampMarker
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarkerStampButton(
                        label = "Road Bump",
                        markerType = "ROAD_BUMP",
                        color = Color(0xFF6A1B9A),
                        modifier = Modifier.weight(1f),
                        onStamp = onStampMarker
                    )
                    MarkerStampButton(
                        label = "Bosch Glitch",
                        markerType = "BOSCH_GLITCH",
                        color = Color(0xFF00838F),
                        modifier = Modifier.weight(1f),
                        onStamp = onStampMarker
                    )
                }
            }
        }
    }
}

@Composable
fun HomeLogOnlyModeCard(
    logOnlyMode: Boolean,
    isAnyServiceRunning: Boolean,
    onToggleLogOnlyMode: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_log_only_toggle"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (logOnlyMode) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Analytics,
                    contentDescription = null,
                    tint = if (logOnlyMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Log Only Mode (No WLED)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (logOnlyMode) {
                            "WLED bypassed — logging 50 Hz IMU, GPS & Bosch Live Data V1.0"
                        } else {
                            "Full telemetry is always logged; enable to ride without WLED hardware"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = logOnlyMode,
                onCheckedChange = onToggleLogOnlyMode,
                enabled = !isAnyServiceRunning,
                modifier = Modifier.testTag("switch_log_only_mode")
            )
        }
    }
}

@Composable
private fun MarkerStampButton(
    label: String,
    markerType: String,
    color: Color,
    modifier: Modifier = Modifier,
    onStamp: (String) -> Unit
) {
    Button(
        onClick = { onStamp(markerType) },
        modifier = modifier
            .height(48.dp)
            .testTag("btn_marker_$markerType"),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
