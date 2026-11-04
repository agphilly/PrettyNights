package com.example.history

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.logging.*
import com.example.ui.theme.appColors
import java.util.*

@Composable
fun HistoryScreen(
    vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
) {
    val context = LocalContext.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val expandedSessionId by vm.expandedSessionId.collectAsStateWithLifecycle()
    val metricsBySessionId by vm.metricsBySessionId.collectAsStateWithLifecycle()

    HistoryContent(
        sessions = sessions,
        expandedSessionId = expandedSessionId,
        metricsBySessionId = metricsBySessionId,
        onToggleExpand = vm::toggleExpand,
        onDeleteSession = vm::deleteSession,
        onUpdateMetadata = vm::updateSessionMetadata,
        onExportCsv = { session -> vm.exportSessionCsv(context, session) }
    )
}

@Composable
fun HistoryContent(
    sessions: List<SessionEntity>,
    expandedSessionId: Long? = null,
    metricsBySessionId: Map<Long, SessionMetricsState> = emptyMap(),
    onToggleExpand: (Long) -> Unit = {},
    onDeleteSession: (Long) -> Unit = {},
    onUpdateMetadata: (Long, String, String) -> Unit = { _, _, _ -> },
    onExportCsv: (SessionEntity) -> Unit = {}
) {
    var editingSession by remember { mutableStateOf<SessionEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Ride Session Logs & Bosch V1.0 Analytics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Full 50 Hz IMU, GPS & Bosch Live Data Interface V1.0 recorded on every ride",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        text = "No recorded rides yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "Start a ride (WLED Active or Log Only) to record 50 Hz IMU, GPS, Bosch Live Data V1.0, and event markers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        expanded = expandedSessionId == session.id,
                        metrics = metricsBySessionId[session.id] ?: SessionMetricsState(),
                        onExpandRequest = { onToggleExpand(session.id) },
                        onEditNotesRequest = { editingSession = session },
                        onDeleteSession = { onDeleteSession(session.id) },
                        onExportCsv = { onExportCsv(session) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(64.dp))
    }

    // --- Edit Session Title & Post-Ride Notes Dialog ---
    editingSession?.let { target ->
        var titleText by remember(target.id) { mutableStateOf(target.title) }
        var notesText by remember(target.id) { mutableStateOf(target.notes) }

        AlertDialog(
            onDismissRequest = { editingSession = null },
            title = { Text("Label Ride Session #${target.id}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it },
                        label = { Text("Ride Title (e.g., Commute - Wet Cobblestones)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_session_title")
                    )
                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        label = { Text("Post-Ride Notes & Bosch V1.0 Observations") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("input_session_notes")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sId = target.id
                        val newTitle = titleText
                        val newNotes = notesText
                        editingSession = null
                        onUpdateMetadata(sId, newTitle, newNotes)
                    },
                    modifier = Modifier.testTag("btn_save_session_notes")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSession = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SessionCard(
    session: SessionEntity,
    expanded: Boolean,
    metrics: SessionMetricsState = SessionMetricsState(),
    onExpandRequest: () -> Unit,
    onEditNotesRequest: () -> Unit,
    onDeleteSession: () -> Unit,
    onExportCsv: () -> Unit = {}
) {
    val formattedDate = remember(session.startTimeMillis) {
        val calendar = Calendar.getInstance().apply { timeInMillis = session.startTimeMillis }
        DateFormat.format("MMM dd, yyyy - hh:mm a", calendar).toString()
    }

    val durationString = remember(session.startMonotonicMs, session.endTimeMonotonicMs) {
        val end = session.endTimeMonotonicMs
        if (end == null) {
            "In progress / Unfinished"
        } else {
            val totalSec = (end - session.startMonotonicMs) / 1000
            val mins = totalSec / 60
            val secs = totalSec % 60
            "${mins}m ${secs}s"
        }
    }

    val imuSummary = metrics.imuSummary
    val boschSamples = metrics.boschSamples
    val stateIntervals = metrics.stateIntervals
    val rideMarkers = metrics.rideMarkers
    val boschReport = metrics.boschReport
    val isLoadingMetrics = metrics.isLoading

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_card_${session.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandRequest() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (session.title.isNotBlank()) session.title else formattedDate,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (session.logOnlyMode) MaterialTheme.appColors.logOnlyBadge else MaterialTheme.appColors.statusPassText
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (session.logOnlyMode) "LOG ONLY" else "WLED ACTIVE",
                                color = MaterialTheme.appColors.onBadgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (session.title.isNotBlank()) {
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.appColors.mutedText
                        )
                    }
                    Text(
                        text = "Duration: $durationString • Control Source: ${session.configuredSpeedSource.wireValue}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (session.notes.isNotBlank()) {
                        Text(
                            text = "Notes: ${session.notes}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onEditNotesRequest,
                        modifier = Modifier.testTag("btn_edit_notes_${session.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = "Edit Session Notes",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onDeleteSession,
                        modifier = Modifier.testTag("btn_delete_session_${session.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Log",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

                    if (isLoadingMetrics) {
                        Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        // 1. Core Session Counters (backed by lightweight SQL summary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            MetricCounterCol("50 Hz IMU Ticks", "${imuSummary.totalSamples}")
                            MetricCounterCol("Bosch Packets", "${boschSamples.size}")
                            MetricCounterCol("Transitions", "${stateIntervals.size}")
                            MetricCounterCol("Markers", "${rideMarkers.size}")
                        }

                        // 2. Action Buttons: Label Session & Export CSV
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onEditNotesRequest,
                                modifier = Modifier.weight(1f).testTag("btn_label_ride_${session.id}")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Edit Notes", fontSize = 12.sp)
                            }
                            Button(
                                onClick = onExportCsv,
                                modifier = Modifier.weight(1f).testTag("btn_export_csv_${session.id}")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export CSV", fontSize = 12.sp)
                            }
                        }

                        // 3. Bosch Live Data Interface V1.0 Reliability & Telemetry Report
                        boschReport?.let { report ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("bosch_reliability_card_${session.id}"),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.BluetoothConnected,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Bosch Live Data V1.0 (eb20/eb21) Report",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "Release v19",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (report.hasLdi003MtuWarning) {
                                        Text(
                                            text = "⚠ LDI-003 Warning: Negotiated ATT_MTU (${report.minNegotiatedMtu}) < 247; notifications may be truncated.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    BoschStatRow("eb21 Notifications", "${report.totalNotifications} (${String.format("%.1f%%", report.decodeSuccessRatePercent)} valid)")
                                    BoschStatRow("Negotiated ATT_MTU", "${report.minNegotiatedMtu} bytes (req ≥ 247)")
                                    BoschStatRow("Freshness Uptime (<4s)", String.format("%.1f%% (%d stale drops)", report.boschFreshnessUptimePercent, report.staleFallbackEvents))
                                    BoschStatRow("Notification Cadence", "avg ${report.avgNotificationIntervalMs} ms | max ${report.maxNotificationIntervalMs} ms")
                                    BoschStatRow("Mean Speed Delta |Bosch - GPS|", String.format("%.2f km/h (max %.1f km/h)", report.meanSpeedDeltaKmh, report.maxBoschSpeedKmh))
                                    BoschStatRow(
                                        "Battery SoC (field 10) / Odo (12)",
                                        "${report.startBatterySoc?.let { "$it%" } ?: "—"} → ${report.endBatterySoc?.let { "$it%" } ?: "—"} | ${report.latestOdometerMeters?.let { "${it}m" } ?: "—"}"
                                    )
                                    BoschStatRow(
                                        "Cadence (2) / Rider Power (5)",
                                        "${report.avgCadenceRpm?.let { "$it rpm" } ?: "—"} | avg ${report.avgRiderPowerWatts?.let { "${it}W" } ?: "—"} (peak ${report.maxRiderPowerWatts?.let { "${it}W" } ?: "—"})"
                                    )
                                    BoschStatRow(
                                        "Ambient Lux (9) / Bike Light (17)",
                                        "${report.latestAmbientLux?.let { String.format("%.1f lx", it) } ?: "—"} | ${report.latestBikeLightLabel}"
                                    )
                                    BoschStatRow(
                                        "Not Driving (25) / Flags (21-24)",
                                        "${report.bikeNotDrivingTransitions} transitions | rsv=${report.lightReserveTriggered} lck=${report.systemLockedObserved}"
                                    )
                                }
                            }
                        }

                        // 4. Stamped In-Ride Event Markers
                        if (rideMarkers.isNotEmpty()) {
                            Text(
                                text = "Stamped In-Ride Event Markers (${rideMarkers.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            rideMarkers.forEach { marker ->
                                val elapsedSec = (marker.timestampMonotonicMs - session.startMonotonicMs) / 1000.0
                                val label = marker.markerType?.wireValue ?: "UNKNOWN"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "• $label",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = String.format("+%.1fs", elapsedSec),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }

                        // 5. State Overlap Summary Breakdown
                        if (stateIntervals.isNotEmpty()) {
                            Text(
                                text = "Ride Episode Breakdown",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            stateIntervals.groupBy { it.stateName }.forEach { (stateName, list) ->
                                val totalTimeMs = list.sumOf {
                                    val end = it.endTimeMonotonicMs ?: session.endTimeMonotonicMs ?: it.startTimeMonotonicMs
                                    end - it.startTimeMonotonicMs
                                }
                                val seconds = totalTimeMs / 1000
                                val avgBrightness = list.map { (it.startBrightness + it.endBrightness) / 2 }.average()

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    MaterialTheme.appColors.colorForRideStateName(stateName)
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stateName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    }

                                    Text(
                                        text = "${list.size} eps | ${seconds}s | bri:${String.format("%.2f", avgBrightness)}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCounterCol(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun BoschStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
