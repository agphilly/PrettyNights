package com.example

import com.example.config.AppConfig
import com.example.config.AppJson
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
 * Pure JVM unit tests verifying typed domain enums, safe fallbacks, Moshi backwards-compatible
 * JSON serialization, and lightweight SQL summary reliability reporting.
 */
class ExampleUnitTest {

    @Test
    fun domainEnums_parseWireValuesAndFallbacksCleanly() {
        // Item 4: SpeedSourceMode falls back to GPS_ONLY for unrecognized strings
        assertEquals(SpeedSourceMode.GPS_ONLY, SpeedSourceMode.fromWireValue("GPS_ONLY"))
        assertEquals(SpeedSourceMode.GPS_ONLY, SpeedSourceMode.fromWireValue("gps"))
        assertEquals(SpeedSourceMode.BOSCH_ONLY, SpeedSourceMode.fromWireValue("BOSCH_ONLY"))
        assertEquals(SpeedSourceMode.BOSCH_PREFERRED, SpeedSourceMode.fromWireValue("BOSCH_PREFERRED"))
        assertEquals(SpeedSourceMode.GPS_ONLY, SpeedSourceMode.fromWireValue("unknown_mode"))
        assertEquals(SpeedSourceMode.GPS_ONLY, SpeedSourceMode.fromWireValue(null))

        assertEquals(SensorAxis.X, SensorAxis.fromWireValue("x"))
        assertEquals(SensorAxis.Y, SensorAxis.fromWireValue("Y"))
        assertEquals(SensorAxis.Z, SensorAxis.fromWireValue("z"))
        assertEquals(SensorAxis.Y, SensorAxis.fromWireValue(null))

        // Item 4: RideMarkerType.fromWireValueOrNull returns null on unknown strings instead of faking BOSCH_GLITCH
        assertEquals(RideMarkerType.HARD_BRAKE, RideMarkerType.fromWireValueOrNull("HARD_BRAKE"))
        assertEquals(RideMarkerType.LIGHT_BRAKE, RideMarkerType.fromWireValueOrNull("light_brake"))
        assertEquals(RideMarkerType.ROAD_BUMP, RideMarkerType.fromWireValueOrNull("road_bump"))
        assertEquals(RideMarkerType.BOSCH_GLITCH, RideMarkerType.fromWireValueOrNull("BOSCH_GLITCH"))
        assertNull(RideMarkerType.fromWireValueOrNull("CORRUPT_DB_VALUE"))
        assertNull(RideMarkerType.fromWireValueOrNull(""))
        assertNull(RideMarkerType.fromWireValueOrNull(null))

        assertEquals(Io13PinRole.PUSHBUTTON, Io13PinRole.fromWireValue("PUSHBUTTON"))
        assertEquals(Io13PinRole.REED_SWITCH, Io13PinRole.fromWireValue("REED_SWITCH"))
        assertEquals(Io13PinRole.STRIP_CH3, Io13PinRole.fromWireValue("STRIP_CH3"))
        assertEquals(Io13PinRole.DISABLED, Io13PinRole.fromWireValue("DISABLED"))

        assertEquals(SegmentTarget.BOTH, SegmentTarget.fromWireValue("BOTH"))
        assertEquals(listOf<Int?>(0), SegmentTarget.fromWireValue("SEG_0_IO16").targetSegmentIds)
        assertEquals(listOf<Int?>(1), SegmentTarget.fromWireValue("SEG_1_IO2").targetSegmentIds)
    }

    @Test
    fun appJsonMoshi_deserializesLegacyWireStringsIntoTypedEnums() {
        val legacyJson = """
            {
              "wledHost": "192.168.1.50",
              "speedSource": "BOSCH_PREFERRED",
              "sensorAxis": "Z",
              "sensorSign": 1,
              "hardwareConfig": {
                "maxCurrentMa": 8000,
                "ch1LedCount": 72,
                "io13Role": "STRIP_CH3",
                "ch3LedCount": 16
              },
              "brakingLook": {
                "type": "custom",
                "effectId": 0,
                "targetSegments": "SEG_1_IO2"
              }
            }
        """.trimIndent()

        val adapter = AppJson.moshi.adapter(AppConfig::class.java)
        val parsed = adapter.fromJson(legacyJson)
        assertNotNull(parsed)
        assertEquals(SpeedSourceMode.BOSCH_PREFERRED, parsed!!.speedSource)
        assertEquals(SensorAxis.Z, parsed.sensorAxis)
        assertEquals(Io13PinRole.STRIP_CH3, parsed.hardwareConfig.io13Role)
        assertEquals(SegmentTarget.SEG_1_IO2, parsed.brakingLook.targetSegments)

        // Round-trip back to JSON and verify wire values are preserved
        val serialized = adapter.toJson(parsed)
        assertTrue(serialized.contains("\"speedSource\":\"BOSCH_PREFERRED\""))
        assertTrue(serialized.contains("\"sensorAxis\":\"Z\""))
        assertTrue(serialized.contains("\"io13Role\":\"STRIP_CH3\""))
        assertTrue(serialized.contains("\"targetSegments\":\"SEG_1_IO2\""))
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
