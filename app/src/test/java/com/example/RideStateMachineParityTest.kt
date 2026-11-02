package com.example

import com.example.config.AppJson
import com.example.statemachine.RideSample
import com.example.statemachine.RideStateMachine
import com.example.statemachine.StateMachineConfig
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Generates and verifies `/tools/telemetry-workbench/fixtures/parity.json` across 4 scripted
 * 50 Hz ride scenarios (`hard_stop`, `road_bump_rejection`, `slow_roll_to_idle`, `brake_then_release`)
 * so `node tools/telemetry-workbench/parity.test.js` can assert 1:1 parity between Kotlin and JS.
 */
class RideStateMachineParityTest {

    data class ParityTick(
        val timestampMs: Long,
        val longitudinalAccel: Float,
        val speedKmh: Float,
        val bikeNotDriving: Boolean? = null,
        val expectedState: String,
        val expectedBrightness: Float
    )

    data class ParityScenario(
        val name: String,
        val description: String,
        val ticks: List<ParityTick>
    )

    data class ParityFixture(
        val config: StateMachineConfig,
        val scenarios: List<ParityScenario>
    )

    private fun round4(v: Float): Float = (v * 10000f).roundToInt() / 10000f

    private fun runScenario(
        name: String,
        description: String,
        config: StateMachineConfig,
        samples: List<RideSample>
    ): ParityScenario {
        val sm = RideStateMachine { config }
        val firstTs = samples.firstOrNull()?.timestampMs ?: 0L
        sm.getAndResetState(firstTs)

        val ticks = samples.map { s ->
            val out = sm.tick(s)
            ParityTick(
                timestampMs = s.timestampMs,
                longitudinalAccel = round4(s.longitudinalAccel),
                speedKmh = round4(s.speedKmh),
                bikeNotDriving = s.bikeNotDriving,
                expectedState = out.state.name,
                expectedBrightness = round4(out.brightness)
            )
        }
        return ParityScenario(name = name, description = description, ticks = ticks)
    }

    private fun resolveRepoFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.parentFile?.exists() == true || direct.exists()) return direct
        val parent = File("..", relativePath)
        return parent
    }

    @Test
    fun generateAndVerifyStateMachineParityFixture() {
        val cfg = StateMachineConfig()

        // Scenario 1: Hard Stop (IDLE -> CRUISING -> BRAKING -> BRAKING_HOLD -> hold 2s + fade 1s -> IDLE)
        val hardStopSamples = mutableListOf<RideSample>()
        for (t in 0L..400L step 20L) {
            val speed = if (t < 100L) 0f else 22f
            hardStopSamples.add(RideSample(t, 0f, speed))
        }
        for (t in 420L..1200L step 20L) {
            val progress = (t - 420L).toFloat() / (1200L - 420L).toFloat()
            val speed = (22f * (1f - progress)).coerceAtLeast(1.0f)
            hardStopSamples.add(RideSample(t, -3.2f, speed))
        }
        for (t in 1220L..4500L step 20L) {
            hardStopSamples.add(RideSample(t, 0f, 0f))
        }

        // Scenario 2: Road Bump Rejection (sub-180ms pothole spikes during CRUISING must not trigger BRAKING)
        val bumpSamples = mutableListOf<RideSample>()
        for (t in 0L..2000L step 20L) {
            val speed = if (t < 60L) 0f else 18.5f
            val isShortBump1 = t in 400L..480L // 80ms < 180ms debounce
            val isShortBump2 = t in 1100L..1220L // 120ms < 180ms debounce
            val accel = if (isShortBump1 || isShortBump2) -3.8f else 0.0f
            bumpSamples.add(RideSample(t, accel, speed))
        }

        // Scenario 3: Slow Roll to Idle (hysteresis between 4.0 and 2.0 km/h, then gentle entry fade to IDLE)
        val slowRollSamples = mutableListOf<RideSample>()
        for (t in 0L..2400L step 20L) {
            val speed = when {
                t < 80L -> 0f
                t < 700L -> 14.0f
                t < 1300L -> 3.0f // Inside [2.0, 4.0] hysteresis band -> stays CRUISING
                else -> 1.2f      // Below 2.0 km/h -> transitions to IDLE
            }
            slowRollSamples.add(RideSample(t, -0.3f, speed))
        }

        // Scenario 4: Brake Then Release (BRAKING -> brief 200ms easing within 350ms cooldown -> full release -> CRUISING)
        val brakeReleaseSamples = mutableListOf<RideSample>()
        for (t in 0L..2600L step 20L) {
            val speed = when {
                t < 80L -> 0f
                t < 400L -> 25.0f
                t < 1400L -> 16.0f
                else -> 18.0f
            }
            val accel = when {
                t in 400L..700L -> -2.8f   // 300ms decel -> enters BRAKING at 580ms
                t in 720L..900L -> -0.2f   // 180ms brief ease (< 350ms cooldown) -> stays BRAKING
                t in 920L..1200L -> -2.6f  // Re-applies brake
                else -> 0.1f               // Full release -> exits BRAKING after 350ms cooldown
            }
            brakeReleaseSamples.add(RideSample(t, accel, speed))
        }

        val scenarios = listOf(
            runScenario("hard_stop", "Hard brake from 22 km/h to stop through hold and fade to IDLE", cfg, hardStopSamples),
            runScenario("road_bump_rejection", "Sub-debounce road bumps rejected while cruising", cfg, bumpSamples),
            runScenario("slow_roll_to_idle", "Coasting through hysteresis band into IDLE entry fade", cfg, slowRollSamples),
            runScenario("brake_then_release", "Braking with brief mid-brake easing followed by release to CRUISING", cfg, brakeReleaseSamples)
        )

        // Verify expected state coverage across scenarios
        assertTrue(scenarios[0].ticks.any { it.expectedState == "BRAKING" })
        assertTrue(scenarios[0].ticks.any { it.expectedState == "BRAKING_HOLD" })
        assertEquals("IDLE", scenarios[0].ticks.last().expectedState)
        assertTrue(scenarios[1].ticks.none { it.expectedState == "BRAKING" })
        assertEquals("IDLE", scenarios[2].ticks.last().expectedState)
        assertEquals("CRUISING", scenarios[3].ticks.last().expectedState)

        val fixture = ParityFixture(config = cfg, scenarios = scenarios)
        val adapter = AppJson.moshi.adapter(ParityFixture::class.java).indent("  ")
        val json = adapter.toJson(fixture)

        val fixtureFile = resolveRepoFile("tools/telemetry-workbench/fixtures/parity.json")
        fixtureFile.parentFile?.mkdirs()
        fixtureFile.writeText(json + "\n")

        // Re-read and verify deserialization matches tick-for-tick
        val loaded = adapter.fromJson(fixtureFile.readText())!!
        assertEquals(fixture.config, loaded.config)
        assertEquals(4, loaded.scenarios.size)
        for (i in scenarios.indices) {
            assertEquals(scenarios[i].name, loaded.scenarios[i].name)
            assertEquals(scenarios[i].ticks.size, loaded.scenarios[i].ticks.size)
            for (j in scenarios[i].ticks.indices) {
                val expected = scenarios[i].ticks[j]
                val actual = loaded.scenarios[i].ticks[j]
                assertEquals(expected.expectedState, actual.expectedState)
                assertTrue(abs(expected.expectedBrightness - actual.expectedBrightness) <= 0.001f)
            }
        }
    }
}
