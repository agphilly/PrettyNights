package com.example.patterns

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.PrettyNightsApp
import com.example.config.WledLook
import com.example.statemachine.RideState
import com.example.wled.WledPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StateLookCard(
    stateLabel: String,
    state: RideState,
    look: WledLook,
    presets: List<WledPreset>?,
    effects: List<String>?,
    palettes: List<String>?,
    isFetchingPresets: Boolean,
    fetchFailed: Boolean,
    onRetryFetch: () -> Unit,
    expanded: Boolean,
    onHeaderClick: () -> Unit,
    onLookChanged: (WledLook) -> Unit,
    isLocked: Boolean,
    wledHost: String
) {
    val coroutineScope = rememberCoroutineScope()
    var previewStatus by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHeaderClick() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val primaryColorHex = look.colors.getOrNull(0) ?: "#FF7800"
                    val parsedColor = remember(primaryColorHex) {
                        try {
                            Color(android.graphics.Color.parseColor(primaryColorHex))
                        } catch (e: Exception) {
                            Color.Red
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(parsedColor)
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

                    // Mode toggle Segment Card (Preset Mode vs Custom Mode)
                    val isPresetMode = look.presetId != null

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Preset Tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isPresetMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable(enabled = !isLocked && !fetchFailed) {
                                    onLookChanged(
                                        look.copy(
                                            presetId = 1,
                                            paletteId = null,
                                            speed = null,
                                            intensity = null
                                        )
                                    )
                                }
                                .padding(vertical = 8.dp)
                                .testTag("tab_preset_${state.name}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "WLED Preset",
                                color = if (isPresetMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Custom Tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (!isPresetMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable(enabled = !isLocked) {
                                    onLookChanged(
                                        look.copy(
                                            presetId = null,
                                            effectId = 0,
                                            paletteId = null,
                                            colors = listOf(
                                                when (state) {
                                                    RideState.IDLE -> "#FF7800"
                                                    RideState.CRUISING -> "#00B4FF"
                                                    RideState.BRAKING -> "#FF0000"
                                                    else -> "#FF0000"
                                                }
                                            ),
                                            speed = null,
                                            intensity = null,
                                            transitionMs = if (state == RideState.BRAKING) 0 else 300
                                        )
                                    )
                                }
                                .padding(vertical = 8.dp)
                                .testTag("tab_custom_${state.name}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Custom Look",
                                color = if (!isPresetMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // --- PRESET OR CUSTOM SECTION ---
                    if (isPresetMode) {
                        PresetLookSection(
                            state = state,
                            look = look,
                            presets = presets,
                            isFetchingPresets = isFetchingPresets,
                            fetchFailed = fetchFailed,
                            isLocked = isLocked,
                            onRetryFetch = onRetryFetch,
                            onLookChanged = onLookChanged
                        )
                    } else {
                        CustomLookSection(
                            state = state,
                            look = look,
                            effects = effects,
                            palettes = palettes,
                            isLocked = isLocked,
                            onLookChanged = onLookChanged
                        )
                    }

                    // --- PREVIEW BUTTON ---
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                previewStatus = "Sending preview..."
                                coroutineScope.launch(Dispatchers.IO) {
                                    val ok = PrettyNightsApp.instance.wledController.sendState(
                                        host = wledHost,
                                        look = look,
                                        brightness = 1.0f
                                    )
                                    withContext(Dispatchers.Main) {
                                        previewStatus = if (ok) "Sent successfully!" else "WLED unreachable!"
                                    }
                                }
                            },
                            enabled = !isLocked,
                            modifier = Modifier.testTag("btn_preview_${state.name}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Preview Look")
                        }

                        previewStatus?.let { status ->
                            val color = when (status) {
                                "Sent successfully!" -> Color(0xFF4CAF50)
                                "WLED unreachable!" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Text(
                                text = status,
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("preview_status_${state.name}")
                            )
                            LaunchedEffect(status) {
                                if (status != "Sending preview...") {
                                    delay(3500)
                                    previewStatus = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
