package com.example.ride

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.config.ConfigManager
import com.example.config.RideMarkerType
import com.example.config.SensorAxis
import com.example.config.WledLook
import com.example.logging.*
import com.example.safety.LockController
import com.example.statemachine.RideSample
import com.example.statemachine.RideState
import com.example.statemachine.RideStateMachine
import com.example.statemachine.StateMachineOutput
import com.example.statemachine.StateMachineTelemetry
import com.example.wled.WledController
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

class RideOrchestrator(
    private val context: Context,
    private val configManager: ConfigManager,
    private val lockController: LockController,
    private val logRepository: LogRepository,
    private val speedProvider: SpeedProvider,
    private val wledController: WledController,
    private val onSpeedSourceCached: (String) -> Unit = { source ->
        try {
            PrettyNightsApp.instance.cachedSpeedSource = source
        } catch (_: Exception) {
        }
    }
) : SensorEventListener {

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lifecycleMutex = Mutex()
    private val intervalMutex = Mutex()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // Reusable Moshi instance & adapter (instantiated once instead of per WLED interval transition)
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val wledLookAdapter = moshi.adapter(WledLook::class.java)

    // Cross-thread session & state machine properties
    @Volatile
    private var activeSessionId: Long? = null

    @Volatile
    var isRunning: Boolean = false

    @Volatile
    private var activeConfig: AppConfig = AppConfig()
    private val stateMachine = RideStateMachine { activeConfig.stateMachine }

    // WLED Sink throttling state (MANDATORY per System Design Section 5 & 6)
    @Volatile
    private var lastSentLook: WledLook? = null

    @Volatile
    private var lastSentState: RideState? = null

    @Volatile
    private var lastSentBrightness: Float = -1f

    @Volatile
    private var lastSendTimeMs: Long = 0L

    // Synchronous transition tracking on sensor thread; DB writes serialized via intervalMutex
    @Volatile
    private var lastLoggedState: RideState? = null

    @Volatile
    private var lastLoggedLook: WledLook? = null

    private var currentStateIntervalId: Long? = null
    private var currentWledIntervalId: Long? = null
    private var activeStateInterval: StateIntervalEntity? = null
    private var activeWledInterval: WledIntervalEntity? = null

    // For IMU sample high-speed buffer batch-writing to Room to prevent database lag
    private val imuSampleBuffer = ConcurrentLinkedQueue<ImuSampleEntity>()
    private var batchLoggingJob: Job? = null
    private var configCollectionJob: Job? = null

    private var onDiagnosticsUpdate: ((String, RideState, Float) -> Unit)? = null

    private val _telemetryFlow = MutableStateFlow<StateMachineTelemetry?>(null)
    val telemetryFlow: StateFlow<StateMachineTelemetry?> = _telemetryFlow.asStateFlow()

    private val _markerCountFlow = MutableStateFlow(0)
    val markerCountFlow: StateFlow<Int> = _markerCountFlow.asStateFlow()

    private val _lastMarkerLabelFlow = MutableStateFlow<String?>(null)
    val lastMarkerLabelFlow: StateFlow<String?> = _lastMarkerLabelFlow.asStateFlow()

    // Listener for Bosch Live Data Interface V1.0 packets (always logged regardless of speedSource or WLED mode)
    private val boschPacketListener: (BoschLiveDataPacket, BoschLiveDataState) -> Unit = { packet, _ ->
        val sessionId = activeSessionId
        if (isRunning && sessionId != null) {
            val gpsKmh = speedProvider.gpsSpeedMps * 3.6f
            val entity = BoschSampleEntity(
                sessionId = sessionId,
                timestampMonotonicMs = packet.timestampMonotonicMs,
                negotiatedMtu = packet.negotiatedMtu,
                payloadSizeBytes = packet.payloadSizeBytes,
                decodeSuccess = packet.decodeSuccess,
                presentFieldsMask = packet.presentFieldsMask,
                speedHundredthsKmh = packet.speedHundredthsKmh,
                speedKmh = packet.speedKmh,
                cadenceRpm = packet.cadenceRpm,
                riderPowerWatts = packet.riderPowerWatts,
                ambientMilliLux = packet.ambientMilliLux,
                ambientLux = packet.ambientLux,
                batterySoc = packet.batterySocPercent,
                ebikeTimeUtcSec = packet.ebikeTimeUtcSec,
                odometerMeters = packet.odometerMeters,
                bikeLightRaw = packet.bikeLightRaw,
                systemLocked = packet.systemLocked,
                chargerConnected = packet.chargerConnected,
                lightReserveState = packet.lightReserveState,
                diagnosisActive = packet.diagnosisProgramActive,
                bikeNotDriving = packet.bikeNotDriving,
                gpsSpeedKmhAtCapture = gpsKmh
            )
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    logRepository.insertBoschSample(entity)
                } catch (e: Exception) {
                    Log.e("RideOrchestrator", "Failed to log Bosch V1.0 packet: ${e.message}")
                }
            }
        }
    }

    fun setDiagnosticsListener(listener: (String, RideState, Float) -> Unit) {
        onDiagnosticsUpdate = listener
    }

    fun recordRideMarker(markerType: RideMarkerType, note: String = "") {
        recordRideMarker(markerType.wireValue, note)
    }

    /**
     * Records a live in-ride event marker (e.g., HARD_BRAKE, LIGHT_BRAKE, ROAD_BUMP, BOSCH_GLITCH)
     * during any active ride (both WLED-enabled and Log-Only rides).
     */
    fun recordRideMarker(markerType: String, note: String = "") {
        val sessionId = activeSessionId ?: return
        if (!isRunning) return
        val nowMono = SystemClock.elapsedRealtime()
        _markerCountFlow.value = _markerCountFlow.value + 1
        _lastMarkerLabelFlow.value = markerType

        coroutineScope.launch(Dispatchers.IO) {
            try {
                logRepository.insertRideMarker(
                    RideMarkerEntity(
                        sessionId = sessionId,
                        timestampMonotonicMs = nowMono,
                        markerType = markerType,
                        note = note
                    )
                )
            } catch (e: Exception) {
                Log.e("RideOrchestrator", "Failed to insert ride marker: ${e.message}")
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        _markerCountFlow.value = 0
        _lastMarkerLabelFlow.value = null

        configCollectionJob = coroutineScope.launch {
            configManager.configFlow.collect { config ->
                activeConfig = config
                onSpeedSourceCached(config.speedSource)
            }
        }

        coroutineScope.launch {
            lifecycleMutex.withLock {
                // If stop() was invoked before start() acquired lifecycleMutex, abort startup cleanly
                if (!isRunning) return@withLock

                val freshConfig = configManager.getConfigSnapshot()
                activeConfig = freshConfig
                onSpeedSourceCached(freshConfig.speedSource)
                val nowWallMs = System.currentTimeMillis()
                val nowMonoMs = SystemClock.elapsedRealtime()

                // 1. Initialize Logging Session in Room BEFORE starting providers so initial packets are captured
                val sessionId = logRepository.startSession(
                    startTimeMillis = nowWallMs,
                    startMonotonicMs = nowMonoMs,
                    logOnlyMode = freshConfig.logOnlyMode,
                    configuredSpeedSource = freshConfig.speedSource
                )
                activeSessionId = sessionId

                // 2. Register Bosch V1.0 packet listener & start speed providers via SpeedProvider contract
                speedProvider.addBoschPacketListener(boschPacketListener)
                speedProvider.start()

                // 3. Reset State Machine
                stateMachine.getAndResetState(nowMonoMs)
                _telemetryFlow.value = enrichTelemetry(stateMachine.latestTelemetry, nowMonoMs)

                // 4. Log initial IDLE state interval & WLED interval sequentially
                val initialLook = activeConfig.idleLook
                lastLoggedState = RideState.IDLE
                lastLoggedLook = initialLook
                logStateIntervalEntry(sessionId, RideState.IDLE, nowMonoMs, activeConfig.stateMachine.idleBrightness)

                // 5. Send initial look to WLED (or record virtual WLED interval if logOnlyMode == true)
                sendToWled(initialLook, RideState.IDLE, activeConfig.stateMachine.idleBrightness, nowMonoMs)
                logWledIntervalEntry(sessionId, RideState.IDLE, initialLook, nowMonoMs)

                // 6. Register Sensors
                accelerometer?.let {
                    sensorManager.registerListener(this@RideOrchestrator, it, SensorManager.SENSOR_DELAY_GAME)
                }

                // 7. Start Background Batch Writer for high-rate IMU datasets
                startBatchWriter(sessionId)

                updateLock(RideState.IDLE)
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        coroutineScope.launch {
            lifecycleMutex.withLock {
                val nowMonoMs = SystemClock.elapsedRealtime()

                // 1. Unregister Sensors
                sensorManager.unregisterListener(this@RideOrchestrator)

                // 2. Unregister Bosch listener and stop speed providers
                speedProvider.removeBoschPacketListener(boschPacketListener)
                speedProvider.stop()

                // 3. Close open Logging Intervals & flush remaining IMU samples
                activeSessionId?.let { sessionId ->
                    closeStateInterval(nowMonoMs, stateMachine.lastOutputBrightness)
                    closeWledInterval(nowMonoMs)

                    // Wait for queued interval writes to finish before closing session
                    intervalMutex.withLock {
                        // Drain barrier ensuring open/close interval writes have completed
                    }

                    val remainingSamples = mutableListOf<ImuSampleEntity>()
                    while (imuSampleBuffer.isNotEmpty()) {
                        imuSampleBuffer.poll()?.let { remainingSamples.add(it) }
                    }
                    logRepository.insertImuSamples(remainingSamples)

                    // Close Room Session Record
                    logRepository.endSession(sessionId, nowMonoMs)
                }

                // 4. Reset safety state
                lockController.forceUnlock()

                // 5. Cancel batch logger coroutine
                batchLoggingJob?.cancel()
                batchLoggingJob = null

                configCollectionJob?.cancel()
                configCollectionJob = null

                activeSessionId = null
                lastSentLook = null
                lastSentState = null
                lastLoggedState = null
                lastLoggedLook = null
                lastSentBrightness = -1f
                lastSendTimeMs = 0L
                _markerCountFlow.value = 0
                _lastMarkerLabelFlow.value = null

                // Re-trigger static idle callback
                onDiagnosticsUpdate?.invoke("Stopped", RideState.IDLE, 0f)
                _telemetryFlow.value = null
            }
        }
    }

    // --- ACCELEROMETER CALLBACKS (Runs at ~50Hz) ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val nowMono = SystemClock.elapsedRealtime()

        // Extract gravity-compensated linear acceleration based on Calibrated Axis
        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawZ = event.values[2]

        val projectedAcceleration = when (SensorAxis.fromWireValue(activeConfig.sensorAxis)) {
            SensorAxis.X -> rawX
            SensorAxis.Z -> rawZ
            SensorAxis.Y -> rawY
        } * activeConfig.sensorSign

        // Retrieve parallel GPS + Bosch Live Data V1.0 telemetry via SpeedProvider interface
        val gpsSpeedKmh = speedProvider.gpsSpeedMps * 3.6f
        val boschLiveState = speedProvider.boschLiveState
        val boschLastMs = boschLiveState?.lastUpdateMonotonicMs ?: 0L
        val boschAgeMs = if (boschLastMs > 0L) (nowMono - boschLastMs) else -1L
        val boschSpeedKmh = if (boschLastMs > 0L) boschLiveState?.speedKmh else null
        val activeSource = speedProvider.activeSpeedSource
        val currentSpeedKmh = speedProvider.speed * 3.6f

        // Tick State Machine (passing Bosch Field 25 `bike_not_driving` for idle corroboration)
        val inputSample = RideSample(
            timestampMs = nowMono,
            longitudinalAccel = projectedAcceleration,
            speedKmh = currentSpeedKmh,
            bikeNotDriving = boschLiveState?.bikeNotDriving
        )

        val output = stateMachine.tick(inputSample)
        _telemetryFlow.value = enrichTelemetry(stateMachine.latestTelemetry, nowMono)

        // Buffer full-spectrum 50 Hz IMU + GPS + Bosch Live Data V1.0 sample (logged on EVERY ride)
        activeSessionId?.let { sessionId ->
            val sample = ImuSampleEntity(
                sessionId = sessionId,
                timestampMonotonicMs = nowMono,
                accelX = rawX,
                accelY = rawY,
                accelZ = rawZ,
                fusedAcceleration = projectedAcceleration,
                calibratedAxis = activeConfig.sensorAxis,
                calibratedSign = activeConfig.sensorSign,
                speedKmh = currentSpeedKmh,
                gpsSpeedKmh = gpsSpeedKmh,
                boschSpeedKmh = boschSpeedKmh,
                boschAgeMs = boschAgeMs,
                boschBikeNotDriving = boschLiveState?.bikeNotDriving,
                boschCadenceRpm = boschLiveState?.cadenceRpm,
                boschRiderPowerW = boschLiveState?.riderPowerWatts,
                boschAmbientLux = boschLiveState?.ambientLux,
                boschBatterySoc = boschLiveState?.batterySocPercent,
                boschBikeLight = boschLiveState?.bikeLightRaw,
                activeSpeedSource = activeSource,
                stateName = output.state.name
            )
            imuSampleBuffer.offer(sample)
        }

        // Fan Out 1: Update safety lock state
        updateLock(output.state)

        // Fan Out 2: Handle WLED State updates & Throttling (Sink)
        val correspondingLook = when (output.state) {
            RideState.IDLE -> activeConfig.idleLook
            RideState.CRUISING -> activeConfig.cruisingLook
            RideState.BRAKING -> activeConfig.brakingLook
            RideState.BRAKING_HOLD -> activeConfig.brakingHoldLook
        }

        // Log state transition & WLED intervals BEFORE updating lastSentLook
        evalAndLogIntervals(output, correspondingLook, nowMono)

        // Evaluate WLED sink (skips outbound HTTP call if logOnlyMode == true)
        evalAndSendWledSink(correspondingLook, output, nowMono)

        // Broadcast stats for home screen diagnostics
        val modePrefix = if (activeConfig.logOnlyMode) "[LOG ONLY] " else ""
        onDiagnosticsUpdate?.invoke(modePrefix + output.info, output.state, output.brightness)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Unused
    }

    private fun enrichTelemetry(base: StateMachineTelemetry, nowMono: Long): StateMachineTelemetry {
        val gpsSpeedKmh = speedProvider.gpsSpeedMps * 3.6f
        val boschState = speedProvider.boschLiveState
        val boschLastMs = boschState?.lastUpdateMonotonicMs ?: 0L
        val boschAgeMs = if (boschLastMs > 0L) (nowMono - boschLastMs) else -1L
        val boschSpeedKmh = if (boschLastMs > 0L) boschState?.speedKmh else null

        return base.copy(
            logOnlyMode = activeConfig.logOnlyMode,
            activeSpeedSource = speedProvider.activeSpeedSource,
            gpsSpeedKmh = gpsSpeedKmh,
            boschSpeedKmh = boschSpeedKmh,
            boschAgeMs = boschAgeMs,
            boschNotifications = boschState?.totalNotifications ?: 0,
            boschDecodeErrors = boschState?.decodeErrors ?: 0,
            boschNegotiatedMtu = boschState?.negotiatedMtu ?: 23,
            boschBatterySoc = boschState?.batterySocPercent,
            boschCadenceRpm = boschState?.cadenceRpm,
            boschRiderPowerW = boschState?.riderPowerWatts,
            boschAmbientLux = boschState?.ambientLux,
            boschBikeLightRaw = boschState?.bikeLightRaw,
            boschBikeNotDriving = boschState?.bikeNotDriving
        )
    }

    // --- WLED SINK THROTTLING (Section 5 & 6) ---

    private fun evalAndSendWledSink(look: WledLook, output: StateMachineOutput, now: Long) {
        val stateChanged = output.state != lastSentState
        val lookChanged = look != lastSentLook

        val timeSinceLastSend = now - lastSendTimeMs
        val brightnessDelta = kotlin.math.abs(output.brightness - lastSentBrightness)

        val shouldSendBrightnessUpdate = !stateChanged && !lookChanged &&
                (timeSinceLastSend >= 250L || brightnessDelta >= 0.10f)

        if (stateChanged || lookChanged || shouldSendBrightnessUpdate) {
            sendToWled(look, output.state, output.brightness, now)
        }
    }

    private fun sendToWled(look: WledLook, state: RideState, brightness: Float, now: Long) {
        lastSentLook = look
        lastSentState = state
        lastSentBrightness = brightness
        lastSendTimeMs = now

        // In Log-Only mode, we skip outbound HTTP calls to the WLED controller while keeping
        // 100% of the state machine evaluation and telemetry logging pipeline active.
        if (activeConfig.logOnlyMode) return

        coroutineScope.launch(Dispatchers.IO) {
            wledController.sendState(activeConfig.wledHost, look, brightness)
        }
    }

    // --- SEQUENTIAL INTERVAL STATE LOGGERS ---

    private fun evalAndLogIntervals(output: StateMachineOutput, look: WledLook, now: Long) {
        val sessionId = activeSessionId ?: return

        // 1. Track State changes synchronously on sensor thread; serialize DB writes via intervalMutex
        if (output.state != lastLoggedState) {
            lastLoggedState = output.state
            closeStateInterval(now, output.brightness)
            logStateIntervalEntry(sessionId, output.state, now, output.brightness)
        }

        // 2. Track WLED Look changes synchronously on sensor thread; serialize DB writes via intervalMutex
        if (look != lastLoggedLook) {
            lastLoggedLook = look
            closeWledInterval(now)
            logWledIntervalEntry(sessionId, output.state, look, now)
        }
    }

    private fun logStateIntervalEntry(sessionId: Long, state: RideState, now: Long, brightness: Float) {
        val interval = StateIntervalEntity(
            sessionId = sessionId,
            stateName = state.name,
            startTimeMonotonicMs = now,
            startBrightness = brightness,
            endBrightness = brightness
        )
        coroutineScope.launch {
            intervalMutex.withLock {
                activeStateInterval = interval
                currentStateIntervalId = logRepository.insertStateInterval(interval)
            }
        }
    }

    private fun closeStateInterval(now: Long, brightness: Float) {
        coroutineScope.launch {
            intervalMutex.withLock {
                val intervalId = currentStateIntervalId
                val interval = activeStateInterval
                if (intervalId != null && interval != null) {
                    val closed = interval.copy(
                        id = intervalId,
                        endTimeMonotonicMs = now,
                        endBrightness = brightness
                    )
                    logRepository.updateStateInterval(closed)
                }
                currentStateIntervalId = null
                activeStateInterval = null
            }
        }
    }

    private fun logWledIntervalEntry(sessionId: Long, state: RideState, look: WledLook, now: Long) {
        val lookJson = wledLookAdapter.toJson(look)
        val interval = WledIntervalEntity(
            sessionId = sessionId,
            stateName = state.name,
            startTimeMonotonicMs = now,
            lookJson = lookJson
        )
        coroutineScope.launch {
            intervalMutex.withLock {
                activeWledInterval = interval
                currentWledIntervalId = logRepository.insertWledInterval(interval)
            }
        }
    }

    private fun closeWledInterval(now: Long) {
        coroutineScope.launch {
            intervalMutex.withLock {
                val intervalId = currentWledIntervalId
                val interval = activeWledInterval
                if (intervalId != null && interval != null) {
                    val closed = interval.copy(
                        id = intervalId,
                        endTimeMonotonicMs = now
                    )
                    logRepository.updateWledInterval(closed)
                }
                currentWledIntervalId = null
                activeWledInterval = null
            }
        }
    }

    // --- HIGH SPEED IMU ROOM WRITER BATCH JOB ---

    private fun startBatchWriter(sessionId: Long) {
        batchLoggingJob?.cancel()
        batchLoggingJob = coroutineScope.launch {
            while (isActive) {
                delay(1200)
                val batchList = mutableListOf<ImuSampleEntity>()
                while (imuSampleBuffer.isNotEmpty()) {
                    imuSampleBuffer.poll()?.let { batchList.add(it) }
                }
                if (batchList.isNotEmpty()) {
                    try {
                        logRepository.insertImuSamples(batchList)
                    } catch (e: Exception) {
                        Log.e("Orchestrator", "Failed to batch log IMU samples: ${e.message}")
                    }
                }
            }
        }
    }

    private fun updateLock(state: RideState) {
        lockController.updateState(state, isRunning)
    }
}
