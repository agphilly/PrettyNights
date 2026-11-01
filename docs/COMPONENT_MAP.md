# Pretty Nights — Component Map & Directory Catalog

## 1. Package Directory Hierarchy

```
app/src/main/java/com/example/
├── MainActivity.kt               # Entry-point Activity, bottom navigation bar, edge-to-edge setup
├── PrettyNightsApp.kt            # Application singleton, dependency container, service bindings
├── config/                       # Configuration persistence & data models
│   └── ConfigManager.kt          # AppConfig, WledLook, Moshi adapters, DataStore preferences
├── home/                         # Home Dashboard & real-time monitoring
│   └── HomeScreen.kt             # Dashboard container, speed gauge, audio visualizer, status banner
├── patterns/                     # Lighting Look customizer & WLED presets
│   └── PatternsScreen.kt         # Look editor, color palette picker, effect list, transition timing
├── settings/                     # User preferences & hardware calibration
│   └── SettingsScreen.kt         # WLED host config, audio port, IMU axis/sign calibration
├── tuning/                       # State machine live diagnostics & parameter tuning
│   └── TuningScreen.kt           # Telemetry panel, manual overrides, threshold sliders
├── history/                      # Ride history & telemetry replay
│   └── HistoryScreen.kt          # Session cards, episodic interval breakdown, database viewer
├── ride/                         # Ride lifecycle, sensors & services
│   ├── RideOrchestrator.kt       # Sensor event dispatcher, WLED rate-limiter, interval logger
│   ├── RideService.kt            # Foreground service hosting RideOrchestrator
│   ├── MusicService.kt           # Foreground service capturing audio FFT and broadcasting UDP
│   └── SpeedProvider.kt          # GPS and Bosch BLE speed abstraction
├── statemachine/                 # Core domain logic
│   ├── RideState.kt              # Enum: IDLE, CRUISING, BRAKING, BRAKING_HOLD
│   ├── RideStateMachine.kt       # Deterministic state machine, debounce, gamma, brightness
│   ├── StateMachineConfig.kt     # Thresholds, fade timings, envelope bounds
│   └── StateMachineTelemetry.kt  # Diagnostic snapshot (speed, accel, active timers, reason)
├── wled/                         # WLED hardware communication
│   ├── WledController.kt         # HTTP JSON API (/json/state, /presets.json) & UDP stream
│   └── WledModels.kt             # Data classes for WLED state, presets, effects, and palettes
├── logging/                      # Persistent database (Room)
│   └── LoggingDatabase.kt        # Room database, DAOs, entities (Session, StateInterval, IMU)
├── safety/                       # Rider distraction prevention
│   └── LockController.kt         # Auto-locks interactive UI while moving > 3 km/h
└── ui/theme/                     # Material 3 styling
    ├── Color.kt                  # Theme colors (Neon Cyan, Amber, Red, Dark Surface)
    ├── Theme.kt                  # Material 3 Theme setup with Dynamic Color support
    └── Type.kt                   # Typography definitions
```

---

## 2. Key Component Responsibilities & Lifecycles

| Component | Package | Role & Responsibility | Lifecycle / Scope |
| :--- | :--- | :--- | :--- |
| `PrettyNightsApp` | Root | Central dependency container: instantiates `ConfigManager`, `LogRepository`, `LockController`, `WledController`, `SpeedProvider`. | Application Process |
| `MainActivity` | Root | Top-level host; manages active tab selection (`HOME`, `PATTERNS`, `HISTORY`, `SETTINGS`) and sub-navigation to `TUNING`. | UI Activity |
| `RideService` | `com.example.ride` | Foreground service with notification. Keeps `RideOrchestrator` running when device is locked. | Foreground Service |
| `MusicService` | `com.example.ride` | Foreground service running audio capture and FFT streaming over UDP. | Foreground Service |
| `RideOrchestrator` | `com.example.ride` | Listens to 50Hz linear accelerometer; ticks `RideStateMachine`; dispatches WLED calls and logs intervals. | Active Ride Session |
| `RideStateMachine` | `com.example.statemachine` | Pure deterministic evaluator of ride states and output brightness curves. | Instantiated per Orchestrator / Tuning run |
| `ConfigManager` | `com.example.config` | Reads and writes `AppConfig` to Jetpack DataStore asynchronously. Emits `configFlow`. | Singleton |
| `LoggingDatabase` | `com.example.logging` | SQLite Room database storing telemetry episodes, IMU acceleration arrays, and session metrics. | Singleton |
| `LockController` | `com.example.safety` | Locks editing interfaces while vehicle speed is greater than idle threshold to keep rider safe. | Singleton |
| `WledController` | `com.example.wled` | Handles HTTP JSON client operations and live UDP socket transmissions to WLED endpoints. | Singleton |
