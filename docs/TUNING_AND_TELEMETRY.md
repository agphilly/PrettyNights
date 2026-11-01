# Pretty Nights — Tuning & Telemetry Specification

## 1. The 12-Step Practical Tuning Methodology
To tune the bike's state transitions, brake detection, and lighting responsiveness without introducing regressions, follow this disciplined workflow:

1. **Define the Tuning Objective**: Name the behavior to improve in one sentence (e.g., *"Eliminate false brake pulses caused by road vibrations while cruising at 20 km/h"*).
2. **Establish a Baseline**: Record current thresholds and sensor assumptions before changing any values. Use **Log Only Mode (No WLED)** at the bottom of the Home screen if collecting baseline rides without WLED hardware installed.
3. **Break into 5 Domains**:
   - **Domain A: Ride-State Logic** (Speed thresholds, decel sensitivity, hold/fade duration, Bosch standstill confirmation).
   - **Domain B: WLED Control Behavior** (Brightness envelope, override timing, reconnect recovery).
   - **Domain C: Audio-Reactivity** (FFT sensitivity, gain/squelch, frequency smoothing).
   - **Domain D: User Experience** (Night legibility, distraction safety, live event marker ergonomics).
   - **Domain E: Safety & Edge Cases** (GPS dropout, Bosch BLE `LDI-002`/`LDI-003` edge cases, WLED offline fallback).
4. **Decide Validation Criteria**: Define trigger condition, expected state transition, acceptable latency, and false-positive tolerance.
5. **Execute Scenario Test Matrix & Stamp Live Markers**: Test against standardized conditions (Smooth cruise, Stop-and-go, GPS jitter, Sudden braking, Potholes) while stamping `HARD_BRAKE`, `LIGHT_BRAKE`, `ROAD_BUMP`, and `BOSCH_GLITCH` markers on the Home screen.
6. **Tune in Small Increments (Live or via Replay Simulator)**: Change only ONE variable at a time and test it immediately against recorded rides using the **In-App Ride Replay Simulator** in **Dev Tuning**.
7. **Capture Evidence**: Record exact before/after values, tested scenario, and replay marker detection/rejection scores.
8. **Focus on Meaningful Levers**: Prioritize `brakingDecelThreshold`, `brakingDebounceMs`, and `brakingCooldownMs` over cosmetic adjustments.
9. **Follow Priority Order**:
   1. Safety-Critical Ride Logic
   2. WLED Reliability & Response
   3. Bosch BLE & GPS Sensor Fusion Stability
   4. Audio-Reactivity Stability
   5. UX Quality & Visual Polish
10. **Keep a Decision Record**: Record notes directly on the ride session in **History** (`Edit Notes`) and in `docs/CHANGELOG.md`.
11. **Define 'Good Enough'**: Stop tuning once criteria (e.g., 100% `HARD_BRAKE`/`LIGHT_BRAKE` markers detected within `±750 ms` and 100% `ROAD_BUMP` markers rejected) are satisfied.
12. **Promote to Requirement**: Convert validated parameters into formal defaults in `StateMachineConfig` and `FEATURE_SPECIFICATIONS.md`.

---

## 2. Factory Baseline Reference Table (`StateMachineConfig`)

| Parameter | Default Value | Unit / Range | Description |
| :--- | :--- | :--- | :--- |
| `globalMinBrightness` | `0.20` | `0.0 – 1.0` | Output floor for all looks; prevents complete blackout |
| `globalMaxBrightness` | `0.80` | `0.0 – 1.0` | Ambient-state ceiling (`IDLE` / `CRUISING` / `BRAKING_HOLD`) |
| `brakingIgnoresGlobalMax` | `true` | `Boolean` | When `true`, active `BRAKING` ramps up to `1.0` regardless of `globalMaxBrightness` |
| `idleEnterSpeedKmh` | `2.0` | `km/h (0 – 15)` | Speed below which the bike is eligible to enter `IDLE` or `BRAKING_HOLD` |
| `idleExitSpeedKmh` | `4.0` | `km/h (0 – 20)` | Speed above which the bike exits `IDLE` or `BRAKING_HOLD` and enters `CRUISING` |
| `idleEntryFadeS` | `0.6` | `Seconds (0 – 5)` | Entry fade duration when transitioning into `IDLE` |
| `idleBrightness` | `0.15` | `0.0 – 1.0` | Clean target brightness in `IDLE` (pre-envelope) |
| `requireBoschIdleConfirm` | `false` | `Boolean` | When `useBoschCorroboration` is enabled, requires Bosch `bike_not_driving` (field 25) to confirm `IDLE` entry |
| `cruisingEntryFadeS` | `0.5` | `Seconds (0 – 5)` | Entry fade duration when transitioning into `CRUISING` |
| `cruisingBrightness` | `0.55` | `0.0 – 1.0` | Clean target brightness in `CRUISING` (pre-envelope) |
| `brakingEntryFadeS` | `0.0` | `Seconds (0 – 3)` | Entry fade duration for `BRAKING` onset (`0.0` = instantaneous brake light) |
| `brakingDecelThreshold` | `-1.50` | `m/s² (-6.0 – -0.2)` | Calibrated longitudinal deceleration threshold required to assert `BRAKING` |
| `brakingDebounceMs` | `180` | `ms (0 – 1000)` | Continuous persistence duration below `brakingDecelThreshold` required to reject potholes |
| `brakingCooldownMs` | `350` | `ms (0 – 2000)` | Hold duration after deceleration eases before returning to `CRUISING` |
| `brakingMinBrightness` | `0.60` | `0.0 – 1.0` | Initial clean brightness at `BRAKING` onset (`v_current == v_entry`) |
| `brakingMaxBrightness` | `1.00` | `0.0 – 1.0` | Maximum clean brightness as speed approaches zero |
| `brakingGamma` | `1.6` | `Power (0.3 – 4.0)` | Nonlinear gamma exponent controlling brake brightness ramp vs. speed reduction |
| `brakingHoldDurationS` | `2.0` | `Seconds (0 – 10)` | Duration to hold stop brightness in `BRAKING_HOLD` before fading toward `idleBrightness` |
| `brakingFadeDurationS` | `1.0` | `Seconds (0 – 10)` | Duration of linear fade from `holdStartBrightness` down to `idleBrightness` |
| `useBoschCorroboration` | `false` | `Boolean` | Enables Bosch eBike Live Data V1.0 standstill/speed corroboration in `RideStateMachine` |

