# Pretty Nights — Tuning & Telemetry Specification

## 1. The 12-Step Practical Tuning Methodology
To tune the bike's state transitions, brake detection, and lighting responsiveness without introducing regressions, follow this disciplined workflow:

1. **Define the Tuning Objective**: Name the behavior to improve in one sentence (e.g., *"Eliminate false brake pulses caused by road vibrations while cruising at 20 km/h"*).
2. **Establish a Baseline**: Record current thresholds and sensor assumptions before changing any values.
3. **Break into 5 Domains**:
   - **Domain A: Ride-State Logic** (Speed thresholds, decel sensitivity, hold/fade duration).
   - **Domain B: WLED Control Behavior** (Brightness envelope, override timing, reconnect recovery).
   - **Domain C: Audio-Reactivity** (FFT sensitivity, gain/squelch, frequency smoothing).
   - **Domain D: User Experience** (Night legibility, distraction safety, degraded state indicators).
   - **Domain E: Safety & Edge Cases** (GPS dropout, stale telemetry, WLED offline fallback).
4. **Decide Validation Criteria**: Define trigger condition, expected state transition, acceptable latency, and false-positive tolerance.
5. **Execute Scenario Test Matrix**: Test against standardized conditions (Smooth cruise, Stop-and-go, GPS jitter, Sudden braking, Potholes).
6. **Tune in Small Increments**: Change only ONE variable at a time; keep all other settings constant.
7. **Capture Evidence**: Record exact before/after values, tested scenario, and actual outcome.
8. **Focus on Meaningful Levers**: Prioritize decel thresholds and debounce filters over cosmetic adjustments.
9. **Follow Priority Order**:
   1. Safety-Critical Ride Logic
   2. WLED Reliability & Response
   3. Audio-Reactivity Stability
   4. UX Quality & Night Readability
   5. Visual Polish
10. **Keep a Decision Record**: Note what was learned, whether to keep/revert the change, and the next test.
11. **Define 'Good Enough'**: Stop tuning once criteria (e.g., 0 false brakes over 5 km) are satisfied.
12. **Promote to Requirement**: Convert validated parameters into formal specifications in `FEATURE_SPECIFICATIONS.md`.

---

## 2. Factory Baseline Reference Table (`StateMachineConfig`)

| Parameter | Default Value | Unit / Range | Description |
| :--- | :--- | :--- | :--- |
| `globalMinBrightness` | `0.10` | 0.0 – 1.0 | Output floor for all looks; prevents complete blackout |
| `globalMaxBrightness` | `1.00` | 0.0 – 1.0 | Ambient-state ceiling (Idle / Cruising / Hold Fade) |
| `brakingIgnoresGlobalMax`| `true` | Boolean | When true, active braking ramps to full 1.0 regardless of ceiling |
| `idleEnterSpeedKmh` | `2.5` | km/h (0 – 20) | Speed at or below which the bike is eligible for IDLE |
| `idleExitSpeedKmh` | `4.0` | km/h (0 – 20) | Speed exceeding this exits IDLE and enters CRUISING |
| `idleEntryFadeS` | `1.5` | Seconds (0 – 5) | Fade duration when transitioning into IDLE |
| `idleBrightness` | `0.25` | 0.0 – 1.0 | Static idle brightness (pre-envelope) |
| `cruisingEntryFadeS` | `0.8` | Seconds (0 – 5) | Fade duration when entering CRUISING |
| `cruisingBrightness` | `0.80` | 0.0 – 1.0 | Static cruising brightness |
| `brakingEntryFadeS` | `0.0` | Seconds (0 – 5) | Fade duration for brake light onset (0 = instantaneous safety light) |
| `brakingDecelThreshold` | `-1.50` | m/s² (-10 – 0) | Forward decel threshold past which braking asserts |
| `brakingDebounceMs` | `150` | ms (0 – 1000) | Required persistence time of decel to reject potholes |
| `brakingCooldownMs` | `500` | ms (0 – 2000) | Hold time after decel ceases to bridge brake pumping |
| `brakingMinBrightness` | `0.60` | 0.0 – 1.0 | Initial brightness when braking begins |
| `brakingMaxBrightness` | `1.00` | 0.0 – 1.0 | Maximum brightness as vehicle approaches a full stop |
| `brakingGamma` | `1.8` | Power (0.2 – 4.0)| Nonlinear gamma curve emphasizing intensity near full stop |
| `brakingHoldDurationS` | `3.0` | Seconds (0 – 10)| Duration to hold stop brightness before fading toward idle |
| `brakingFadeDurationS` | `2.0` | Seconds (0 – 10)| Duration of fade from held brake light down to idle |
| `useBoschCorroboration`| `true` | Boolean | Uses Bosch wheel acceleration as soft confirmation |

---

## 3. Real-Time Diagnostics & Telemetry Snapshot
`RideStateMachine` continuously emits a `StateMachineTelemetry` object over `telemetryFlow` sampled at 7 Hz (150ms) in the Tuning screen:
- **`state`**: Current active `RideState`.
- **`currentSpeedKmh`**: Smoothed forward speed.
- **`smoothedAccel`**: Filtered longitudinal acceleration.
- **`lastOutputBrightness`**: Exact post-envelope brightness sent to WLED.
- **`debounceProgressMs`**: Time remaining before decel confirms as braking.
- **`cooldownRemainingMs`**: Time remaining before brake release transitions to hold/fade.
- **`transitionReason`**: Human-readable explanation of the last state shift.
