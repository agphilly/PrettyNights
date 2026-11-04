# Pretty Nights — Smart Bicycle WLED Lighting & Telemetry Controller

**Pretty Nights** (`com.aistudio.prettynights.gskwqp`) is an Android cockpit, real-time telemetry recorder, and lighting orchestration application built with Kotlin and Jetpack Compose (Material 3). It transforms a smartphone into an automated bicycle lighting controller that drives **WLED (ESP8266/ESP32)** addressable LED strips using 50 Hz gravity-compensated IMU deceleration, **Bosch eBike Systems (The smart system) Live Data Interface V1.0** (`eb20`/`eb21`) and GPS velocity streams, 50 Hz audio-reactive FFT streaming, and manual look presets.

For complete functional requirements, state-machine matrices, packet layouts, UI states, and edge-case specifications, see [**`FEATURE_SPECIFICATIONS.md`**](./FEATURE_SPECIFICATIONS.md) and the [**`/docs` Engineering Suite**](./docs/ARCHITECTURE.md).

---

## Key Capabilities

- **4-State Kinetic Ride Orchestration (`RideStateMachine`, `RideOrchestrator`, & `RideService`)**:
  - Evaluates 50 Hz 3-axis linear acceleration (`Sensor.TYPE_LINEAR_ACCELERATION`) projected along the calibrated mount axis (`X`, `Y`, `Z` and polarity `+1`/`-1`) together with GPS and Bosch BLE velocity to transition deterministically between `IDLE`, `CRUISING`, `BRAKING`, and `BRAKING_HOLD`.
  - Applies nonlinear braking gamma brightness curves (`brakingGamma`), debounce (`brakingDebounceMs`) and cooldown (`brakingCooldownMs`) filters, global brightness envelopes, and adaptive WLED HTTP rate limiting (`250 ms` ramp throttling with instant `< 50 ms` state transitions).
- **Always-On Full Telemetry Capture & Log Only Mode (No WLED)**:
  - Every ride session—whether **WLED Active** or **Log Only Mode (No WLED)** (toggled via the card at the bottom of the Home screen)—simultaneously records 50 Hz IMU acceleration, GPS ground speed, and Bosch Live Data Interface V1.0 BLE notifications (`0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4`) to SQLite Room (`LoggingDatabase`).
  - **Log Only Mode** bypasses WLED network checks and HTTP/UDP output so riders can capture real-world baseline telemetry on any bicycle even when WLED hardware is not installed.
- **Bosch eBike Systems — The smart system — Live Data Interface V1.0 (`BoschLiveData.kt`)**:
  - Implements BLE GATT client + GAP Peripheral Service Solicitation (`0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4`) and wire-level protobuf decoding (`com.bosch.ebike.LiveData` fields `1, 2, 5, 9, 10, 11, 12, 17, 21–25`), including Release v19 `ATT_MTU >= 247` negotiation (`LDI-003`) and dynamic field-presence merging (`LDI-002`).
- **Live Ground-Truth Event Markers, Session Notes & In-App Replay Simulator**:
  - Stamp live in-ride markers (`HARD_BRAKE`, `LIGHT_BRAKE`, `ROAD_BUMP`, `BOSCH_GLITCH`) directly from the Home screen during any active ride and annotate post-ride session notes in **History**.
  - Replay recorded 50 Hz ride sessions inside **Dev Tuning** (`TuningReplayCard.kt`) against live slider settings and selectable speed streams (`Recorded`, `Bosch eb21 (#1)`, or `GPS Only`) with automatic brake-detection and bump-rejection scoring.
  - Export unified 4-section CSV files (`SessionExportHelper.kt`) containing session/Bosch reliability summaries, event markers, raw Bosch `eb21` notifications, and 50 Hz telemetry rows.
- **50 Hz Audio-Reactive Music Mode (`MusicService`)**:
  - Captures system audio playback via Android 10+ `MediaProjection` (`AudioPlaybackCaptureConfiguration`) or microphone fallback at 44.1 kHz 16-bit mono PCM, computes a 1024-point Hann-windowed radix-2 FFT across 16 logarithmic bands with software AGC, renders a live spectrum visualizer on the Home screen, and multicasts 84-byte Little-Endian **WLED Audio Sync V2** packets to `239.0.0.1:11988`.
