package com.example

import com.example.statemachine.*
import org.junit.Assert.*
import org.junit.Test

class RideStateMachineTest {

    private fun createMachine() = RideStateMachine { StateMachineConfig() }

    @Test
    fun testInitialStateAndTransitions() {
        val sm = createMachine()
        sm.getAndResetState(0L)

        // Rule: Start in IDLE
        assertEquals(RideState.IDLE, sm.getCurrentState())

        // Rule: Under IDLE, strong decel reading is ignored (Section 7: No IDLE -> BRAKING)
        val tickDecelInIdle = sm.tick(
            RideSample(
                timestampMs = 100L,
                longitudinalAccel = -2.5f, // past -1.5 limit
                speedKmh = 0.0f
            )
        )
        assertEquals(RideState.IDLE, tickDecelInIdle.state)

        // Rule: Transition IDLE -> CRUISING when speed > idleExitSpeedKmh (4.0)
        val tickBelowExitSpeed = sm.tick(
            RideSample(
                timestampMs = 200L,
                longitudinalAccel = 0f,
                speedKmh = 3.5f // below 4.0
            )
        )
        assertEquals(RideState.IDLE, tickBelowExitSpeed.state)

        val tickAboveExitSpeed = sm.tick(
            RideSample(
                timestampMs = 300L,
                longitudinalAccel = 0f,
                speedKmh = 4.2f // above 4.0
            )
        )
        assertEquals(RideState.CRUISING, tickAboveExitSpeed.state)
    }

    @Test
    fun testCruisingToIdleHysteresis() {
         val sm = createMachine()
         sm.getAndResetState(0L)

         // Move to CRUISING first
         sm.tick(RideSample(100L, 0f, 5.0f))
         assertEquals(RideState.CRUISING, sm.getCurrentState())

         // Drop speed to 3.0f (which is between idleEnterSpeedKmh (2.0) and idleExitSpeedKmh (4.0)) - should stay CRUISING due to hysteresis
         sm.tick(RideSample(200L, 0f, 3.0f))
         assertEquals(RideState.CRUISING, sm.getCurrentState())

         // Drop below idleEnterSpeedKmh (2.0) - should transition to IDLE
         sm.tick(RideSample(300L, 0f, 1.8f))
         assertEquals(RideState.IDLE, sm.getCurrentState())
    }

    @Test
    fun testBrakingDebounceAndCooldown() {
        val sm = createMachine()
        sm.getAndResetState(0L)

         // Move to CRUISING first
         sm.tick(RideSample(100L, 0f, 10.0f))
         assertEquals(RideState.CRUISING, sm.getCurrentState())

         // Under CRUISING, start decelerating heavily
         // Debounce window is 180ms
         sm.tick(RideSample(200L, -2.0f, 9.5f))
         assertEquals(RideState.CRUISING, sm.getCurrentState()) // not yet debounced at 100ms delta

         sm.tick(RideSample(400L, -2.0f, 9.0f))
         assertEquals(RideState.BRAKING, sm.getCurrentState()) // debounced because delta is 200ms (> 180ms)

         // Check cooldown: brief easing off shorter than 350ms keeps braking active
         sm.tick(RideSample(450L, 0.0f, 8.8f)) // Eased off at 450ms
         assertEquals(RideState.BRAKING, sm.getCurrentState()) // stays active (within cooldown of 50ms)

         sm.tick(RideSample(750L, 0.0f, 8.8f)) // at 750ms, elapsed easing is 300ms (< 350ms)
         assertEquals(RideState.BRAKING, sm.getCurrentState()) // still starts active

         sm.tick(RideSample(810L, 0.0f, 8.8f)) // at 810ms, elapsed easing is 360ms (> 350ms cooldown)
         // So braking ceases and next state should be CRUISING
         sm.tick(RideSample(820L, 0.0f, 8.8f))
         assertEquals(RideState.CRUISING, sm.getCurrentState())
    }

