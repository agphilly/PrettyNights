package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.config.ActiveSpeedSource
import com.example.config.AppConfig
import com.example.config.ConfigManager
import com.example.config.SpeedSourceMode
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Concurrency & lifecycle unit tests for [RideOrchestrator]:
 * - Item 1: Single sequential coroutine writer owns `openState` and `openWled` so out-of-order IO delays
 *   can never leak an unclosed interval row or close the same interval twice.
 * - Item 2: Single sequential command channel owns `start()` and `stop()` so rapid back-to-back
 *   calls leave `_isRunning`, sessions, and sensor listeners in a deterministic state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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

    private class FakeConfigManager(context: Context, initial: AppConfig = AppConfig(logOnlyMode = true)) : ConfigManager(context) {
        val state = MutableStateFlow(initial)
        override val configFlow: Flow<AppConfig> get() = state
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
            // Random 1-5ms delay simulating variable SQLite disk write latency
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeSpeed = FakeSpeedProvider()
        val fakeConfig = FakeConfigManager(context, AppConfig(logOnlyMode = true))
        val fakeLogs = FakeDelayedLogRepository()
        val orchestrator = RideOrchestrator(
            context = context,
            speedProvider = fakeSpeed,
            wledController = WledController(null),
            configManager = fakeConfig,
            lockController = LockController(),
            logRepository = fakeLogs
        )

        orchestrator.start()
        orchestrator.awaitIdle()

        var nowMs = 1000L
        // Trigger 20 rapid state transitions back-to-back with zero delay between ticks
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

        // Verify at least 20 state intervals were recorded (1 initial IDLE + 20 transitions)
        assertTrue(
            "Expected >= 20 state intervals, got ${fakeLogs.stateIntervals.size}",
            fakeLogs.stateIntervals.size >= 20
        )
        assertTrue(
            "Expected >= 15 WLED intervals, got ${fakeLogs.wledIntervals.size}",
            fakeLogs.wledIntervals.size >= 15
        )

        // Every state interval must be closed with a non-null endTimeMonotonicMs and closed exactly once
        for ((id, row) in fakeLogs.stateIntervals) {
            assertNotNull("State interval $id was left unclosed!", row.endTimeMonotonicMs)
            assertEquals("State interval $id close count mismatch", 1, fakeLogs.stateCloseCounts[id])
        }

        // Every WLED interval must be closed with a non-null endTimeMonotonicMs and closed exactly once
        for ((id, row) in fakeLogs.wledIntervals) {
            assertNotNull("WLED interval $id was left unclosed!", row.endTimeMonotonicMs)
            assertEquals("WLED interval $id close count mismatch", 1, fakeLogs.wledCloseCounts[id])
        }
    }

    @Test
    fun startStopCommandChannel_serializesBackToBackCallsWithoutLeakingSessionsOrListeners() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeSpeed = FakeSpeedProvider()
        val fakeConfig = FakeConfigManager(context, AppConfig(logOnlyMode = true))
        val fakeLogs = FakeDelayedLogRepository()
        val orchestrator = RideOrchestrator(
            context = context,
            speedProvider = fakeSpeed,
            wledController = WledController(null),
            configManager = fakeConfig,
            lockController = LockController(),
            logRepository = fakeLogs
        )

        // Fire start(), stop(), start(), stop(), start() back-to-back without awaiting between calls
        orchestrator.start()
        orchestrator.stop()
        orchestrator.start()
        orchestrator.stop()
        orchestrator.start()

        orchestrator.awaitIdle()

        // Final command was start(): must be running with 1 active session and 3 total created (2 closed, 1 open)
        assertTrue(orchestrator.isRunningFlow.value)
        assertEquals(3, fakeLogs.sessions.size)
        val openSessionsAfterStarts = fakeLogs.sessions.values.filter { it.endTimeMonotonicMs == null }
        assertEquals(1, openSessionsAfterStarts.size)
        assertTrue(orchestrator.registeredSensorListenerCount <= 1)

        // Now stop() and verify everything is closed and unregistered
        orchestrator.stop()
        orchestrator.awaitIdle()

        assertFalse(orchestrator.isRunningFlow.value)
        val remainingOpenSessions = fakeLogs.sessions.values.filter { it.endTimeMonotonicMs == null }
        assertEquals(0, remainingOpenSessions.size)
        assertEquals(0, orchestrator.registeredSensorListenerCount)
    }
}
