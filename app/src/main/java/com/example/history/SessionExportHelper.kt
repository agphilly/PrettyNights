package com.example.history

import android.content.Context
import android.content.Intent
import com.example.logging.*
import com.example.ride.BoschLiveDataSpec
import java.util.Locale
import kotlin.math.abs

/**
 * Computed Bosch Live Data Interface V1.0 (Release v19) reliability & telemetry metrics for a ride session.
 */
data class BoschSessionReliabilityReport(
    val totalNotifications: Int,
    val decodeSuccessCount: Int,
    val decodeErrorCount: Int,
    val decodeSuccessRatePercent: Float,
    val minNegotiatedMtu: Int,
    val maxNegotiatedMtu: Int,
    val hasLdi003MtuWarning: Boolean,
    val avgNotificationIntervalMs: Long,
    val maxNotificationIntervalMs: Long,
    val boschFreshnessUptimePercent: Float,
    val staleFallbackEvents: Int,
    val meanSpeedDeltaKmh: Float,
    val maxBoschSpeedKmh: Float,
    val avgCadenceRpm: Int?,
    val avgRiderPowerWatts: Int?,
    val maxRiderPowerWatts: Int?,
    val latestAmbientLux: Float?,
    val startBatterySoc: Int?,
    val endBatterySoc: Int?,
    val latestOdometerMeters: Long?,
    val latestBikeLightLabel: String,
    val lightReserveTriggered: Boolean,
    val systemLockedObserved: Boolean,
    val chargerConnectedObserved: Boolean,
    val diagnosisActiveObserved: Boolean,
    val bikeNotDrivingTransitions: Int
)

object SessionExportHelper {

    fun computeBoschReport(
        imuSamples: List<ImuSampleEntity>,
        boschSamples: List<BoschSampleEntity>
    ): BoschSessionReliabilityReport {
        val freshTicks = imuSamples.count { it.boschAgeMs in 0L..3999L }
        var staleEvents = 0
        var wasFresh = false
        for (sample in imuSamples) {
            val isFresh = sample.boschAgeMs in 0L..3999L
            if (wasFresh && !isFresh) {
                staleEvents++
            }
            wasFresh = isFresh
        }
        val summary = ImuSessionSummary(
            totalSamples = imuSamples.size,
            freshBoschSamples = freshTicks,
            maxBoschSpeedKmh = imuSamples.mapNotNull { it.boschSpeedKmh }.maxOrNull()
        )
        return computeBoschReportFromSummary(
            imuSummary = summary,
            boschSamples = boschSamples,
            exactStaleFallbackEvents = staleEvents
        )
    }

