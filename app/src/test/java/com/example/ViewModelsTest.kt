package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.config.ActiveSpeedSource
import com.example.config.AppConfig
import com.example.config.ConfigManager
import com.example.config.RideMarkerType
import com.example.config.SpeedSourceMode
import com.example.history.HistoryViewModel
import com.example.home.HomeViewModel
import com.example.home.ScreenWledStatus
import com.example.logging.BoschSampleEntity
import com.example.logging.ImuSessionSummary
import com.example.logging.LogRepository
import com.example.logging.RideMarkerEntity
import com.example.logging.SessionEntity
import com.example.logging.StateIntervalEntity
import com.example.patterns.PatternsViewModel
import com.example.ride.BoschLiveDataPacket
import com.example.ride.BoschLiveDataState
import com.example.ride.BoschSpeedProvider
import com.example.ride.RideOrchestrator
import com.example.ride.SpeedProvider
import com.example.safety.LockController
import com.example.settings.SettingsViewModel
import com.example.statemachine.RideState
import com.example.tuning.TuningViewModel
import com.example.wled.WledController
import com.example.wled.WledPreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for all 5 screen ViewModels (`HistoryViewModel`, `TuningViewModel`, `PatternsViewModel`,
 * `SettingsViewModel`, `HomeViewModel`) using fake dependencies and `UnconfinedTestDispatcher`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ViewModelsTest {

    private class FakeMemoryConfigManager(context: Context) : ConfigManager(context) {
        val state = MutableStateFlow(AppConfig())
        override val configFlow: Flow<AppConfig> get() = state
        override suspend fun updateConfig(update: (AppConfig) -> AppConfig) {
            state.value = update(state.value)
        }
    }

    private class FakeHistoryLogRepository : LogRepository(null) {
        val sessionsState = MutableStateFlow(
            listOf(
                SessionEntity(
                    id = 42L,
                    startTimeMillis = 1_700_000_000_000L,
                    startMonotonicMs = 1_000L,
                    endTimeMonotonicMs = 601_000L,
                    configuredSpeedSource = SpeedSourceMode.BOSCH_PREFERRED
                )
            )
        )
        var lastUpdatedMetadata: Triple<Long, String, String>? = null

        override val allSessions: Flow<List<SessionEntity>> get() = sessionsState

        override suspend fun getImuSessionSummary(sessionId: Long): ImuSessionSummary {
            return ImuSessionSummary(totalSamples = 200, freshBoschSamples = 180, maxBoschSpeedKmh = 31.5f)
        }

        override fun getBoschSamples(sessionId: Long): Flow<List<BoschSampleEntity>> =
            flowOf(
                listOf(
                    BoschSampleEntity(
                        sessionId = sessionId,
                        timestampMonotonicMs = 2000L,
                        negotiatedMtu = 247,
                        decodeSuccess = true,
                        speedKmh = 25.0f,
                        gpsSpeedKmhAtCapture = 24.5f,
                        batterySoc = 80
                    )
                )
            )

        override fun getStateIntervals(sessionId: Long): Flow<List<StateIntervalEntity>> = flowOf(emptyList())
        override fun getRideMarkers(sessionId: Long): Flow<List<RideMarkerEntity>> = flowOf(emptyList())

        override suspend fun updateSessionMetadata(sessionId: Long, title: String, notes: String) {
            lastUpdatedMetadata = Triple(sessionId, title, notes)
        }
    }

    private class FakeWledController : WledController(null) {
        var pingResponse = true

        override fun ping(host: String): Boolean = pingResponse

        override fun fetchPresets(host: String): List<WledPreset>? {
            return listOf(WledPreset(1, "Idle Amber"), WledPreset(2, "Cruise Cyan"))
        }

        override fun fetchEffects(host: String): List<String>? {
            return listOf("Solid", "Breathe")
        }

        override fun fetchPalettes(host: String): List<String>? {
            return listOf("Default", "Rainbow")
        }
    }

    private class NoOpSpeedProvider : SpeedProvider {
        override val speed: Float = 0f
        override val lastUpdateTimeMs: Long = 0L
        override val isAvailable: Boolean = true
        override val gpsSpeedMps: Float = 0f
        override val activeSpeedSource: ActiveSpeedSource = ActiveSpeedSource.GPS
        override val boschLiveState: BoschLiveDataState? = null
        override fun start() {}
        override fun stop() {}
        override fun addBoschPacketListener(listener: (BoschLiveDataPacket, BoschLiveDataState) -> Unit) {}
        override fun removeBoschPacketListener(listener: (BoschLiveDataPacket, BoschLiveDataState) -> Unit) {}
    }

    @Test
    fun historyViewModel_loadsSessionDetailsAndSavesNotes() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeRepo = FakeHistoryLogRepository()
        val vm = HistoryViewModel(logRepository = fakeRepo, ioDispatcher = dispatcher)

        vm.loadSessionMetrics(42L)
        val detail = vm.metricsBySessionId.value[42L]
        assertNotNull(detail)
        assertEquals(200, detail!!.imuSummary.totalSamples)
        assertEquals(90.0f, detail.boschReport!!.boschFreshnessUptimePercent, 0.01f)

        vm.updateSessionMetadata(42L, "Evening Commute", "Smooth test ride")
        assertEquals(Triple(42L, "Evening Commute", "Smooth test ride"), fakeRepo.lastUpdatedMetadata)
    }

    @Test
    fun patternsViewModel_loadsDiscoveryAndSavesConfig() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val configManager = FakeMemoryConfigManager(context)
        val fakeWled = FakeWledController()
        val lockController = LockController()

        val vm = PatternsViewModel(
            configManager = configManager,
            lockController = lockController,
            wledController = fakeWled,
            ioDispatcher = dispatcher
        )

        vm.fetchWledData("4.3.2.1")
        assertEquals("Idle Amber", vm.presets.value?.firstOrNull()?.name)
        assertEquals(2, vm.effects.value?.size)

        val updated = AppConfig(wledHost = "192.168.1.50")
        vm.saveConfig(updated)
        assertEquals("192.168.1.50", configManager.state.value.wledHost)
    }

    @Test
    fun settingsAndHomeAndTuningViewModels_updateConfigAndCheckReachability() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val configManager = FakeMemoryConfigManager(context)
        val fakeWled = FakeWledController()
        val lockController = LockController()
        val fakeLogs = FakeHistoryLogRepository()
        val boschProvider = BoschSpeedProvider(context)
        val orchestrator = RideOrchestrator(
            context = context,
            speedProvider = NoOpSpeedProvider(),
            wledController = fakeWled,
            configManager = configManager,
            lockController = lockController,
            logRepository = fakeLogs
        )

        val settingsVm = SettingsViewModel(
            configManager = configManager,
            lockController = lockController,
            wledController = fakeWled,
            ioDispatcher = dispatcher
        )
        settingsVm.testConnection("4.3.2.1")
        assertTrue(settingsVm.testConnectionResult.value?.startsWith("Reachable") == true)

        val homeVm = HomeViewModel(
            configManager = configManager,
            orchestrator = orchestrator,
            boschSpeedProvider = boschProvider,
            wledController = fakeWled,
            ioDispatcher = dispatcher
        )
        homeVm.checkWledReachability("4.3.2.1")
        assertEquals(ScreenWledStatus.REACHABLE, homeVm.wledStatus.value)
        homeVm.toggleLogOnlyMode(true)
        assertTrue(configManager.state.value.logOnlyMode)
        homeVm.stampMarker(RideMarkerType.HARD_BRAKE)

        val tuningVm = TuningViewModel(
            configManager = configManager,
            lockController = lockController,
            orchestrator = orchestrator,
            wledController = fakeWled,
            logRepository = fakeLogs,
            ioDispatcher = dispatcher
        )
        tuningVm.selectOverride(RideState.BRAKING)
        assertEquals(RideState.BRAKING, tuningVm.overrideState.value)
    }
}