    @Test
    fun testBrakingHoldAndTransitions() {
         val sm = createMachine()
         sm.getAndResetState(0L)

         // Cruising -> Decel debounced -> Braking
         sm.tick(RideSample(100L, 0f, 12.0f))
         sm.tick(RideSample(200L, -2.5f, 11.0f))
         sm.tick(RideSample(400L, -2.5f, 9.0f))
         assertEquals(RideState.BRAKING, sm.getCurrentState())

         // Slow down below 2.0 km/h is a transition to BRAKING_HOLD
         sm.tick(RideSample(500L, -2.5f, 1.5f))
         assertEquals(RideState.BRAKING_HOLD, sm.getCurrentState())

         // Re-braking from within hold is NOT handled (should stay hold and not register new braking onset)
         sm.tick(RideSample(600L, -3.0f, 1.2f))
         assertEquals(RideState.BRAKING_HOLD, sm.getCurrentState())

         // Let hold (2.0s) and fade (1.0s) timers elapse
         // Total hold + fade duration is 3.0s (3000ms). Entered hold at 500L, so 3500L should transition to IDLE.
         sm.tick(RideSample(3400L, 0f, 0.5f))
         assertEquals(RideState.BRAKING_HOLD, sm.getCurrentState()) // 2900ms elapsed since entering hold
         assertEquals("fade", sm.latestTelemetry.holdFadePhase)

         sm.tick(RideSample(3600L, 0f, 0.5f))
         assertEquals(RideState.IDLE, sm.getCurrentState()) // 3100ms elapsed since entering hold (IDLE)
         assertEquals("n/a", sm.latestTelemetry.holdFadePhase)
    }

    @Test
    fun testBrakingGammaBrightnessAndGlobalCeilingBypass() {
        val config = StateMachineConfig(
            globalMinBrightness = 0.2f,
            globalMaxBrightness = 0.8f,
            brakingIgnoresGlobalMax = true,
            brakingMinBrightness = 0.6f,
            brakingMaxBrightness = 1.0f,
            brakingGamma = 1.6f
        )
        val sm = RideStateMachine { config }
        sm.getAndResetState(0L)

        // Enter CRUISING at 20 km/h
        sm.tick(RideSample(100L, 0f, 20.0f))
        assertEquals(RideState.CRUISING, sm.getCurrentState())

        // Assert BRAKING at 20 km/h -> entrySpeedKmh captured as 20.0f
        sm.tick(RideSample(200L, -2.5f, 20.0f))
        val onsetOut = sm.tick(RideSample(400L, -2.5f, 20.0f))
        assertEquals(RideState.BRAKING, onsetOut.state)
        assertEquals(20.0f, sm.latestTelemetry.entrySpeedKmh ?: 0f, 0.01f)

        // Slow down to 2.5 km/h while still braking -> brightness should exceed 0.80 globalMaxBrightness
        val hardBrakeOut = sm.tick(RideSample(600L, -2.5f, 2.5f))
        assertEquals(RideState.BRAKING, hardBrakeOut.state)
        assertTrue(
            "Expected braking brightness (${hardBrakeOut.brightness}) to bypass globalMaxBrightness (0.8)",
            hardBrakeOut.brightness > 0.85f
        )
    }

    @Test
    fun testBoschCorroborationDefersIdleUntilStopped() {
        val config = StateMachineConfig(
            useBoschCorroboration = true,
            requireBoschIdleConfirm = true
        )
        val sm = RideStateMachine { config }
        sm.getAndResetState(0L)

        // Enter CRUISING
        sm.tick(RideSample(100L, 0f, 10.0f))
        assertEquals(RideState.CRUISING, sm.getCurrentState())

        // Speed drops below 2.0 km/h, but Bosch says bikeNotDriving = false -> remain CRUISING
        sm.tick(RideSample(200L, 0f, 1.0f, bikeNotDriving = false))
        assertEquals(RideState.CRUISING, sm.getCurrentState())

        // Once Bosch confirms bikeNotDriving = true -> transition to IDLE
        sm.tick(RideSample(300L, 0f, 1.0f, bikeNotDriving = true))
        assertEquals(RideState.IDLE, sm.getCurrentState())
    }

    @Test
    fun testLockControllerSafetyStates() {
        val lockController = com.example.safety.LockController()
        assertFalse(lockController.isLocked.value)

        // Service running in IDLE -> unlocked
        lockController.updateState(RideState.IDLE, isServiceRunning = true)
        assertFalse(lockController.isLocked.value)

        // Service running in CRUISING / BRAKING / BRAKING_HOLD -> locked
        lockController.updateState(RideState.CRUISING, isServiceRunning = true)
        assertTrue(lockController.isLocked.value)

        lockController.updateState(RideState.BRAKING, isServiceRunning = true)
        assertTrue(lockController.isLocked.value)

        // Force unlock on stop -> unlocked
        lockController.forceUnlock()
        assertFalse(lockController.isLocked.value)
    }

    @Test
    fun testMusicServiceFftAndWledV2PacketSchema() {
        val fftSize = 1024
        val sampleRate = 44100.0
        val targetFreq = 1000.0
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)

        for (i in 0 until fftSize) {
            val sample = kotlin.math.sin(2.0 * kotlin.math.PI * targetFreq * i / sampleRate)
            val hann = 0.5 * (1.0 - kotlin.math.cos(2.0 * kotlin.math.PI * i / (fftSize - 1)))
            real[i] = sample * hann
            imag[i] = 0.0
        }

