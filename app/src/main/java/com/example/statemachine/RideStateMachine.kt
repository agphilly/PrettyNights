package com.example.statemachine

enum class RideState {
    IDLE,
    CRUISING,
    BRAKING,
    BRAKING_HOLD
}

data class RideSample(
    val timestampMs: Long,
    val longitudinalAccel: Float, // Negative = deceleration (m/s^2)
    val speedKmh: Float, // km/h
    val isMoving: Boolean? = null,
    val bikeNotDriving: Boolean? = null
)

data class StateMachineOutput(
    val state: RideState,
    val brightness: Float,
    val info: String = "" // Diagnostic info
)

data class StateMachineTelemetry(
    val state: RideState = RideState.IDLE,
    val brightness: Float = 0.15f,
    val timeInStateMs: Long = 0L,
    val entrySpeedKmh: Float? = null,
    val decelActive: Boolean = false,
    val inEntryFade: Boolean = false,
    val holdFadePhase: String = "n/a",
    val logOnlyMode: Boolean = false,
    val activeSpeedSource: String = "GPS",
    val gpsSpeedKmh: Float = 0f,
    val boschSpeedKmh: Float? = null,
    val boschAgeMs: Long = -1L,
    val boschNotifications: Int = 0,
    val boschDecodeErrors: Int = 0,
    val boschNegotiatedMtu: Int = 23,
    val boschBatterySoc: Int? = null,
    val boschCadenceRpm: Int? = null,
    val boschRiderPowerW: Int? = null,
    val boschAmbientLux: Float? = null,
    val boschBikeLightRaw: Int? = null,
    val boschBikeNotDriving: Boolean? = null
)

data class StateMachineConfig(
    val globalMinBrightness: Float = 0.2f,
    val globalMaxBrightness: Float = 0.8f,
    val brakingIgnoresGlobalMax: Boolean = true,
    
    val idleEnterSpeedKmh: Float = 2.0f,
    val idleExitSpeedKmh: Float = 4.0f,
    
    val idleEntryFadeS: Float = 0.6f,
    val idleBrightness: Float = 0.15f,
    val requireBoschIdleConfirm: Boolean = false,
    
    val cruisingEntryFadeS: Float = 0.5f,
    val cruisingBrightness: Float = 0.55f,
    
    val brakingEntryFadeS: Float = 0.0f,
    val brakingDecelThreshold: Float = -1.5f,
    val brakingDebounceMs: Long = 180L,
    val brakingCooldownMs: Long = 350L,
    val brakingMinBrightness: Float = 0.6f,
    val brakingMaxBrightness: Float = 1.0f,
    val brakingGamma: Float = 1.6f,
    
    val brakingHoldDurationS: Float = 2.0f,
    val brakingFadeDurationS: Float = 1.0f,
    
    val useBoschCorroboration: Boolean = false
)

class RideStateMachine(private val configProvider: () -> StateMachineConfig) {

    private var currentState: RideState = RideState.IDLE
    private var lastStateChangeMs: Long = 0L

    // For deceleration debounce and cooldown tracking
    private var decelStartMs: Long? = null
    private var easedOffMs: Long? = null

    // Entry speed reference captured when BRAKING is entered
    private var entrySpeedKmh: Float? = null

    // For generic entry fade
    private var entryFadeStartBrightness: Float = 0.15f

    // Bookkeeping of last calculated clean (0-1) brightness before applying the global envelope mapping
    private var lastCleanBrightness: Float = 0.15f

    // Last output brightness (after envelope mapping)
    var lastOutputBrightness: Float = 0.15f
        private set

    var latestTelemetry: StateMachineTelemetry = StateMachineTelemetry()
        private set

    fun getAndResetState(initialTimeMs: Long) {
        currentState = RideState.IDLE
        lastStateChangeMs = initialTimeMs
        decelStartMs = null
        easedOffMs = null
        entrySpeedKmh = null
        
        val cfg = configProvider()
        lastCleanBrightness = cfg.idleBrightness
        entryFadeStartBrightness = cfg.idleBrightness
        
        // Emitted value mapped to physical envelope
        val floor = cfg.globalMinBrightness
        val ceiling = cfg.globalMaxBrightness
        lastOutputBrightness = floor + (ceiling - floor) * lastCleanBrightness

        latestTelemetry = StateMachineTelemetry(
            state = currentState,
            brightness = lastOutputBrightness,
            timeInStateMs = 0L,
            entrySpeedKmh = null,
            decelActive = false,
            inEntryFade = false,
            holdFadePhase = "n/a"
        )
    }

