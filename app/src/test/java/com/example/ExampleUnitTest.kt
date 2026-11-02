package com.example

import com.example.config.Io13PinRole
import com.example.config.RideMarkerType
import com.example.config.SegmentTarget
import com.example.config.SensorAxis
import com.example.config.SpeedSourceMode
import com.example.history.SessionExportHelper
import com.example.logging.BoschSampleEntity
import com.example.logging.ImuSessionSummary
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM unit tests verifying typed domain enums and lightweight SQL summary reliability reporting.
 */
class ExampleUnitTest {

    @Test
    fun domainEnums_parseWireValuesAndFallbacksCleanly() {
        assertEquals(SpeedSourceMode.GPS_ONLY, SpeedSourceMode.fromWireValue("GPS_ONLY"))
        assertEquals(SpeedSourceMode.GPS_ONLY, SpeedSourceMode.fromWireValue("gps"))
        assertEquals(SpeedSourceMode.BOSCH_ONLY, SpeedSourceMode.fromWireValue("BOSCH_ONLY"))
        assertEquals(SpeedSourceMode.BOSCH_PREFERRED, SpeedSourceMode.fromWireValue("BOSCH_PREFERRED"))
        assertEquals(SpeedSourceMode.BOSCH_PREFERRED, SpeedSourceMode.fromWireValue("unknown_mode"))

        assertEquals(SensorAxis.X, SensorAxis.fromWireValue("x"))
        assertEquals(SensorAxis.Y, SensorAxis.fromWireValue("Y"))
        assertEquals(SensorAxis.Z, SensorAxis.fromWireValue("z"))
        assertEquals(SensorAxis.Y, SensorAxis.fromWireValue(null))

        assertEquals(RideMarkerType.HARD_BRAKE, RideMarkerType.fromWireValue("HARD_BRAKE"))
        assertEquals(RideMarkerType.ROAD_BUMP, RideMarkerType.fromWireValue("road_bump"))
        assertEquals(RideMarkerType.BOSCH_GLITCH, RideMarkerType.fromWireValue("BOSCH_GLITCH"))

        assertEquals(Io13PinRole.PUSHBUTTON, Io13PinRole.fromWireValue("PUSHBUTTON"))
        assertEquals(Io13PinRole.REED_SWITCH, Io13PinRole.fromWireValue("REED_SWITCH"))
        assertEquals(Io13PinRole.STRIP_CH3, Io13PinRole.fromWireValue("STRIP_CH3"))
        assertEquals(Io13PinRole.DISABLED, Io13PinRole.fromWireValue("DISABLED"))

        assertEquals(SegmentTarget.BOTH, SegmentTarget.fromWireValue("BOTH"))
        assertEquals(listOf<Int?>(0), SegmentTarget.fromWireValue("SEG_0_IO16").targetSegmentIds)
        assertEquals(listOf<Int?>(1), SegmentTarget.fromWireValue("SEG_1_IO2").targetSegmentIds)
    }

    @Test
    fun sessionExportHelper_computesReliabilityReportFromLightweightSummary() {
        val summary = ImuSessionSummary(
            totalSamples = 500,
            freshBoschSamples = 450,
            maxBoschSpeedKmh = 28.4f
        )
        val boschPackets = listOf(
            BoschSampleEntity(
                sessionId = 1L,
                timestampMonotonicMs = 1000L,
                negotiatedMtu = 247,
                decodeSuccess = true,
                speedKmh = 24.0f,
                gpsSpeedKmhAtCapture = 23.5f,
                batterySoc = 82
            ),
            BoschSampleEntity(
                sessionId = 1L,
                timestampMonotonicMs = 2000L,
                negotiatedMtu = 247,
                decodeSuccess = true,
                speedKmh = 26.5f,
                gpsSpeedKmhAtCapture = 26.0f,
                batterySoc = 81
            )
        )

        val report = SessionExportHelper.computeBoschReportFromSummary(summary, boschPackets)
        assertEquals(2, report.totalNotifications)
        assertEquals(90.0f, report.boschFreshnessUptimePercent, 0.01f)
        assertEquals(0.5f, report.meanSpeedDeltaKmh, 0.01f)
        assertEquals(82, report.startBatterySoc)
        assertEquals(81, report.endBatterySoc)
    }
}
