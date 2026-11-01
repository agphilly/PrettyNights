package com.example.ride

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Bosch eBike Systems — The smart system — Live Data Interface V1.0 (Release v19)
 *
 * Official Specification Reference: 20260501_LiveDataInterface_V1_28042026.pdf
 * - Base UUID: 0000xxxx-eaa2-11e9-81b4-2a2ae2dbcce4
 * - eBike Live Data Service UUID: 0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4
 * - Live Data Characteristic UUID: 0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4 (Read | Notify, Enc Mode 1 Level 2)
 * - Payload: Protocol Buffers (proto3) message `com.bosch.ebike.LiveData`
 */
object BoschLiveDataSpec {
    val EBIKE_LIVE_DATA_SERVICE_UUID: UUID =
        UUID.fromString("0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4")
    val LIVE_DATA_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val REQUIRED_ATT_MTU = 247

    // Present-field bitmask flags for LDI-002 partial notification merging (Section 2.2.4.3)
    const val MASK_SPEED = 1 shl 0                   // Field 1: optional uint32 speed (1/100 km/h)
    const val MASK_CADENCE = 1 shl 1                 // Field 2: optional int32 cadence (1 rpm)
    const val MASK_RIDER_POWER = 1 shl 2             // Field 5: optional uint32 rider_power (1 W)
    const val MASK_AMBIENT_BRIGHTNESS = 1 shl 3      // Field 9: optional uint32 ambient_brightness (1/1000 lux)
    const val MASK_BATTERY_SOC = 1 shl 4             // Field 10: optional uint32 battery_soc (1 %)
    const val MASK_TIME = 1 shl 5                    // Field 11: optional int64 time (UTC sec)
    const val MASK_ODOMETER = 1 shl 6                // Field 12: optional uint32 odometer (1 m)
    const val MASK_BIKE_LIGHT = 1 shl 7              // Field 17: optional LightState bike_light
    const val MASK_SYSTEM_LOCKED = 1 shl 8           // Field 21: optional bool system_locked
    const val MASK_CHARGER_CONNECTED = 1 shl 9       // Field 22: optional bool charger_connected
    const val MASK_LIGHT_RESERVE_STATE = 1 shl 10    // Field 23: optional bool light_reserve_state
    const val MASK_DIAGNOSIS_ACTIVE = 1 shl 11       // Field 24: optional bool diagnosis_program_active
    const val MASK_BIKE_NOT_DRIVING = 1 shl 12       // Field 25: optional bool bike_not_driving

    fun formatLightState(rawCode: Int?): String {
        return when (rawCode) {
            null -> "n/a"
            0 -> "INVALID (0)"
            1 -> "OFF (1)"
            2 -> "ON (2)"
            else -> "UNKNOWN ($rawCode)" // Per Section 2.2.4.2: preserve unrecognized enum integers
        }
    }
}

/**
 * Represents a single decoded `com.bosch.ebike.LiveData` notification packet from characteristic `eb21`.
 */
data class BoschLiveDataPacket(
    val timestampMonotonicMs: Long,
    val negotiatedMtu: Int,
    val payloadSizeBytes: Int,
    val decodeSuccess: Boolean,
    val presentFieldsMask: Int = 0,
    val speedHundredthsKmh: Int? = null,             // Field 1 (0..65535 -> 0..655.35 km/h)
    val cadenceRpm: Int? = null,                     // Field 2 (-32768..32767 rpm)
    val riderPowerWatts: Int? = null,                // Field 5 (0..65535 W)
    val ambientMilliLux: Long? = null,               // Field 9 (1/1000 lux)
    val batterySocPercent: Int? = null,              // Field 10 (0..100 %)
    val ebikeTimeUtcSec: Long? = null,               // Field 11 (UTC seconds since 1970-01-01)
    val odometerMeters: Long? = null,                // Field 12 (0..4294967295 m)
    val bikeLightRaw: Int? = null,                   // Field 17 (0=INVALID, 1=OFF, 2=ON, or future enum)
    val systemLocked: Boolean? = null,               // Field 21
    val chargerConnected: Boolean? = null,           // Field 22
    val lightReserveState: Boolean? = null,          // Field 23
    val diagnosisProgramActive: Boolean? = null,     // Field 24
    val bikeNotDriving: Boolean? = null              // Field 25 (true = standing still, false = moving)
) {
    val speedKmh: Float?
        get() = speedHundredthsKmh?.let { it / 100f }

    val ambientLux: Float?
        get() = ambientMilliLux?.let { it / 1000f }

    fun hasField(maskBit: Int): Boolean = (presentFieldsMask and maskBit) != 0
}

