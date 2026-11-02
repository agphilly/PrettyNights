package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.config.AppConfig
import com.example.config.SpeedSourceMode

private fun SpeedSourceMode.description(): String = when (this) {
    SpeedSourceMode.GPS_ONLY ->
        "Uses your phone's GPS. Works without a bike connection. Recommended default."
    SpeedSourceMode.BOSCH_PREFERRED ->
        "Uses the bike's speed sensor when available (more accurate), and falls back to GPS automatically if that connection drops or hasn't been set up."
    SpeedSourceMode.BOSCH_ONLY ->
        "Uses only the bike's speed sensor. If that connection isn't active, ride speed will read as zero — not recommended unless you know the bike connection is working."
}

@Composable
fun SpeedSourceSettingsCard(
    config: AppConfig,
    isLocked: Boolean,
    onConfigSave: (AppConfig) -> Unit
) {
    val currentSelection = config.speedSource

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Speed Signal Source Preference",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SpeedSourceMode.entries.forEach { mode ->
                    val isSelected = currentSelection == mode
                    val label = when (mode) {
                        SpeedSourceMode.GPS_ONLY -> "GPS Only"
                        SpeedSourceMode.BOSCH_PREFERRED -> "Bosch Preferred"
                        SpeedSourceMode.BOSCH_ONLY -> "Bosch Only"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !isLocked) {
                                onConfigSave(config.copy(speedSource = mode))
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                if (!isLocked) {
                                    onConfigSave(config.copy(speedSource = mode))
                                }
                            },
                            enabled = !isLocked,
                            modifier = Modifier.testTag("radio_option_${mode.wireValue}")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = mode.description(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            if (currentSelection == SpeedSourceMode.BOSCH_PREFERRED || currentSelection == SpeedSourceMode.BOSCH_ONLY) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                        .padding(12.dp)
                        .testTag("bike_connection_warning"),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "No bike connection detected. Bosch Preferred will use GPS until one is connected; Bosch Only will read zero speed.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
