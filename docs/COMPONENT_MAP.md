# Pretty Nights — Component Map & Directory Catalog

### 1. Package Directory Hierarchy
 
```
app/src/main/java/com/example/
├── MainActivity.kt               # Entry-point Activity, bottom navigation bar, edge-to-edge M3 scaffold
├── PrettyNightsApp.kt            # Application entrypoint hosting AppContainer
├── AppContainer.kt               # Central dependency injection container & ViewModel factories
├── config/                       # Configuration persistence & data models
│   ├── ConfigManager.kt          # AppConfig, ChannelConfig, SegmentConfig, WledLook, DataStore JSON persistence
│   └── DomainEnums.kt            # Typed domain enums: SpeedSourceMode, SensorAxis, RideMarkerType, Io13PinRole, SegmentRoleType, SegmentRole
├── home/                         # Home Cockpit Dashboard & real-time monitoring
│   ├── HomeViewModel.kt          # Home state holder & WLED/Ride/Music orchestration
│   ├── HomeScreen.kt             # Dashboard layout: Readiness, Action Controls, Visualizer, bottom Log-Only card
│   ├── HomeReadinessCard.kt      # WLED reachability pill (or LOG ONLY badge), Bosch BLE status, live metrics
│   ├── HomeActionControls.kt     # Mode selector, Start/Stop CTA, In-Ride Event Marker bar, HomeLogOnlyModeCard
│   └── HomeVisualizerCard.kt     # Interactive bike frame LED preview & 16-band live audio FFT visualizer
├── patterns/                     # Lighting Look customizer & WLED presets
│   ├── PatternsViewModel.kt      # State holder for 4 state looks & WLED controller metadata discovery
│   ├── PatternsScreen.kt         # Per-state look cards (IDLE, CRUISING, BRAKING, BRAKING_HOLD) & controller sync
│   ├── StateLookCard.kt          # Expandable look card shell, Preset/Custom mode tabs, and live preview trigger
│   ├── PresetLookSection.kt      # WLED controller preset selector dropdown & brightness override notice
│   ├── CustomLookSection.kt      # Effect/palette dropdowns, 3-slot RGB sliders & per-segment transition timing
│   └── ColorPickerSliders.kt     # Interactive 3-slot RGB color swatches and channel sliders
├── settings/                     # User preferences & hardware calibration (Config Tab)
│   ├── SettingsViewModel.kt      # State holder for network, channels/segments, speed source & IMU calibration
│   ├── SettingsScreen.kt         # System & Hardware Config container and entry point to Developer Tuning
│   ├── WledNetworkSettingsCard.kt# WLED hostname sanitizer, UDP Audio Sync port, connection test
│   ├── WledHardwareConfigCard.kt # Multi-channel GPIO & SegmentRole (Ambient/Brake) range editor & WLED provisioning
│   ├── SpeedSourceSettingsCard.kt# Speed source selector (GPS_ONLY, BOSCH_PREFERRED, BOSCH_ONLY) & BLE status
│   └── SensorCalibrationCard.kt  # 3-axis (X/Y/Z) & polarity (+1/-1) mount calibration + 1-tap auto-detect
├── tuning/                       # State machine live diagnostics, replay simulator & parameter tuning
│   ├── TuningViewModel.kt        # State holder for live telemetry, manual override, replay simulator & parameter edits
│   ├── TuningScreen.kt           # Developer Tuning dashboard container & reset/validation header
│   ├── TuningTelemetryCard.kt    # 150ms sampled state machine + Bosch eb21 live diagnostic card
│   ├── TuningManualOverrideCard.kt# Stationary manual look override (IDLE, CRUISING, BRAKING, BRAKING_HOLD + brightness)
│   ├── TuningReplayCard.kt       # In-App Ride Replay Simulator (Recorded / Bosch eb21 / GPS speed streams)
│   ├── TuningConfigSyncCard.kt   # JSON & Kotlin StateMachineConfig import/export bridge for Web Workbench
│   └── TuningParameterSections.kt# Expandable parameter cards for all StateMachineConfig thresholds & fades
├── history/                      # Ride history, Bosch V1.0 reliability audit & CSV export
│   ├── HistoryViewModel.kt       # State holder for recorded sessions, SQL summaries, notes & CSV export
│   ├── HistoryScreen.kt          # Expandable session cards, Bosch V1.0 report, notes dialog, marker badges
│   └── SessionExportHelper.kt    # 4-section unified CSV exporter shared via Android FileProvider
├── ride/                         # Ride lifecycle, parallel sensors, Bosch BLE & foreground services
│   ├── RideOrchestrator.kt       # 50Hz sensor coordinator, role-based segment fan-out, WLED rate-limiter, Room batch logger
│   ├── RideService.kt            # Foreground service (location) hosting RideOrchestrator with wake lock
│   ├── MusicService.kt           # Foreground service (microphone|mediaProjection) 1024-pt FFT & UDP V2 streamer
│   ├── SpeedProvider.kt          # Always-on parallel GpsSpeedProvider & BoschSpeedProvider + FusedSpeedProvider
│   └── BoschLiveData.kt          # Bosch Live Data Interface V1.0 (eb20/eb21) BLE client & protobuf decoder
├── statemachine/                 # Core deterministic domain logic
│   └── RideStateMachine.kt       # RideState, RideSample, StateMachineConfig, StateMachineTelemetry, evaluator
├── wled/                         # WLED hardware communication & local interface binding
│   ├── WledController.kt         # Bound HTTP JSON client (sendComposedState, pushHardwareAndSegments, /presets.json, /json/eff, /json/pal)
│   └── WledNetworkTransport.kt   # Dual-mode USB-C Ethernet priority, strict Wi-Fi socket binding & USB Serial OTG
├── logging/                      # Persistent SQLite database (Room v3)
│   └── LoggingDatabase.kt        # Entities & DAOs: sessions, state_intervals, wled_intervals, imu_samples, bosch_samples, ride_markers
├── safety/                       # Rider distraction prevention
│   └── LockController.kt         # Auto-locks configuration UI when moving (CRUISING / BRAKING / BRAKING_HOLD)
└── ui/theme/                     # Material 3 styling
    ├── Color.kt                  # Theme palette (Neon Cyan, Electric Purple, Amber, Red, Dark Surfaces)
    ├── Theme.kt                  # Material 3 Theme setup
    └── Type.kt                   # Typography definitions
```