/**
 * Merged running state of `com.bosch.ebike.LiveData` across partial notifications (Section 2.2.4.3 & LDI-002).
 */
data class BoschLiveDataState(
    val connectedDeviceName: String? = null,
    val isAdvertisingSolicitation: Boolean = false,
    val isConnected: Boolean = false,
    val negotiatedMtu: Int = 23,
    val lastUpdateMonotonicMs: Long = 0L,
    val totalNotifications: Int = 0,
    val decodeErrors: Int = 0,
    val speedKmh: Float = 0f,
    val cadenceRpm: Int? = null,
    val riderPowerWatts: Int? = null,
    val ambientLux: Float? = null,
    val batterySocPercent: Int? = null,
    val ebikeTimeUtcSec: Long? = null,
    val odometerMeters: Long? = null,
    val bikeLightRaw: Int? = null,
    val systemLocked: Boolean? = null,
    val chargerConnected: Boolean? = null,
    val lightReserveState: Boolean? = null,
    val diagnosisProgramActive: Boolean? = null,
    val bikeNotDriving: Boolean? = null
) {
    fun mergePacket(packet: BoschLiveDataPacket): BoschLiveDataState {
        if (!packet.decodeSuccess) {
            return copy(
                negotiatedMtu = packet.negotiatedMtu,
                totalNotifications = totalNotifications + 1,
                decodeErrors = decodeErrors + 1
            )
        }
        return copy(
            negotiatedMtu = packet.negotiatedMtu,
            lastUpdateMonotonicMs = packet.timestampMonotonicMs,
            totalNotifications = totalNotifications + 1,
            speedKmh = if (packet.hasField(BoschLiveDataSpec.MASK_SPEED)) (packet.speedKmh ?: speedKmh) else speedKmh,
            cadenceRpm = if (packet.hasField(BoschLiveDataSpec.MASK_CADENCE)) packet.cadenceRpm else cadenceRpm,
            riderPowerWatts = if (packet.hasField(BoschLiveDataSpec.MASK_RIDER_POWER)) packet.riderPowerWatts else riderPowerWatts,
            ambientLux = if (packet.hasField(BoschLiveDataSpec.MASK_AMBIENT_BRIGHTNESS)) packet.ambientLux else ambientLux,
            batterySocPercent = if (packet.hasField(BoschLiveDataSpec.MASK_BATTERY_SOC)) packet.batterySocPercent else batterySocPercent,
            ebikeTimeUtcSec = if (packet.hasField(BoschLiveDataSpec.MASK_TIME)) packet.ebikeTimeUtcSec else ebikeTimeUtcSec,
            odometerMeters = if (packet.hasField(BoschLiveDataSpec.MASK_ODOMETER)) packet.odometerMeters else odometerMeters,
            bikeLightRaw = if (packet.hasField(BoschLiveDataSpec.MASK_BIKE_LIGHT)) packet.bikeLightRaw else bikeLightRaw,
            systemLocked = if (packet.hasField(BoschLiveDataSpec.MASK_SYSTEM_LOCKED)) packet.systemLocked else systemLocked,
            chargerConnected = if (packet.hasField(BoschLiveDataSpec.MASK_CHARGER_CONNECTED)) packet.chargerConnected else chargerConnected,
            lightReserveState = if (packet.hasField(BoschLiveDataSpec.MASK_LIGHT_RESERVE_STATE)) packet.lightReserveState else lightReserveState,
            diagnosisProgramActive = if (packet.hasField(BoschLiveDataSpec.MASK_DIAGNOSIS_ACTIVE)) packet.diagnosisProgramActive else diagnosisProgramActive,
            bikeNotDriving = if (packet.hasField(BoschLiveDataSpec.MASK_BIKE_NOT_DRIVING)) packet.bikeNotDriving else bikeNotDriving
        )
    }
}

