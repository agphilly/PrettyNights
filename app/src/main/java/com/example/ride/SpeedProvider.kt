package com.example.ride

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

// --- Speed Provider Interface ---
interface SpeedProvider {
    val speed: Float             // Current speed in m/s
    val lastUpdateTimeMs: Long   // SystemClock.elapsedRealtime() timestamp
    val isAvailable: Boolean
    fun start()
    fun stop()
}

// --- GPS Speed Provider ---
class GpsSpeedProvider(
    private val context: Context
) : SpeedProvider {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _speedState = MutableStateFlow(0f)
    val speedFlow = _speedState.asStateFlow()

    override var speed: Float = 0f
        private set

    override var lastUpdateTimeMs: Long = 0L
        private set

    override var isAvailable: Boolean = false
        private set

    private var locationCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(500)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation ?: return
                    speed = if (location.hasSpeed()) {
                        location.speed
                    } else {
                        elapsedSpeed(location)
                    }
                    lastUpdateTimeMs = SystemClock.elapsedRealtime()
                    _speedState.value = speed
                    isAvailable = true
                }

                override fun onLocationAvailability(availability: LocationAvailability) {
                    isAvailable = availability.isLocationAvailable
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                context.mainLooper
            )
        } catch (e: Exception) {
            Log.e("GpsSpeedProvider", "Failed to register GPS speed updates: ${e.message}")
            isAvailable = false
        }
    }

    override fun stop() {
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (_: Exception) {
            }
        }
        locationCallback = null
        speed = 0f
        _speedState.value = 0f
        isAvailable = false
    }

    private var lastLocation: Location? = null
    private var lastLocTime: Long = 0L

    private fun elapsedSpeed(location: Location): Float {
        val prev = lastLocation
        val prevTime = lastLocTime
        lastLocation = location
        lastLocTime = SystemClock.elapsedRealtime()

        if (prev == null || prevTime == 0L) return 0f
        val distance = prev.distanceTo(location)
        val timeSec = (lastLocTime - prevTime) / 1000f
        if (timeSec <= 0.1f) return 0f
        return (distance / timeSec).coerceIn(0f, 45f)
    }
}

/**
 * Authentic Bosch eBike Systems (The smart system) Live Data Interface V1.0 BLE Speed & Telemetry Provider.
 *
 * Connects via BLE GATT / GAP Service Solicitation (`0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4`) and decodes
 * `com.bosch.ebike.LiveData` notifications on characteristic `0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4`.
 */
