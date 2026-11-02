package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.config.WledHardwareConfig
import com.example.wled.WledHardwareLiveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun WledHardwareConfigCard(
    config: AppConfig,
    isLocked: Boolean,
    onConfigSave: (AppConfig) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val hw = config.hardwareConfig

    var ch1CountText by remember(hw.ch1LedCount) { mutableStateOf(hw.ch1LedCount.toString()) }
    var ch2CountText by remember(hw.ch2LedCount) { mutableStateOf(hw.ch2LedCount.toString()) }
    var ch3CountText by remember(hw.ch3LedCount) { mutableStateOf(hw.ch3LedCount.toString()) }
    var ch1NameText by remember(hw.ch1ZoneName) { mutableStateOf(hw.ch1ZoneName) }
    var ch2NameText by remember(hw.ch2ZoneName) { mutableStateOf(hw.ch2ZoneName) }

    var liveInfo by remember { mutableStateOf<WledHardwareLiveInfo?>(null) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusMessage by remember { mutableStateOf<String?>(null) }
    var syncIsError by remember { mutableStateOf(false) }

    fun saveHw(updated: WledHardwareConfig) {
        onConfigSave(config.copy(hardwareConfig = updated))
    }

    val seg0Start = 0
    val seg0Stop = hw.ch1LedCount.coerceAtLeast(1)
    val seg1Start = seg0Stop
    val seg1Stop = if (hw.ch2Enabled) seg1Start + hw.ch2LedCount.coerceAtLeast(1) else seg1Start
    val seg2Start = seg1Stop
    val seg2Stop = if (hw.io13Role == "STRIP_CH3") seg2Start + hw.ch3LedCount.coerceAtLeast(1) else seg2Start

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wled_hardware_config_card"),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "NV-LC616WL Hardware, I/O & Segments",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Configure GPIO 16, GPIO 2, GPIO 13, current limiter & WLED segments",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        isSyncing = true
                        syncStatusMessage = "Reading /json/info..."
                        syncIsError = false
                        coroutineScope.launch(Dispatchers.IO) {
                            val info = PrettyNightsApp.instance.wledController.fetchHardwareInfo(config.wledHost)
                            withContext(Dispatchers.Main) {
                                isSyncing = false
                                liveInfo = info
                                if (info != null) {
                                    syncIsError = false
                                    syncStatusMessage = "Connected: WLED v${info.version} (${info.ledCount} LEDs, ${info.maxPowerMa} mA cap)"
                                } else {
                                    syncIsError = true
                                    syncStatusMessage = "Could not read /json/info from ${config.wledHost}"
                                }
                            }
                        }
                    },
                    enabled = !isLocked && !isSyncing,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("btn_read_controller_info")
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "Read Controller Info", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Read Info", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            liveInfo?.let { info ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("hardware_live_info_banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FW: ${info.version} (${info.arch})",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "LEDs: ${info.ledCount} • Limit: ${info.maxPowerMa}mA • Draw: ${info.currentPowerMa}mA",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

            // SECTION 1: POWER SUPPLY CURRENT LIMITER (20A Fuse / USB-C 3A Max)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color(0xFFFACC15),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "1. Power Supply Current Limiter",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${hw.maxCurrentMa} mA (${String.format("%.1f", hw.maxCurrentMa / 1000f)} A)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (hw.maxCurrentMa <= 3000) Color(0xFF4CAF50) else Color(0xFFFACC15)
                    )
                }

                // One-tap Power Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Triple("USB-C 5V (2.5A)", 2500, "btn_pwr_preset_2500"),
                        Triple("Battery Pack (8A)", 8000, "btn_pwr_preset_8000"),
                        Triple("Max Terminal (15A)", 15000, "btn_pwr_preset_15000")
                    ).forEach { (label, maVal, tag) ->
                        val selected = hw.maxCurrentMa == maVal
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .clickable(enabled = !isLocked) {
                                    saveHw(hw.copy(maxCurrentMa = maVal))
                                }
                                .padding(vertical = 8.dp)
                                .testTag(tag),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Slider(
                    value = hw.maxCurrentMa.toFloat(),
                    onValueChange = { raw ->
                        val stepped = ((raw / 250f).roundToInt() * 250).coerceIn(500, 15000)
                        saveHw(hw.copy(maxCurrentMa = stepped))
                    },
                    valueRange = 500f..15000f,
                    enabled = !isLocked,
                    modifier = Modifier.testTag("slider_max_current_ma")
                )

                Text(
                    text = if (hw.maxCurrentMa > 3000) {
                        "Note: >3,000 mA requires wiring DC power to the left V+/V- lever terminals (NV-LC616WL USB-C port is rated 3A max)."
                    } else {
                        "Safe for both 5V/3A USB-C power bank input and main V+/V- lever terminals."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hw.maxCurrentMa > 3000) Color(0xFFFACC15) else Color.Gray
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

            // SECTION 2: CHANNEL 1 (IO16 / GPIO 16)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TerminalBadge(color = Color(0xFF00B4FF), text = "IO16")
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Channel 1 Output (GPIO 16 • Segment 0)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Top V+ / IO16 / GND lever terminals (10A max)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                    Text(
                        text = "LEDs $seg0Start..${seg0Stop - 1}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ch1NameText,
                        onValueChange = {
                            ch1NameText = it
                            saveHw(hw.copy(ch1ZoneName = it))
                        },
                        enabled = !isLocked,
                        label = { Text("Seg 0 Zone Label") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1.4f)
                            .testTag("input_ch1_zone_name")
                    )

                    OutlinedTextField(
                        value = ch1CountText,
                        onValueChange = { text ->
                            ch1CountText = text.filter { it.isDigit() }
                            val parsed = ch1CountText.toIntOrNull()?.coerceIn(1, 1000)
                            if (parsed != null) {
                                saveHw(hw.copy(ch1LedCount = parsed))
                            }
                        },
                        enabled = !isLocked,
                        label = { Text("LED Count") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_ch1_led_count")
                    )
                }

                StripProtocolAndReverseRow(
                    channelTag = "ch1",
                    stripType = hw.ch1StripType,
                    colorOrder = hw.ch1ColorOrder,
                    reversed = hw.ch1Reversed,
                    enabled = !isLocked,
                    onStripTypeChange = { saveHw(hw.copy(ch1StripType = it)) },
                    onColorOrderChange = { saveHw(hw.copy(ch1ColorOrder = it)) },
                    onReversedChange = { saveHw(hw.copy(ch1Reversed = it)) }
                )
            }

            // SECTION 3: CHANNEL 2 (IO2 / GPIO 2)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        TerminalBadge(color = Color(0xFF00B4FF), text = "IO2")
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Channel 2 Output (GPIO 2 • Segment 1)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Middle V+ / IO2 / GND lever terminals (10A max)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                    Switch(
                        checked = hw.ch2Enabled,
                        onCheckedChange = { saveHw(hw.copy(ch2Enabled = it)) },
                        enabled = !isLocked,
                        modifier = Modifier.testTag("switch_ch2_enabled")
                    )
                }

                if (hw.ch2Enabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = ch2NameText,
                            onValueChange = {
                                ch2NameText = it
                                saveHw(hw.copy(ch2ZoneName = it))
                            },
                            enabled = !isLocked,
                            label = { Text("Seg 1 Zone Label") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1.4f)
                                .testTag("input_ch2_zone_name")
                        )

                        OutlinedTextField(
                            value = ch2CountText,
                            onValueChange = { text ->
                                ch2CountText = text.filter { it.isDigit() }
                                val parsed = ch2CountText.toIntOrNull()?.coerceIn(1, 1000)
                                if (parsed != null) {
                                    saveHw(hw.copy(ch2LedCount = parsed))
                                }
                            },
                            enabled = !isLocked,
                            label = { Text("LED Count") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_ch2_led_count")
                        )
                    }

                    StripProtocolAndReverseRow(
                        channelTag = "ch2",
                        stripType = hw.ch2StripType,
                        colorOrder = hw.ch2ColorOrder,
                        reversed = hw.ch2Reversed,
                        enabled = !isLocked,
                        onStripTypeChange = { saveHw(hw.copy(ch2StripType = it)) },
                        onColorOrderChange = { saveHw(hw.copy(ch2ColorOrder = it)) },
                        onReversedChange = { saveHw(hw.copy(ch2Reversed = it)) }
                    )
                }
            }

            // SECTION 4: AUXILIARY TERMINAL (IO13 / GPIO 13)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TerminalBadge(color = Color(0xFF94A3B8), text = "IO13")
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Auxiliary I/O Terminal (GPIO 13)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Bottom gray IO13 + black GND lever terminals",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "PUSHBUTTON" to "Pushbutton",
                        "REED_SWITCH" to "Reed Switch",
                        "STRIP_CH3" to "3rd LED Strip",
                        "DISABLED" to "Disabled"
                    ).forEach { (roleKey, label) ->
                        val selected = hw.io13Role == roleKey
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable(enabled = !isLocked) {
                                    saveHw(hw.copy(io13Role = roleKey))
                                }
                                .padding(vertical = 8.dp)
                                .testTag("btn_io13_role_$roleKey"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (hw.io13Role == "STRIP_CH3") {
                    OutlinedTextField(
                        value = ch3CountText,
                        onValueChange = { text ->
                            ch3CountText = text.filter { it.isDigit() }
                            val parsed = ch3CountText.toIntOrNull()?.coerceIn(1, 1000)
                            if (parsed != null) {
                                saveHw(hw.copy(ch3LedCount = parsed))
                            }
                        },
                        enabled = !isLocked,
                        label = { Text("Channel 3 (IO13) LED Count") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_ch3_led_count")
                    )
                }
            }

            // SECTION 5: LIVE SEGMENT ADDRESS MAP PREVIEW
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Computed WLED Multi-Segment Address Map:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "• Seg 0 (${hw.ch1ZoneName}): index $seg0Start .. $seg0Stop (${hw.ch1LedCount} LEDs on GPIO 16)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                if (hw.ch2Enabled) {
                    Text(
                        text = "• Seg 1 (${hw.ch2ZoneName}): index $seg1Start .. $seg1Stop (${hw.ch2LedCount} LEDs on GPIO 2)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
                if (hw.io13Role == "STRIP_CH3") {
                    Text(
                        text = "• Seg 2 (Aux IO13): index $seg2Start .. $seg2Stop (${hw.ch3LedCount} LEDs on GPIO 13)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }

            // PUSH TO CONTROLLER CTA
            Button(
                onClick = {
                    isSyncing = true
                    syncStatusMessage = "Pushing /json/cfg & /json/state to ${config.wledHost}..."
                    syncIsError = false
                    coroutineScope.launch(Dispatchers.IO) {
                        val controller = PrettyNightsApp.instance.wledController
                        val ok = controller.pushHardwareAndSegments(config.wledHost, hw)
                        val refreshedInfo = if (ok) controller.fetchHardwareInfo(config.wledHost) else null
                        withContext(Dispatchers.Main) {
                            isSyncing = false
                            if (refreshedInfo != null) {
                                liveInfo = refreshedInfo
                            }
                            if (ok) {
                                syncIsError = false
                                syncStatusMessage = "Provisioned GPIO 16/2/13, ${hw.maxCurrentMa}mA limiter & segments on WLED!"
                            } else {
                                syncIsError = true
                                syncStatusMessage = "WLED unreachable at ${config.wledHost} — settings saved locally."
                            }
                        }
                    }
                },
                enabled = !isLocked && !isSyncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_push_hardware_segments"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSyncing) "Provisioning WLED Controller..." else "Push Hardware & Segments to WLED",
                    fontWeight = FontWeight.Bold
                )
            }

            syncStatusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (syncIsError) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
                    modifier = Modifier.testTag("hardware_sync_status_text")
                )
            }
        }
    }
}