/**
 * Zero-allocation Protobuf (proto3 wire format) decoder & test encoder for `com.bosch.ebike.LiveData`.
 *
 * Implements:
 * - Section 2.2.4.1 (Message Changes): Safely skips unknown field numbers across wire types 0, 1, 2, 5.
 * - Section 2.2.4.2 (Enumerations): Preserves unrecognized integer values for `bike_light`.
 * - Section 2.2.4.3 & Section 6 (LDI-002): Tracks explicit field presence via `presentFieldsMask`.
 * - Section 6 (LDI-003): Detects truncated varints/fields when ATT_MTU < 247 and marks `decodeSuccess = false`.
 */
object BoschLiveDataProtoDecoder {

    fun decode(
        payload: ByteArray,
        timestampMonotonicMs: Long = SystemClock.elapsedRealtime(),
        negotiatedMtu: Int = BoschLiveDataSpec.REQUIRED_ATT_MTU
    ): BoschLiveDataPacket {
        var index = 0
        val limit = payload.size
        var mask = 0

        var speedHundredths: Int? = null
        var cadence: Int? = null
        var riderPower: Int? = null
        var ambientMilliLux: Long? = null
        var batterySoc: Int? = null
        var ebikeTime: Long? = null
        var odometer: Long? = null
        var bikeLight: Int? = null
        var systemLocked: Boolean? = null
        var chargerConnected: Boolean? = null
        var lightReserve: Boolean? = null
        var diagnosisActive: Boolean? = null
        var bikeNotDriving: Boolean? = null

        var decodeSuccess = true

        while (index < limit) {
            // Read tag varint
            var tag = 0L
            var shift = 0
            var tagComplete = false
            while (index < limit && shift < 64) {
                val b = payload[index++].toInt() and 0xFF
                tag = tag or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) {
                    tagComplete = true
                    break
                }
                shift += 7
            }
            if (!tagComplete || tag == 0L) {
                decodeSuccess = false
                break
            }

            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()

            when (wireType) {
                0 -> { // Varint
                    var value = 0L
                    var vShift = 0
                    var varintComplete = false
                    while (index < limit && vShift < 64) {
                        val b = payload[index++].toInt() and 0xFF
                        value = value or ((b.toLong() and 0x7FL) shl vShift)
                        if ((b and 0x80) == 0) {
                            varintComplete = true
                            break
                        }
                        vShift += 7
                    }
                    if (!varintComplete) {
                        // LDI-003: Notification was truncated mid-varint (e.g., ATT_MTU < 247)
                        decodeSuccess = false
                        break
                    }

                    when (fieldNumber) {
                        1 -> {
                            speedHundredths = (value and 0xFFFFFFFFL).toInt().coerceIn(0, 65535)
                            mask = mask or BoschLiveDataSpec.MASK_SPEED
                        }
                        2 -> {
                            cadence = value.toInt()
                            mask = mask or BoschLiveDataSpec.MASK_CADENCE
                        }
                        5 -> {
                            riderPower = (value and 0xFFFFFFFFL).toInt().coerceIn(0, 65535)
                            mask = mask or BoschLiveDataSpec.MASK_RIDER_POWER
                        }
                        9 -> {
                            ambientMilliLux = value and 0xFFFFFFFFL
                            mask = mask or BoschLiveDataSpec.MASK_AMBIENT_BRIGHTNESS
                        }
                        10 -> {
                            batterySoc = (value and 0xFFFFFFFFL).toInt().coerceIn(0, 100)
                            mask = mask or BoschLiveDataSpec.MASK_BATTERY_SOC
                        }
                        11 -> {
                            ebikeTime = value
                            mask = mask or BoschLiveDataSpec.MASK_TIME
                        }
                        12 -> {
                            odometer = value and 0xFFFFFFFFL
                            mask = mask or BoschLiveDataSpec.MASK_ODOMETER
                        }
                        17 -> {
                            bikeLight = value.toInt()
                            mask = mask or BoschLiveDataSpec.MASK_BIKE_LIGHT
                        }
                        21 -> {
                            systemLocked = value != 0L
                            mask = mask or BoschLiveDataSpec.MASK_SYSTEM_LOCKED
                        }
                        22 -> {
                            chargerConnected = value != 0L
                            mask = mask or BoschLiveDataSpec.MASK_CHARGER_CONNECTED
                        }
                        23 -> {
                            lightReserve = value != 0L
                            mask = mask or BoschLiveDataSpec.MASK_LIGHT_RESERVE_STATE
                        }
                        24 -> {
                            diagnosisActive = value != 0L
                            mask = mask or BoschLiveDataSpec.MASK_DIAGNOSIS_ACTIVE
                        }
                        25 -> {
                            bikeNotDriving = value != 0L
                            mask = mask or BoschLiveDataSpec.MASK_BIKE_NOT_DRIVING
                        }
                        else -> {
                            // Section 2.2.4.1: Unknown varint field — safely ignored
                        }
                    }
                }
                1 -> { // 64-bit fixed
                    if (index + 8 > limit) {
                        decodeSuccess = false
                        break
                    }
                    index += 8
                }
                2 -> { // Length-delimited (string, bytes, embedded messages)
                    var length = 0
                    var lShift = 0
                    var lenComplete = false
                    while (index < limit && lShift < 32) {
                        val b = payload[index++].toInt() and 0xFF
                        length = length or ((b and 0x7F) shl lShift)
                        if ((b and 0x80) == 0) {
                            lenComplete = true
                            break
                        }
                        lShift += 7
                    }
                    if (!lenComplete || length < 0 || index + length > limit) {
                        decodeSuccess = false
                        break
                    }
                    index += length
                }
                5 -> { // 32-bit fixed
                    if (index + 4 > limit) {
                        decodeSuccess = false
                        break
                    }
                    index += 4
                }
                else -> {
                    // Invalid or unsupported wire type (likely MTU truncation corruption)
                    decodeSuccess = false
                    break
                }
            }
        }

