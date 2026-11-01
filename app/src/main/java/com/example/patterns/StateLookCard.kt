package com.example.patterns

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                        try { Color(android.graphics.Color.parseColor(primaryColorHex)) } catch (e: Exception) { Color.Red }
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

                    // --- PRESET ACTIVE LAYOUT ---
                    if (isPresetMode) {
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
                                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
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

                    // --- CUSTOM ACTION MODE LAYOUT ---
                    if (!isPresetMode) {
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
                                    text = if (state == RideState.BRAKING)
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
                                        colors = if (state == RideState.BRAKING)
                                            SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.error, activeTrackColor = MaterialTheme.colorScheme.error)
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