---

## 3. Real-Time Diagnostics & Telemetry Snapshot (`StateMachineTelemetry`)
`RideOrchestrator` continuously emits a `StateMachineTelemetry` snapshot over `telemetryFlow`, sampled at 6.7 Hz (`150 ms`) in `TuningTelemetryCard`:
- **Core State Machine Metrics**:
  - `state`: Current active `RideState` (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`).
  - `brightness`: Exact post-envelope normalized brightness (`0.0 – 1.0`).
  - `timeInStateMs`: Elapsed milliseconds in the current state.
  - `entrySpeedKmh`: Speed captured at `BRAKING` onset.
  - `decelActive`, `inEntryFade`, `holdFadePhase` (`"n/a"`, `"hold"`, `"fade"`).
  - `logOnlyMode`: Whether WLED network traffic is currently bypassed.
- **Parallel Velocity & Bosch Live Data V1.0 (`eb20`/`eb21`) Metrics**:
  - `activeSpeedSource`: Stream currently driving the state machine (`GPS`, `BOSCH`, `GPS_FALLBACK`, `BOSCH_STALE`).
  - `gpsSpeedKmh` & `boschSpeedKmh`: Simultaneous GPS and Bosch (`field 1`) speeds.
  - `boschAgeMs`, `boschNotifications`, `boschDecodeErrors`, `boschNegotiatedMtu`.
  - `boschBatterySoc` (`field 10`), `boschCadenceRpm` (`field 2`), `boschRiderPowerW` (`field 5`), `boschAmbientLux` (`field 9`), `boschBikeLightRaw` (`field 17`), `boschBikeNotDriving` (`field 25`).

---

## 4. In-App Ride Replay Simulator (`TuningReplayCard`)
Located in **Dev Tuning** (via **Settings $\rightarrow$ Developer & Tuning Dashboard**), the Replay Simulator evaluates any recorded 50 Hz session (`ImuSampleEntity` + `RideMarkerEntity`) against your current slider values in milliseconds:
- **Selectable Replay Speed Stream**:
  - `Recorded`: Replays using the speed value that controlled the state machine during the ride.
  - `Bosch eb21 (#1)`: Replays using recorded `boschSpeedKmh` (`com.bosch.ebike.LiveData` field 1) with GPS fallback if a sample has no Bosch value.
  - `GPS Only`: Replays using recorded `gpsSpeedKmh`.
- **Automated Ground-Truth Scoring**:
  - **Brake Markers Detected**: Percentage of stamped `HARD_BRAKE` and `LIGHT_BRAKE` markers that have a simulated `BRAKING` or `BRAKING_HOLD` interval within `±750 ms`.
  - **Road Bumps Rejected**: Percentage of stamped `ROAD_BUMP` markers where the state machine stayed out of `BRAKING` within `±750 ms`.

---

## 5. Standalone 50 Hz Web Telemetry & State-Machine Workbench (`/tools/telemetry-workbench/`)
For deep visual inspection of tricky road events on a larger screen, open `/tools/telemetry-workbench/index.html` in any desktop browser (or host `/tools/telemetry-workbench/` on GitHub Pages):
1. **Load Exported CSVs or Built-In Tricky Ride**:
   - Drag and drop any exported `prettynights_session_<id>.csv` onto the canvas, or click **Load Built-In Tricky Ride (50 Hz)** to inspect a 30-second (`1,500`-tick) reference capture featuring a sharp pothole (`ROAD_BUMP`), a light check-brake (`LIGHT_BRAKE`), downhill brake-pumping (`HARD_BRAKE`), and an emergency stop with 1 Hz GPS lag vs. Bosch `eb21` wheel speed.
2. **Synchronized 5-Track Oscilloscope**:
   - **BEFORE (Recorded)** and **AFTER (Simulated)** state ribbons stacked directly above a violet **DIFF (`Before ≠ After`)** band so you can immediately see every 20 ms tick where your new slider values alter state transitions.
   - Toggleable signal overlays (`Fused Accel`, `Raw Accel X/Y/Z`, `Threshold Overlays`, `Active Speed`, `Bosch eb21 Speed`, `GPS 1Hz Speed`, `Brightness Rec vs Sim`, `Bosch Age Sawtooth`, and `Cadence & Power`) with mouse-wheel zoom and a 20 ms crosshair HUD showing live `decelDebounceElapsedMs` and `cooldownElapsedMs` timers.
3. **One-Click JSON & Kotlin Round-Trip to Android**:
   - Click **Sync Config (JSON / Kotlin)** in the Web Workbench to copy the tuned `StateMachineConfig` JSON (or Kotlin code snippet).
   - Open **Settings → Developer / Tuning** in the Android app, tap **Import / Edit JSON** inside the **Web Workbench Config Sync** card, and paste the JSON to apply and persist all 20 parameters to Jetpack DataStore.
