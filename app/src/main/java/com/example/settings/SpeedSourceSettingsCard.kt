package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.config.AppConfig

@Composable
fun SpeedSourceSettingsCard(
    config: AppConfig,
    isLocked: Boolean,
    onConfigSave: (AppConfig) -> Unit
) {
    val currentSelection = remember(config.speedSource) {
        when (config.speedSource) {
            "GPS", "GPS_ONLY" -> "GPS_ONLY"
            "BOSCH_PREFERRED" -> "BOSCH_PREFERRED"
            "BOSCH_ONLY" -> "BOSCH_ONLY"
            else -> "GPS_ONLY"
        }
    }

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

            val options = listOf(
                Triple(
                    "GPS_ONLY",
                    "GPS Only",
                    "Uses your phone's GPS. Works without a bike connection. Recommended default."
                ),
                Triple(
                    "BOSCH_PREFERRED",
                    "Bosch Preferred",
                    "Uses the bike's speed sensor when available (more accurate), and falls back to GPS automatically if that connection drops or hasn't been set up."
                ),
                Triple(
                    "BOSCH_ONLY",
                    "Bosch Only",
                    "Uses only the bike's speed sensor. If that connection isn't active, ride speed will read as zero — not recommended unless you know the bike connection is working."
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { (key, label, desc) ->
                    val isSelected = currentSelection == key
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !isLocked) {
                                onConfigSave(config.copy(speedSource = key))
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                if (!isLocked) {
                                    onConfigSave(config.copy(speedSource = key))
                                }
                            },
                            enabled = !isLocked,
                            modifier = Modifier.testTag("radio_option_$key")
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
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            if (currentSelection == "BOSCH_PREFERRED" || currentSelection == "BOSCH_ONLY") {
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