@Composable
private fun TerminalBadge(color: Color, text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = color
        )
    }
}

@Composable
private fun StripProtocolAndReverseRow(
    channelTag: String,
    stripType: Int,
    colorOrder: Int,
    reversed: Boolean,
    enabled: Boolean,
    onStripTypeChange: (Int) -> Unit,
    onColorOrderChange: (Int) -> Unit,
    onReversedChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Strip Type Chips (WS281x RGB = 22, SK6812 RGBW = 30)
            listOf(
                22 to "WS2812B (RGB)",
                30 to "SK6812 (RGBW)"
            ).forEach { (typeCode, label) ->
                val selected = stripType == typeCode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable(enabled = enabled) { onStripTypeChange(typeCode) }
                        .padding(vertical = 6.dp)
                        .testTag("btn_${channelTag}_type_$typeCode"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Color Order Chips (0 = GRB, 1 = RGB)
            listOf(
                0 to "GRB",
                1 to "RGB"
            ).forEach { (orderCode, label) ->
                val selected = colorOrder == orderCode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable(enabled = enabled) { onColorOrderChange(orderCode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("btn_${channelTag}_order_$orderCode"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Reverse Strip Direction",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
            Switch(
                checked = reversed,
                onCheckedChange = onReversedChange,
                enabled = enabled,
                modifier = Modifier.testTag("switch_${channelTag}_reversed")
            )
        }
    }
}
