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
    onSelectMode: (String) -> Unit,
    onToggleService: () -> Unit,
    onNavigateTab: (Int) -> Unit,
    onNavigateToTuning: () -> Unit
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
                    if (isModeStandard) "START RIDE" else "START MUSIC"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // --- Secondary Navigation / Setup Shortcuts ---
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_shortcuts_panel"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Setup & Calibration",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Shortcut to Patterns
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateTab(1) }
                        .testTag("shortcut_patterns"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Patterns", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }

                // Shortcut to Calibration settings
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateTab(3) }
                        .testTag("shortcut_settings"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Calibrate", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }

                // Shortcut to Developer / Tuning Dashboard
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToTuning() }
                        .testTag("shortcut_tuning"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Dev Tuning", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
