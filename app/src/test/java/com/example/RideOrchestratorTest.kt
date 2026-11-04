package com.example

import com.example.config.ActiveSpeedSource
import com.example.config.AppConfig
import com.example.config.ChannelConfig
import com.example.config.ConfigManager
import com.example.config.SegmentConfig
import com.example.config.SegmentRole
import com.example.config.SpeedSourceMode
import com.example.config.WledLook
import com.example.logging.BoschSampleEntity
import com.example.logging.ImuSampleEntity
import com.example.logging.LogRepository
import com.example.logging.RideMarkerEntity
import com.example.logging.SessionEntity
import com.example.logging.StateIntervalEntity
import com.example.logging.WledIntervalEntity
import com.example.ride.BoschLiveDataPacket
import com.example.ride.BoschLiveDataState
import com.example.ride.RideOrchestrator
import com.example.ride.SpeedProvider
import com.example.safety.LockController
import com.example.wled.WledController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Concurrency, lifecycle, and role-based segment fan-out unit tests for [RideOrchestrator].
 */
class RideOrchestratorTest {

    private class FakeSpeedProvider(var currentSpeedMps: Float = 0f) : SpeedProvider {
        override val speed: Float get() = currentSpeedMps
        override val lastUpdateTimeMs: Long = 1000L
        override val isAvailable: Boolean = true
        override val gpsSpeedMps: Float get() = currentSpeedMps
        override val activeSpeedSource: ActiveSpeedSource = ActiveSpeedSource.GPS
        override val boschLiveState: BoschLiveDataState? = null
        var startCount = 0
        var stopCount = 0

        override fun start() {
            startCount++
        }

        override fun stop() {
            stopCount++
        }

        override fun addBoschPacketListener(listener: (BoschLiveDataPacket, BoschLiveDataState) -> Unit) {}
        override fun removeBoschPacketListener(listener: (BoschLiveDataPacket, BoschLiveDataState) -> Unit) {}
    }

    private class FakeConfigManager(initial: AppConfig = AppConfig(logOnlyMode = true)) : ConfigManager(null) {
        val state = MutableStateFlow(initial)
        override val configFlow: Flow<AppConfig> get() = state
    }

    private class RecordingWledController : WledController(null) {
        val sentPayloads = CopyOnWriteArrayList<Map<Int, WledLook>>()

        override fun sendComposedState(
            host: String,
            segmentLooks: Map<Int, WledLook>,
            brightness: Float
        ): Boolean {
            sentPayloads.add(segmentLooks)
            return true
        }
    }

    private class FakeDelayedLogRepository : LogRepository(null) {
        private val nextId = AtomicLong(100L)
        val sessions = Collections.synchronizedMap(LinkedHashMap<Long, SessionEntity>())
        val stateIntervals = Collections.synchronizedMap(LinkedHashMap<Long, StateIntervalEntity>())
        val wledIntervals = Collections.synchronizedMap(LinkedHashMap<Long, WledIntervalEntity>())
        val stateCloseCounts = Collections.synchronizedMap(HashMap<Long, Int>())
        val wledCloseCounts = Collections.synchronizedMap(HashMap<Long, Int>())

        override suspend fun startSession(
            startTimeMillis: Long,
            startMonotonicMs: Long,
            weather: String?,
            logOnlyMode: Boolean,
            configuredSpeedSource: SpeedSourceMode
        ): Long {
            delay(Random.nextLong(1L, 5L))
            val id = nextId.incrementAndGet()
            sessions[id] = SessionEntity(
                id = id,
                startTimeMillis = startTimeMillis,
                startMonotonicMs = startMonotonicMs,
                weatherEnrichment = weather,
                logOnlyMode = logOnlyMode,
                configuredSpeedSource = configuredSpeedSource
            )
            return id
        }

        override suspend fun endSession(sessionId: Long, endMonotonicMs: Long) {
            delay(Random.nextLong(1L, 5L))
            val existing = sessions[sessionId]
            if (existing != null) {
                sessions[sessionId] = existing.copy(endTimeMonotonicMs = endMonotonicMs)
            }
        }

        override suspend fun insertStateInterval(interval: StateIntervalEntity): Long {
            delay(Random.nextLong(1L, 6L))
            val id = nextId.incrementAndGet()
            stateIntervals[id] = interval.copy(id = id)
            stateCloseCounts[id] = 0
            return id
        }

        override suspend fun updateStateInterval(interval: StateIntervalEntity) {
            delay(Random.nextLong(1L, 4L))
            if (interval.endTimeMonotonicMs != null) {
                stateCloseCounts[interval.id] = (stateCloseCounts[interval.id] ?: 0) + 1
            }
            stateIntervals[interval.id] = interval
        }