    fun tick(sample: RideSample): StateMachineOutput {
        val cfg = configProvider()
        val now = sample.timestampMs

        // 1. Deceleration Debounce & Cooldown Logic
        val isDecelPassingThreshold = sample.longitudinalAccel <= cfg.brakingDecelThreshold

        if (isDecelPassingThreshold) {
            if (decelStartMs == null) {
                decelStartMs = now
            }
            easedOffMs = null
        } else {
            val wasAlreadyDebounced = decelStartMs != null && (now - decelStartMs!! >= cfg.brakingDebounceMs)
            if (!wasAlreadyDebounced && currentState != RideState.BRAKING) {
                // Sub-debounce spike (e.g. pothole/road bump) ended before debounce elapsed: reset immediately
                decelStartMs = null
                easedOffMs = null
            } else {
                if (easedOffMs == null) {
                    easedOffMs = now
                }
                if (now - easedOffMs!! >= cfg.brakingCooldownMs) {
                    decelStartMs = null
                    easedOffMs = null
                }
            }
        }

        val isDecelAsserted = decelStartMs != null && (now - decelStartMs!! >= cfg.brakingDebounceMs)

        // 2. State Machine Transitions
        var nextState = currentState

        when (currentState) {
            RideState.IDLE -> {
                // Return to CRUISING once speed exceeds exit threshold
                if (sample.speedKmh > cfg.idleExitSpeedKmh) {
                    nextState = RideState.CRUISING
                }
            }

            RideState.CRUISING -> {
                // CRUISING splits to BRAKING or IDLE. Evaluation order matters: check braking first.
                if (isDecelAsserted) {
                    nextState = RideState.BRAKING
                } else {
                    // Check if speed dropped below entry threshold. Incorporate Bosch corroboration if enabled.
                    val speedQualifies = sample.speedKmh < cfg.idleEnterSpeedKmh
                    val boschSaysNotDriving = if (cfg.useBoschCorroboration && cfg.requireBoschIdleConfirm) {
                        sample.bikeNotDriving ?: true // null defaults to allowing idle
                    } else {
                        true
                    }
                    if (speedQualifies && boschSaysNotDriving) {
                        nextState = RideState.IDLE
                    }
                }
            }

            RideState.BRAKING -> {
                // From BRAKING, we can transitions to:
                if (sample.speedKmh < cfg.idleEnterSpeedKmh) {
                    // 1. BRAKING_HOLD: slowed to near-stop
                    nextState = RideState.BRAKING_HOLD
                } else if (!isDecelAsserted) {
                    // 2. CRUISING: deceleration eased off before stopping
                    nextState = RideState.CRUISING
                }
            }

            RideState.BRAKING_HOLD -> {
                val durationInHold = now - lastStateChangeMs
                val holdDurationMs = cfg.brakingHoldDurationS * 1000
                val fadeDurationMs = cfg.brakingFadeDurationS * 1000

                if (sample.speedKmh > cfg.idleExitSpeedKmh) {
                    // Rolling again cancels the stop sequence and returns to CRUISING
                    nextState = RideState.CRUISING
                } else if (durationInHold >= (holdDurationMs + fadeDurationMs)) {
                    // Entire hold and linear fade timer elapsed
                    nextState = RideState.IDLE
                }
            }
        }

        // 3. Handle Transitions and Capture Entry states
        if (nextState != currentState) {
            // Capture departing clean brightness for generic entry fade
            entryFadeStartBrightness = lastCleanBrightness

            // Entry speed lifecycle management (Section 5)
            if (nextState == RideState.BRAKING && currentState != RideState.BRAKING && currentState != RideState.BRAKING_HOLD) {
                entrySpeedKmh = sample.speedKmh
            } else if (nextState == RideState.CRUISING || nextState == RideState.IDLE) {
                entrySpeedKmh = null
            }

            // Capture the reached braking brightness on entry to BRAKING_HOLD (Section 5)
            if (nextState == RideState.BRAKING_HOLD) {
                // If transitioning to hold, we capture the last clean brightness
                holdStartBrightness = lastCleanBrightness
            }

            currentState = nextState
            lastStateChangeMs = now
        }

        // 4. State-specific Live Targets Calculation (in clean 0-1 space)
        val durationInCurrentState = now - lastStateChangeMs

        val liveTarget = when (currentState) {
            RideState.IDLE -> cfg.idleBrightness
            
            RideState.CRUISING -> cfg.cruisingBrightness
            
            RideState.BRAKING -> {
                val refSpeed = entrySpeedKmh ?: 1.0f
                val ratio = (1.0f - (sample.speedKmh / if (refSpeed > 0f) refSpeed else 1.0f)).coerceIn(0.0f, 1.0f)
                val powerRatio = Math.pow(ratio.toDouble(), cfg.brakingGamma.toDouble()).toFloat()
                cfg.brakingMinBrightness + (cfg.brakingMaxBrightness - cfg.brakingMinBrightness) * powerRatio
            }
            
            RideState.BRAKING_HOLD -> {
                val holdLimitMs = cfg.brakingHoldDurationS * 1000
                if (durationInCurrentState <= holdLimitMs) {
                    holdStartBrightness
                } else {
                    val fadeElapsed = durationInCurrentState - holdLimitMs
                    val fadeTotalMs = cfg.brakingFadeDurationS * 1000
                    val progress = (fadeElapsed.toFloat() / if (fadeTotalMs > 0) fadeTotalMs else 1.0f).coerceIn(0f, 1f)
                    holdStartBrightness + progress * (cfg.idleBrightness - holdStartBrightness)
                }
            }
        }

        // 5. Generic Entry Fade Calculation
        val entryFadeMs = when (currentState) {
            RideState.IDLE -> cfg.idleEntryFadeS * 1000
            RideState.CRUISING -> cfg.cruisingEntryFadeS * 1000
            RideState.BRAKING -> cfg.brakingEntryFadeS * 1000
            RideState.BRAKING_HOLD -> 0f // BRAKING_HOLD starts flat at reached brightness
        }

        val cleanBrightness = if (entryFadeMs > 0 && durationInCurrentState < entryFadeMs) {
            val progress = (durationInCurrentState.toFloat() / entryFadeMs).coerceIn(0f, 1f)
            entryFadeStartBrightness + progress * (liveTarget - entryFadeStartBrightness)
        } else {
            liveTarget
        }

        // Keep clean bookkeeping
        lastCleanBrightness = cleanBrightness

        // 6. Global Brightness Envelope Mapping (Section 5)
        val floor = cfg.globalMinBrightness
        val ceiling = if ((currentState == RideState.BRAKING || currentState == RideState.BRAKING_HOLD) && cfg.brakingIgnoresGlobalMax) {
            1.0f
        } else {
            cfg.globalMaxBrightness
        }

        val finalBrightness = (floor + (ceiling - floor) * cleanBrightness).coerceIn(0f, 1f)
        lastOutputBrightness = finalBrightness

        val holdFadePhaseValue = if (currentState == RideState.BRAKING_HOLD) {
            val holdLimitMs = cfg.brakingHoldDurationS * 1000
            if (durationInCurrentState <= holdLimitMs) "hold" else "fade"
        } else {
            "n/a"
        }

        val inEntryFadeValue = entryFadeMs > 0 && durationInCurrentState < entryFadeMs

        latestTelemetry = StateMachineTelemetry(
            state = currentState,
            brightness = finalBrightness,
            timeInStateMs = durationInCurrentState,
            entrySpeedKmh = entrySpeedKmh,
            decelActive = isDecelPassingThreshold,
            inEntryFade = inEntryFadeValue,
            holdFadePhase = holdFadePhaseValue
        )

        // Logging telemetry
        val diagnostics = "HzState=$currentState Speed=${String.format("%.1f", sample.speedKmh)}km/h " +
                "Accel=${String.format("%.1f", sample.longitudinalAccel)}m/s² Bright=${String.format("%.2f", finalBrightness)}"

        return StateMachineOutput(
            state = currentState,
            brightness = finalBrightness,
            info = diagnostics
        )
    }

    // Helper to store the start of a holding pattern brightness
    private var holdStartBrightness: Float = 0.8f

    fun getCurrentState(): RideState = currentState
}

