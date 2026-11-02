package com.example.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.config.AppConfig
import com.example.config.SensorAxis

@Composable
fun SensorCalibrationCard(
    config: AppConfig,
    isLocked: Boolean,
    onConfigSave: (AppConfig) -> Unit
) {
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
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Sensor Orientation Alignment",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Configure the physical phone mount axis alignment and sign polarity depending on how the device is mounted to the bike handlebars.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            // Forward Axis Alignment
            Text("Mount Forward Axis Alignment", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SensorAxis.entries.forEach { axis ->
                    FilterChip(
                        selected = config.sensorAxis == axis,
                        onClick = {
                            if (!isLocked) {
                                onConfigSave(config.copy(sensorAxis = axis))
                            }
                        },
                        enabled = !isLocked,
                        label = { Text("Axis ${axis.wireValue}", modifier = Modifier.padding(horizontal = 12.dp)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Deceleration Sign Polarity
            Text("Sensor Deceleration Sign Polarity", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = config.sensorSign == -1,
                    onClick = {
                        if (!isLocked) {
                            onConfigSave(config.copy(sensorSign = -1))
                        }
                    },
                    enabled = !isLocked,
                    label = { Text("Negative Decel (-)", modifier = Modifier.padding(horizontal = 6.dp)) }
                )
                FilterChip(
                    selected = config.sensorSign == 1,
                    onClick = {
                        if (!isLocked) {
                            onConfigSave(config.copy(sensorSign = 1))
                        }
                    },
                    enabled = !isLocked,
                    label = { Text("Positive Decel (+)", modifier = Modifier.padding(horizontal = 6.dp)) }
                )
            }
        }
    }
}