- **WLED Look & Pattern Studio & Segment Role Architecture (`PatternsScreen`, `WledHardwareConfigCard`, & `WledController`)**:
  - Independent 4-state lighting profiles (`idleLook`, `cruisingLook`, `brakingLook`, `brakingHoldLook`) decoupled from physical strip topology via `SegmentRole` (`MAIN_FRAME` / `"Ambient"` holds `cruisingLook` during braking while `BRAKE_LIGHTS` / `"Brake"` snaps immediately with per-segment `"tt"` transition times in a single `/json/state` POST). Supports saved WLED Preset IDs (`ps`) or custom looks (`fx` effect, `fp` palette, 3 RGB color slots, `sx` speed, `ix` intensity, and transition timing) with live controller discovery (`/presets.json`, `/json/eff`, `/json/pal`) and offline defaults.
- **Dual-Mode Physical Transport & Local Socket Binding (`WledNetworkTransport.kt`)**:
  - Automatically adapts to both rider power setups without manual Android network switching: prioritizes **USB-C to Ethernet (`TRANSPORT_ETHERNET`)** when running on battery, or explicitly binds HTTP and UDP sockets to **Wi-Fi (`TRANSPORT_WIFI`)** when charging so Android never routes private WLED traffic (`4.3.2.1`, `192.168.x.x`, `prettylights.local`) over 5G/LTE mobile data. Also supports **USB-C Serial (`115200` baud)** JSON mirroring.
- **Standalone 50 Hz Web Telemetry & State-Machine Workbench (`tools/telemetry-workbench/` & `TuningConfigSyncCard.kt`)**:
  - Zero-dependency browser / GitHub Pages oscilloscope and state-machine tuning bench that loads 4-section Pretty Nights CSV exports, plots selectable multi-track signals (`fused_accel_ms2`, `accel_x/y/z`, `speed_kmh`, `bosch_speed_kmh`, `gps_speed_kmh`, `bosch_age_ms`, `brightness`), re-runs a 1:1 port of `RideStateMachine` in `< 5 ms` as you drag tuning sliders, highlights **Before (Recorded) vs. After (Simulated)** state diffs, and round-trips tuned configs via JSON or Kotlin snippets with the Android **Developer / Tuning** screen.

---

## Documentation Suite (`/docs`)

| Document | Contents |
| :--- | :--- |
| [**`FEATURE_SPECIFICATIONS.md`**](./FEATURE_SPECIFICATIONS.md) | End-to-end product & engineering requirements, state formulas, packet schemas, and verification matrix. |
| [**`docs/ARCHITECTURE.md`**](./docs/ARCHITECTURE.md) | System architecture diagram, 50 Hz sensor-to-WLED pipeline, foreground services, and persistence design. |
| [**`docs/COMPONENT_MAP.md`**](./docs/COMPONENT_MAP.md) | Complete directory tree and file-by-file responsibilities across all 37 Kotlin source files and the Web Workbench. |
| [**`docs/PROTOCOLS.md`**](./docs/PROTOCOLS.md) | WLED HTTP JSON API, UDP Port 11988 V2 binary layout, Bosch Live Data Interface V1.0 (`eb20`/`eb21`) protobuf spec, and IMU projection math. |
| [**`docs/HARDWARE_SPEC.md`**](./docs/HARDWARE_SPEC.md) | NaveiTech `NV-LC616WL` ESP32-WLED controller electrical ratings, GPIO pinout (`IO16`/`IO2`/`IO13`), RJ45 Ethernet + Wi-Fi wiring diagrams, and WLED settings. |
| [**`docs/TUNING_AND_TELEMETRY.md`**](./docs/TUNING_AND_TELEMETRY.md) | 12-step field-tuning methodology, `StateMachineConfig` parameter reference table, Replay Simulator, and Web Workbench workflow. |
| [**`docs/TUNING_TEST_PLAN.md`**](./docs/TUNING_TEST_PLAN.md) | 4-phase real-world data collection, Bosch V1.0 reliability audit, iterative replay tuning, and Web Workbench CSV analysis plan. |
| [**`docs/CHANGELOG.md`**](./docs/CHANGELOG.md) | Build-by-build release history, semantic versions, and suggested Git commit messages. |
| [**`tools/telemetry-workbench/`**](./tools/telemetry-workbench/README.md) | Standalone browser / GitHub Pages 50 Hz CSV oscilloscope, Before/After state-machine tuning workbench, and JSON/Kotlin config bridge. |

