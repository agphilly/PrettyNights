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

// --- Speed Provider Interface ---
interface SpeedProvider {
    val speed: Float             // Current speed in m/s
    val lastUpdateTimeMs: Long  // SystemClock.elapsedRealtime() timestamp
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
                    if (location.hasSpeed()) {
                        speed = location.speed
                    } else {
                        // Calculate speed from custom coordinates if speed isn't exposed
                        speed = elapsedSpeed(location)
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
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        speed = 0f
        _speedState.value = 0f
        isAvailable = false
    }

    // Fallback speed calculation in case system speed is omitted
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
        return (distance / timeSec).coerceIn(0f, 45f) // cap at 45 m/s (~160 km/h) for safety
    }
}

// --- Bosch eBike BLE Speed Provider Placeholder ---
class BoschSpeedProvider : SpeedProvider {

    override var speed: Float = 0f
    override var lastUpdateTimeMs: Long = 0L
    override var isAvailable: Boolean = true

    private var activeSimThread: Thread? = null

    // For demonstration and future validation, Bosch eBike can be simulated
    // with speed updates or left at 0f (representing BLE placeholder status).
    // Let's add a clean simulator that increases speed on startup and returns to 0 when stopped
    override fun start() {
        isAvailable = true
        lastUpdateTimeMs = SystemClock.elapsedRealtime()
        
        activeSimThread = Thread {
            try {
                var simSpeed = 0.0f
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    // Let's simulate a bike speed fluctuating between 2.0 and 8.0 m/s
                    simSpeed = if (simSpeed > 8f) 2f else simSpeed + 0.8f
                    speed = simSpeed
                    lastUpdateTimeMs = SystemClock.elapsedRealtime()
                }
            } catch (e: InterruptedException) {
                // Stopped safely
            }
        }.apply { start() }
    }

    override fun stop() {
        activeSimThread?.interrupt()
        activeSimThread = null
        speed = 0f
        lastUpdateTimeMs = 0L
    }
}

// --- Fused Speed Provider ---
class FusedSpeedProvider(
    private val gpsProvider: GpsSpeedProvider,
    private val boschProvider: BoschSpeedProvider,
    private val getSourcePref: () -> String // "GPS", "BOSCH_PREFERRED", "BOSCH_ONLY"
) : SpeedProvider {

    override val speed: Float
        get() {
            val pref = getSourcePref()
            val now = SystemClock.elapsedRealtime()
            
            return when (pref) {
                "GPS" -> gpsProvider.speed
                "BOSCH_ONLY" -> boschProvider.speed
                else -> { // "BOSCH_PREFERRED"
                    val boschAgeMs = now - boschProvider.lastUpdateTimeMs
                    // Section 4: "using Bosch while fresh, falling back to GPS when it goes stale"
                    if (boschProvider.isAvailable && boschAgeMs < 4000L) {
                        boschProvider.speed
                    } else {
                        // Bosch went stale (e.g. connection dropped) -> fall back to GPS
                        gpsProvider.speed
                    }
                }
            }
        }

    override val lastUpdateTimeMs: Long
        get() {
            val pref = getSourcePref()
            return when (pref) {
                "GPS" -> gpsProvider.lastUpdateTimeMs
                "BOSCH_ONLY" -> boschProvider.lastUpdateTimeMs
                else -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now - boschProvider.lastUpdateTimeMs < 4000L) boschProvider.lastUpdateTimeMs else gpsProvider.lastUpdateTimeMs
                }
            }
        }

    override val isAvailable: Boolean
        get() {
            val pref = getSourcePref()
            return when (pref) {
                "GPS" -> gpsProvider.isAvailable
                "BOSCH_ONLY" -> boschProvider.isAvailable
                else -> gpsProvider.isAvailable || boschProvider.isAvailable
            }
        }

    override fun start() {
        gpsProvider.start()
        boschProvider.start()
    }

    override fun stop() {
        gpsProvider.stop()
        boschProvider.stop()
    }
}
