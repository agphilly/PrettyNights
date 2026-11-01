# Pretty Nights — System Architecture

## 1. System Overview & Vision
**Pretty Nights** is an intelligent, reactive lighting and 50 Hz telemetry system for night biking. It connects an Android device to a WLED-controlled addressable LED strip and parallel bike telemetry sources (**GPS** and **Bosch eBike Systems — The smart system — Live Data Interface V1.0** via BLE `eb20`/`eb21`). The app automatically adjusts lighting modes and brightness according to ride dynamics (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`), supports real-time audio-reactive lighting synchronization via low-latency UDP streaming (`239.0.0.1:11988`), and includes a **Log Only Mode (No WLED)** for capturing baseline telemetry and ground-truth rider markers on any ride.

```
                    +-------------------------------------------+
                    |     Parallel Sensors & Telemetry          |
                    |  - 50 Hz Linear Accel (IMU)               |
                    |  - Always-On GPS + Bosch BLE (eb20/eb21)  |
                    +---------------------+---------------------+
                                          |
                                          v (50 Hz)
+-------------------+   +-----------------+---+   +------------------------+
|  ConfigManager    |-->|  RideStateMachine   |-->|   RideOrchestrator     |
|  (DataStore JSON) |   |  - 4-State Logic    |   |   - WLED Rate Limiter  |
+-------------------+   |  - Gamma Envelope   |   |   - 50Hz Batch Logger  |
                        +---------------------+   |   - Live Marker Stamp  |
                                                  +-----------+------------+
                                                              |
                  +-------------------------------------------+-----------------------------------+
                  |                                           |                                   |
                  v (HTTP / Skipped in Log-Only)              v (Room SQLite DB)                  v (StateFlow)
        +-------------------+                       +--------------------+              +--------------------+
        |  WLED Controller  |                       |  LoggingDatabase   |              |  Compose UI Layer  |
        |  - /json/state    |                       |  - Sessions/Notes  |              |  - Home / Markers  |
        |  - Presets & FX   |                       |  - 50Hz IMU+Bosch  |              |  - History / CSV   |
        +-------------------+                       |  - Raw eb21 Packets|              |  - Tuning Replay   |
                                                    +--------------------+              +--------------------+
```

---

## 2. Core Architectural Layers

### A. Always-On Telemetry & Sensor Pipeline (`com.example.ride` & `com.example.statemachine`)
1. **Sensors & Parallel Speed Providers**:
   - **Linear Acceleration Sensor** (`Sensor.TYPE_LINEAR_ACCELERATION`): Sampled at ~50 Hz (`SENSOR_DELAY_GAME`). Gravity-compensated acceleration is projected along the calibrated mount axis (`X`, `Y`, or `Z`) and polarity sign (`+1` or `-1`) to isolate longitudinal acceleration/deceleration.
   - **Always-On Parallel Speed & Bosch Capture (`GpsSpeedProvider` + `BoschSpeedProvider` + `BoschLiveDataClient`)**: Both GPS and Bosch BLE Live Data V1.0 (`0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4` / `0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4`) run and record simultaneously on every ride. The configured `speedSource` (`GPS_ONLY`, `BOSCH_PREFERRED`, `BOSCH_ONLY`) only selects which velocity stream drives live `RideStateMachine` transitions.
2. **RideStateMachine**:
   - Pure functional state machine running without Android UI dependencies (used both live in `RideOrchestrator` and offline in `TuningReplayCard`).
   - Computes state transitions (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`) using debounce timers (`brakingDebounceMs`), cooldown periods (`brakingCooldownMs`), speed thresholds (`idleEnterSpeedKmh`, `idleExitSpeedKmh`), and optional Bosch standstill corroboration (`useBoschCorroboration`, `requireBoschIdleConfirm`).
   - Calculates target brightness using gamma power curves (`brakingGamma`) and transition fades (`idleEntryFadeS`, `cruisingEntryFadeS`, `brakingEntryFadeS`, `brakingHoldDurationS`, `brakingFadeDurationS`).

### B. Orchestration & Fan-Out (`RideOrchestrator`)
- **Central Coordinator**: Receives 50 Hz sensor samples, executes state machine ticks, and fans out results:
  1. **WLED Throttling Sink (or Log-Only Bypass)**: When `logOnlyMode == false`, sends immediate state/look changes to `WledController` while rate-limiting continuous brightness ramps to every `250 ms` (or on jumps `>= 0.10`). When `logOnlyMode == true`, bypasses WLED network traffic completely while continuing full state evaluation and telemetry recording.
  2. **Room Database Interval & 50 Hz Batch Logger**: Records open/closed intervals for states (`StateIntervalEntity`) and WLED looks (`WledIntervalEntity`), stamps live rider markers (`RideMarkerEntity`), logs raw Bosch `eb21` protobuf packets (`BoschSampleEntity`), and batch-inserts unified 50 Hz IMU + GPS + Bosch samples (`ImuSampleEntity`) every `1200 ms`.
  3. **Safety Lock Manager**: Notifies `LockController` to engage UI safety locks while the bike is in `CRUISING`, `BRAKING`, or `BRAKING_HOLD`.
  4. **Diagnostics Flow**: Emits real-time state, speed, acceleration, and Bosch `eb21` telemetry to `telemetryFlow` for live UI display.

