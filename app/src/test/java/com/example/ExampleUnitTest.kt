package com.example

import com.example.config.AppConfig
import com.example.config.AppJson
import com.example.config.ChannelConfig
import com.example.config.Io13PinRole
import com.example.config.RideMarkerType
import com.example.config.SegmentConfig
import com.example.config.SegmentRole
import com.example.config.SegmentRoleType
import com.example.config.SensorAxis
import com.example.config.SpeedSourceMode
import com.example.config.WledLook
import com.example.config.withSequentialSegmentIds
import com.example.history.SessionExportHelper
import com.example.logging.BoschSampleEntity
import com.example.logging.ImuSessionSummary
import com.example.settings.validateSegmentCandidate
import com.example.wled.WledController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM unit tests verifying typed domain enums, SegmentRole / ChannelConfig / SegmentConfig
 * serialization, DataStore migration defaults, overlap validation, sequential segment IDs,
 * and WledController JSON payload builders.
 */
class ExampleUnitTest {

    @Test
    fun domainEnums_parseWireValuesAndFallbacksCleanly() {
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

        assertEquals(SegmentRoleType.AMBIENT, SegmentRole.MAIN_FRAME.roleType)
        assertEquals("Ambient", SegmentRole.MAIN_FRAME.displayLabel)
        assertEquals(SegmentRoleType.NOTIFICATION, SegmentRole.BRAKE_LIGHTS.roleType)
        assertEquals("Brake", SegmentRole.BRAKE_LIGHTS.displayLabel)
        assertEquals(SegmentRole.MAIN_FRAME, SegmentRole.fromWireValue("MAIN_FRAME"))
        assertEquals(SegmentRole.BRAKE_LIGHTS, SegmentRole.fromWireValue("BRAKE_LIGHTS"))
        assertEquals(SegmentRole.BRAKE_LIGHTS, SegmentRole.fromWireValue("Brake"))
        assertEquals(SegmentRole.MAIN_FRAME, SegmentRole.fromWireValue("UNKNOWN_ROLE"))
    }

