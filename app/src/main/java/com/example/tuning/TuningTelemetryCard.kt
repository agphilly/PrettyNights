package com.example.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ride.BoschLiveDataSpec
import com.example.statemachine.StateMachineTelemetry

@Composable
fun TuningTelemetryCard(
    telemetry: StateMachineTelemetry?,
    isRideRunning: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("telemetry_panel"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "State Machine & Bosch V1.0 Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isRideRunning && telemetry != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.Green)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (telemetry.logOnlyMode) "LIVE (LOG ONLY)" else "LIVE",
                            color = Color.Green,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        "Off",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isRideRunning && telemetry != null) {
                TelemetryRow(label = "Current state", value = telemetry.state.name)
                TelemetryRow(label = "Brightness", value = String.format("%.2f", telemetry.brightness))
                TelemetryRow(label = "Time in state", value = "${telemetry.timeInStateMs} ms")
                TelemetryRow(
                    label = "Entry speed",
                    value = telemetry.entrySpeedKmh?.let { String.format("%.1f km/h", it) } ?: "n/a"
                )
                TelemetryRow(label = "Decel active", value = if (telemetry.decelActive) "yes" else "no")
                TelemetryRow(label = "In entry fade", value = if (telemetry.inEntryFade) "yes" else "no")
                TelemetryRow(label = "Hold/fade phase", value = telemetry.holdFadePhase)

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                )

                TelemetryRow(label = "Active speed source", value = telemetry.activeSpeedSource)
                TelemetryRow(label = "GPS speed", value = String.format("%.1f km/h", telemetry.gpsSpeedKmh))
                TelemetryRow(
                    label = "Bosch speed (eb21 #1)",
                    value = telemetry.boschSpeedKmh?.let {
                        String.format("%.2f km/h (age %d ms)", it, telemetry.boschAgeMs)
                    } ?: "awaiting eb21"
                )
                TelemetryRow(
                    label = "Bosch MTU / Packets",
                    value = "MTU=${telemetry.boschNegotiatedMtu} | pkts=${telemetry.boschNotifications} (err=${telemetry.boschDecodeErrors})"
                )
                TelemetryRow(
                    label = "Bosch Bat (#10) / Pwr (#5) / Cad (#2)",
                    value = "${telemetry.boschBatterySoc?.let { "$it%" } ?: "—"} | ${telemetry.boschRiderPowerW?.let { "${it}W" } ?: "—"} | ${telemetry.boschCadenceRpm?.let { "$it rpm" } ?: "—"}"
                )
                TelemetryRow(
                    label = "Bosch Lux (#9) / Light (#17)",
                    value = "${telemetry.boschAmbientLux?.let { String.format("%.1f lx", it) } ?: "—"} | ${BoschLiveDataSpec.formatLightState(telemetry.boschBikeLightRaw)}"
                )
                TelemetryRow(
                    label = "Bosch bike_not_driving (#25)",
                    value = telemetry.boschBikeNotDriving?.toString() ?: "n/a"
                )
            } else {
                Text(
                    text = "No active ride",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow(label = "Current state", value = "—")
                TelemetryRow(label = "Brightness", value = "—")
                TelemetryRow(label = "Time in state", value = "—")
                TelemetryRow(label = "Entry speed", value = "—")
                TelemetryRow(label = "Decel active", value = "—")
                TelemetryRow(label = "In entry fade", value = "—")
                TelemetryRow(label = "Hold/fade phase", value = "—")
            }
        }
    }
}