        override suspend fun insertWledInterval(interval: WledIntervalEntity): Long {
            delay(Random.nextLong(1L, 6L))
            val id = nextId.incrementAndGet()
            wledIntervals[id] = interval.copy(id = id)
            wledCloseCounts[id] = 0
            return id
        }

        override suspend fun updateWledInterval(interval: WledIntervalEntity) {
            delay(Random.nextLong(1L, 4L))
            if (interval.endTimeMonotonicMs != null) {
                wledCloseCounts[interval.id] = (wledCloseCounts[interval.id] ?: 0) + 1
            }
            wledIntervals[interval.id] = interval
        }

        override suspend fun insertImuSamples(samples: List<ImuSampleEntity>) {}
        override suspend fun insertBoschSample(sample: BoschSampleEntity) {}
        override suspend fun insertRideMarker(marker: RideMarkerEntity): Long = 1L
    }

    @Test
    fun intervalWriterSequentialChannel_closesEveryIntervalOnceAndNeverTwice() = runBlocking {
        val fakeSpeed = FakeSpeedProvider()
        val fakeConfig = FakeConfigManager(AppConfig(logOnlyMode = true))
        val fakeLogs = FakeDelayedLogRepository()
        val orchestrator = RideOrchestrator(
            context = null,
            speedProvider = fakeSpeed,
            wledController = WledController(null),
            configManager = fakeConfig,
            lockController = LockController(),
            logRepository = fakeLogs
        )

        orchestrator.start()
        orchestrator.awaitIdle()

        var nowMs = 1000L
        for (cycle in 1..5) {
            // 1. IDLE -> CRUISING (speed = 20 km/h = 5.56 m/s)
            fakeSpeed.currentSpeedMps = 20f / 3.6f
            nowMs += 20L
            orchestrator.onLinearAccelerationTick(0f, 0f, 0f, nowMs)

            // 2. CRUISING -> BRAKING (decel = -3.5 m/s² along Y with sensorSign = -1 -> rawY = +3.5f, >180ms)
            nowMs += 20L
            orchestrator.onLinearAccelerationTick(0f, 3.5f, 0f, nowMs)
            nowMs += 200L
            orchestrator.onLinearAccelerationTick(0f, 3.5f, 0f, nowMs)

            // 3. BRAKING -> BRAKING_HOLD (speed drops below 2.0 km/h -> 0.2 m/s)
            fakeSpeed.currentSpeedMps = 0.2f
            nowMs += 20L
            orchestrator.onLinearAccelerationTick(0f, 3.5f, 0f, nowMs)

            // 4. BRAKING_HOLD -> IDLE (3100ms hold + fade elapsed)
            nowMs += 3100L
            orchestrator.onLinearAccelerationTick(0f, 0f, 0f, nowMs)
        }

        orchestrator.stop()
        orchestrator.awaitIdle()

        assertTrue(
            "Expected >= 20 state intervals, got ${fakeLogs.stateIntervals.size}",
            fakeLogs.stateIntervals.size >= 20
        )
        assertTrue(
            "Expected >= 15 WLED intervals, got ${fakeLogs.wledIntervals.size}",
            fakeLogs.wledIntervals.size >= 15
        )

        for ((id, row) in fakeLogs.stateIntervals) {
            assertNotNull("State interval $id was left unclosed!", row.endTimeMonotonicMs)
            assertEquals("State interval $id close count mismatch", 1, fakeLogs.stateCloseCounts[id])
        }

        for ((id, row) in fakeLogs.wledIntervals) {
            assertNotNull("WLED interval $id was left unclosed!", row.endTimeMonotonicMs)
            assertEquals("WLED interval $id close count mismatch", 1, fakeLogs.wledCloseCounts[id])
        }
    }