    @Test
    fun appJsonMoshi_migratesLegacyHardwareConfigAndRoundTripsChannelsAndActiveInMusicMode() {
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
                "effectId": 0,
                "transitionMs": 0
              }
            }
        """.trimIndent()

        val adapter = AppJson.moshi.adapter(AppConfig::class.java)
        val parsedLegacy = adapter.fromJson(legacyJson)
        assertNotNull(parsedLegacy)
        assertEquals(SpeedSourceMode.BOSCH_PREFERRED, parsedLegacy!!.speedSource)
        assertEquals(SensorAxis.Z, parsedLegacy.sensorAxis)
        // Legacy JSON without "channels" defaults cleanly to emptyList()
        assertTrue(parsedLegacy.channels.isEmpty())

        // Now test full round-trip with channels, SegmentRole, and activeInMusicMode
        val configured = parsedLegacy.copy(
            channels = listOf(
                ChannelConfig(
                    gpioPin = 16,
                    ledCount = 60,
                    stripType = 22,
                    colorOrder = 0,
                    reversed = false,
                    segments = listOf(
                        SegmentConfig(
                            wledSegmentId = 0,
                            startLed = 0,
                            endLed = 60,
                            label = "Top Tube",
                            role = SegmentRole.MAIN_FRAME,
                            activeInMusicMode = true
                        )
                    )
                ),
                ChannelConfig(
                    gpioPin = 2,
                    ledCount = 30,
                    stripType = 30,
                    colorOrder = 1,
                    reversed = true,
                    segments = listOf(
                        SegmentConfig(
                            wledSegmentId = 1,
                            startLed = 0,
                            endLed = 30,
                            label = "Rear Tail",
                            role = SegmentRole.BRAKE_LIGHTS,
                            activeInMusicMode = false
                        )
                    )
                )
            )
        )

        val serialized = adapter.toJson(configured)
        val roundTripped = adapter.fromJson(serialized)
        assertNotNull(roundTripped)
        assertEquals(2, roundTripped!!.channels.size)
        assertEquals(16, roundTripped.channels[0].gpioPin)
        assertEquals(SegmentRole.MAIN_FRAME, roundTripped.channels[0].segments[0].role)
        assertTrue(roundTripped.channels[0].segments[0].activeInMusicMode)
        assertEquals(2, roundTripped.channels[1].gpioPin)
        assertEquals(SegmentRole.BRAKE_LIGHTS, roundTripped.channels[1].segments[0].role)
        assertFalse(roundTripped.channels[1].segments[0].activeInMusicMode)
    }

    @Test
    fun segmentValidationAndSequentialIds_enforceNonOverlappingRangesAndGlobalOrdering() {
        val existing = listOf(
            SegmentConfig(
                wledSegmentId = 99,
                startLed = 10,
                endLed = 30,
                label = "Center Frame",
                role = SegmentRole.MAIN_FRAME
            )
        )

        // Overlapping range [20, 40) is rejected
        val overlapErr = validateSegmentCandidate(
            existingSegments = existing,
            channelLedCount = 60,
            label = "Overlap Seg",
            startLed = 20,
            endLed = 40
        )
        assertNotNull(overlapErr)
        assertTrue(overlapErr!!.contains("Overlaps"))

        // Adjacent non-overlapping range [30, 60) is accepted
        val validErr = validateSegmentCandidate(
            existingSegments = existing,
            channelLedCount = 60,
            label = "Rear Seg",
            startLed = 30,
            endLed = 60
        )
        assertNull(validErr)

        // Sequential segment ID assignment across multiple channels
        val channels = listOf(
            ChannelConfig(
                gpioPin = 16,
                ledCount = 60,
                stripType = 22,
                colorOrder = 0,
                segments = listOf(
                    SegmentConfig(wledSegmentId = 7, startLed = 0, endLed = 30, label = "A", role = SegmentRole.MAIN_FRAME),
                    SegmentConfig(wledSegmentId = 9, startLed = 30, endLed = 60, label = "B", role = SegmentRole.MAIN_FRAME)
                )
            ),
            ChannelConfig(
                gpioPin = 2,
                ledCount = 20,
                stripType = 22,
                colorOrder = 0,
                segments = listOf(
                    SegmentConfig(wledSegmentId = 42, startLed = 0, endLed = 20, label = "C", role = SegmentRole.BRAKE_LIGHTS)
                )
            )
        ).withSequentialSegmentIds()

        assertEquals(0, channels[0].segments[0].wledSegmentId)
        assertEquals(1, channels[0].segments[1].wledSegmentId)
        assertEquals(2, channels[1].segments[0].wledSegmentId)
    }

    @Test
    fun wledController_buildsHardwareCfgAndChannelRelativeSegmentsAndPerSegmentTt() {
        val controller = WledController(null)
        val channels = listOf(
            ChannelConfig(
                gpioPin = 16,
                ledCount = 60,
                stripType = 22,
                colorOrder = 0,
                reversed = false,
                segments = listOf(
                    SegmentConfig(
                        wledSegmentId = 0,
                        startLed = 0,
                        endLed = 45,
                        label = "Main Frame",
                        role = SegmentRole.MAIN_FRAME
                    )
                )
            ),
            ChannelConfig(
                gpioPin = 2,
                ledCount = 30,
                stripType = 30,
                colorOrder = 1,
                reversed = true,
                segments = listOf(
                    SegmentConfig(
                        wledSegmentId = 1,
                        startLed = 5,
                        endLed = 25,
                        label = "Rear Tail",
                        role = SegmentRole.BRAKE_LIGHTS
                    )
                )
            )
        )

        val cfgJson = controller.buildHardwareCfgJson(channels)
        assertTrue(cfgJson.contains("\"total\":90"))
        assertTrue(cfgJson.contains("[16]"))
        assertTrue(cfgJson.contains("[2]"))

        // Channel 2 starts at global offset 60, so channel-relative [5, 25) maps to global [65, 85)
        val segJson = controller.buildSegmentLayoutJson(channels)
        val segLayout = AppJson.moshi.adapter(com.example.wled.WledSegmentLayoutPayload::class.java).fromJson(segJson)
        assertNotNull(segLayout)
        assertEquals(2, segLayout!!.seg.size)

        val seg0 = segLayout.seg[0]
        assertEquals(0, seg0.id)
        assertEquals(0, seg0.start)
        assertEquals(45, seg0.stop)
        assertEquals("Main Frame", seg0.n)

        val seg1 = segLayout.seg[1]
        assertEquals(1, seg1.id)
        assertEquals(65, seg1.start)
        assertEquals(85, seg1.stop)
        assertEquals("Rear Tail", seg1.n)

        // Verify sendComposedState JSON has per-segment tt and no top-level tt/transition
        val composedJson = controller.buildComposedStateJson(
            segmentLooks = mapOf(
                0 to WledLook(effectId = 0, colors = listOf("#00B4FF"), transitionMs = 600),
                1 to WledLook(effectId = 0, colors = listOf("#FF0000"), transitionMs = 0)
            ),
            brightness = 1.0f
        )
        assertFalse(composedJson.contains("\"transition\""))
        val parsedState = AppJson.moshi.adapter(com.example.wled.WledStatePayload::class.java).fromJson(composedJson)
        assertNotNull(parsedState)
        assertEquals(6, parsedState!!.seg!![0].tt)
        assertEquals(0, parsedState.seg!![1].tt)
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
