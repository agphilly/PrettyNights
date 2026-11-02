package com.example.config

import com.squareup.moshi.FromJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Type-safe domain enums for PrettyNights configuration, hardware provisioning,
 * and ride telemetry, with backwards-compatible wire string conversion.
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

enum class SegmentTarget(
    val wireValue: String,
    val displayLabel: String,
    val targetSegmentIds: List<Int?>
) {
    BOTH("BOTH", "Both (IO16 + IO2)", listOf(null)),
    SEG_0_IO16("SEG_0_IO16", "Seg 0 (IO16)", listOf(0)),
    SEG_1_IO2("SEG_1_IO2", "Seg 1 (IO2)", listOf(1));

    companion object {
        fun fromWireValue(raw: String?): SegmentTarget {
            return entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) }
                ?: BOTH
        }
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
    fun segTargetToJson(v: SegmentTarget): String = v.wireValue

    @FromJson
    fun segTargetFromJson(s: String): SegmentTarget = SegmentTarget.fromWireValue(s)

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