        return BoschLiveDataPacket(
            timestampMonotonicMs = timestampMonotonicMs,
            negotiatedMtu = negotiatedMtu,
            payloadSizeBytes = payload.size,
            decodeSuccess = decodeSuccess,
            presentFieldsMask = mask,
            speedHundredthsKmh = speedHundredths,
            cadenceRpm = cadence,
            riderPowerWatts = riderPower,
            ambientMilliLux = ambientMilliLux,
            batterySocPercent = batterySoc,
            ebikeTimeUtcSec = ebikeTime,
            odometerMeters = odometer,
            bikeLightRaw = bikeLight,
            systemLocked = systemLocked,
            chargerConnected = chargerConnected,
            lightReserveState = lightReserve,
            diagnosisProgramActive = diagnosisActive,
            bikeNotDriving = bikeNotDriving
        )
    }

    /**
     * Helper to serialize a `com.bosch.ebike.LiveData` protobuf payload for unit tests and diagnostic verification.
     */
    fun encode(
        speedHundredthsKmh: Int? = null,
        cadenceRpm: Int? = null,
        riderPowerWatts: Int? = null,
        ambientMilliLux: Long? = null,
        batterySocPercent: Int? = null,
        ebikeTimeUtcSec: Long? = null,
        odometerMeters: Long? = null,
        bikeLightRaw: Int? = null,
        systemLocked: Boolean? = null,
        chargerConnected: Boolean? = null,
        lightReserveState: Boolean? = null,
        diagnosisProgramActive: Boolean? = null,
        bikeNotDriving: Boolean? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun writeVarint(value: Long) {
            var v = value
            while (true) {
                if ((v and 0x7FL.inv()) == 0L) {
                    out.write(v.toInt())
                    return
                } else {
                    out.write((v.toInt() and 0x7F) or 0x80)
                    v = v ushr 7
                }
            }
        }

        fun writeFieldVarint(fieldNumber: Int, value: Long) {
            val tag = (fieldNumber.toLong() shl 3) or 0L
            writeVarint(tag)
            writeVarint(value)
        }

        speedHundredthsKmh?.let { writeFieldVarint(1, it.toLong() and 0xFFFFFFFFL) }
        cadenceRpm?.let { writeFieldVarint(2, it.toLong()) }
        riderPowerWatts?.let { writeFieldVarint(5, it.toLong() and 0xFFFFFFFFL) }
        ambientMilliLux?.let { writeFieldVarint(9, it and 0xFFFFFFFFL) }
        batterySocPercent?.let { writeFieldVarint(10, it.toLong() and 0xFFFFFFFFL) }
        ebikeTimeUtcSec?.let { writeFieldVarint(11, it) }
        odometerMeters?.let { writeFieldVarint(12, it and 0xFFFFFFFFL) }
        bikeLightRaw?.let { writeFieldVarint(17, it.toLong()) }
        systemLocked?.let { writeFieldVarint(21, if (it) 1L else 0L) }
        chargerConnected?.let { writeFieldVarint(22, if (it) 1L else 0L) }
        lightReserveState?.let { writeFieldVarint(23, if (it) 1L else 0L) }
        diagnosisProgramActive?.let { writeFieldVarint(24, if (it) 1L else 0L) }
        bikeNotDriving?.let { writeFieldVarint(25, if (it) 1L else 0L) }

        return out.toByteArray()
    }
}

