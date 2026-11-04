package com.example.config

import com.squareup.moshi.FromJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Type-safe domain enums and segment/channel models for PrettyNights configuration,
 * hardware provisioning, and ride telemetry, with backwards-compatible wire string conversion.
 */

enum class SpeedSourceMode(val wireValue: String, val displayLabel: String) {
    GPS_ONLY("GPS_ONLY", "GPS Only"),
    BOSCH_PREFERRED("BOSCH_PREFERRED", "Bosch Preferred (GPS Fallback)"),
    BOSCH_ONLY("BOSCH_ONLY", "Bosch Only");

    companion object {
        fun fromWireValue(raw: String?): SpeedSourceMode {
            return when (raw?.trim()?.uppercase()) {
                "GPS", "GPS_ONLY" -> GPS_ONLY
                "BOSCH", "BOSCH_ONLY" -> BOSCH_ONLY
                "BOSCH_PREFERRED" -> BOSCH_PREFERRED
                else -> GPS_ONLY
            }
        }
    }
}

enum class ActiveSpeedSource {
    GPS,
    BOSCH,
    GPS_FALLBACK
}

enum class SensorAxis(val wireValue: String) {
    X("X"),
    Y("Y"),
    Z("Z");

    companion object {
        fun fromWireValue(raw: String?): SensorAxis {
            return when (raw?.trim()?.uppercase()) {
                "X" -> X
                "Z" -> Z
                else -> Y
            }
        }
    }
}

enum class RideMarkerType(val wireValue: String, val displayLabel: String) {
    HARD_BRAKE("HARD_BRAKE", "Hard Brake"),
    LIGHT_BRAKE("LIGHT_BRAKE", "Light Brake"),
    ROAD_BUMP("ROAD_BUMP", "Road Bump"),
    BOSCH_GLITCH("BOSCH_GLITCH", "Bosch Glitch");

    companion object {
        fun fromWireValueOrNull(raw: String?): RideMarkerType? =
            entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) }

        fun fromWireValue(raw: String?): RideMarkerType =
            fromWireValueOrNull(raw) ?: HARD_BRAKE
    }
}

enum class Io13PinRole(val wireValue: String, val displayLabel: String, val wledButtonType: Int) {
    PUSHBUTTON("PUSHBUTTON", "Pushbutton", 2),
    REED_SWITCH("REED_SWITCH", "Reed Switch", 4),
    STRIP_CH3("STRIP_CH3", "3rd LED Strip", 0),
    DISABLED("DISABLED", "Disabled", 0);

    companion object {
        fun fromWireValue(raw: String?): Io13PinRole {
            return entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) }
                ?: PUSHBUTTON
        }
    }
}

enum class SegmentRoleType {
    AMBIENT,
    NOTIFICATION
}

enum class SegmentRole(val roleType: SegmentRoleType, val displayLabel: String) {
    MAIN_FRAME(SegmentRoleType.AMBIENT, "Ambient"),
    BRAKE_LIGHTS(SegmentRoleType.NOTIFICATION, "Brake");
    // Future: TAIL_LIGHT, INDICATOR_LEFT, INDICATOR_RIGHT
    // displayLabel drives the role dropdown in the UI — add new roles here only

    companion object {
        fun fromWireValue(raw: String?): SegmentRole {
            val trimmed = raw?.trim() ?: return MAIN_FRAME
            return entries.firstOrNull {
                it.name.equals(trimmed, ignoreCase = true) ||
                    it.displayLabel.equals(trimmed, ignoreCase = true)
            } ?: MAIN_FRAME
        }
    }
}

data class SegmentConfig(
    val wledSegmentId: Int,              // Segment ID sent in WLED /json/state
    val startLed: Int,                   // LED offset from channel start
    val endLed: Int,                     // Exclusive end
    val label: String,                   // User-facing physical label e.g. "Rear Tail"
    val role: SegmentRole,
    val activeInMusicMode: Boolean = false  // Phase 2: persist and round-trip only; no logic yet
)

data class ChannelConfig(
    val gpioPin: Int,
    val ledCount: Int,
    val stripType: Int,           // 22 = WS281x RGB, 30 = SK6812 RGBW
    val colorOrder: Int,          // 0 = GRB, 1 = RGB, 2 = BRG
    val reversed: Boolean = false,
    val segments: List<SegmentConfig> = emptyList()
    // Channel is identified by gpioPin; there is no user-editable channel name.
)

/**
 * Assigns globally sequential wledSegmentId values (0, 1, 2 ... N-1) across all channels
 * in declaration order. Used at save time and in buildSegmentLayoutJson().
 */
fun List<ChannelConfig>.withSequentialSegmentIds(): List<ChannelConfig> {
    var nextId = 0
    return map { channel ->
        val updatedSegments = channel.segments.map { seg ->
            val assigned = seg.copy(wledSegmentId = nextId)
            nextId++
            assigned
        }
        channel.copy(segments = updatedSegments)
    }
}

object DomainEnumJson {
    @ToJson
    fun speedSourceToJson(v: SpeedSourceMode): String = v.wireValue

    @FromJson
    fun speedSourceFromJson(s: String): SpeedSourceMode = SpeedSourceMode.fromWireValue(s)

    @ToJson
    fun axisToJson(v: SensorAxis): String = v.wireValue

    @FromJson
    fun axisFromJson(s: String): SensorAxis = SensorAxis.fromWireValue(s)

    @ToJson
    fun io13ToJson(v: Io13PinRole): String = v.wireValue

    @FromJson
    fun io13FromJson(s: String): Io13PinRole = Io13PinRole.fromWireValue(s)

    @ToJson
    fun segmentRoleToJson(v: SegmentRole): String = v.name

    @FromJson
    fun segmentRoleFromJson(s: String): SegmentRole = SegmentRole.fromWireValue(s)

    @ToJson
    fun markerToJson(v: RideMarkerType?): String? = v?.wireValue

    @FromJson
    fun markerFromJson(s: String?): RideMarkerType? = RideMarkerType.fromWireValueOrNull(s)
}

object AppJson {
    val moshi: Moshi = Moshi.Builder()
        .add(DomainEnumJson)
        .addLast(KotlinJsonAdapterFactory())
        .build()
}