        com.example.ride.MusicService.fftInPlace(real, imag)

        var maxMag = -1.0
        var maxBin = -1
        for (i in 1 until fftSize / 2) {
            val mag = kotlin.math.sqrt(real[i] * real[i] + imag[i] * imag[i])
            if (mag > maxMag) {
                maxMag = mag
                maxBin = i
            }
        }
        val detectedFreq = maxBin * (sampleRate / fftSize)
        // Bin resolution is 44100 / 1024 = 43.066 Hz; 1000 Hz should land within 1 bin (~43 Hz)
        assertEquals(targetFreq, detectedFreq, 45.0)

        val myVals = ByteArray(32) { 128.toByte() }
        val fftResult = ByteArray(16) { (it * 10).toByte() }
        val packet = com.example.ride.MusicService.packV2Packet(
            myVals = myVals,
            sampleAgc = 200,
            sampleRaw = 180,
            sampleAvg = 150.5f,
            samplePeak = 1,
            fftResult = fftResult,
            fftMagnitude = maxMag,
            fftMajorPeak = detectedFreq
        )

        // Verify 84-byte Little-Endian WLED V2 Packet structure
        assertEquals(84, packet.size)
        assertEquals('0'.code.toByte(), packet[0])
        assertEquals('0'.code.toByte(), packet[1])
        assertEquals('0'.code.toByte(), packet[2])
        assertEquals('0'.code.toByte(), packet[3])
        assertEquals('2'.code.toByte(), packet[4])
        assertEquals(0.toByte(), packet[5])

