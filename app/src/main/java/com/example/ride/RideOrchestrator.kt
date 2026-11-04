package com.example.ride

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.example.config.AppConfig
import com.example.config.AppJson
import com.example.config.ConfigManager
import com.example.config.RideMarkerType
import com.example.config.SegmentRole
import com.example.config.SegmentRoleType
import com.example.config.SensorAxis
import com.example.config.SpeedSourceMode
import com.example.config.WledLook
import com.example.logging.*
import com.example.safety.LockController
import com.example.statemachine.RideSample
import com.example.statemachine.RideState
import com.example.statemachine.RideStateMachine
import com.example.statemachine.StateMachineOutput
import com.example.statemachine.StateMachineTelemetry
import com.example.wled.WledController
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

open class RideOrchestrator(
    private val context: Context? = null,
    private val configManager: ConfigManager,
    private val lockController: LockController,
    private val logRepository: LogRepository,
    private val speedProvider: SpeedProvider,
    private val wledController: WledController,
    private val onSpeedSourceCached: (SpeedSourceMode) -> Unit = {}
) : SensorEventListener {

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sensorManager: SensorManager? =
        context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val wledLookAdapter = AppJson.moshi.adapter(WledLook::class.java)

    // --- Sequential Interval Operations Channel (Item 1) ---
    private sealed interface IntervalOp {
        data class OpenState(val entity: StateIntervalEntity) : IntervalOp
        data class CloseState(val endMs: Long, val endBrightness: Float) : IntervalOp
        data class OpenWled(val entity: WledIntervalEntity) : IntervalOp
        data class CloseWled(val endMs: Long) : IntervalOp
        class Flush(val done: CompletableDeferred<Unit>) : IntervalOp
    }

    private val intervalOps = Channel<IntervalOp>(Channel.UNLIMITED)

    // --- Sequential Lifecycle Command Queue (Item 2) ---
    private sealed interface LifecycleCmd {
        data object Start : LifecycleCmd
        data object Stop : LifecycleCmd
        class Barrier(val done: CompletableDeferred<Unit>) : LifecycleCmd
    }

    private val lifecycleCmds = Channel<LifecycleCmd>(Channel.UNLIMITED)

    private val _isRunning = MutableStateFlow(false)
    open val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()
    open val isRunning: Boolean
        get() = _isRunning.value

    // Cross-thread session & state machine properties
    @Volatile
    private var activeSessionId: Long? = null

    val currentSessionId: Long?
        get() = activeSessionId

    private val _registeredSensorListeners = AtomicInteger(0)
    val registeredSensorListenerCount: Int
        get() = _registeredSensorListeners.get()

    @Volatile
    private var activeConfig: AppConfig = AppConfig()
    private val stateMachine = RideStateMachine { activeConfig.stateMachine }

    // WLED Sink throttling state (MANDATORY per System Design Section 5 & 6)
    @Volatile
    private var lastSentSegmentLooks: Map<Int, WledLook>? = null

    @Volatile
    private var lastSentState: RideState? = null

    @Volatile
    private var lastSentBrightness: Float = -1f

    @Volatile
    private var lastSendTimeMs: Long = 0L

    // Synchronous transition tracking on sensor thread; DB writes serialized via intervalOps channel
    @Volatile
    private var lastLoggedState: RideState? = null

    @Volatile
    private var lastLoggedLook: WledLook? = null

    // For IMU sample high-speed buffer batch-writing to Room to prevent database lag
    private val imuSampleBuffer = ConcurrentLinkedQueue<ImuSampleEntity>()
    private var batchLoggingJob: Job? = null
    private var configCollectionJob: Job? = null

    private var onDiagnosticsUpdate: ((String, RideState, Float) -> Unit)? = null

    private val _telemetryFlow = MutableStateFlow<StateMachineTelemetry?>(null)
    open val telemetryFlow: StateFlow<StateMachineTelemetry?> = _telemetryFlow.asStateFlow()

    private val _markerCountFlow = MutableStateFlow(0)
    open val markerCountFlow: StateFlow<Int> = _markerCountFlow.asStateFlow()

    private val _lastMarkerLabelFlow = MutableStateFlow<String?>(null)
    open val lastMarkerLabelFlow: StateFlow<String?> = _lastMarkerLabelFlow.asStateFlow()

    init {
        // Single interval writer coroutine: owns openState and openWled exclusively
        coroutineScope.launch(Dispatchers.IO) {
            var openState: StateIntervalEntity? = null
            var openWled: WledIntervalEntity? = null
            for (op in intervalOps) {
                try {
                    when (op) {
                        is IntervalOp.OpenState -> {
                            val id = logRepository.insertStateInterval(op.entity)
                            openState = op.entity.copy(id = id)
                        }
                        is IntervalOp.CloseState -> {
                            openState?.let {
                                logRepository.updateStateInterval(
                                    it.copy(
                                        endTimeMonotonicMs = op.endMs,
                                        endBrightness = op.endBrightness
                                    )
                                )
                            }
                            openState = null
                        }
                        is IntervalOp.OpenWled -> {
                            val id = logRepository.insertWledInterval(op.entity)
                            openWled = op.entity.copy(id = id)
                        }
                        is IntervalOp.CloseWled -> {
                            openWled?.let {
                                logRepository.updateWledInterval(
                                    it.copy(endTimeMonotonicMs = op.endMs)
                                )
                            }
                            openWled = null
                        }
                        is IntervalOp.Flush -> op.done.complete(Unit)
                    }
                } catch (e: Exception) {
                    Log.e("RideOrchestrator", "Interval writer error: ${e.message}")
                    if (op is IntervalOp.Flush) {
                        op.done.complete(Unit)
                    }
                }
            }
        }

        // Single lifecycle consumer loop: processes Start and Stop strictly in order
        coroutineScope.launch {
            for (cmd in lifecycleCmds) {
                when (cmd) {
                    LifecycleCmd.Start -> if (activeSessionId == null) doStart()
                    LifecycleCmd.Stop -> if (activeSessionId != null) doStop()
                    is LifecycleCmd.Barrier -> cmd.done.complete(Unit)
                }
            }
        }
    }

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

    open fun setDiagnosticsListener(listener: (String, RideState, Float) -> Unit) {
        onDiagnosticsUpdate = listener
    }

    /**
     * Records a live in-ride event marker during any active ride (both WLED-enabled and Log-Only rides).
     */
    open fun recordRideMarker(markerType: RideMarkerType, note: String = "") {
        val sessionId = activeSessionId ?: return
        if (!isRunning) return
        val nowMono = SystemClock.elapsedRealtime()
        _markerCountFlow.value = _markerCountFlow.value + 1
        _lastMarkerLabelFlow.value = markerType.wireValue

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

    open fun start() {
        lifecycleCmds.trySend(LifecycleCmd.Start)
    }

    open fun stop() {
        lifecycleCmds.trySend(LifecycleCmd.Stop)
    }

    /**
     * Suspends until all queued start/stop commands and interval writes up to this point have completed.
     */
    suspend fun awaitIdle() {
        val lifecycleDone = CompletableDeferred<Unit>()
        lifecycleCmds.send(LifecycleCmd.Barrier(lifecycleDone))
        lifecycleDone.await()

        val intervalsDone = CompletableDeferred<Unit>()
        intervalOps.send(IntervalOp.Flush(intervalsDone))
        intervalsDone.await()
    }

    /**
     * Single point where ride state maps to a WledLook for a given segment role.
     */
    private fun lookForSegment(role: SegmentRole, state: RideState): WledLook {
        val ambientLook = when (state) {
            RideState.IDLE         -> activeConfig.idleLook
            RideState.CRUISING     -> activeConfig.cruisingLook
            // Ambient segments hold the last cruising look during braking
            RideState.BRAKING      -> activeConfig.cruisingLook
            RideState.BRAKING_HOLD -> activeConfig.cruisingLook
        }
        return when (role.roleType) {
            SegmentRoleType.AMBIENT -> ambientLook
            SegmentRoleType.NOTIFICATION -> when (role) {
                SegmentRole.BRAKE_LIGHTS -> when (state) {
                    RideState.BRAKING      -> activeConfig.brakingLook
                    RideState.BRAKING_HOLD -> activeConfig.brakingHoldLook
                    else                   -> ambientLook  // inherit ambient when not triggered
                }
                else -> ambientLook
            }
        }
    }

    private fun resolveRepresentativeLook(
        state: RideState,
        segmentLooks: Map<Int, WledLook>
    ): WledLook {
        val allSegments = activeConfig.channels.flatMap { it.segments }
        val ambientSeg = allSegments.firstOrNull { it.role.roleType == SegmentRoleType.AMBIENT }
        if (ambientSeg != null) {
            segmentLooks[ambientSeg.wledSegmentId]?.let { return it }
        }
        segmentLooks.values.firstOrNull()?.let { return it }
        return when (state) {
            RideState.IDLE -> activeConfig.idleLook
            RideState.CRUISING -> activeConfig.cruisingLook
            RideState.BRAKING -> activeConfig.brakingLook
            RideState.BRAKING_HOLD -> activeConfig.brakingHoldLook
        }
    }

    private suspend fun doStart() {
        _markerCountFlow.value = 0
        _lastMarkerLabelFlow.value = null

        val freshConfig = configManager.getConfigSnapshot()
        activeConfig = freshConfig
        onSpeedSourceCached(freshConfig.speedSource)

        configCollectionJob?.cancel()
        configCollectionJob = coroutineScope.launch {
            configManager.configFlow.collect { config ->
                activeConfig = config
                onSpeedSourceCached(config.speedSource)
            }
        }

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

        // 4. Enqueue initial IDLE state interval & WLED interval sequentially
        val initialSegmentLooks: Map<Int, WledLook> = activeConfig.channels
            .flatMap { it.segments }
            .associate { seg -> seg.wledSegmentId to lookForSegment(seg.role, RideState.IDLE) }
        val initialLook = resolveRepresentativeLook(RideState.IDLE, initialSegmentLooks)
        lastLoggedState = RideState.IDLE
        lastLoggedLook = initialLook
        val initBrightness = activeConfig.stateMachine.idleBrightness
        intervalOps.trySend(
            IntervalOp.OpenState(
                StateIntervalEntity(
                    sessionId = sessionId,
                    stateName = RideState.IDLE.name,
                    startTimeMonotonicMs = nowMonoMs,
                    startBrightness = initBrightness,
                    endBrightness = initBrightness
                )
            )
        )
        intervalOps.trySend(
            IntervalOp.OpenWled(
                WledIntervalEntity(
                    sessionId = sessionId,
                    stateName = RideState.IDLE.name,
                    startTimeMonotonicMs = nowMonoMs,
                    lookJson = wledLookAdapter.toJson(initialLook)
                )
            )
        )

        // 5. Send initial composed state to WLED (or skip HTTP if logOnlyMode == true)
        sendToWled(initialSegmentLooks, RideState.IDLE, initBrightness, nowMonoMs)

        // 6. Register Sensors
        if (sensorManager != null) {
            accelerometer?.let {
                sensorManager.registerListener(this@RideOrchestrator, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        _registeredSensorListeners.incrementAndGet()

        // 7. Start Background Batch Writer for high-rate IMU datasets
        startBatchWriter()

        _isRunning.value = true
        updateLock(RideState.IDLE)
    }

    private suspend fun doStop() {
        _isRunning.value = false
        val nowMonoMs = SystemClock.elapsedRealtime()

        // 1. Unregister Sensors
        sensorManager?.unregisterListener(this@RideOrchestrator)
        _registeredSensorListeners.updateAndGet { current -> (current - 1).coerceAtLeast(0) }

        // 2. Unregister Bosch listener and stop speed providers
        speedProvider.removeBoschPacketListener(boschPacketListener)
        speedProvider.stop()

        // 3. Close open Logging Intervals & flush remaining IMU samples
        activeSessionId?.let { sessionId ->
            intervalOps.trySend(IntervalOp.CloseState(nowMonoMs, stateMachine.lastOutputBrightness))
            intervalOps.trySend(IntervalOp.CloseWled(nowMonoMs))
            val flushed = CompletableDeferred<Unit>()
            intervalOps.trySend(IntervalOp.Flush(flushed))
            flushed.await()

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
        lastSentSegmentLooks = null
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

    // --- ACCELEROMETER CALLBACKS (Runs at ~50Hz) ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val nowMono = SystemClock.elapsedRealtime()
        onLinearAccelerationTick(
            rawX = event.values[0],
            rawY = event.values[1],
            rawZ = event.values[2],
            nowMono = nowMono
        )
    }

    fun onLinearAccelerationTick(
        rawX: Float,
        rawY: Float,
        rawZ: Float,
        nowMono: Long = SystemClock.elapsedRealtime()
    ) {
        if (!isRunning) return

        val projectedAcceleration = when (activeConfig.sensorAxis) {
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
                calibratedAxis = activeConfig.sensorAxis.wireValue,
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
                activeSpeedSource = activeSource.name,
                stateName = output.state.name
            )
            imuSampleBuffer.offer(sample)
        }

        // Fan Out 1: Update safety lock state
        updateLock(output.state)

        // Fan Out 2: Handle WLED State updates & Throttling (Sink)
        evalAndSendWledSink(output, nowMono)

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
            activeSpeedSource = speedProvider.activeSpeedSource.name,
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

    // --- WLED SINK THROTTLING & ROLE-BASED FAN-OUT ---

    private fun evalAndSendWledSink(output: StateMachineOutput, now: Long) {
        // Build the segment look map from all configured segments across all channels
        val segmentLooks: Map<Int, WledLook> = activeConfig.channels
            .flatMap { it.segments }
            .associate { seg -> seg.wledSegmentId to lookForSegment(seg.role, output.state) }

        val representativeLook = resolveRepresentativeLook(output.state, segmentLooks)
        evalAndLogIntervals(output, representativeLook, now)

        val stateChanged = output.state != lastSentState
        val lookChanged = segmentLooks != lastSentSegmentLooks

        val timeSinceLastSend = now - lastSendTimeMs
        val brightnessDelta = kotlin.math.abs(output.brightness - lastSentBrightness)

        val shouldSendBrightnessUpdate = !stateChanged && !lookChanged &&
                (timeSinceLastSend >= 250L || brightnessDelta >= 0.10f)

        if (stateChanged || lookChanged || shouldSendBrightnessUpdate) {
            sendToWled(segmentLooks, output.state, output.brightness, now)
        }
    }

    private fun sendToWled(
        segmentLooks: Map<Int, WledLook>,
        state: RideState,
        brightness: Float,
        now: Long
    ) {
        lastSentSegmentLooks = segmentLooks
        lastSentState = state
        lastSentBrightness = brightness
        lastSendTimeMs = now

        // In Log-Only mode, we skip outbound HTTP calls to the WLED controller while keeping
        // 100% of the state machine evaluation and telemetry logging pipeline active.
        if (activeConfig.logOnlyMode || segmentLooks.isEmpty()) return

        coroutineScope.launch(Dispatchers.IO) {
            wledController.sendComposedState(activeConfig.wledHost, segmentLooks, brightness)
        }
    }

    // --- SEQUENTIAL INTERVAL STATE LOGGERS ---

    private fun evalAndLogIntervals(output: StateMachineOutput, look: WledLook, now: Long) {
        val sessionId = activeSessionId ?: return

        // 1. Track State changes synchronously on sensor thread; enqueue DB operations in order
        if (output.state != lastLoggedState) {
            lastLoggedState = output.state
            intervalOps.trySend(IntervalOp.CloseState(now, output.brightness))
            intervalOps.trySend(
                IntervalOp.OpenState(
                    StateIntervalEntity(
                        sessionId = sessionId,
                        stateName = output.state.name,
                        startTimeMonotonicMs = now,
                        startBrightness = output.brightness,
                        endBrightness = output.brightness
                    )
                )
            )
        }

        // 2. Track WLED Look changes synchronously on sensor thread; enqueue DB operations in order
        if (look != lastLoggedLook) {
            lastLoggedLook = look
            intervalOps.trySend(IntervalOp.CloseWled(now))
            intervalOps.trySend(
                IntervalOp.OpenWled(
                    WledIntervalEntity(
                        sessionId = sessionId,
                        stateName = output.state.name,
                        startTimeMonotonicMs = now,
                        lookJson = wledLookAdapter.toJson(look)
                    )
                )
            )
        }
    }

    // --- HIGH SPEED IMU ROOM WRITER BATCH JOB ---

    private fun startBatchWriter() {
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