/**
 * Android BLE GAP Peripheral Advertiser + GATT Client for Bosch eBike Systems Live Data Interface V1.0.
 *
 * Implements:
 * - Section 2.1.3 (GAP Role): Advertises Service Solicitation UUID `0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4`
 *   so the Bosch eBike (GAP Central `ebike_<num>`) can discover and connect to the phone.
 * - Section 2.1.5.5 (Attribute Caching): Performs fresh `discoverServices()` on every connection and
 *   discovers `0000eb20-...` -> `0000eb21-...` dynamically without caching handles.
 * - Section 6 (LDI-003 Workaround): Requests `ATT_MTU = 247` immediately upon connection before enabling
 *   `eb21` notifications so Release v19 eBikes do not silently truncate protobuf messages.
 */
@SuppressLint("MissingPermission")
class BoschLiveDataClient(
    private val context: Context,
    private val onPacketReceived: (BoschLiveDataPacket) -> Unit,
    private val onConnectionStateChanged: (Boolean, String?, Int, Boolean) -> Unit
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeGatt: BluetoothGatt? = null
    private var negotiatedMtu: Int = 23
    private var isAdvertising: Boolean = false
    private var isConnected: Boolean = false
    private var connectedDeviceName: String? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            onConnectionStateChanged(isConnected, connectedDeviceName, negotiatedMtu, isAdvertising)
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.w("BoschLiveDataClient", "Service Solicitation advertising failed: $errorCode")
            onConnectionStateChanged(isConnected, connectedDeviceName, negotiatedMtu, isAdvertising)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                connectedDeviceName = gatt.device?.name ?: gatt.device?.address ?: "Bosch eBike"
                onConnectionStateChanged(true, connectedDeviceName, negotiatedMtu, isAdvertising)
                // LDI-003 Workaround (Section 6): Request ATT_MTU = 247 immediately on connection
                // before enabling notifications so Release v19 firmware does not truncate frames.
                val requested = gatt.requestMtu(BoschLiveDataSpec.REQUIRED_ATT_MTU)
                if (!requested) {
                    // Fallback if MTU request fails to dispatch: still discover services
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                connectedDeviceName = null
                negotiatedMtu = 23
                onConnectionStateChanged(false, null, negotiatedMtu, isAdvertising)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            onConnectionStateChanged(isConnected, connectedDeviceName, negotiatedMtu, isAdvertising)
            // Section 2.1.5.5: Always perform fresh GATT service discovery (never cache handles)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(BoschLiveDataSpec.EBIKE_LIVE_DATA_SERVICE_UUID) ?: return
            val liveDataChar =
                service.getCharacteristic(BoschLiveDataSpec.LIVE_DATA_CHARACTERISTIC_UUID) ?: return

            gatt.setCharacteristicNotification(liveDataChar, true)
            val cccd = liveDataChar.getDescriptor(BoschLiveDataSpec.CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }
        }

        // Android 13+ (API 33+) callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BoschLiveDataSpec.LIVE_DATA_CHARACTERISTIC_UUID) {
                handleRawNotification(value)
            }
        }

        // Android 12 and below callback
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == BoschLiveDataSpec.LIVE_DATA_CHARACTERISTIC_UUID
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleRawNotification(value)
            }
        }
    }

    private fun handleRawNotification(bytes: ByteArray) {
        val packet = BoschLiveDataProtoDecoder.decode(
            payload = bytes,
            timestampMonotonicMs = SystemClock.elapsedRealtime(),
            negotiatedMtu = negotiatedMtu
        )
        onPacketReceived(packet)
    }

    fun start() {
        try {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                return
            }

            // 1. Start GAP Peripheral advertising with Service Solicitation UUID `0000eb20-...` (Section 2.1.3)
            startServiceSolicitationAdvertising(adapter)

            // 2. Connect GATT Client to bonded Bosch eBike (`ebike_<num>` or bonded device exposing `eb20`)
            connectToBondedBoschEbike(adapter)
        } catch (se: SecurityException) {
            Log.w("BoschLiveDataClient", "Bluetooth permission not granted yet: ${se.message}")
        } catch (e: Exception) {
            Log.e("BoschLiveDataClient", "Failed to start BoschLiveDataClient: ${e.message}")
        }
    }

    private fun startServiceSolicitationAdvertising(adapter: BluetoothAdapter) {
        try {
            val bleAdvertiser = adapter.bluetoothLeAdvertiser ?: return
            advertiser = bleAdvertiser

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val dataBuilder = AdvertiseData.Builder()
                .setIncludeDeviceName(true)

            val solicitationUuid = ParcelUuid(BoschLiveDataSpec.EBIKE_LIVE_DATA_SERVICE_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dataBuilder.addServiceSolicitationUuid(solicitationUuid)
            } else {
                dataBuilder.addServiceUuid(solicitationUuid)
            }

            bleAdvertiser.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
        } catch (e: Exception) {
            Log.w("BoschLiveDataClient", "Could not start BLE solicitation advertising: ${e.message}")
        }
    }

    private fun connectToBondedBoschEbike(adapter: BluetoothAdapter) {
        try {
            val bondedDevices = adapter.bondedDevices ?: emptySet()
            val targetDevice = bondedDevices.firstOrNull { device ->
                val name = device.name?.lowercase() ?: ""
                val uuids = device.uuids?.map { it.uuid } ?: emptyList()
                name.startsWith("ebike_") ||
                    name.contains("bosch") ||
                    uuids.contains(BoschLiveDataSpec.EBIKE_LIVE_DATA_SERVICE_UUID)
            } ?: return

            activeGatt?.close()
            activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                targetDevice.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                targetDevice.connectGatt(context, true, gattCallback)
            }
        } catch (e: Exception) {
            Log.w("BoschLiveDataClient", "Could not connect to bonded Bosch eBike: ${e.message}")
        }
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        advertiser = null
        isAdvertising = false

        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: Exception) {
        }
        activeGatt = null
        isConnected = false
        connectedDeviceName = null
        negotiatedMtu = 23
        onConnectionStateChanged(false, null, negotiatedMtu, false)
    }
}