### External Hardware & Protocol References
- **Official WLED Knowledge Base**: [https://kno.wled.ge/](https://kno.wled.ge/)
  - [WLED JSON API (`/json/state`, `/json/eff`, `/json/pal`)](https://kno.wled.ge/interfaces/json-api/)
  - [WLED Presets (`/presets.json`)](https://kno.wled.ge/features/presets/)
  - [WLED Effects (`fx`)](https://kno.wled.ge/features/effects/) & [Color Palettes (`fp`)](https://kno.wled.ge/features/palettes/)
  - [WLED Audio-Reactive & UDP Sound Sync V2 (Port `11988`)](https://kno.wled.ge/advanced/audio-reactive/)
- **Bosch eBike Systems — The smart system**: *Live Data Interface V1.0 (Release v19)* (`eb20` / `eb21` BLE GATT & Protobuf specification, documented in [`docs/PROTOCOLS.md`](./docs/PROTOCOLS.md)).

---

## Keeping Version Control on GitHub

You can synchronize this project with GitHub directly from **Google AI Studio** or via a local Git/Android Studio environment.

### Workflow A: Built-In GitHub Sync in Google AI Studio (Recommended)
1. Click the **Settings / Project Menu** in the top navigation bar of Google AI Studio.
2. Select **Save to GitHub** (or **Push to GitHub**).
3. Authorize your GitHub account if prompted, select or create your target repository, choose a branch (e.g., `main`), and enter a commit message (see [**`docs/CHANGELOG.md`**](./docs/CHANGELOG.md) for the ready-to-use commit message for the current build).
4. After future edits in AI Studio, use **Push to GitHub** again to commit and push updated snapshots directly to your branch.

### Workflow B: Local Git & Android Studio Development
1. Export the project as a ZIP from the AI Studio **Settings / Export** menu (or clone your synced GitHub repository):
   ```bash
   git clone https://github.com/<your-username>/<your-repo>.git
   cd <your-repo>
   ```
2. **Restore the Debug Signing Keystore**:
   Because `debug.keystore` is excluded from Git in `.gitignore` while `debug.keystore.base64` is tracked for deterministic APK signing compatibility, decode it once after cloning:
   ```bash
   base64 -d debug.keystore.base64 > debug.keystore
   ```
3. **Configure Secrets / Environment Variables**:
   API keys and secrets are managed via the **Secrets panel in Google AI Studio** (and injected into `BuildConfig` by the Secrets Gradle Plugin). For local builds, copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
   > **Note:** Never commit `.env` or `debug.keystore` to Git. Both are protected in `.gitignore`.

---

## Hardware & Network Topology

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       ANDROID DEVICE (Pretty Nights)                    │
│                                                                         │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌───────────────┐  │
│  │  RideOrchestrator    │  │  BoschLiveDataClient │  │  MusicService │  │
│  │  50Hz IMU + GPS/BLE  │  │  BLE eb20 / eb21 V1  │  │  50Hz 16-FFT  │  │
│  └──────────┬───────────┘  └──────────┬───────────┘  └───────┬───────┘  │
└─────────────┼─────────────────────────┼──────────────────────┼──────────┘
              │ HTTP POST /json/state   │ BLE GATT / GAP       │ UDP V2 (84B)
              │ <wledHost>:80 (or       │ Service Solicit eb20 │ 239.0.0.1:11988
              │ Log-Only Mode bypass)   │ ATT_MTU >= 247       │
              ▼                         ▼                      ▼
┌───────────────────────────┐ ┌──────────────────────┐ ┌──────────────────┐
│ ESP8266 / ESP32 WLED      │ │ BOSCH SMART SYSTEM   │ │ WLED AUDIO SYNC  │
│ • IDLE / CRUISING /       │ │ • LiveData Protobuf  │ │ • 16-Band FFT    │
│   BRAKING / BRAKING_HOLD  │ │   Speed, Power, SoC  │ │ • AGC + Peak     │
└───────────────────────────┘ └──────────────────────┘ └──────────────────┘
```

---

## Project Architecture

| Package / Module | Responsibility |
| :--- | :--- |
| `com.example.MainActivity`, `PrettyNightsApp` & `AppContainer` | Edge-to-edge M3 scaffold, bottom navigation bar (`Ride`, `Patterns`, `History`, `Config` + `Tuning` route), and `AppContainer` dependency container. |
| `com.example.home` | `HomeViewModel`, `HomeScreen` (`HomeContent`), `HomeReadinessCard`, `HomeActionControls`, and `HomeVisualizerCard`: live speed/state readout, Start/Stop Ride CTA, in-ride event marker bar, bike lighting/FFT visualizer, and bottom Log Only Mode toggle card. |
| `com.example.ride` | `RideOrchestrator` (50 Hz sensor coordinator, sequential command & interval channels, WLED rate-limiter, Room batch logger), `RideService`, `MusicService` (1024-pt FFT + UDP V2), `SpeedProvider`, and `BoschLiveData` (`eb20`/`eb21` BLE client + protobuf decoder). |
| `com.example.statemachine` | `RideStateMachine` pure deterministic evaluator (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`), `StateMachineConfig`, and `StateMachineTelemetry`. |
| `com.example.patterns` | `PatternsViewModel`, `PatternsScreen` (`PatternsContent`), `StateLookCard`, `PresetLookSection`, `CustomLookSection`, and `ColorPickerSliders` for per-state WLED presets, custom segments, effects, palettes, and transitions. |
| `com.example.history` | `HistoryViewModel`, `HistoryScreen` (`HistoryContent`), and `SessionExportHelper`: expandable session cards with SQL summary metrics, Bosch V1.0 reliability report, session notes editor, and 4-section CSV export. |
| `com.example.settings` & `com.example.tuning` | `SettingsViewModel` / `SettingsScreen` (`SettingsContent`, `WledNetworkSettingsCard`, `WledHardwareConfigCard`, `SpeedSourceSettingsCard`, `SensorCalibrationCard`) and `TuningViewModel` / `TuningScreen` (`TuningContent`, `TuningTelemetryCard`, `TuningManualOverrideCard`, `TuningReplayCard`, `TuningParameterSections`). |
| `com.example.config` & `com.example.logging` | `ConfigManager` (DataStore JSON persistence for `AppConfig`), `DomainEnums` (typed wire enums + `AppJson.moshi`), and `LoggingDatabase` (Room SQLite tables with `EnumConverters` for sessions, intervals, 50 Hz IMU/GPS/Bosch samples, raw Bosch `eb21` packets, and ride markers). |
| `com.example.wled` & `com.example.safety` | `WledController` (`/json/state`, `/json/cfg`, `/presets.json`, `/json/eff`, `/json/pal`) and `LockController` (motion safety lock when moving). |

---

## Building & Running Tests

### Run JVM & Robolectric Unit Tests
```bash
gradle :app:testDebugUnitTest
```

### Run Kotlin ↔ JavaScript State-Machine Parity Test
```bash
node tools/telemetry-workbench/parity.test.js
```

### Assemble Debug APK
```bash
gradle :app:assembleDebug
```
The compiled debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Continuous Integration (GitHub Actions)

This repository includes an automated workflow at [`.github/workflows/android-ci.yml`](./.github/workflows/android-ci.yml) that runs on every `push` and `pull_request`:
1. Provisions Ubuntu with **JDK 21**, the Android SDK, and **Gradle 8.13** with dependency caching.
2. Restores `debug.keystore` from `debug.keystore.base64` and initializes `.env` from `.env.example`.
3. Runs the unit test suite (`:app:testDebugUnitTest`), verifies Kotlin-to-JS state machine parity (`node tools/telemetry-workbench/parity.test.js`), and publishes HTML test reports as build artifacts.
4. Compiles the debug APK (`:app:assembleDebug`) and uploads `pretty-nights-debug-apk` to the workflow run summary.
