package com.example.patterns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.config.WledLook
import com.example.statemachine.RideState
import com.example.wled.WledPreset

@Composable
fun PresetLookSection(
    state: RideState,
    look: WledLook,
    presets: List<WledPreset>?,
    isFetchingPresets: Boolean,
    fetchFailed: Boolean,
    isLocked: Boolean,
    onRetryFetch: () -> Unit,
    onLookChanged: (WledLook) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            ),
            border = CardDefaults.outlinedCardBorder().copy(
                width = 1.dp,
                brush = SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
            )
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Presets Info",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Some presets store their own brightness, which may override the live brightness effect.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Text(
            "Select saved preset from ESP32",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )

        if (isFetchingPresets) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Fetching presets from controller...", fontSize = 12.sp)
            }
        } else if (fetchFailed) {
            Text(
                "Couldn't load presets — check the WLED connection.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
            Button(onClick = onRetryFetch, modifier = Modifier.testTag("btn_inner_retry")) {
                Text("Retry Connection")
            }
        } else {
            val currentPresetId = look.presetId ?: 1
            val isPresetMissing = presets != null && presets.none { it.id == currentPresetId }
            var showDropdown by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxWidth()) {
                val presetNameVal = presets?.find { it.id == currentPresetId }?.name
                    ?: "Preset $currentPresetId"

                OutlinedButton(
                    onClick = { if (!isLocked) showDropdown = true },
                    enabled = !isLocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preset_dropdown_trigger_${state.name}"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPresetMissing) "Preset $currentPresetId (Not found)" else presetNameVal,
                            color = if (isPresetMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = showDropdown,
                    onDismissRequest = { showDropdown = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    if (presets.isNullOrEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No presets found on this controller") },
                            onClick = { showDropdown = false }
                        )
                    } else {
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text("Preset ${preset.id}: ${preset.name}") },
                                onClick = {
                                    onLookChanged(look.copy(presetId = preset.id))
                                    showDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            if (isPresetMissing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Preset $currentPresetId not found on controller. Please re-select.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
