package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
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
import com.example.config.AppConfig
import com.example.config.ChannelConfig
import com.example.config.SegmentConfig
import com.example.config.SegmentRole
import com.example.config.withSequentialSegmentIds
import com.example.ui.theme.appColors
import com.example.wled.WledHardwareLiveInfo

internal const val MAX_SEGMENTS_PER_CHANNEL = 10

/**
 * Validates a proposed [startLed, endLed) segment range against channel bounds and existing
 * segments on the same channel. Returns null if valid, or a user-facing error message if invalid.
 */
internal fun validateSegmentCandidate(
    existingSegments: List<SegmentConfig>,
    channelLedCount: Int,
    label: String,
    startLed: Int?,
    endLed: Int?
): String? {
    if (existingSegments.size >= MAX_SEGMENTS_PER_CHANNEL) {
        return "Maximum of $MAX_SEGMENTS_PER_CHANNEL segments reached for this channel."
    }
    if (label.isBlank()) {
        return "Segment label is required."
    }
    if (startLed == null || endLed == null) {
        return "Start LED and End LED must be valid integers."
    }
    if (startLed < 0 || endLed > channelLedCount || startLed >= endLed) {
        return "Range must satisfy 0 <= Start LED < End LED <= $channelLedCount."
    }
    val overlapping = existingSegments.firstOrNull { existing ->
        startLed < existing.endLed && endLed > existing.startLed
    }
    if (overlapping != null) {
        return "Overlaps with '${overlapping.label}' (${overlapping.startLed}..${overlapping.endLed})."
    }
    return null
}

