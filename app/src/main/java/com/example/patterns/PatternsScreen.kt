package com.example.patterns

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.config.WledLook
import com.example.statemachine.RideState
import com.example.wled.WledPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternsScreen(
    config: AppConfig,
    onConfigSave: (AppConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Safety lock observation (Continuous observation - Section 10)
    val isLocked by PrettyNightsApp.instance.lockController.isLocked.collectAsStateWithLifecycle()

    // Local configuration editing state (Discards changes unless saved - Section 11 & 13)
    var localConfig by remember(config) { mutableStateOf(config) }
    var saveStatusMsg by remember { mutableStateOf<String?>(null) }

    // WLED Fetched Metadata states
    var presets by remember { mutableStateOf<List<WledPreset>?>(null) }
    var effects by remember { mutableStateOf<List<String>?>(null) }
    var palettes by remember { mutableStateOf<List<String>?>(null) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchFailed by remember { mutableStateOf(false) }

    // Function to fetch presets, effects, and palettes from WLED
    fun fetchWledData() {
        val host = localConfig.wledHost
        if (host.isBlank()) return
        isFetching = true
        fetchFailed = false
        coroutineScope.launch(Dispatchers.IO) {
            val controller = PrettyNightsApp.instance.wledController
            val fetchedPresets = controller.fetchPresets(host)
            val fetchedEffects = controller.fetchEffects(host)
            val fetchedPalettes = controller.fetchPalettes(host)

            withContext(Dispatchers.Main) {
                isFetching = false
                if (fetchedPresets == null) {
                    fetchFailed = true
                } else {
                    presets = fetchedPresets
                    effects = fetchedEffects
                    palettes = fetchedPalettes
                    fetchFailed = false
                }
            }
        }
    }

    // Trigger initial fetch on screen load / hostname change
    LaunchedEffect(localConfig.wledHost) {
        fetchWledData()
    }

    var activeEditingState by remember { mutableStateOf<RideState?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Glow Pattern Assignments",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Map WLED lighting looks, custom palettes, speeds, and transitions for each ride state.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // --- SAFETY LOCK OUT WARNING BANNER (Section 10) ---
            if (isLocked) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ride_active_lock_banner"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                "Locked while riding",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Editing controls are disabled for rider safety.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // --- RETRY TRIGGER FOR METADATA COOPERATION (Section 5) ---
            if (fetchFailed) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Couldn't load presets — check the WLED connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = { fetchWledData() },
                            modifier = Modifier.testTag("btn_retry_fetch_presets")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Preset Load")
                        }
                    }
                }
            }

            // --- 3 CONFIGURABLE STATE SLOTS ONLY (IDLE, CRUISING, BRAKING) ---
            StateLookCard(
                stateLabel = "IDLE (Stationary)",
                state = RideState.IDLE,
                look = localConfig.idleLook,
                presets = presets,
                effects = effects,
                palettes = palettes,
                isFetchingPresets = isFetching,
                fetchFailed = fetchFailed,
                onRetryFetch = { fetchWledData() },
                expanded = activeEditingState == RideState.IDLE,
                onHeaderClick = {
                    activeEditingState = if (activeEditingState == RideState.IDLE) null else RideState.IDLE
                },
                onLookChanged = { newLook ->
                    localConfig = localConfig.copy(idleLook = newLook)
                },
                isLocked = isLocked,
                wledHost = localConfig.wledHost
            )

            StateLookCard(
                stateLabel = "CRUISING (In Motion)",
                state = RideState.CRUISING,
                look = localConfig.cruisingLook,
                presets = presets,
                effects = effects,
                palettes = palettes,
                isFetchingPresets = isFetching,
                fetchFailed = fetchFailed,
                onRetryFetch = { fetchWledData() },
                expanded = activeEditingState == RideState.CRUISING,
                onHeaderClick = {
                    activeEditingState = if (activeEditingState == RideState.CRUISING) null else RideState.CRUISING
                },
                onLookChanged = { newLook ->
                    localConfig = localConfig.copy(cruisingLook = newLook)
                },
                isLocked = isLocked,
                wledHost = localConfig.wledHost
            )

            StateLookCard(
                stateLabel = "BRAKING (Decelerating)",
                state = RideState.BRAKING,
                look = localConfig.brakingLook,
                presets = presets,
                effects = effects,
                palettes = palettes,
                isFetchingPresets = isFetching,
                fetchFailed = fetchFailed,
                onRetryFetch = { fetchWledData() },
                expanded = activeEditingState == RideState.BRAKING,
                onHeaderClick = {
                    activeEditingState = if (activeEditingState == RideState.BRAKING) null else RideState.BRAKING
                },
                onLookChanged = { newLook ->
                    localConfig = localConfig.copy(brakingLook = newLook)
                },
                isLocked = isLocked,
                wledHost = localConfig.wledHost
            )

            // Save Settings status and action (Saves localConfig - Section 8 & 13)
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    onConfigSave(localConfig)
                    saveStatusMsg = "Configuration saved successfully!"
                },
                enabled = !isLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_save_look_config"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Configuration", fontWeight = FontWeight.Bold)
            }

            saveStatusMsg?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("save_status_text")
                )
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3000)
                    saveStatusMsg = null
                }
            }

            Spacer(modifier = Modifier.height(80.dp)) // padding for bottom navigation bars
        }
    }
}

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
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

                    // Mode toggle Segment Card (Preset Mode vs Custom Mode - Section 4)
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
                                    // Custom mode clears presetId (Section 4)
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
                            // Preset advisory note warning (Section 6)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                                ),
                                border = CardDefaults.outlinedCardBorder().copy(
                                    width = 1.dp,
                                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
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

                            // Preset selection drop downs
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
                                // Preset Selector Dropdown Box
                                val currentPresetId = look.presetId ?: 1
                                val isPresetMissing = presets != null && presets.none { it.id == currentPresetId }

                                var showDropdown by remember { mutableStateOf(false) }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    // Dropdown Trigger Field
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

                                // Preset missing alert prompt (Section 11, edge case)
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
                            // Section: EFFECTS SELECTION DROPDOWN (Section 9)
                            var showEffectDropdown by remember { mutableStateOf(false) }
                            val currentEffectId = look.effectId

                            Text(
                                "WLED Speed Effect",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )

                            if (!effects.isNullOrEmpty()) {
                                // Effects list is successfully fetched - Dropdown implementation
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
                                // Effects list unreachable/not fetched - Direct free form validated text entry
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


                            // Section: PALETTE SELECTION DROPDOWN (Section 9)
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
                                    // Fallback to dropdown selection of palettes
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
                                    // Direct manual entry
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


                            // Section: COLORS (Primary & Secondary - Section 3, 4 & 9)
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

                            // Secondary Segment Color Option (Section 3 & 4)
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
                                                newList.add("#000000") // Start with solid black
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


                            // Section: OPTIONAL SPEED (Section 3, 4 & 9)
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


                            // Section: OPTIONAL INTENSITY (Section 3, 4 & 9)
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


                            // Section: TRANSITION DURATIONS (Section 3, 6, 9 & 13)
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
                                        valueRange = 0f..3000f, // 0 to 3 seconds
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

                    // --- STATE ACTION BOX (Preview button - Section 7, 11 & 13) ---
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
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
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
                                    kotlinx.coroutines.delay(3500)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSliders(
    r: Int,
    g: Int,
    b: Int,
    onColorChanged: (Int, Int, Int) -> Unit,
    isLocked: Boolean,
    stateName: String,
    typeName: String
) {
    val quickColors = listOf(
        Pair("Red", "#FF0000"),
        Pair("Amber", "#FF7800"),
        Pair("Yellow", "#FFFF00"),
        Pair("Green", "#00FF00"),
        Pair("Cyan", "#00B4FF"),
        Pair("Blue", "#0000FF"),
        Pair("Purple", "#800080"),
        Pair("White", "#FFFFFF")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Quick Colors chips array
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickColors.forEach { (name, hex) ->
                val (qr, qg, qb) = hexToRgb(hex)
                val isSelected = r == qr && g == qg && b == qb
                val chipColor = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }

                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .clickable(enabled = !isLocked) {
                            onColorChanged(qr, qg, qb)
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(chipColor)
                                .border(0.5.dp, Color.White, CircleShape)
                        )
                        Text(
                            text = name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // RED slider
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Red", fontSize = 11.sp, color = Color.Gray)
                Text(r.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = r.toFloat(),
                    onValueChange = { onColorChanged(it.toInt().coerceIn(0, 255), g, b) },
                    enabled = !isLocked,
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).testTag("slider_${stateName}_${typeName}_r")
                )
                OutlinedTextField(
                    value = r.toString(),
                    onValueChange = { input ->
                        val parsed = input.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        onColorChanged(parsed, g, b)
                    },
                    enabled = !isLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(64.dp).testTag("input_${stateName}_${typeName}_r"),
                    singleLine = true
                )
            }
        }

        // GREEN slider
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Green", fontSize = 11.sp, color = Color.Gray)
                Text(g.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = g.toFloat(),
                    onValueChange = { onColorChanged(r, it.toInt().coerceIn(0, 255), b) },
                    enabled = !isLocked,
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).testTag("slider_${stateName}_${typeName}_g")
                )
                OutlinedTextField(
                    value = g.toString(),
                    onValueChange = { input ->
                        val parsed = input.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        onColorChanged(r, parsed, b)
                    },
                    enabled = !isLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(64.dp).testTag("input_${stateName}_${typeName}_g"),
                    singleLine = true
                )
            }
        }

        // BLUE slider
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Blue", fontSize = 11.sp, color = Color.Gray)
                Text(b.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = b.toFloat(),
                    onValueChange = { onColorChanged(r, g, it.toInt().coerceIn(0, 255)) },
                    enabled = !isLocked,
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).testTag("slider_${stateName}_${typeName}_b")
                )
                OutlinedTextField(
                    value = b.toString(),
                    onValueChange = { input ->
                        val parsed = input.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        onColorChanged(r, g, parsed)
                    },
                    enabled = !isLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(64.dp).testTag("input_${stateName}_${typeName}_b"),
                    singleLine = true
                )
            }
        }
    }
}

private fun hexToRgb(hex: String): Triple<Int, Int, Int> {
    val clean = hex.trim().removePrefix("#")
    return try {
        if (clean.length == 6) {
            val r = clean.substring(0, 2).toInt(16)
            val g = clean.substring(2, 4).toInt(16)
            val b = clean.substring(4, 6).toInt(16)
            Triple(r, g, b)
        } else if (clean.length == 3) {
            val r = (clean.substring(0, 1) + clean.substring(0, 1)).toInt(16)
            val g = (clean.substring(1, 2) + clean.substring(1, 2)).toInt(16)
            val b = (clean.substring(2, 3) + clean.substring(2, 3)).toInt(16)
            Triple(r, g, b)
        } else {
            Triple(255, 120, 0)
        }
    } catch (e: Exception) {
        Triple(255, 120, 0)
    }
}

private fun rgbToHex(r: Int, g: Int, b: Int): String {
    return String.format("#%02X%02X%02X", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}
