# Pretty Nights — Real-World Tuning & Bosch Live Data V1.0 Test Plan

## Core Architectural Invariant: Always-On Full Telemetry Capture
Every ride session—whether **WLED Active** or **Log Only Mode (No WLED)**—records 100% of all telemetry streams simultaneously:
1. **50 Hz IMU Linear Acceleration** (`accelX`, `accelY`, `accelZ`, `fusedAcceleration`, calibrated axis & polarity).
2. **GPS Ground Speed** (`gpsSpeedKmh`).
3. **Bosch eBike Systems — The smart system — Live Data Interface V1.0 (`eb20`/`eb21`)**:
   - Every raw `com.bosch.ebike.LiveData` notification packet (`speed`, `cadence`, `rider_power`, `ambient_brightness`, `battery_soc`, `time`, `odometer`, `bike_light`, `system_locked`, `charger_connected`, `light_reserve_state`, `diagnosis_program_active`, `bike_not_driving`), negotiated `ATT_MTU`, and decode status.
   - Merged live Bosch values on every 50 Hz IMU tick (`boschSpeedKmh`, `boschAgeMs`, `boschBikeNotDriving`, `boschCadenceRpm`, `boschRiderPowerW`, `boschAmbientLux`, `boschBatterySoc`, `boschBikeLight`).
4. **Live Rider Event Markers** (`HARD_BRAKE`, `LIGHT_BRAKE`, `ROAD_BUMP`, `BOSCH_GLITCH`) and **State Transitions** (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`).

> **Decoupled Capture vs. Control**: Changing `speedSource` (`GPS_ONLY`, `BOSCH_PREFERRED`, `BOSCH_ONLY`) or Tuning corroboration toggles (`useBoschCorroboration`, `requireBoschIdleConfirm`) **never disables recording**. You can ride with `GPS_ONLY` controlling the lights while still capturing 100% of Bosch Live Data V1.0 packets in the background, and then replay the exact same ride using Bosch speed (`field 1`) or `bike_not_driving` (`field 25`) in the Tuning Replay Simulator.

---

## Phase 1: Baseline Data Collection (With or Without WLED Hardware)
1. **Mount & Calibrate**:
   - Secure the phone in your bike mount and verify the forward axis (`X`, `Y`, or `Z`) and polarity sign (`+1` or `-1`) in **Settings**.
2. **Select Ride Mode**:
   - If riding with your WLED controller powered on, leave **Log Only Mode (No WLED)** off.
   - If riding without WLED hardware attached, scroll to the bottom of the **Home** screen (below the bike visualizer card) and toggle **Log Only Mode (No WLED)** on.
3. **Stamp Live Ground-Truth Markers**:
   - During the ride, tap the **Stamp Live Event Marker** buttons on the Home dashboard right after:
     - **Hard Brake** (`HARD_BRAKE`): Intentional firm stop from cruising speed.
     - **Light Brake** (`LIGHT_BRAKE`): Gentle speed check or feathering brakes.
     - **Road Bump** (`ROAD_BUMP`): Pothole, cobblestone, or curb drop where the brake light should **not** trigger.
     - **Bosch Glitch** (`BOSCH_GLITCH`): Any perceived lag or dropout on the eBike display/BLE link.
4. **Post-Ride Labeling**:
   - Open **History**, tap the **Edit Notes** icon on the session, and record road surface, mount rigidity, and Bosch eBike firmware version.

---

## Phase 2: Bosch Live Data Interface V1.0 (Release v19) Reliability Audit
Open the recorded ride in **History** and inspect the **Bosch Live Data V1.0 (`eb20`/`eb21`) Report**:
1. **MTU & Truncation Check (`LDI-003`)**:
   - Confirm `Negotiated ATT_MTU >= 247 bytes` and `100.0% valid` protobuf decode rate.
   - If `ATT_MTU < 247`, Release v19 firmware truncates notifications without disconnecting (`LDI-003`).
2. **Freshness & Notification Cadence (`LDI-002`)**:
   - Check **Freshness Uptime (<4s)** and **Notification Cadence** (`avg ms` / `max ms`).
   - Verify whether unchanged fields are omitted dynamically (`LDI-002`) by inspecting `present_fields_mask` in the exported CSV.
3. **Velocity & Standstill Corroboration (`Field 1` vs GPS & `Field 25`)**:
   - Compare **Mean Speed Delta `|Bosch - GPS|`** during steady cruising vs. rapid deceleration (wheel sensor responds faster than GPS during hard stops).
   - Inspect **`bike_not_driving` (`field 25`)** transitions at traffic lights to evaluate whether enabling `useBoschCorroboration` + `requireBoschIdleConfirm` eliminates false GPS drift while stopped.

---

## Phase 3: Iterative Parameter Tuning via In-App Replay Simulator
1. Open **Settings**, tap **Developer & Tuning Dashboard** (`TuningScreen`), and locate the **In-App Ride Replay Simulator** card.
2. Select your recorded ride session and choose the **Replay Speed Stream**:
   - **Recorded**: Uses the speed source that was active during the ride.
   - **Bosch eb21 (#1)**: Forces the state machine to use recorded Bosch wheel speed (`LiveData.speed`).
   - **GPS Only**: Forces the state machine to use recorded GPS speed.
3. Adjust sliders (`brakingDecelThreshold`, `brakingDebounceMs`, `brakingCooldownMs`, `idleEnterSpeedKmh`, `idleExitSpeedKmh`, `useBoschCorroboration`, `requireBoschIdleConfirm`) and tap **Run Replay with Current Sliders**:
   - Target **100% Brake Markers Detected** (`HARD_BRAKE` / `LIGHT_BRAKE` within `±750 ms`).
   - Target **100% Road Bumps Rejected** (`ROAD_BUMP` markers that do not trigger `BRAKING`).

---

## Phase 4: External CSV Analysis & Regression Archive
- Tap **Export CSV** on any session in **History** to share the unified CSV containing:
  - Session metadata & Bosch V1.0 reliability summary headers.
  - Stamped in-ride event markers (`# IN_RIDE_EVENT_MARKERS`).
  - Raw Bosch `com.bosch.ebike.LiveData` notification packets (`# BOSCH_LIVE_DATA_V1_NOTIFICATIONS`).
  - Full 50 Hz unified IMU + GPS + Bosch + State Machine telemetry (`# UNIFIED_50HZ_RIDE_TELEMETRY`).
