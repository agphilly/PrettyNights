package com.example.tuning

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.PrettyNightsApp
import com.example.logging.ImuSampleEntity
import com.example.logging.RideMarkerEntity
import com.example.logging.SessionEntity
import com.example.statemachine.RideSample
import com.example.statemachine.RideState
import com.example.statemachine.RideStateMachine
import com.example.statemachine.StateMachineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.abs

data class ReplayComparisonResult(
    val totalSamples: Int,
    val replaySpeedSource: String,
    val recordedBrakeEpisodes: Int,
    val simulatedBrakeEpisodes: Int,
    val recordedStateSharePct: Map<RideState, Float>,
    val simulatedStateSharePct: Map<RideState, Float>,
    val brakeMarkersTotal: Int,
    val brakeMarkersDetected: Int,
    val roadBumpMarkersTotal: Int,
    val roadBumpMarkersRejected: Int
)

object RideReplaySimulator {
    fun runReplay(
        imuSamples: List<ImuSampleEntity>,
        markers: List<RideMarkerEntity>,
        config: StateMachineConfig,
        replaySpeedSource: String // "RECORDED", "BOSCH", "GPS"
    ): ReplayComparisonResult {
        if (imuSamples.isEmpty()) {
            return ReplayComparisonResult(
                totalSamples = 0,
                replaySpeedSource = replaySpeedSource,
                recordedBrakeEpisodes = 0,
                simulatedBrakeEpisodes = 0,
                recordedStateSharePct = emptyMap(),
                simulatedStateSharePct = emptyMap(),
                brakeMarkersTotal = 0,
                brakeMarkersDetected = 0,
                roadBumpMarkersTotal = 0,
                roadBumpMarkersRejected = 0
            )
        }

        val simMachine = RideStateMachine { config }
        simMachine.getAndResetState(imuSamples.first().timestampMonotonicMs)

        val recordedCounts = mutableMapOf<RideState, Int>()
        val simulatedCounts = mutableMapOf<RideState, Int>()
        val simulatedStatesByTimestamp = ArrayList<Pair<Long, RideState>>(imuSamples.size)

        var recordedBrakeEps = 0
        var prevRecordedState: RideState? = null

        var simulatedBrakeEps = 0
        var prevSimulatedState: RideState? = null

        for (sample in imuSamples) {
            val recState = try {
                RideState.valueOf(sample.stateName)
            } catch (_: Exception) {
                RideState.IDLE
            }
            recordedCounts[recState] = (recordedCounts[recState] ?: 0) + 1
            if (recState == RideState.BRAKING && prevRecordedState != RideState.BRAKING) {
                recordedBrakeEps++
            }
            prevRecordedState = recState

            val selectedSpeedKmh = when (replaySpeedSource) {
                "BOSCH" -> sample.boschSpeedKmh ?: sample.gpsSpeedKmh
                "GPS" -> sample.gpsSpeedKmh
                else -> sample.speedKmh
            }

            val simOutput = simMachine.tick(
                RideSample(
                    timestampMs = sample.timestampMonotonicMs,
                    longitudinalAccel = sample.fusedAcceleration,
                    speedKmh = selectedSpeedKmh,
                    bikeNotDriving = sample.boschBikeNotDriving
                )
            )
            val simState = simOutput.state
            simulatedCounts[simState] = (simulatedCounts[simState] ?: 0) + 1
            if (simState == RideState.BRAKING && prevSimulatedState != RideState.BRAKING) {
                simulatedBrakeEps++
            }
            prevSimulatedState = simState
            simulatedStatesByTimestamp.add(sample.timestampMonotonicMs to simState)
        }

        val total = imuSamples.size.toFloat()
        val recShare = RideState.entries.associateWith { ((recordedCounts[it] ?: 0) * 100f) / total }
        val simShare = RideState.entries.associateWith { ((simulatedCounts[it] ?: 0) * 100f) / total }

        // Evaluate marker accuracy within ±750 ms window
        val brakeMarkers = markers.filter { it.markerType == "HARD_BRAKE" || it.markerType == "LIGHT_BRAKE" }
        var brakeDetected = 0
        for (bm in brakeMarkers) {
            val hit = simulatedStatesByTimestamp.any { (ts, st) ->
                abs(ts - bm.timestampMonotonicMs) <= 750L && (st == RideState.BRAKING || st == RideState.BRAKING_HOLD)
            }
            if (hit) brakeDetected++
        }

        val bumpMarkers = markers.filter { it.markerType == "ROAD_BUMP" }
        var bumpRejected = 0
        for (rm in bumpMarkers) {
            val falseTrigger = simulatedStatesByTimestamp.any { (ts, st) ->
                (ts - rm.timestampMonotonicMs) in -250L..400L && st == RideState.BRAKING
            }
            if (!falseTrigger) bumpRejected++
        }

        return ReplayComparisonResult(
            totalSamples = imuSamples.size,
            replaySpeedSource = replaySpeedSource,
            recordedBrakeEpisodes = recordedBrakeEps,
            simulatedBrakeEpisodes = simulatedBrakeEps,
            recordedStateSharePct = recShare,
            simulatedStateSharePct = simShare,
            brakeMarkersTotal = brakeMarkers.size,
            brakeMarkersDetected = brakeDetected,
            roadBumpMarkersTotal = bumpMarkers.size,
            roadBumpMarkersRejected = bumpRejected
        )
    }
}