    /**
     * Computes the Bosch Live Data Interface V1.0 reliability report using a lightweight SQL
     * `ImuSessionSummary` instead of loading all 50 Hz `ImuSampleEntity` rows into heap memory.
     */
    fun computeBoschReportFromSummary(
        imuSummary: ImuSessionSummary,
        boschSamples: List<BoschSampleEntity>,
        exactStaleFallbackEvents: Int? = null
    ): BoschSessionReliabilityReport {
        val totalNotifs = boschSamples.size
        val decodeSuccess = boschSamples.count { it.decodeSuccess }
        val decodeErrors = totalNotifs - decodeSuccess
        val decodeSuccessPct = if (totalNotifs > 0) (decodeSuccess * 100f) / totalNotifs else 100f

        val minMtu = boschSamples.minOfOrNull { it.negotiatedMtu } ?: 247
        val maxMtu = boschSamples.maxOfOrNull { it.negotiatedMtu } ?: 247
        val hasLdi003Warning = totalNotifs > 0 && minMtu < BoschLiveDataSpec.REQUIRED_ATT_MTU

        // Notification intervals & stale gap detection (> 4000 ms between notifications)
        var intervalSum = 0L
        var intervalCount = 0
        var maxInterval = 0L
        var derivedStaleGaps = 0
        for (i in 1 until boschSamples.size) {
            val delta = (boschSamples[i].timestampMonotonicMs - boschSamples[i - 1].timestampMonotonicMs)
                .coerceAtLeast(0L)
            intervalSum += delta
            intervalCount++
            if (delta > maxInterval) maxInterval = delta
            if (delta >= 4000L) derivedStaleGaps++
        }
        val avgInterval = if (intervalCount > 0) intervalSum / intervalCount else 0L

        val freshnessPct = if (imuSummary.totalSamples > 0) {
            (imuSummary.freshBoschSamples * 100f) / imuSummary.totalSamples
        } else {
            0f
        }
        val staleEvents = exactStaleFallbackEvents ?: derivedStaleGaps

        // Speed delta |Bosch - GPS|
        val validDeltas = boschSamples.mapNotNull { b ->
            b.speedKmh?.let { abs(it - b.gpsSpeedKmhAtCapture) }
        }
        val meanDelta = if (validDeltas.isNotEmpty()) validDeltas.average().toFloat() else 0f
        val maxBoschSpeed = boschSamples.mapNotNull { it.speedKmh }.maxOrNull()
            ?: imuSummary.maxBoschSpeedKmh
            ?: 0f

        val cadences = boschSamples.mapNotNull { it.cadenceRpm }
        val powers = boschSamples.mapNotNull { it.riderPowerWatts }
        val socs = boschSamples.mapNotNull { it.batterySoc }

        // Count field 25 `bike_not_driving` state transitions
        var notDrivingTransitions = 0
        var prevNotDriving: Boolean? = null
        for (b in boschSamples) {
            val current = b.bikeNotDriving ?: continue
            if (prevNotDriving != null && prevNotDriving != current) {
                notDrivingTransitions++
            }
            prevNotDriving = current
        }

        val latestLightCode = boschSamples.lastOrNull { it.bikeLightRaw != null }?.bikeLightRaw

        return BoschSessionReliabilityReport(
            totalNotifications = totalNotifs,
            decodeSuccessCount = decodeSuccess,
            decodeErrorCount = decodeErrors,
            decodeSuccessRatePercent = decodeSuccessPct,
            minNegotiatedMtu = minMtu,
            maxNegotiatedMtu = maxMtu,
            hasLdi003MtuWarning = hasLdi003Warning,
            avgNotificationIntervalMs = avgInterval,
            maxNotificationIntervalMs = maxInterval,
            boschFreshnessUptimePercent = freshnessPct,
            staleFallbackEvents = staleEvents,
            meanSpeedDeltaKmh = meanDelta,
            maxBoschSpeedKmh = maxBoschSpeed,
            avgCadenceRpm = if (cadences.isNotEmpty()) cadences.average().toInt() else null,
            avgRiderPowerWatts = if (powers.isNotEmpty()) powers.average().toInt() else null,
            maxRiderPowerWatts = powers.maxOrNull(),
            latestAmbientLux = boschSamples.lastOrNull { it.ambientLux != null }?.ambientLux,
            startBatterySoc = socs.firstOrNull(),
            endBatterySoc = socs.lastOrNull(),
            latestOdometerMeters = boschSamples.lastOrNull { it.odometerMeters != null }?.odometerMeters,
            latestBikeLightLabel = BoschLiveDataSpec.formatLightState(latestLightCode),
            lightReserveTriggered = boschSamples.any { it.lightReserveState == true },
            systemLockedObserved = boschSamples.any { it.systemLocked == true },
            chargerConnectedObserved = boschSamples.any { it.chargerConnected == true },
            diagnosisActiveObserved = boschSamples.any { it.diagnosisActive == true },
            bikeNotDrivingTransitions = notDrivingTransitions
        )
    }

    /**
     * Builds the unified CSV export string containing:
     * 1. Session & Bosch V1.0 Reliability Header
     * 2. In-Ride Stamped Event Markers
     * 3. Bosch `com.bosch.ebike.LiveData` (`eb21`) Notification Log
     * 4. 50 Hz Unified IMU + GPS + Bosch + State Machine Telemetry
     */
    fun buildSessionCsv(
        session: SessionEntity,
        imuSamples: List<ImuSampleEntity>,
        boschSamples: List<BoschSampleEntity>,
        markers: List<RideMarkerEntity>,
        report: BoschSessionReliabilityReport
    ): String {
        val sw = java.io.StringWriter()
        writeSessionCsvToAppendable(sw, session, imuSamples, boschSamples, markers, report)
        return sw.toString()
    }

