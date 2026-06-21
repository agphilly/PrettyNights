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

         sm.tick(RideSample(3600L, 0f, 0.5f))
         assertEquals(RideState.IDLE, sm.getCurrentState()) // 3100ms elapsed since entering hold (IDLE)
    }
}