        val buffer = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(200, buffer.getInt(38))
        assertEquals(180, buffer.getInt(42))
        assertEquals(150.5f, buffer.getFloat(46), 0.01f)
        assertEquals(1.toByte(), buffer.get(50))
        assertEquals(0.toByte(), buffer.get(51))
        assertEquals(detectedFreq, buffer.getDouble(76), 0.01)
    }

    @Test
    fun testBoschLiveDataV1ProtoDecoderAllTwelveFieldsAndLdi002Merging() {
        // 1. Encode a full com.bosch.ebike.LiveData packet with all 12 fields from the V1.0 (Release v19) spec
        val payload = com.example.ride.BoschLiveDataProtoDecoder.encode(
            speedHundredthsKmh = 2485,           // Field 1: 24.85 km/h
            cadenceRpm = 78,                     // Field 2: 78 rpm
            riderPowerWatts = 215,               // Field 5: 215 W
            ambientMilliLux = 125400L,           // Field 9: 125.4 lux
            batterySocPercent = 84,              // Field 10: 84 %
            ebikeTimeUtcSec = 1777593600L,       // Field 11: UTC seconds
            odometerMeters = 452310L,            // Field 12: 452,310 m
            bikeLightRaw = 2,                    // Field 17: LIGHT_STATE_ON (2)
            systemLocked = false,                // Field 21
            chargerConnected = false,            // Field 22
            lightReserveState = false,           // Field 23
            diagnosisProgramActive = false,      // Field 24
            bikeNotDriving = false               // Field 25: false = moving
        )

        val decoded = com.example.ride.BoschLiveDataProtoDecoder.decode(
            payload = payload,
            timestampMonotonicMs = 1000L,
            negotiatedMtu = 247
        )

        assertTrue(decoded.decodeSuccess)
        assertEquals(2485, decoded.speedHundredthsKmh)
        assertEquals(24.85f, decoded.speedKmh ?: 0f, 0.001f)
        assertEquals(78, decoded.cadenceRpm)
        assertEquals(215, decoded.riderPowerWatts)
        assertEquals(125.4f, decoded.ambientLux ?: 0f, 0.001f)
        assertEquals(84, decoded.batterySocPercent)
        assertEquals(1777593600L, decoded.ebikeTimeUtcSec)
        assertEquals(452310L, decoded.odometerMeters)
        assertEquals(2, decoded.bikeLightRaw)
        assertEquals(false, decoded.systemLocked)
        assertEquals(false, decoded.chargerConnected)
        assertEquals(false, decoded.lightReserveState)
        assertEquals(false, decoded.diagnosisProgramActive)
        assertEquals(false, decoded.bikeNotDriving)

        // 2. Merge into BoschLiveDataState, then apply a partial notification (LDI-002) that only updates speed & bike_not_driving
        val initialState = com.example.ride.BoschLiveDataState().mergePacket(decoded)
        assertEquals(24.85f, initialState.speedKmh, 0.001f)
        assertEquals(84, initialState.batterySocPercent)
        assertEquals(false, initialState.bikeNotDriving)

        val partialPayload = com.example.ride.BoschLiveDataProtoDecoder.encode(
            speedHundredthsKmh = 0,
            bikeNotDriving = true
        )
        val partialPacket = com.example.ride.BoschLiveDataProtoDecoder.decode(
            payload = partialPayload,
            timestampMonotonicMs = 1300L,
            negotiatedMtu = 247
        )
        val mergedState = initialState.mergePacket(partialPacket)

        assertEquals(0.0f, mergedState.speedKmh, 0.001f)
        assertEquals(true, mergedState.bikeNotDriving)
        // Unchanged omitted fields must remain preserved from previous notifications
        assertEquals(84, mergedState.batterySocPercent)
        assertEquals(215, mergedState.riderPowerWatts)
        assertEquals(2, mergedState.bikeLightRaw)

        // 3. Verify LDI-003 truncated varint detection (e.g., ATT_MTU < 247 truncating mid-varint)
        val truncatedPayload = payload.copyOfRange(0, payload.size - 1).apply {
            this[this.size - 1] = 0x80.toByte() // dangling continuation bit
        }
        val truncatedPacket = com.example.ride.BoschLiveDataProtoDecoder.decode(
            payload = truncatedPayload,
            timestampMonotonicMs = 1500L,
            negotiatedMtu = 23
        )
        assertFalse(truncatedPacket.decodeSuccess)
    }

    @Test
    fun testTuningReplaySimulatorWithMarkersAndBoschStream() {
        val samples = mutableListOf<com.example.logging.ImuSampleEntity>()
        // 0..1000ms: Cruising at 20 km/h
        for (t in 0L..1000L step 20L) {
            samples.add(
                com.example.logging.ImuSampleEntity(
                    sessionId = 1L,
                    timestampMonotonicMs = t,
                    accelX = 0f,
                    accelY = 0f,
                    accelZ = 0f,
                    fusedAcceleration = 0f,
                    calibratedAxis = "Y",
                    calibratedSign = -1,
                    speedKmh = 20f,
                    gpsSpeedKmh = 20f,
                    boschSpeedKmh = 20.5f,
                    boschAgeMs = 100L,
                    boschBikeNotDriving = false,
                    stateName = "CRUISING"
                )
            )
        }
        // 1020..1400ms: Hard braking (-3.0 m/s²) -> exceeds 180ms debounce
        for (t in 1020L..1400L step 20L) {
            samples.add(
                com.example.logging.ImuSampleEntity(
                    sessionId = 1L,
                    timestampMonotonicMs = t,
                    accelX = 0f,
                    accelY = 3.0f,
                    accelZ = 0f,
                    fusedAcceleration = -3.0f,
                    calibratedAxis = "Y",
                    calibratedSign = -1,
                    speedKmh = 12f,
                    gpsSpeedKmh = 12f,
                    boschSpeedKmh = 11.5f,
                    boschAgeMs = 100L,
                    boschBikeNotDriving = false,
                    stateName = "BRAKING"
                )
            )
        }
        // 2000ms: Brief 40ms road bump (-2.5 m/s²) -> shorter than 180ms debounce, should be rejected
        for (t in 1420L..2500L step 20L) {
            val isBumpSpike = t == 2000L || t == 2020L
            samples.add(
                com.example.logging.ImuSampleEntity(
                    sessionId = 1L,
                    timestampMonotonicMs = t,
                    accelX = 0f,
                    accelY = 0f,
                    accelZ = 0f,
                    fusedAcceleration = if (isBumpSpike) -2.5f else 0f,
                    calibratedAxis = "Y",
                    calibratedSign = -1,
                    speedKmh = 15f,
                    gpsSpeedKmh = 15f,
                    boschSpeedKmh = 15f,
                    boschAgeMs = 100L,
                    boschBikeNotDriving = false,
                    stateName = "CRUISING"
                )
            )
        }

        val markers = listOf(
            com.example.logging.RideMarkerEntity(sessionId = 1L, timestampMonotonicMs = 1200L, markerType = "HARD_BRAKE"),
            com.example.logging.RideMarkerEntity(sessionId = 1L, timestampMonotonicMs = 2020L, markerType = "ROAD_BUMP")
        )

        val result = com.example.tuning.RideReplaySimulator.runReplay(
            imuSamples = samples,
            markers = markers,
            config = StateMachineConfig(),
            replaySpeedSource = "BOSCH"
        )

        assertEquals(1, result.simulatedBrakeEpisodes)
        assertEquals(1, result.brakeMarkersDetected)
        assertEquals(1, result.roadBumpMarkersRejected)
    }
}
