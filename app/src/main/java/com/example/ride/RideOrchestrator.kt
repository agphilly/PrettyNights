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
import java.util.concurrent.ConcurrentLinkedQueue

class RideOrchestrator(
    private val context: Context,
    private val configManager: ConfigManager,
    private val lockController: LockController,
    private val logRepository: LogRepository,
    private val speedProvider: SpeedProvider,
    private val wledController: WledController
) : SensorEventListener {

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // Current session properties
    private var activeSessionId: Long? = null
    var isRunning = false

    // State machine
    private var activeConfig: AppConfig = AppConfig()
    private val stateMachine = RideStateMachine { activeConfig.stateMachine }

    // WLED Sink throttling state (MANDATORY per System Design Section 5 & 6)
    private var lastSentLook: WledLook? = null
    private var lastSentState: RideState? = null
    private var lastSentBrightness: Float = -1f
    private var lastSendTimeMs: Long = 0L

    // State interval and Wled interval logging references
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

    fun setDiagnosticsListener(listener: (String, RideState, Float) -> Unit) {
        onDiagnosticsUpdate = listener
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        configCollectionJob = coroutineScope.launch {
            configManager.configFlow.collect { config ->
                activeConfig = config
            }
        }

        coroutineScope.launch {
            // Load fresh configuration snap
            val freshConfig = configManager.getConfigSnapshot()
            activeConfig = freshConfig
            PrettyNightsApp.instance.cachedSpeedSource = freshConfig.speedSource
            val nowWallMs = System.currentTimeMillis()
            val nowMonoMs = SystemClock.elapsedRealtime()

            // 1. Start speed provider
            speedProvider.start()

            // 2. Initialize Logging Session in Room
            val sessionId = logRepository.startSession(nowWallMs, nowMonoMs)
            activeSessionId = sessionId

            // 3. Reset State Machine
            stateMachine.getAndResetState(nowMonoMs)
            _telemetryFlow.value = stateMachine.latestTelemetry

            // 4. Log initial IDLE state interval
            logStateIntervalEntry(sessionId, RideState.IDLE, nowMonoMs, activeConfig.stateMachine.idleBrightness)

            // 5. Send initial look to WLED
            val initialLook = activeConfig.idleLook
            sendToWled(initialLook, RideState.IDLE, activeConfig.stateMachine.idleBrightness, nowMonoMs)
            logWledIntervalEntry(sessionId, RideState.IDLE, initialLook, nowMonoMs)

            // 6. Register Sensors
            accelerometer?.let {
                sensorManager.registerListener(this@RideOrchestrator, it, SensorManager.SENSOR_DELAY_GAME)
            }

            // 7. Start Background Batch Writer for high-rate IMU datasets (every 1 second)
            startBatchWriter(sessionId)

            updateLock(RideState.IDLE)
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        coroutineScope.launch {
            val nowMonoMs = SystemClock.elapsedRealtime()

            // 1. Unregister Sensors
            sensorManager.unregisterListener(this@RideOrchestrator)

            // 2. Stop speed provider
            speedProvider.stop()

            // 3. Close open Logging Intervals
            activeSessionId?.let { sessionId ->
                closeStateInterval(nowMonoMs, stateMachine.lastOutputBrightness)
                closeWledInterval(nowMonoMs)
                
                // Flush remaining IMU samples from buffer
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
            lastSentBrightness = -1f
            lastSendTimeMs = 0L

            // Re-trigger static idle callback
            onDiagnosticsUpdate?.invoke("Stopped", RideState.IDLE, 0f)
            _telemetryFlow.value = null
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

        val projectedAcceleration = when (activeConfig.sensorAxis.uppercase()) {
            "X" -> rawX
            "Z" -> rawZ
            else -> rawY // default forward is Y
        } * activeConfig.sensorSign // apply calibration polarity sign

        // Retrieve speed
        val currentSpeed = speedProvider.speed

        // Buffer IMU acceleration samples for batch database injection
        activeSessionId?.let { sessionId ->
            val sample = ImuSampleEntity(
                sessionId = sessionId,
                timestampMonotonicMs = nowMono,
                accelX = rawX,
                accelY = rawY,
                accelZ = rawZ,
                fusedAcceleration = projectedAcceleration,
                calibratedAxis = activeConfig.sensorAxis,
                calibratedSign = activeConfig.sensorSign
            )
            imuSampleBuffer.offer(sample)
        }

        // Tick State Machine
        val inputSample = RideSample(
            timestampMs = nowMono,
            longitudinalAccel = projectedAcceleration,
            speedKmh = currentSpeed * 3.6f
        )

        val output = stateMachine.tick(inputSample)
        _telemetryFlow.value = stateMachine.latestTelemetry

        // Fan Out 1: Update safety lock state
        updateLock(output.state)

        // Fan Out 2: Handle Wled State updates & Throttling (Sink)
        val correspondingLook = when (output.state) {
            RideState.IDLE -> activeConfig.idleLook
            RideState.CRUISING -> activeConfig.cruisingLook
            RideState.BRAKING, RideState.BRAKING_HOLD -> activeConfig.brakingLook
        }

        evalAndSendWledSink(correspondingLook, output, nowMono)

        // Fan Out 3: Log state transition intervals on delta
        evalAndLogIntervals(output, correspondingLook, nowMono)

        // Broadcast stats for home screen diagnostics
        onDiagnosticsUpdate?.invoke(output.info, output.state, output.brightness)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Unused
    }

    // --- WLED SINK THROTTLING (Section 5 & 6) ---

    private fun evalAndSendWledSink(look: WledLook, output: StateMachineOutput, now: Long) {
        // Rule: A change in the state's look or ride state sends IMMEDIATELY for high responsiveness.
        // Brightness-only changes during a continuous ramp are throttled (rate-limited).
        val stateChanged = output.state != lastSentState
        val lookChanged = look != lastSentLook
        
        val timeSinceLastSend = now - lastSendTimeMs
        val brightnessDelta = Math.abs(output.brightness - lastSentBrightness)

        // We rate-limit brightness-only changes to every 200ms or if there is a big jump (> 0.1)
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

        coroutineScope.launch(Dispatchers.IO) {
            wledController.sendState(activeConfig.wledHost, look, brightness)
        }
    }

    // --- INTERVAL STATE LOGGERS ---

    private fun evalAndLogIntervals(output: StateMachineOutput, look: WledLook, now: Long) {
        val sessionId = activeSessionId ?: return
        
        // 1. Track State changes
        if (output.state != activeStateInterval?.stateName?.let { RideState.valueOf(it) }) {
            closeStateInterval(now, output.brightness)
            logStateIntervalEntry(sessionId, output.state, now, output.brightness)
        }

        // 2. Track WLED Look changes
        if (look != lastSentLook) {
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
            endBrightness = brightness // initialized same, overwritten on close
        )
        activeStateInterval = interval
        coroutineScope.launch {
            currentStateIntervalId = logRepository.insertStateInterval(interval)
        }
    }

    private fun closeStateInterval(now: Long, brightness: Float) {
        val intervalId = currentStateIntervalId
        val interval = activeStateInterval
        if (intervalId != null && interval != null) {
            val closed = interval.copy(
                id = intervalId,
                endTimeMonotonicMs = now,
                endBrightness = brightness
            )
            coroutineScope.launch {
                logRepository.updateStateInterval(closed)
            }
        }
        currentStateIntervalId = null
        activeStateInterval = null
    }

    private fun logWledIntervalEntry(sessionId: Long, state: RideState, look: WledLook, now: Long) {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(WledLook::class.java)
        val lookJson = adapter.toJson(look)

        val interval = WledIntervalEntity(
            sessionId = sessionId,
            stateName = state.name,
            startTimeMonotonicMs = now,
            lookJson = lookJson
        )
        activeWledInterval = interval
        coroutineScope.launch {
            currentWledIntervalId = logRepository.insertWledInterval(interval)
        }
    }

    private fun closeWledInterval(now: Long) {
        val intervalId = currentWledIntervalId
        val interval = activeWledInterval
        if (intervalId != null && interval != null) {
            val closed = interval.copy(
                id = intervalId,
                endTimeMonotonicMs = now
            )
            coroutineScope.launch {
                logRepository.updateWledInterval(closed)
            }
        }
        currentWledIntervalId = null
        activeWledInterval = null
    }

    // --- HIGH SPEED IMU ROOM WRITER BATCH JOB ---

    private fun startBatchWriter(sessionId: Long) {
        batchLoggingJob?.cancel()
        batchLoggingJob = coroutineScope.launch {
            while (isActive) {
                delay(1200) // batch every 1.2 seconds
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