    @Test
    fun roleBasedSegmentFanOut_holdsCruisingOnAmbientWhileTriggeringBrakeSegments() = runBlocking {
        val fakeSpeed = FakeSpeedProvider()
        val idleLook = WledLook(effectId = 1, colors = listOf("#FF7800"), transitionMs = 400)
        val cruiseLook = WledLook(effectId = 2, colors = listOf("#00B4FF"), transitionMs = 300)
        val brakeLook = WledLook(effectId = 3, colors = listOf("#FF0000"), transitionMs = 0)
        val holdLook = WledLook(effectId = 4, colors = listOf("#AA0000"), transitionMs = 0)

        val config = AppConfig(
            logOnlyMode = false,
            idleLook = idleLook,
            cruisingLook = cruiseLook,
            brakingLook = brakeLook,
            brakingHoldLook = holdLook,
            channels = listOf(
                ChannelConfig(
                    gpioPin = 16,
                    ledCount = 60,
                    stripType = 22,
                    colorOrder = 0,
                    segments = listOf(
                        SegmentConfig(
                            wledSegmentId = 0,
                            startLed = 0,
                            endLed = 60,
                            label = "Frame",
                            role = SegmentRole.MAIN_FRAME
                        )
                    )
                ),
                ChannelConfig(
                    gpioPin = 2,
                    ledCount = 30,
                    stripType = 22,
                    colorOrder = 0,
                    segments = listOf(
                        SegmentConfig(
                            wledSegmentId = 1,
                            startLed = 0,
                            endLed = 30,
                            label = "Tail",
                            role = SegmentRole.BRAKE_LIGHTS
                        )
                    )
                )
            )
        )

        val fakeConfig = FakeConfigManager(config)
        val fakeLogs = FakeDelayedLogRepository()
        val recordingWled = RecordingWledController()
        val orchestrator = RideOrchestrator(
            context = null,
            speedProvider = fakeSpeed,
            wledController = recordingWled,
            configManager = fakeConfig,
            lockController = LockController(),
            logRepository = fakeLogs
        )

        orchestrator.start()
        orchestrator.awaitIdle()
        delay(50)

        // 1. Initial IDLE: both Seg 0 (MAIN_FRAME) and Seg 1 (BRAKE_LIGHTS) receive idleLook
        assertTrue(recordingWled.sentPayloads.isNotEmpty())
        val idleSent = recordingWled.sentPayloads.last()
        assertEquals(idleLook, idleSent[0])
        assertEquals(idleLook, idleSent[1])

        // 2. CRUISING: both Seg 0 and Seg 1 receive cruisingLook
        fakeSpeed.currentSpeedMps = 20f / 3.6f
        orchestrator.onLinearAccelerationTick(0f, 0f, 0f, 1020L)
        delay(50)
        val cruiseSent = recordingWled.sentPayloads.last()
        assertEquals(cruiseLook, cruiseSent[0])
        assertEquals(cruiseLook, cruiseSent[1])

        // 3. BRAKING: Seg 0 (MAIN_FRAME) holds cruisingLook, Seg 1 (BRAKE_LIGHTS) triggers brakingLook
        orchestrator.onLinearAccelerationTick(0f, 3.5f, 0f, 1040L)
        orchestrator.onLinearAccelerationTick(0f, 3.5f, 0f, 1250L)
        delay(50)
        val brakeSent = recordingWled.sentPayloads.last()
        assertEquals(cruiseLook, brakeSent[0])
        assertEquals(brakeLook, brakeSent[1])

        // 4. BRAKING_HOLD: Seg 0 (MAIN_FRAME) holds cruisingLook, Seg 1 (BRAKE_LIGHTS) triggers brakingHoldLook
        fakeSpeed.currentSpeedMps = 0.2f
        orchestrator.onLinearAccelerationTick(0f, 3.5f, 0f, 1270L)
        delay(50)
        val holdSent = recordingWled.sentPayloads.last()
        assertEquals(cruiseLook, holdSent[0])
        assertEquals(holdLook, holdSent[1])

        // Verify WledIntervalEntity logged representative MAIN_FRAME look (idleLook -> cruiseLook)
        val loggedLookJsons = fakeLogs.wledIntervals.values.map { it.lookJson }
        assertTrue(loggedLookJsons.any { it.contains("#FF7800") })
        assertTrue(loggedLookJsons.any { it.contains("#00B4FF") })
        // Since MAIN_FRAME holds cruisingLook during braking, representative look does not switch to brakeLook
        assertFalse(loggedLookJsons.any { it.contains("#FF0000") })

        orchestrator.stop()
        orchestrator.awaitIdle()
    }

    @Test
    fun startStopCommandChannel_serializesBackToBackCallsWithoutLeakingSessionsOrListeners() = runBlocking {
        val fakeSpeed = FakeSpeedProvider()
        val fakeConfig = FakeConfigManager(AppConfig(logOnlyMode = true))
        val fakeLogs = FakeDelayedLogRepository()
        val orchestrator = RideOrchestrator(
            context = null,
            speedProvider = fakeSpeed,
            wledController = WledController(null),
            configManager = fakeConfig,
            lockController = LockController(),
            logRepository = fakeLogs
        )

        orchestrator.start()
        orchestrator.stop()
        orchestrator.start()
        orchestrator.stop()
        orchestrator.start()

        orchestrator.awaitIdle()

        assertTrue(orchestrator.isRunningFlow.value)
        assertEquals(3, fakeLogs.sessions.size)
        val openSessionsAfterStarts = fakeLogs.sessions.values.filter { it.endTimeMonotonicMs == null }
        assertEquals(1, openSessionsAfterStarts.size)
        assertTrue(orchestrator.registeredSensorListenerCount <= 1)

        orchestrator.stop()
        orchestrator.awaitIdle()

        assertFalse(orchestrator.isRunningFlow.value)
        val remainingOpenSessions = fakeLogs.sessions.values.filter { it.endTimeMonotonicMs == null }
        assertEquals(0, remainingOpenSessions.size)
        assertEquals(0, orchestrator.registeredSensorListenerCount)
    }
}