@Composable
fun WledHardwareConfigCard(
    config: AppConfig,
    isLocked: Boolean,
    liveInfo: WledHardwareLiveInfo? = null,
    isSyncing: Boolean = false,
    syncStatusMessage: String? = null,
    syncIsError: Boolean = false,
    onReadHardwareInfo: () -> Unit = {},
    onPushHardwareAndSegments: (List<ChannelConfig>) -> Unit = {},
    onConfigSave: (AppConfig) -> Unit
) {
    val channels = config.channels

    var showAddChannelForm by remember { mutableStateOf(false) }
    var newGpioPinText by remember { mutableStateOf("") }
    var newLedCountText by remember { mutableStateOf("") }
    var newStripType by remember { mutableStateOf(22) }
    var newColorOrder by remember { mutableStateOf(0) }
    var newReversed by remember { mutableStateOf(false) }
    var addChannelError by remember { mutableStateOf<String?>(null) }

    fun saveChannels(updated: List<ChannelConfig>) {
        val normalized = updated.withSequentialSegmentIds()
        onConfigSave(config.copy(channels = normalized))
    }

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
                            text = "WLED Channels & Segment Roles",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Configure GPIO strips and assign Ambient or Brake roles per segment",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.appColors.mutedText
                        )
                    }
                }

                OutlinedButton(
                    onClick = onReadHardwareInfo,
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

            if (channels.isEmpty()) {
                Text(
                    text = "No GPIO channels configured yet. Add a channel below to configure strip segments and roles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("empty_channels_message")
                )
            }

            // Render each ChannelConfig identically
            channels.forEachIndexed { channelIndex, channel ->
                ChannelConfigItemCard(
                    channelIndex = channelIndex,
                    channel = channel,
                    isLocked = isLocked,
                    onChannelUpdated = { updatedChannel ->
                        val nextList = channels.toMutableList()
                        nextList[channelIndex] = updatedChannel
                        saveChannels(nextList)
                    },
                    onChannelRemoved = {
                        val nextList = channels.toMutableList()
                        nextList.removeAt(channelIndex)
                        saveChannels(nextList)
                    }
                )
            }

            // Add Channel Button & Inline Form
            if (!showAddChannelForm) {
                OutlinedButton(
                    onClick = {
                        addChannelError = null
                        showAddChannelForm = true
                    },
                    enabled = !isLocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("btn_open_add_channel")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add GPIO Channel", fontWeight = FontWeight.Bold)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(12.dp)
                        .testTag("add_channel_form"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "New GPIO Channel",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newGpioPinText,
                            onValueChange = {
                                newGpioPinText = it.filter { ch -> ch.isDigit() }
                                addChannelError = null
                            },
                            enabled = !isLocked,
                            label = { Text("GPIO Pin (e.g. 16)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_new_channel_gpio")
                        )

                        OutlinedTextField(
                            value = newLedCountText,
                            onValueChange = {
                                newLedCountText = it.filter { ch -> ch.isDigit() }
                                addChannelError = null
                            },
                            enabled = !isLocked,
                            label = { Text("LED Count") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_new_channel_led_count")
                        )
                    }

                    StripProtocolAndReverseRow(
                        channelTag = "new_ch",
                        stripType = newStripType,
                        colorOrder = newColorOrder,
                        reversed = newReversed,
                        enabled = !isLocked,
                        onStripTypeChange = { newStripType = it },
                        onColorOrderChange = { newColorOrder = it },
                        onReversedChange = { newReversed = it }
                    )

                    addChannelError?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("add_channel_error_text")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showAddChannelForm = false
                                addChannelError = null
                            },
                            enabled = !isLocked,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("btn_cancel_add_channel")
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                val pin = newGpioPinText.toIntOrNull()
                                val count = newLedCountText.toIntOrNull()
                                when {
                                    pin == null || pin < 0 -> {
                                        addChannelError = "Enter a valid GPIO pin number."
                                    }
                                    channels.any { it.gpioPin == pin } -> {
                                        addChannelError = "GPIO $pin is already configured."
                                    }
                                    count == null || count !in 1..1000 -> {
                                        addChannelError = "LED count must be between 1 and 1000."
                                    }
                                    else -> {
                                        val added = ChannelConfig(
                                            gpioPin = pin,
                                            ledCount = count,
                                            stripType = newStripType,
                                            colorOrder = newColorOrder,
                                            reversed = newReversed,
                                            segments = emptyList()
                                        )
                                        saveChannels(channels + added)
                                        newGpioPinText = ""
                                        newLedCountText = ""
                                        newStripType = 22
                                        newColorOrder = 0
                                        newReversed = false
                                        addChannelError = null
                                        showAddChannelForm = false
                                    }
                                }
                            },
                            enabled = !isLocked,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("btn_save_new_channel")
                        ) {
                            Text("Save Channel", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // PUSH TO CONTROLLER CTA
            Button(
                onClick = { onPushHardwareAndSegments(channels) },
                enabled = !isLocked && !isSyncing && channels.isNotEmpty(),
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
                    color = if (syncIsError) MaterialTheme.colorScheme.error else MaterialTheme.appColors.statusSuccess,
                    modifier = Modifier.testTag("hardware_sync_status_text")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelConfigItemCard(
    channelIndex: Int,
    channel: ChannelConfig,
    isLocked: Boolean,
    onChannelUpdated: (ChannelConfig) -> Unit,
    onChannelRemoved: () -> Unit
) {
    var showAddSegmentForm by remember { mutableStateOf(false) }
    var segmentLabelText by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf(SegmentRole.MAIN_FRAME) }
    var roleDropdownExpanded by remember { mutableStateOf(false) }
    var startLedText by remember { mutableStateOf("") }
    var endLedText by remember { mutableStateOf("") }
    var segmentValidationError by remember { mutableStateOf<String?>(null) }

    val coveredLeds = remember(channel.segments) {
        channel.segments.sumOf { (it.endLed - it.startLed).coerceAtLeast(0) }
    }
    val isFullyCovered = coveredLeds == channel.ledCount && channel.ledCount > 0
    val canAddMoreSegments = channel.segments.size < MAX_SEGMENTS_PER_CHANNEL

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(12.dp)
            .testTag("channel_card_${channel.gpioPin}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Channel Header: "GPIO 16 — 60 LEDs" (no user-editable channel name)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TerminalBadge(color = MaterialTheme.appColors.hardwareGpioBadge, text = "GPIO ${channel.gpioPin}")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "GPIO ${channel.gpioPin} — ${channel.ledCount} LEDs",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("channel_header_${channel.gpioPin}")
                )
            }

            IconButton(
                onClick = onChannelRemoved,
                enabled = !isLocked,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_remove_channel_${channel.gpioPin}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove GPIO ${channel.gpioPin}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        StripProtocolAndReverseRow(
            channelTag = "ch_${channel.gpioPin}",
            stripType = channel.stripType,
            colorOrder = channel.colorOrder,
            reversed = channel.reversed,
            enabled = !isLocked,
            onStripTypeChange = { onChannelUpdated(channel.copy(stripType = it)) },
            onColorOrderChange = { onChannelUpdated(channel.copy(colorOrder = it)) },
            onReversedChange = { onChannelUpdated(channel.copy(reversed = it)) }
        )

        // Existing Segments List
        if (channel.segments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                channel.segments.forEachIndexed { segIndex, seg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("segment_row_${channel.gpioPin}_$segIndex"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = seg.label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Role: ${seg.role.displayLabel} • LEDs ${seg.startLed}..${seg.endLed} (Seg #${seg.wledSegmentId})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                val updatedSegs = channel.segments.toMutableList()
                                updatedSegs.removeAt(segIndex)
                                onChannelUpdated(channel.copy(segments = updatedSegs))
                            },
                            enabled = !isLocked,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_delete_segment_${channel.gpioPin}_$segIndex")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete segment ${seg.label}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Strip coverage indicator (always shown below the segment list)
        val coverageColor = if (isFullyCovered) MaterialTheme.appColors.statusSuccess else MaterialTheme.appColors.statusWarning
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(coverageColor.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("coverage_indicator_${channel.gpioPin}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isFullyCovered) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = coverageColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$coveredLeds / ${channel.ledCount} LEDs configured",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = if (isFullyCovered) "Strip fully configured" else "Unassigned LEDs hold last state",
                style = MaterialTheme.typography.labelSmall,
                color = coverageColor,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Add Segment button (disabled when channel has 10 segments)
        if (!showAddSegmentForm) {
            OutlinedButton(
                onClick = {
                    segmentValidationError = null
                    showAddSegmentForm = true
                },
                enabled = !isLocked && canAddMoreSegments,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("btn_add_segment_${channel.gpioPin}")
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (canAddMoreSegments) "Add Segment" else "Max 10 Segments Reached",
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Inline Segment Form
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
                    .testTag("segment_form_${channel.gpioPin}"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "New Segment on GPIO ${channel.gpioPin}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )

                // 1. Label — free-text TextField
                OutlinedTextField(
                    value = segmentLabelText,
                    onValueChange = {
                        segmentLabelText = it
                        segmentValidationError = null
                    },
                    enabled = !isLocked,
                    label = { Text("Label (e.g. Rear tail, Top tube)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_seg_label_${channel.gpioPin}")
                )

                // 2. Role — ExposedDropdownMenuBox populated from SegmentRole.entries.map { it.displayLabel }
                ExposedDropdownMenuBox(
                    expanded = roleDropdownExpanded,
                    onExpandedChange = { if (!isLocked) roleDropdownExpanded = !roleDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedRole.displayLabel,
                        onValueChange = {},
                        readOnly = true,
                        enabled = !isLocked,
                        label = { Text("Role") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleDropdownExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .testTag("dropdown_seg_role_${channel.gpioPin}")
                    )

                    ExposedDropdownMenu(
                        expanded = roleDropdownExpanded,
                        onDismissRequest = { roleDropdownExpanded = false }
                    ) {
                        SegmentRole.entries.forEach { roleOption ->
                            DropdownMenuItem(
                                text = { Text(roleOption.displayLabel) },
                                onClick = {
                                    selectedRole = roleOption
                                    roleDropdownExpanded = false
                                },
                                modifier = Modifier.testTag("role_option_${channel.gpioPin}_${roleOption.displayLabel}")
                            )
                        }
                    }
                }

                // 3. Start LED / End LED — integer inputs (0 … channel ledCount, End LED exclusive)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startLedText,
                        onValueChange = {
                            startLedText = it.filter { ch -> ch.isDigit() }
                            segmentValidationError = null
                        },
                        enabled = !isLocked,
                        label = { Text("Start LED (0..${channel.ledCount - 1})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_seg_start_${channel.gpioPin}")
                    )

                    OutlinedTextField(
                        value = endLedText,
                        onValueChange = {
                            endLedText = it.filter { ch -> ch.isDigit() }
                            segmentValidationError = null
                        },
                        enabled = !isLocked,
                        label = { Text("End LED (excl, <=${channel.ledCount})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_seg_end_${channel.gpioPin}")
                    )
                }

                segmentValidationError?.let { errorMsg ->
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("seg_validation_error_${channel.gpioPin}")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            showAddSegmentForm = false
                            segmentValidationError = null
                        },
                        enabled = !isLocked,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("btn_cancel_seg_${channel.gpioPin}")
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val startLed = startLedText.toIntOrNull()
                            val endLed = endLedText.toIntOrNull()
                            val error = validateSegmentCandidate(
                                existingSegments = channel.segments,
                                channelLedCount = channel.ledCount,
                                label = segmentLabelText,
                                startLed = startLed,
                                endLed = endLed
                            )
                            if (error != null) {
                                segmentValidationError = error
                            } else {
                                val newSegment = SegmentConfig(
                                    wledSegmentId = 0, // Recomputed sequentially by saveChannels()
                                    startLed = startLed!!,
                                    endLed = endLed!!,
                                    label = segmentLabelText.trim(),
                                    role = selectedRole
                                )
                                onChannelUpdated(channel.copy(segments = channel.segments + newSegment))
                                // Post-save behavior: reset form to empty so user can immediately add another segment
                                segmentLabelText = ""
                                startLedText = ""
                                endLedText = ""
                                selectedRole = SegmentRole.MAIN_FRAME
                                segmentValidationError = null
                                if (channel.segments.size + 1 >= MAX_SEGMENTS_PER_CHANNEL) {
                                    showAddSegmentForm = false
                                }
                            }
                        },
                        enabled = !isLocked,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("btn_save_seg_${channel.gpioPin}")
                    ) {
                        Text("Save Segment", fontWeight = FontWeight.Bold)
                    }
                }
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

            // Color Order Chips (0 = GRB, 1 = RGB, 2 = BRG)
            listOf(
                0 to "GRB",
                1 to "RGB",
                2 to "BRG"
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
                        .padding(horizontal = 8.dp, vertical = 6.dp)
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