class BoschSpeedProvider(
    private val context: Context? = null
) : SpeedProvider {

    private val _liveStateFlow = MutableStateFlow(BoschLiveDataState())
    val liveStateFlow: StateFlow<BoschLiveDataState> = _liveStateFlow.asStateFlow()

    val liveState: BoschLiveDataState
        get() = _liveStateFlow.value

    private val packetListeners = CopyOnWriteArrayList<(BoschLiveDataPacket, BoschLiveDataState) -> Unit>()

    private var bleClient: BoschLiveDataClient? = null

    override val speed: Float
        get() = (_liveStateFlow.value.speedKmh / 3.6f)

    val speedKmh: Float
        get() = _liveStateFlow.value.speedKmh

    override val lastUpdateTimeMs: Long
        get() = _liveStateFlow.value.lastUpdateMonotonicMs

    override val isAvailable: Boolean
        get() {
            val state = _liveStateFlow.value
            val now = SystemClock.elapsedRealtime()
            return state.lastUpdateMonotonicMs > 0L && (now - state.lastUpdateMonotonicMs) < BOSCH_STALE_TIMEOUT_MS
        }

    fun addPacketListener(listener: (BoschLiveDataPacket, BoschLiveDataState) -> Unit) {
        packetListeners.add(listener)
    }

    fun removePacketListener(listener: (BoschLiveDataPacket, BoschLiveDataState) -> Unit) {
        packetListeners.remove(listener)
    }

    override fun start() {
        _liveStateFlow.value = BoschLiveDataState()
        val appContext = context ?: return
        if (bleClient == null) {
            bleClient = BoschLiveDataClient(
                context = appContext,
                onPacketReceived = { packet ->
                    handleDecodedPacket(packet)
                },
                onConnectionStateChanged = { connected, deviceName, mtu, advertising ->
                    _liveStateFlow.value = _liveStateFlow.value.copy(
                        isConnected = connected,
                        connectedDeviceName = deviceName,
                        negotiatedMtu = mtu,
                        isAdvertisingSolicitation = advertising
                    )
                }
            )
        }
        bleClient?.start()
    }

    override fun stop() {
        bleClient?.stop()
        _liveStateFlow.value = BoschLiveDataState()
    }

    /**
     * Processes a raw `com.bosch.ebike.LiveData` protobuf payload (from BLE notification or test injection).
     */
    fun injectLiveDataPayload(
        payload: ByteArray,
        negotiatedMtu: Int = _liveStateFlow.value.negotiatedMtu.coerceAtLeast(BoschLiveDataSpec.REQUIRED_ATT_MTU),
        timestampMonotonicMs: Long = SystemClock.elapsedRealtime()
    ): BoschLiveDataPacket {
        val packet = BoschLiveDataProtoDecoder.decode(
            payload = payload,
            timestampMonotonicMs = timestampMonotonicMs,
            negotiatedMtu = negotiatedMtu
        )
        handleDecodedPacket(packet)
        return packet
    }

    private fun handleDecodedPacket(packet: BoschLiveDataPacket) {
        val updatedState = _liveStateFlow.value.mergePacket(packet)
        _liveStateFlow.value = updatedState
        for (listener in packetListeners) {
            listener(packet, updatedState)
        }
    }

    companion object {
        const val BOSCH_STALE_TIMEOUT_MS = 4000L
    }
}

/**
 * Fused Speed Provider.
 *
 * CRITICAL INVARIANT: Both `gpsProvider` and `boschProvider` ALWAYS start and record in parallel
 * on every ride regardless of `getSourcePref()`. The `getSourcePref()` setting ONLY determines
 * which speed stream drives the live `RideStateMachine` transitions.
 */
class FusedSpeedProvider(
    val gpsProvider: GpsSpeedProvider,
    val boschProvider: BoschSpeedProvider,
    private val getSourcePref: () -> String // "GPS_ONLY" / "GPS", "BOSCH_PREFERRED", "BOSCH_ONLY"
) : SpeedProvider {

    val activeSpeedSource: String
        get() {
            val pref = getSourcePref()
            val now = SystemClock.elapsedRealtime()
            return when (pref) {
                "GPS", "GPS_ONLY" -> "GPS"
                "BOSCH_ONLY" -> "BOSCH"
                else -> { // "BOSCH_PREFERRED"
                    val boschLast = boschProvider.lastUpdateTimeMs
                    val boschFresh = boschLast > 0L && (now - boschLast) < BoschSpeedProvider.BOSCH_STALE_TIMEOUT_MS
                    if (boschFresh) "BOSCH" else "GPS_FALLBACK"
                }
            }
        }

    override val speed: Float
        get() {
            return when (activeSpeedSource) {
                "BOSCH" -> boschProvider.speed
                else -> gpsProvider.speed
            }
        }

    override val lastUpdateTimeMs: Long
        get() {
            return when (activeSpeedSource) {
                "BOSCH" -> boschProvider.lastUpdateTimeMs
                else -> gpsProvider.lastUpdateTimeMs
            }
        }

    override val isAvailable: Boolean
        get() {
            val pref = getSourcePref()
            return when (pref) {
                "GPS", "GPS_ONLY" -> gpsProvider.isAvailable
                "BOSCH_ONLY" -> boschProvider.isAvailable
                else -> gpsProvider.isAvailable || boschProvider.isAvailable
            }
        }

    override fun start() {
        // Always start BOTH providers so 100% of GPS + Bosch Live Data V1.0 telemetry is logged
        gpsProvider.start()
        boschProvider.start()
    }

    override fun stop() {
        gpsProvider.stop()
        boschProvider.stop()
    }
}