---

## 2. Key Component Responsibilities & Lifecycles

| Component | Package | Role & Responsibility | Lifecycle / Scope |
| :--- | :--- | :--- | :--- |
| `PrettyNightsApp` | Root | Central dependency container: instantiates `ConfigManager`, `LoggingDatabase`, `LockController`, `WledController`, `RideOrchestrator`. | Application Process |
| `MainActivity` | Root | Top-level host; manages bottom navigation (`HOME`, `PATTERNS`, `HISTORY`, `SETTINGS`) and sub-routing to `TUNING`. | UI Activity |
| `RideService` | `com.example.ride` | Foreground service with persistent notification and wake lock keeping `RideOrchestrator` active when screen is off. | Foreground Service |
| `MusicService` | `com.example.ride` | Foreground service capturing internal playback or mic audio and multicasting 84-byte WLED V2 packets at 50 Hz over the bound local interface. | Foreground Service |
| `RideOrchestrator` | `com.example.ride` | Listens to 50 Hz linear accelerometer + parallel GPS & Bosch speed; ticks `RideStateMachine`; dispatches WLED calls (unless `logOnlyMode`) and logs 50 Hz telemetry + markers. | Singleton / Active Ride |
| `BoschLiveDataClient` | `com.example.ride` | Advertises GAP Service Solicitation (`eb20`), connects to Bosch smart system eBikes, requests `ATT_MTU = 247` (`LDI-003`), subscribes to `eb21`, and decodes `com.bosch.ebike.LiveData` protobuf notifications. | Active Ride Session |
| `RideStateMachine` | `com.example.statemachine` | Pure deterministic evaluator of ride states (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`) and output brightness curves. | Orchestrator & Replay Simulator |
| `TuningReplayCard` | `com.example.tuning` | Replays recorded 50 Hz `ImuSampleEntity` streams through `RideStateMachine` using live slider values and scores brake/bump marker accuracy. | Composable UI |
| `TuningConfigSyncCard` | `com.example.tuning` | Serializes and imports `StateMachineConfig` JSON and Kotlin code snippets to round-trip tuned parameters with the Web Workbench. | Composable UI |
| `SessionExportHelper` | `com.example.history` | Formats and streams unified 4-section CSV files (Summary, Event Markers, Raw Bosch `eb21` Notifications, 50 Hz Telemetry) to disk and shares via `FileProvider`. | On-Demand Export |
| `ConfigManager` | `com.example.config` | Reads and writes `AppConfig` to Jetpack DataStore asynchronously and emits `configFlow`. | Singleton |
| `LoggingDatabase` | `com.example.logging` | SQLite Room database (v3, composite-indexed) storing sessions, intervals, 50 Hz IMU+GPS+Bosch samples, raw Bosch packets, and rider markers. | Singleton |
| `LockController` | `com.example.safety` | Locks editing interfaces while the bike is moving (`CRUISING`, `BRAKING`, `BRAKING_HOLD`) to protect rider focus. | Singleton |
| `WledController` | `com.example.wled` | Handles HTTP JSON client operations (`/json/state`, `/presets.json`, `/json/eff`, `/json/pal`) over bound local sockets with fast 1x retry and RTT latency tracking. | Singleton |
| `WledNetworkTransport` | `com.example.wled` | Monitors `ConnectivityManager` and `UsbManager` to prioritize USB-C Ethernet (`TRANSPORT_ETHERNET`), lock Wi-Fi sockets (`TRANSPORT_WIFI` without 5G fallback), and mirror JSON over USB Serial (`115200` baud). | Singleton |
| `Web Telemetry Workbench` | `tools/telemetry-workbench/` | Standalone browser/GitHub Pages 50 Hz CSV oscilloscope (`index.html`, `styles.css`, `workbench.js`), 1:1 `JsRideStateMachine` simulator, Before/After diff highlighter, and JSON/Kotlin config bridge. | Desktop / Web Browser |
