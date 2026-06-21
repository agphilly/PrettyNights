package com.example.history

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.PrettyNightsApp
import com.example.logging.SessionEntity
import com.example.logging.StateIntervalEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*

@Composable
fun HistoryScreen() {
    val logRepository = PrettyNightsApp.instance.logRepository
    val sessions by logRepository.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var expandedSessionId by remember { mutableStateOf<Long?>(null) }

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
            Text(
                text = "Ride Session Logs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
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
                        text = "Your completed ride files and telemetry statistics will be archived here.",
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
                        onExpandRequest = {
                            expandedSessionId = if (expandedSessionId == session.id) null else session.id
                        },
                        onDeleteSession = {
                            coroutineScope.launch {
                                logRepository.deleteSession(session.id)
                                if (expandedSessionId == session.id) {
                                    expandedSessionId = null
                                }
                            }
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(64.dp))
    }
}

@Composable
fun SessionCard(
    session: SessionEntity,
    expanded: Boolean,
    onExpandRequest: () -> Unit,
    onDeleteSession: () -> Unit
) {
    val logRepository = PrettyNightsApp.instance.logRepository
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

    // Load related metrics when expanded
    var imuCount by remember { mutableStateOf(0) }
    var stateIntervals by remember { mutableStateOf<List<StateIntervalEntity>>(emptyList()) }
    var isLoadingMetrics by remember { mutableStateOf(false) }

    LaunchedEffect(expanded) {
        if (expanded) {
            isLoadingMetrics = true
            // Load counts on background Dispatcher safely
            val samples = logRepository.getImuSamples(session.id).first()
            val intervals = logRepository.getStateIntervals(session.id).first()
            imuCount = samples.size
            stateIntervals = intervals
            isLoadingMetrics = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Column {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Duration: $durationString",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDeleteSession) {
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

                    if (isLoadingMetrics) {
                        Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        // Core session metrics
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Telemetry Points", fontSize = 11.sp, color = Color.Gray)
                                Text("$imuCount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Transitions", fontSize = 11.sp, color = Color.Gray)
                                Text("${stateIntervals.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                            }
                        }

                        // State Overlap summary breakdowns (Section 11)
                        if (stateIntervals.isNotEmpty()) {
                            Text(
                                text = "Ride Episode Breakdown (Room Interval query)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )

                            // Group and display intervals
                            stateIntervals.groupBy { it.stateName }.forEach { (stateName, list) ->
                                val totalTimeMs = list.sumOf {
                                    val end = it.endTimeMonotonicMs ?: session.endTimeMonotonicMs ?: it.startTimeMonotonicMs
                                    end - it.startTimeMonotonicMs
                                }
                                val seconds = totalTimeMs / 1000
                                val avgBrightness = list.map { (it.startBrightness + it.endBrightness)/2 }.average()

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
                                                    when (stateName) {
                                                        "BRAKING", "BRAKING_HOLD" -> Color.Red
                                                        "CRUISING" -> Color.Cyan
                                                        else -> Color(0xFFC084FC)
                                                    }
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stateName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    }

                                    Text(
                                        text = "${list.size} eps | ${seconds}s | bri:${String.format("%.1f", avgBrightness)}",
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
