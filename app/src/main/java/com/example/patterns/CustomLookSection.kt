package com.example.patterns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.config.WledLook
import com.example.statemachine.RideState

@Composable
fun CustomLookSection(
    state: RideState,
    look: WledLook,
    effects: List<String>?,
    palettes: List<String>?,
    isLocked: Boolean,
    onLookChanged: (WledLook) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Section: EFFECTS SELECTION DROPDOWN
        var showEffectDropdown by remember { mutableStateOf(false) }
        val currentEffectId = look.effectId

        Text(
            "WLED Speed Effect",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )

        if (!effects.isNullOrEmpty()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                val currentEffectName = effects.getOrNull(currentEffectId) ?: "Effect $currentEffectId"
                OutlinedButton(
                    onClick = { if (!isLocked) showEffectDropdown = true },
                    enabled = !isLocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("effect_dropdown_trigger_${state.name}"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$currentEffectId: $currentEffectName", color = MaterialTheme.colorScheme.onSurface)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = showEffectDropdown,
                    onDismissRequest = { showEffectDropdown = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    effects.forEachIndexed { index, effectName ->
                        DropdownMenuItem(
                            text = { Text("$index: $effectName") },
                            onClick = {
                                onLookChanged(look.copy(effectId = index))
                                showEffectDropdown = false
                            }
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = currentEffectId.toString(),
                onValueChange = { input ->
                    val value = input.toIntOrNull()?.coerceIn(0, 255) ?: 0
                    onLookChanged(look.copy(effectId = value))
                },
                enabled = !isLocked,
                label = { Text("WLED Effect ID") },
                supportingText = { Text("Unvalidated against the controller") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }

        // Section: PALETTE SELECTION DROPDOWN
        var showPaletteDropdown by remember { mutableStateOf(false) }
        val currentPaletteId = look.paletteId
        val hasPalettePreference = currentPaletteId != null

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Set custom palette?", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = hasPalettePreference,
                onCheckedChange = { checked ->
                    onLookChanged(look.copy(paletteId = if (checked) 0 else null))
                },
                enabled = !isLocked
            )
        }

        if (hasPalettePreference) {
            val resolvedPaletteId = currentPaletteId ?: 0
            if (!palettes.isNullOrEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    val paletteName = palettes.getOrNull(resolvedPaletteId) ?: "Palette $resolvedPaletteId"
                    OutlinedButton(
                        onClick = { if (!isLocked) showPaletteDropdown = true },
                        enabled = !isLocked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("palette_dropdown_trigger_${state.name}"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$resolvedPaletteId: $paletteName", color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }

                    DropdownMenu(
                        expanded = showPaletteDropdown,
                        onDismissRequest = { showPaletteDropdown = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        palettes.forEachIndexed { index, paletteName ->
                            DropdownMenuItem(
                                text = { Text("$index: $paletteName") },
                                onClick = {
                                    onLookChanged(look.copy(paletteId = index))
                                    showPaletteDropdown = false
                                }
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = resolvedPaletteId.toString(),
                    onValueChange = { input ->
                        val value = input.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        onLookChanged(look.copy(paletteId = value))
                    },
                    enabled = !isLocked,
                    label = { Text("WLED Palette ID") },
                    supportingText = { Text("Unvalidated against the controller") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        }

        // Section: COLORS (Primary & Secondary)
        Text("Primary Color RGB Mapping", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)

        val primaryHex = look.colors.getOrNull(0) ?: "#FF7800"
        val (pr, pg, pb) = remember(primaryHex) { hexToRgb(primaryHex) }

        ColorPickerSliders(
            r = pr,
            g = pg,
            b = pb,
            onColorChanged = { r, g, b ->
                val newHex = rgbToHex(r, g, b)
                val newList = look.colors.toMutableList()
                if (newList.isEmpty()) {
                    newList.add(newHex)
                } else {
                    newList[0] = newHex
                }
                onLookChanged(look.copy(colors = newList))
            },
            isLocked = isLocked,
            stateName = state.name,
            typeName = "primary"
        )

        // Secondary Segment Color Option
        val hasSecondary = look.colors.size > 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Enable Secondary Color?", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = hasSecondary,
                onCheckedChange = { checked ->
                    val newList = look.colors.toMutableList()
                    if (checked) {
                        if (newList.size < 2) {
                            newList.add("#000000")
                        }
                    } else {
                        if (newList.size >= 2) {
                            newList.removeAt(1)
                        }
                    }
                    onLookChanged(look.copy(colors = newList))
                },
                enabled = !isLocked
            )
        }

        if (hasSecondary) {
            val secondaryHex = look.colors.getOrNull(1) ?: "#000000"
            val (sr, sg, sb) = remember(secondaryHex) { hexToRgb(secondaryHex) }

            Text("Secondary Color RGB Mapping", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            ColorPickerSliders(
                r = sr,
                g = sg,
                b = sb,
                onColorChanged = { r, g, b ->
                    val newHex = rgbToHex(r, g, b)
                    val newList = look.colors.toMutableList()
                    while (newList.size < 2) {
                        newList.add("#000000")
                    }
                    newList[1] = newHex
                    onLookChanged(look.copy(colors = newList))
                },
                isLocked = isLocked,
                stateName = state.name,
                typeName = "secondary"
            )
        }

        // Section: OPTIONAL SPEED
        val hasSpeedOverride = look.speed != null
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Set Custom Speed?", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = hasSpeedOverride,
                onCheckedChange = { checked ->
                    onLookChanged(look.copy(speed = if (checked) 128 else null))
                },
                enabled = !isLocked
            )
        }

        if (hasSpeedOverride) {
            val currentSpeed = look.speed ?: 128
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Speed (0-255)", style = MaterialTheme.typography.bodySmall)
                    Text(currentSpeed.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Slider(
                        value = currentSpeed.toFloat(),
                        onValueChange = {
                            onLookChanged(look.copy(speed = it.toInt().coerceIn(0, 255)))
                        },
                        enabled = !isLocked,
                        valueRange = 0f..255f,
                        modifier = Modifier.weight(1f).testTag("speed_slider_${state.name}")
                    )
                    OutlinedTextField(
                        value = currentSpeed.toString(),
                        onValueChange = { input ->
                            val intVal = input.toIntOrNull()?.coerceIn(0, 255) ?: 128
                            onLookChanged(look.copy(speed = intVal))
                        },
                        enabled = !isLocked,
                        modifier = Modifier.width(70.dp).testTag("speed_input_${state.name}"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        }

        // Section: OPTIONAL INTENSITY
        val hasIntensityOverride = look.intensity != null
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Set Custom Intensity?", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = hasIntensityOverride,
                onCheckedChange = { checked ->
                    onLookChanged(look.copy(intensity = if (checked) 128 else null))
                },
                enabled = !isLocked
            )
        }

        if (hasIntensityOverride) {
            val currentIntensity = look.intensity ?: 128
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Intensity (0-255)", style = MaterialTheme.typography.bodySmall)
                    Text(currentIntensity.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Slider(
                        value = currentIntensity.toFloat(),
                        onValueChange = {
                            onLookChanged(look.copy(intensity = it.toInt().coerceIn(0, 255)))
                        },
                        enabled = !isLocked,
                        valueRange = 0f..255f,
                        modifier = Modifier.weight(1f).testTag("intensity_slider_${state.name}")
                    )
                    OutlinedTextField(
                        value = currentIntensity.toString(),
                        onValueChange = { input ->
                            val intVal = input.toIntOrNull()?.coerceIn(0, 255) ?: 128
                            onLookChanged(look.copy(intensity = intVal))
                        },
                        enabled = !isLocked,
                        modifier = Modifier.width(70.dp).testTag("intensity_input_${state.name}"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        }

        // Section: TRANSITION DURATIONS
        val currentTransitionMs = look.transitionMs
        Column {
            Text(
                text = if (state == RideState.BRAKING || state == RideState.BRAKING_HOLD)
                    "Crossfade Transition Override (Braking default is 0)"
                else "Look Crossfade Transition",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Slider(
                    value = currentTransitionMs.toFloat(),
                    onValueChange = {
                        onLookChanged(look.copy(transitionMs = it.toInt().coerceAtLeast(0)))
                    },
                    enabled = !isLocked,
                    valueRange = 0f..3000f,
                    modifier = Modifier.weight(1f).testTag("transition_slider_${state.name}"),
                    colors = if (state == RideState.BRAKING || state == RideState.BRAKING_HOLD)
                        SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.error,
                            activeTrackColor = MaterialTheme.colorScheme.error
                        )
                    else SliderDefaults.colors()
                )
                OutlinedTextField(
                    value = currentTransitionMs.toString(),
                    onValueChange = { input ->
                        val valMs = input.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        onLookChanged(look.copy(transitionMs = valMs))
                    },
                    enabled = !isLocked,
                    modifier = Modifier.width(80.dp).testTag("transition_input_${state.name}"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    suffix = { Text("ms") }
                )
            }
        }
    }
}