    /**
     * Streams the 4-section CSV directly into any Appendable (such as a BufferedWriter on disk)
     * so multi-hour 50 Hz rides never allocate a huge single String in heap memory.
     */
    fun writeSessionCsvToAppendable(
        out: Appendable,
        session: SessionEntity,
        imuSamples: List<ImuSampleEntity>,
        boschSamples: List<BoschSampleEntity>,
        markers: List<RideMarkerEntity>,
        report: BoschSessionReliabilityReport
    ) {
        // Section 1: Session Metadata & Bosch V1.0 Summary
        out.appendLine("# PRETTY NIGHTS TELEMETRY & BOSCH LIVE DATA V1.0 EXPORT")
        out.appendLine("# session_id,${session.id}")
        out.appendLine("# title,\"${session.title.replace("\"", "\"\"")}\"")
        out.appendLine("# notes,\"${session.notes.replace("\"", "\"\"")}\"")
        out.appendLine("# mode,${if (session.logOnlyMode) "LOG_ONLY_NO_WLED" else "WLED_ACTIVE"}")
        out.appendLine("# configured_speed_source,${session.configuredSpeedSource}")
        out.appendLine("# start_wall_ms,${session.startTimeMillis}")
        out.appendLine("# start_monotonic_ms,${session.startMonotonicMs}")
        out.appendLine("# end_monotonic_ms,${session.endTimeMonotonicMs ?: ""}")
        out.appendLine("# bosch_eb21_notifications,${report.totalNotifications}")
        out.appendLine("# bosch_decode_success_pct,${String.format(Locale.US, "%.2f", report.decodeSuccessRatePercent)}")
        out.appendLine("# bosch_min_mtu,${report.minNegotiatedMtu}")
        out.appendLine("# bosch_freshness_uptime_pct,${String.format(Locale.US, "%.2f", report.boschFreshnessUptimePercent)}")
        out.appendLine("# bosch_stale_fallback_events,${report.staleFallbackEvents}")
        out.appendLine("# bosch_mean_speed_delta_kmh,${String.format(Locale.US, "%.2f", report.meanSpeedDeltaKmh)}")
        out.appendLine()

        // Section 2: In-Ride Event Markers
        out.appendLine("# IN_RIDE_EVENT_MARKERS")
        out.appendLine("marker_id,timestamp_monotonic_ms,elapsed_ms,marker_type,note")
        for (m in markers) {
            val elapsed = m.timestampMonotonicMs - session.startMonotonicMs
            out.appendLine("${m.id},${m.timestampMonotonicMs},$elapsed,${m.markerType},\"${m.note.replace("\"", "\"\"")}\"")
        }
        out.appendLine()

        // Section 3: Bosch Live Data Interface V1.0 (eb21) Notifications
        out.appendLine("# BOSCH_LIVE_DATA_V1_NOTIFICATIONS (0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4)")
        out.appendLine(
            "packet_id,timestamp_monotonic_ms,elapsed_ms,negotiated_mtu,payload_bytes,decode_success,present_fields_mask," +
                "speed_raw_100th_kmh,speed_kmh,cadence_rpm,rider_power_w,ambient_millilux,ambient_lux," +
                "battery_soc_pct,ebike_time_utc_s,odometer_m,bike_light_code,system_locked,charger_connected," +
                "light_reserve_state,diagnosis_active,bike_not_driving,gps_speed_kmh_at_capture"
        )
        for (b in boschSamples) {
            val elapsed = b.timestampMonotonicMs - session.startMonotonicMs
            out.appendLine(
                "${b.id},${b.timestampMonotonicMs},$elapsed,${b.negotiatedMtu},${b.payloadSizeBytes},${b.decodeSuccess},${b.presentFieldsMask}," +
                    "${b.speedHundredthsKmh ?: ""},${b.speedKmh?.let { String.format(Locale.US, "%.2f", it) } ?: ""}," +
                    "${b.cadenceRpm ?: ""},${b.riderPowerWatts ?: ""},${b.ambientMilliLux ?: ""},${b.ambientLux?.let { String.format(Locale.US, "%.3f", it) } ?: ""}," +
                    "${b.batterySoc ?: ""},${b.ebikeTimeUtcSec ?: ""},${b.odometerMeters ?: ""},${b.bikeLightRaw ?: ""}," +
                    "${b.systemLocked ?: ""},${b.chargerConnected ?: ""},${b.lightReserveState ?: ""},${b.diagnosisActive ?: ""},${b.bikeNotDriving ?: ""}," +
                    String.format(Locale.US, "%.2f", b.gpsSpeedKmhAtCapture)
            )
        }
        out.appendLine()

        // Section 4: 50 Hz Unified IMU + GPS + Bosch + State Machine Telemetry
        out.appendLine("# UNIFIED_50HZ_RIDE_TELEMETRY")
        out.appendLine(
            "sample_id,timestamp_monotonic_ms,elapsed_ms,state_name,active_speed_source,speed_kmh,gps_speed_kmh," +
                "bosch_speed_kmh,bosch_age_ms,bosch_bike_not_driving,bosch_cadence_rpm,bosch_rider_power_w," +
                "bosch_ambient_lux,bosch_battery_soc,bosch_bike_light,fused_accel_ms2,accel_x,accel_y,accel_z,axis,sign,marker_at_tick"
        )
        for (s in imuSamples) {
            val elapsed = s.timestampMonotonicMs - session.startMonotonicMs
            val nearMarker = markers.firstOrNull { kotlin.math.abs(it.timestampMonotonicMs - s.timestampMonotonicMs) <= 25L }?.markerType ?: ""
            out.appendLine(
                "${s.id},${s.timestampMonotonicMs},$elapsed,${s.stateName},${s.activeSpeedSource}," +
                    "${String.format(Locale.US, "%.2f", s.speedKmh)},${String.format(Locale.US, "%.2f", s.gpsSpeedKmh)}," +
                    "${s.boschSpeedKmh?.let { String.format(Locale.US, "%.2f", it) } ?: ""},${s.boschAgeMs}," +
                    "${s.boschBikeNotDriving ?: ""},${s.boschCadenceRpm ?: ""},${s.boschRiderPowerW ?: ""}," +
                    "${s.boschAmbientLux?.let { String.format(Locale.US, "%.3f", it) } ?: ""},${s.boschBatterySoc ?: ""},${s.boschBikeLight ?: ""}," +
                    "${String.format(Locale.US, "%.3f", s.fusedAcceleration)}," +
                    "${String.format(Locale.US, "%.3f", s.accelX)},${String.format(Locale.US, "%.3f", s.accelY)},${String.format(Locale.US, "%.3f", s.accelZ)}," +
                    "${s.calibratedAxis},${s.calibratedSign},$nearMarker"
            )
        }
    }

    /**
     * Streams the session CSV directly to `cacheDir/exports/prettynights_session_<id>.csv` and shares it
     * via `FileProvider` (`Intent.EXTRA_STREAM`) to avoid Android's 1 MB Binder `TransactionTooLargeException`.
     */
    fun exportAndShareSessionCsv(
        context: Context,
        session: SessionEntity,
        imuSamples: List<ImuSampleEntity>,
        boschSamples: List<BoschSampleEntity>,
        markers: List<RideMarkerEntity>,
        report: BoschSessionReliabilityReport
    ): java.io.File {
        val exportDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val csvFile = java.io.File(exportDir, "prettynights_session_${session.id}.csv")
        csvFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writeSessionCsvToAppendable(writer, session, imuSamples, boschSamples, markers, report)
        }
        shareCsvFile(context, session.id, csvFile)
        return csvFile
    }

    fun shareSessionCsv(context: Context, sessionId: Long, csvContent: String) {
        val exportDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val csvFile = java.io.File(exportDir, "prettynights_session_${sessionId}.csv")
        csvFile.writeText(csvContent, Charsets.UTF_8)
        shareCsvFile(context, sessionId, csvFile)
    }

    private fun shareCsvFile(context: Context, sessionId: Long, csvFile: java.io.File) {
        val sendIntent = try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                csvFile
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Pretty Nights Ride Session #$sessionId Telemetry & Bosch V1.0 CSV")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("prettynights_session_${sessionId}.csv", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            // Safe fallback for unit test environments where FileProvider may not be registered
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Pretty Nights Ride Session #$sessionId Telemetry & Bosch V1.0 CSV")
                putExtra(Intent.EXTRA_TEXT, csvFile.readText(Charsets.UTF_8).take(250_000))
            }
        }
        val chooser = Intent.createChooser(sendIntent, "Export Ride Session #$sessionId CSV")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
