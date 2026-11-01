# Pretty Nights — System Architecture

## 1. System Overview & Vision
**Pretty Nights** is an intelligent, reactive lighting and telemetry system for night biking. It connects an Android device to a WLED-controlled addressable LED strip and optional bike telemetry (such as Bosch eBike systems via BLE or GPS speed providers). The app automatically adjusts lighting modes and brightness according to ride dynamics (Idle, Cruising, Braking, Braking Hold) and supports real-time audio-reactive lighting synchronization via low-latency UDP streaming.

```
                    +-----------------------+
                    |  Sensors / Telemetry  |
                    |  - Linear Accel (IMU) |
                    |  - GPS / Bosch BLE    |
                    +-----------+-----------+
                                |
                                v (50 Hz)
+-------------------+   +---------------+   +--------------------+
|  ConfigManager    |-->| RideState-    |-->| RideOrchestrator   |
|  (DataStore JSON) |   | Machine       |   | - Rate limiter     |
+-------------------+   +---------------+   | - State logger     |
                                            +---------+----------+
                                                      |
                  +-----------------------------------+-----------------------------------+
                  |                                   |                                   |
                  v (HTTP / UDP DDP)                  v (Room DB)                         v (StateFlow)
        +-------------------+               +--------------------+              +--------------------+
        |  WLED Controller  |               |  LoggingDatabase   |              |  Compose UI Layer  |
        |  - LED Strip Look |               |  - SessionEntity   |              |  - Dashboard / Home|
        |  - Brightness     |               |  - Intervals / IMU |              |  - Tuning / History|
        +-------------------+               +--------------------+              +--------------------+
```

---

## 2. Core Architectural Layers

### A. Telemetry & Sensor Pipeline (`com.example.ride` & `com.example.statemachine`)
1. **Sensors**:
   - `Linear Acceleration Sensor` (`Sensor.TYPE_LINEAR_ACCELERATION`): Sampled at ~50Hz (`SENSOR_DELAY_GAME`). Gravity-compensated acceleration is projected along the calibrated axis (X, Y, or Z) and sign (+1 or -1) to isolate forward/backward longitudinal acceleration.
   - `SpeedProvider`: Dispatches speed updates (m/s) from GPS (`LocationManager`) or Bosch BLE (`BoschBleManager`) based on the configured priority mode (`GPS_ONLY`, `BOSCH_PREFERRED`, `BOSCH_ONLY`).
2. **RideStateMachine**:
   - Pure functional state machine running without Android UI dependencies.
   - Computes state transitions (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`) using debounce timers (`brakingDebounceMs`), cooldown periods (`brakingCooldownMs`), and speed thresholds (`idleEnterSpeedKmh`, `idleExitSpeedKmh`).
   - Calculates target brightness using gamma power curves (`brakingGamma`) and transition fades (`idleEntryFadeS`, `cruisingEntryFadeS`, `brakingEntryFadeS`, `brakingHoldDurationS`, `brakingFadeDurationS`).

### B. Orchestration & Fan-Out (`RideOrchestrator`)
- **Central Coordinator**: Receives 50Hz sensor samples, executes state machine ticks, and fans out results:
  1. **WLED Throttling Sink**: Sends immediate state/look changes to `WledController`, while rate-limiting continuous brightness ramps to every 250ms (or on significant jumps > 0.10) to prevent saturating the ESP8266/ESP32 network buffer.
  2. **Room Database Interval Logger**: Records open/closed intervals for states (`StateIntervalEntity`) and WLED looks (`WledIntervalEntity`), buffering IMU samples for high-speed batch insertion.
  3. **Safety Lock Manager**: Notifies `LockController` to engage UI safety locks while the bike is moving.
  4. **Diagnostics Flow**: Emits real-time state, speed, acceleration, and transition reasons to `telemetryFlow` for live UI display.

### C. Background Foreground Services (`com.example.ride`)
- **`RideService`**: An Android Foreground Service (`foregroundServiceType="location|connectedDevice"`) running in the background during active rides. Keeps CPU wake locks active and orchestrates sensors and WLED communication when the screen is off or the user navigates away.
- **`MusicService`**: An independent Foreground Service (`foregroundServiceType="microphone|mediaProjection"`) running real-time audio FFT capture and UDP broadcast (port 11988) for music-reactive lighting.

### D. Configuration & Persistence (`com.example.config` & `com.example.logging`)
- **Jetpack DataStore (`ConfigManager`)**: Serializes `AppConfig` into atomic JSON preferences (`prettynights_config`). Handles WLED hostname, looks (`idleLook`, `cruisingLook`, `brakingLook`, `brakingHoldLook`), sensor calibration, and full `StateMachineConfig`.
- **Room SQLite (`LoggingDatabase`)**: Stores persistent ride history:
  - `sessions`: Session ID, start/end wall-clock and monotonic timestamps, weather metadata.
  - `state_intervals`: State duration, transition boundaries, start/end brightness.
  - `wled_intervals`: Exact JSON look active per episode.
  - `imu_samples`: 50Hz raw and projected acceleration datasets.
  - `bosch_samples`: Speed, battery SoC, and assist mode telemetry.

### E. User Interface Layer (`Jetpack Compose`)
- Multi-tab Material 3 design with full edge-to-edge layout (`enableEdgeToEdge()`):
  - **Home**: Live speed gauge, ride state badge, start/stop ride button, audio visualizer, and WLED reachability banner.
  - **Patterns**: Customizer for lighting presets, solid colors, effects, speed, intensity, and transition durations for each ride state.
  - **History**: Historical ride session viewer with episodic state breakdowns.
  - **Settings**: WLED network parameters, port configuration, and sensor calibration.
  - **Tuning (Dev/Advanced)**: 150ms real-time state machine telemetry panel, manual look override controls, and dynamic slider envelope tuning.

---

## 3. Documentation Index (`/docs`)
- **[CHANGELOG.md](./CHANGELOG.md)**: Build-by-build release history, suggested Git commit messages, and the *"Push to GitHub"* workflow.
- **[COMPONENT_MAP.md](./COMPONENT_MAP.md)**: Complete package hierarchy and file-level responsibilities across all modules.
- **[PROTOCOLS.md](./PROTOCOLS.md)**: WLED HTTP JSON API, UDP Port 11988 Audio-Reactive v2 packet structure, Bosch BLE speed modes, and IMU calibration math.
- **[TUNING_AND_TELEMETRY.md](./TUNING_AND_TELEMETRY.md)**: 12-parameter `StateMachineConfig` reference table, braking gamma math, and field-tuning guide.

