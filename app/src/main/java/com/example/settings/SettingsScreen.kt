package com.example.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.statemachine.StateMachineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    config: AppConfig,
    onConfigSave: (AppConfig) -> Unit,
    onNavigateToTuning: () -> Unit = {}
) {
    val isLocked by PrettyNightsApp.instance.lockController.isLocked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    
    var wledHostInput by remember(config.wledHost) { mutableStateOf(config.wledHost) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun cleanHost(input: String): Pair<String, String?> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return Pair("", null)
        }
        var cleaned = trimmed
        var correction: String? = null
        
        if (cleaned.startsWith("http://", ignoreCase = true)) {
            cleaned = cleaned.substring("http://".length)
            correction = "Scheme prefix 'http://' is not allowed and has been stripped."
        } else if (cleaned.startsWith("https://", ignoreCase = true)) {
            cleaned = cleaned.substring("https://".length)
            correction = "Scheme 'https://' is not allowed and has been stripped."
        }
        
        // Strip path or query first (anything starting with / or ?)
        val slashIndex = cleaned.indexOf("/")
        val qIndex = cleaned.indexOf("?")
        val cutIndex = when {
            slashIndex != -1 && qIndex != -1 -> minOf(slashIndex, qIndex)
            slashIndex != -1 -> slashIndex
            else -> qIndex
        }
        if (cutIndex != -1) {
            cleaned = cleaned.substring(0, cutIndex)
            correction = if (correction == null) {
                "Path/query segments are not allowed and have been stripped."
            } else {
                "$correction Path/query stripped."
            }
        }

        // Strip port (anything starting with :)
        if (cleaned.contains(":")) {
            cleaned = cleaned.substringBefore(":")
            correction = if (correction == null) {
                "Port is not allowed and has been stripped."
            } else {
                "$correction Port stripped."
            }
        }
        
        return Pair(cleaned.trim(), correction)
    }

    val (cleanedHost, correctionNote) = remember(wledHostInput) { cleanHost(wledHostInput) }

    // Save on close/dispose of composable screen
    DisposableEffect(Unit) {
        onDispose {
            if (!isLocked) {
                val (cleaned, _) = cleanHost(wledHostInput)
                if (cleaned.isNotEmpty()) {
                    onConfigSave(config.copy(wledHost = cleaned))
                }
            }
        }
    }

    val currentSelection = remember(config.speedSource) {
        when (config.speedSource) {
            "GPS", "GPS_ONLY" -> "GPS_ONLY"
            "BOSCH_PREFERRED" -> "BOSCH_PREFERRED"
            "BOSCH_ONLY" -> "BOSCH_ONLY"
            else -> "GPS_ONLY"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- SAFETY LOCK OUT WARNING BANNER ---
            if (isLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                        .padding(12.dp)
                        .testTag("ride_active_lock_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked icon",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Locked while riding — Settings cannot be modified",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // --- HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Application Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // --- DEVELOPER / TUNING ENTRY PANEL ---
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isLocked) { onNavigateToTuning() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isLocked) 0.05f else 0.15f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Developer / Tuning Dashboard",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Tune deep variables backing pure ride transitions with live telemetry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Navigate to developer dashboard",
                        tint = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary
                    )
                }
            }

            // --- 1. WLED HOST TARGET ---
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
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "LED Controller Target",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Text(
                        text = "Recommend a hostname (e.g. prettylights.local) over a raw IP because the controller's IP is typically DHCP-assigned and can change. Raw IP entry still works.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    
                    OutlinedTextField(
                        value = wledHostInput,
                        onValueChange = {
                            if (!isLocked) {
                                wledHostInput = it
                                // Auto clean and save if valid (non-empty)
                                val (cleaned, _) = cleanHost(it)
                                if (cleaned.isNotEmpty()) {
                                    onConfigSave(config.copy(wledHost = cleaned))
                                }
                            }
                        },
                        enabled = !isLocked,
                        label = { Text("WLED Host (Hostname or IP)") },
                        leadingIcon = { Icon(Icons.Default.SettingsInputComponent, null) },
                        placeholder = { Text("prettylights.local") },
                        supportingText = {
                            Column {
                                if (correctionNote != null) {
                                    Text(
                                        text = correctionNote,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    Text(
                                        text = "Address of the WLED device, e.g. prettylights.local or 192.168.1.50",
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wled_host_input"),
                        singleLine = true
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val (cleaned, _) = cleanHost(wledHostInput)
                                if (cleaned.isEmpty()) {
                                    testResult = "Unreachable"
                                } else {
                                    testResult = "Checking..."
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            PrettyNightsApp.instance.wledController.ping(cleaned)
                                        }
                                        testResult = if (ok) "Reachable" else "Unreachable"
                                    }
                                }
                            },
                            enabled = !isLocked,
                            modifier = Modifier.testTag("btn_test_connection")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Connection")
                        }
                        
                        testResult?.let { result ->
                            val color = when (result) {
                                "Reachable" -> Color(0xFF4CAF50)
                                "Unreachable" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(color.copy(alpha = 0.15f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("test_connection_result")
                            ) {
                                if (result == "Checking...") {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(14.dp),
                                        color = color
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (result == "Reachable") Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = result,
                                    color = color,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            // --- 2. SPEED SOURCE SELECTION ---
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

                    // Warning if Bosch Preferred or Bosch Only is selected
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

            // --- 3. SENSOR ORIENTATION & POLARITY ALIGNMENT ---
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
                        listOf("X", "Y", "Z").forEach { axis ->
                            FilterChip(
                                selected = config.sensorAxis == axis,
                                onClick = {
                                    if (!isLocked) {
                                        onConfigSave(config.copy(sensorAxis = axis))
                                    }
                                },
                                enabled = !isLocked,
                                label = { Text("Axis $axis", modifier = Modifier.padding(horizontal = 12.dp)) }
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

            // --- 3. DEV STATE MACHINE TUNING PARAMETERS (Section 14) ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("State Machine Tuning", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Detailed variables backing the pure ride state transitions loop.", fontSize = 11.sp, color = Color.Gray)

                    val sm = config.stateMachine

                    // Group 1: Global Envelope Brightness
                    Text("Global Brightness Envelope", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Global Min Brightness", style = MaterialTheme.typography.bodySmall)
                            Text("${(sm.globalMinBrightness * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.globalMinBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(globalMinBrightness = it))) },
                            valueRange = 0.0f..0.5f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Global Max Brightness (Ambient Ceiling)", style = MaterialTheme.typography.bodySmall)
                            Text("${(sm.globalMaxBrightness * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.globalMaxBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(globalMaxBrightness = it))) },
                            valueRange = 0.5f..1.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Braking Ignores Global Max", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text("Allows hard braking to punch through up to 100% look brightness.", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = sm.brakingIgnoresGlobalMax,
                            onCheckedChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingIgnoresGlobalMax = it))) }
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))

                    // Group 2: Speeds & Transitions (Hysteresis & Fades)
                    Text("Speeds & Idle transitions", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Idle Enter Speed (To Idle)", style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.1f", sm.idleEnterSpeedKmh)} km/h", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.idleEnterSpeedKmh,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleEnterSpeedKmh = it))) },
                            valueRange = 1.0f..10.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Idle Exit Speed (To Cruise)", style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.1f", sm.idleExitSpeedKmh)} km/h", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.idleExitSpeedKmh,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleExitSpeedKmh = it.coerceAtLeast(sm.idleEnterSpeedKmh)))) },
                            valueRange = 2.0f..15.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Idle Clean Brightness", style = MaterialTheme.typography.bodySmall)
                            Text("${(sm.idleBrightness * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.idleBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleBrightness = it))) },
                            valueRange = 0.05f..0.5f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Idle Entry Fade Duration", style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.1f", sm.idleEntryFadeS)} s", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.idleEntryFadeS,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(idleEntryFadeS = it))) },
                            valueRange = 0.0f..5.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))

                    // Group 3: Cruising Parameters
                    Text("Cruising State", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cruising Clean Brightness", style = MaterialTheme.typography.bodySmall)
                            Text("${(sm.cruisingBrightness * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.cruisingBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(cruisingBrightness = it))) },
                            valueRange = 0.05f..1.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cruising Entry Fade Duration", style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.1f", sm.cruisingEntryFadeS)} s", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.cruisingEntryFadeS,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(cruisingEntryFadeS = it))) },
                            valueRange = 0.0f..5.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))

                    // Group 4: Active Braking Parameters
                    Text("Active Braking Rules", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Braking deceleration limit", style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.2f", sm.brakingDecelThreshold)} m/s²", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.brakingDecelThreshold,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingDecelThreshold = it))) },
                            valueRange = -3.5f..-0.5f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Braking Debounce Duration", style = MaterialTheme.typography.bodySmall)
                            Text("${sm.brakingDebounceMs} ms", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.brakingDebounceMs.toFloat(),
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingDebounceMs = it.toLong()))) },
                            valueRange = 50f..1000f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Braking Cooldown Duration", style = MaterialTheme.typography.bodySmall)
                            Text("${sm.brakingCooldownMs} ms", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.brakingCooldownMs.toFloat(),
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingCooldownMs = it.toLong()))) },
                            valueRange = 50f..2000f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Braking Min Brightness (Onset)", style = MaterialTheme.typography.bodySmall)
                            Text("${(sm.brakingMinBrightness * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.brakingMinBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingMinBrightness = it))) },
                            valueRange = 0.1f..1.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Braking Max Brightness (Stopping)", style = MaterialTheme.typography.bodySmall)
                            Text("${(sm.brakingMaxBrightness * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.brakingMaxBrightness,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingMaxBrightness = it))) },
                            valueRange = 0.1f..1.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Braking Curve Exponent (Gamma)", style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.2f", sm.brakingGamma)}", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.brakingGamma,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingGamma = it))) },
                            valueRange = 0.5f..3.5f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Braking Entry Fade Duration", style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.1f", sm.brakingEntryFadeS)} s", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = sm.brakingEntryFadeS,
                            onValueChange = { onConfigSave(config.copy(stateMachine = sm.copy(brakingEntryFadeS = it))) },
                            valueRange = 0.0f..3.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))

                    // Group 5: Stop, Hold & Fade
                    Text("Braking Hold & Fade", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hold Duration (s)", fontSize = 12.sp, color = Color.Gray)
                            OutlinedTextField(
                                value = sm.brakingHoldDurationS.toString(),
                                onValueChange = {
                                    val s = it.toFloatOrNull()?.coerceIn(0f, 10f) ?: 2.0f
                                    onConfigSave(config.copy(stateMachine = sm.copy(brakingHoldDurationS = s)))
                                }
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fade Duration (s)", fontSize = 12.sp, color = Color.Gray)
                            OutlinedTextField(
                                value = sm.brakingFadeDurationS.toString(),
                                onValueChange = {
                                    val s = it.toFloatOrNull()?.coerceIn(0f, 10f) ?: 1.0f
                                    onConfigSave(config.copy(stateMachine = sm.copy(brakingFadeDurationS = s)))
                                }
                            )
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))

                    // Group 6: Bosch Corroboration Switches
                    Text("Bosch eBike Corroboration", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use Bosch Corroboration", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text("Enable extra Bluetooth corroboration signals checks", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = sm.useBoschCorroboration,
                            onCheckedChange = { onConfigSave(config.copy(stateMachine = sm.copy(useBoschCorroboration = it))) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Require Bosch Idle Confirm", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text("Defer idling transitions if Bosch says still driving", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = sm.requireBoschIdleConfirm,
                            onCheckedChange = { onConfigSave(config.copy(stateMachine = sm.copy(requireBoschIdleConfirm = it))) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(64.dp))
        }

        // --- SAFETY LOCKOUT OVERLAY (Section 7) ---
        AnimatedVisibility(
            visible = isLocked,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onErrorContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Text(
                            text = "Safety Lock Active",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Pretty Nights blocks settings modifications while the bike is in active state. Please stop to configure.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
