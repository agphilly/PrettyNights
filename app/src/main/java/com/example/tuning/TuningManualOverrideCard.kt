package com.example.tuning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.statemachine.RideState
import kotlin.math.roundToInt

@Composable
fun TuningManualOverrideCard(
    isRideRunning: Boolean,
    overrideState: RideState?,
    overrideBrightness: Float,
    wledUnreachableError: Boolean,
    onSelectOverride: (RideState?) -> Unit,
    onBrightnessChange: (Float) -> Unit
) {
    TuningSectionHeader(title = "Manual Override")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("manual_override_panel"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isRideRunning) {
                Text(
                    text = "Available when no ride is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("override_disabled_note")
                )
            }

            // Status Line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                if (overrideState != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        ),
                        border = CardDefaults.outlinedCardBorder().copy(
                            width = 1.dp,
                            brush = SolidColor(MaterialTheme.colorScheme.error)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Override active: ${overrideState.name} — lights are not reflecting a real ride.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.testTag("override_status_text")
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No override active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("override_status_text")
                    )
                }
            }

            // Connection Error notification
            if (wledUnreachableError && overrideState != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "WLED controller unreachable. Try again.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Action Buttons: IDLE, CRUISING, BRAKING
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val buttonStates = listOf(
                    Triple(RideState.IDLE, "IDLE", "override_idle_button"),
                    Triple(RideState.CRUISING, "CRUISING", "override_cruising_button"),
                    Triple(RideState.BRAKING, "BRAKING", "override_braking_button")
                )

                buttonStates.forEach { (stateType, label, tag) ->
                    val isSelected = overrideState == stateType
                    Button(
                        onClick = {
                            if (overrideState == stateType) {
                                onSelectOverride(null)
                            } else {
                                onSelectOverride(stateType)
                            }
                        },
                        enabled = !isRideRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(tag)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Brightness slider
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Brightness",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format("%.2f", overrideBrightness),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = overrideBrightness,
                    onValueChange = { newVal ->
                        val stepped = (newVal * 100).roundToInt() / 100f
                        onBrightnessChange(stepped)
                    },
                    valueRange = 0.0f..1.0f,
                    enabled = !isRideRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("override_brightness_slider")
                )
            }
        }
    }
}