### C. Background Foreground Services (`com.example.ride`)
- **`RideService`**: An Android Foreground Service (`foregroundServiceType="location"`) running in the background during active rides. Keeps CPU wake locks active and orchestrates sensors, Bosch BLE, and WLED communication when the screen is off or the user navigates away.
- **`MusicService`**: An independent Foreground Service (`foregroundServiceType="mediaProjection|microphone"`) running real-time 1024-point Hann FFT capture and 50 Hz UDP multicast (`239.0.0.1:11988`) for music-reactive lighting.

### D. Configuration & Persistence (`com.example.config` & `com.example.logging`)
- **Jetpack DataStore (`ConfigManager`)**: Serializes `AppConfig` into atomic preferences (`prettynights_config`), including `wledHost`, `audioSyncPort`, `logOnlyMode`, `speedSource`, sensor mount calibration, per-state `WledLook` profiles, and `StateMachineConfig`.
- **Room SQLite (`LoggingDatabase`, version 3)**: Stores persistent ride history across 6 entities:
  - `sessions` (`SessionEntity`): Session ID, start/end wall-clock and monotonic timestamps, title, notes, `logOnlyMode`, and `configuredSpeedSource`.
  - `state_intervals` (`StateIntervalEntity`): State duration, transition boundaries, start/end brightness.
  - `wled_intervals` (`WledIntervalEntity`): Exact JSON look active per episode.
  - `imu_samples` (`ImuSampleEntity`): Unified 50 Hz raw/projected acceleration + GPS speed + merged Bosch `eb21` fields + active state.
  - `bosch_samples` (`BoschSampleEntity`): Every raw `com.bosch.ebike.LiveData` notification packet with `negotiatedMtu`, `presentFieldsMask`, decoded fields, and simultaneous GPS speed.
  - `ride_markers` (`RideMarkerEntity`): Rider-stamped in-ride ground-truth markers (`HARD_BRAKE`, `LIGHT_BRAKE`, `ROAD_BUMP`, `BOSCH_GLITCH`).

### E. User Interface Layer (`Jetpack Compose`)
- Multi-tab Material 3 design with full edge-to-edge layout (`enableEdgeToEdge()`):
  - **Home (`com.example.home`)**: Readiness banner (`WLED` + `Bosch BLE`), mode selector (`Ride Mode` vs `Music Mode`), Start/Stop CTA, live **Stamp Live Event Marker** bar during rides, interactive bike/audio visualizer, and the **Log Only Mode (No WLED)** toggle card at the bottom of the screen.
  - **Patterns (`com.example.patterns`)**: Per-state lighting look studio (`IDLE`, `CRUISING`, `BRAKING`) with preset picker, RGB color sliders, effect/palette selectors, and transition timing.
  - **History (`com.example.history`)**: Session viewer with Bosch Live Data V1.0 reliability audit card, session notes editor, state/marker breakdowns, and 4-section CSV export (`SessionExportHelper`).
  - **Settings (`com.example.settings`)**: WLED network host/port sanitization, live physical transport link banner (`USB-C Ethernet`, `Wi-Fi Bound`, `USB Serial`), speed source priority, 3-axis IMU calibration, and Developer Tuning navigation.
  - **Tuning (`com.example.tuning`)**: 150 ms live state machine + Bosch `eb21` telemetry panel, stationary manual look override, **In-App Ride Replay Simulator** (`TuningReplayCard`), **Web Workbench Config Sync (JSON / Kotlin)** (`TuningConfigSyncCard`), and parameter sliders.

### F. Standalone Web Telemetry & State-Machine Workbench (`/tools/telemetry-workbench/`)
- Zero-dependency desktop browser / GitHub Pages application (`index.html`, `styles.css`, `workbench.js`) that parses 4-section Pretty Nights CSV exports, renders a 5-track synchronized `<canvas>` oscilloscope (`BEFORE` state ribbon, `AFTER` state ribbon, `DIFF` band, Acceleration + Thresholds, and Speed + Bosch + Brightness), runs a 1:1 JavaScript port of `RideStateMachine` in `< 5 ms` as sliders move, and exports/imports `StateMachineConfig` JSON or Kotlin snippets.

---

## 3. Documentation Index (`/docs`)
- **[CHANGELOG.md](./CHANGELOG.md)**: Build-by-build release history, suggested Git commit messages, and the *"Push to GitHub"* workflow.
- **[COMPONENT_MAP.md](./COMPONENT_MAP.md)**: Complete package hierarchy and file-level responsibilities across all 36 Kotlin files and `/tools/telemetry-workbench/`.
- **[PROTOCOLS.md](./PROTOCOLS.md)**: WLED HTTP JSON API, Dual-Mode local socket binding (`TRANSPORT_ETHERNET` / `TRANSPORT_WIFI`), UDP Port 11988 Audio-Reactive V2 packet structure, Bosch Live Data Interface V1.0 (`eb20`/`eb21`) protobuf spec, and IMU calibration math.
- **[TUNING_AND_TELEMETRY.md](./TUNING_AND_TELEMETRY.md)**: 12-step tuning methodology, `StateMachineConfig` reference table, Replay Simulator, and Web Workbench guide.
- **[TUNING_TEST_PLAN.md](./TUNING_TEST_PLAN.md)**: 4-phase real-world ride data collection, Bosch V1.0 reliability audit, iterative replay, and Web Workbench CSV analysis plan.
- **[../tools/telemetry-workbench/README.md](../tools/telemetry-workbench/README.md)**: Standalone 50 Hz Web Telemetry & State-Machine Workbench user guide.