@Composable
fun TuningReplayCard(
    currentStateMachineConfig: StateMachineConfig
) {
    val logRepository = PrettyNightsApp.instance.logRepository
    val sessions by logRepository.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var selectedSessionId by remember { mutableStateOf<Long?>(null) }
    var replaySpeedSource by remember { mutableStateOf("RECORDED") }
    var replayResult by remember { mutableStateOf<ReplayComparisonResult?>(null) }
    var isRunningReplay by remember { mutableStateOf(false) }

    LaunchedEffect(sessions) {
        if (selectedSessionId == null && sessions.isNotEmpty()) {
            selectedSessionId = sessions.first().id
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tuning_replay_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "In-App Ride Replay Simulator",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Re-run recorded 50 Hz IMU, GPS & Bosch Live Data V1.0 through current sliders",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            if (sessions.isEmpty()) {
                Text(
                    text = "Record a ride first (WLED Active or Log Only) to test tuning changes against real-world data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            } else {
                // Session selector chips (up to 4 most recent rides)
                Text(
                    text = "Select Recorded Session:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sessions.take(4).forEach { session ->
                        val isSelected = session.id == selectedSessionId
                        val label = remember(session) {
                            val cal = Calendar.getInstance().apply { timeInMillis = session.startTimeMillis }
                            val dateStr = DateFormat.format("MMM dd HH:mm", cal).toString()
                            val titlePart = if (session.title.isNotBlank()) "${session.title} ($dateStr)" else "Session #${session.id} ($dateStr)"
                            val modeTag = if (session.logOnlyMode) "LOG ONLY" else "WLED"
                            "$titlePart • $modeTag"
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { selectedSessionId = session.id }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("replay_select_session_${session.id}")
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Speed Source Selector for Replay
                Text(
                    text = "Replay Speed Stream:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "RECORDED" to "Recorded",
                        "BOSCH" to "Bosch eb21 (#1)",
                        "GPS" to "GPS Only"
                    ).forEach { (key, title) ->
                        val selected = replaySpeedSource == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { replaySpeedSource = key }
                                .padding(vertical = 8.dp)
                                .testTag("replay_speed_source_$key"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val sId = selectedSessionId ?: return@Button
                        isRunningReplay = true
                        coroutineScope.launch {
                            val result = withContext(Dispatchers.Default) {
                                val samples = logRepository.getImuSamples(sId).first()
                                val markers = logRepository.getRideMarkers(sId).first()
                                RideReplaySimulator.runReplay(
                                    imuSamples = samples,
                                    markers = markers,
                                    config = currentStateMachineConfig,
                                    replaySpeedSource = replaySpeedSource
                                )
                            }
                            replayResult = result
                            isRunningReplay = false
                        }
                    },
                    enabled = !isRunningReplay && selectedSessionId != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_run_replay_simulation"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunningReplay) "Simulating 50 Hz Replay..." else "Run Replay with Current Sliders",
                        fontWeight = FontWeight.Bold
                    )
                }

                replayResult?.let { res ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("replay_comparison_results"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Replay Results (${res.totalSamples} ticks • Speed: ${res.replaySpeedSource})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TelemetryRow(
                                label = "Braking triggers (Recorded → Simulated)",
                                value = "${res.recordedBrakeEpisodes} → ${res.simulatedBrakeEpisodes}"
                            )
                            RideState.entries.forEach { st ->
                                val recPct = res.recordedStateSharePct[st] ?: 0f
                                val simPct = res.simulatedStateSharePct[st] ?: 0f
                                TelemetryRow(
                                    label = "${st.name} share (Rec → Sim)",
                                    value = String.format("%.1f%% → %.1f%%", recPct, simPct)
                                )
                            }
                            if (res.brakeMarkersTotal > 0 || res.roadBumpMarkersTotal > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                                )
                                TelemetryRow(
                                    label = "Brake Markers Detected (±750ms)",
                                    value = "${res.brakeMarkersDetected} / ${res.brakeMarkersTotal}"
                                )
                                TelemetryRow(
                                    label = "Road Bumps Rejected (±750ms)",
                                    value = "${res.roadBumpMarkersRejected} / ${res.roadBumpMarkersTotal}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
