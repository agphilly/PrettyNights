# Pretty Nights — Build History & GitHub Changelog

All notable changes, architectural milestones, and component updates for the **Pretty Nights** Android application (`com.aistudio.prettynights.gskwqp`) are documented in this file by build number and semantic version.

---

## GitHub Release & Push Workflow

### How to Record a New Build
1. **During Development (`[Unreleased]`)**:
   - Work-in-progress features, bug fixes, and refactors made between GitHub pushes are staged under the `## [Unreleased]` section below so no intermediate changes are lost across sessions.
2. **When Pushing to GitHub (`"I'm going to push to GitHub"`)**:
   - Tell the assistant: **"I'm going to push to GitHub"**.
   - The assistant will automatically:
     1. Promote `## [Unreleased]` (plus any changes from the current session) into a numbered build block (`## [Build X — vX.Y.Z] - YYYY-MM-DD`).
     2. Generate a ready-to-copy **Suggested Git Commit** message (`<type>(<scope>): <summary>`) inside the build entry.
     3. Increment `versionCode` and `versionName` in `/app/build.gradle.kts` to match the new build.

### Standard Build Entry Template
```markdown
## [Build X — vX.Y.Z] - YYYY-MM-DD
**Suggested Git Commit:** `type(scope): concise summary of this build`

### Added
- New user-facing features, services, or documentation files.

### Changed / Refactored
- Architectural improvements, tuning adjustments, or modularization changes.

### Fixed
- Bug fixes, type mismatches, or protocol edge cases resolved.

### Key Files Touched
- `/path/to/File.kt`
```

---

## [Unreleased]
*No unreleased changes currently staged. Changes made after Build 6 (`v1.5.0`) will appear here until the next GitHub push.*

---

## [Build 6 — v1.5.0] - 2026-11-01
**Suggested Git Commit:** `feat(telemetry): add Always-On Bosch Live Data V1.0 capture, Log-Only mode, event markers, and Tuning Replay Simulator`

### Added
- **Bosch eBike Systems — The smart system — Live Data Interface V1.0 (`BoschLiveData.kt`)**:
  - Implemented BLE Service Solicitation (`0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4`), GATT client subscription to `eb21` (`0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4`), automatic `ATT_MTU = 247` negotiation to prevent Release v19 silent notification truncation (`LDI-003`), and wire-level `com.bosch.ebike.LiveData` protobuf decoding with dynamic field-presence tracking (`LDI-002`).
- **Always-On Parallel Telemetry Capture (`SpeedProvider.kt` & `RideOrchestrator.kt`)**:
  - Decoupled sensor capture from state-machine control so every ride (both WLED-enabled and Log-Only) simultaneously records 50 Hz IMU linear acceleration, GPS ground speed, and Bosch `eb21` notifications (`ImuSampleEntity` & `BoschSampleEntity` in Room `LoggingDatabase` v3).
- **Log Only Mode (No WLED) (`HomeLogOnlyModeCard` & `AppConfig.logOnlyMode`)**:
  - Added a dedicated **Log Only Mode (No WLED)** toggle card at the bottom of the Home screen (below the bike visualizer card) allowing riders to record full 50 Hz IMU, GPS, and Bosch BLE telemetry without WLED hardware connected.
- **Live Ground-Truth Event Markers & Post-Ride Session Notes**:
  - Added an in-ride 4-button **Stamp Live Event Marker** bar (`HARD_BRAKE`, `LIGHT_BRAKE`, `ROAD_BUMP`, `BOSCH_GLITCH`) on the Home screen and an interactive **Edit Session Notes** dialog in `HistoryScreen`.
- **In-App Ride Replay Simulator (`TuningReplayCard.kt`) & 4-Section CSV Exporter (`SessionExportHelper.kt`)**:
  - Added deterministic 50 Hz session replay in `TuningScreen` with selectable speed stream (`Recorded`, `Bosch eb21 (#1)`, `GPS Only`) and automated brake-detection / bump-rejection scoring.
  - Added unified CSV export via `FileProvider` containing session/Bosch reliability headers, event markers, raw Bosch `eb21` notifications, and 50 Hz telemetry rows.
- **Test Plan Documentation (`/docs/TUNING_TEST_PLAN.md`)**:
  - Added a 4-phase field testing, Bosch V1.0 reliability audit, and iterative replay tuning guide.

### Changed / Refactored
- Streamlined `HomeScreen` layout by placing `HomeLogOnlyModeCard` at the bottom of the page and removing the duplicate Setup & Calibration shortcut panel (navigation to Patterns/Settings uses the bottom bar, and Developer Tuning is accessed from Settings).
- Updated `/README.md`, `/FEATURE_SPECIFICATIONS.md`, `/docs/ARCHITECTURE.md`, `/docs/COMPONENT_MAP.md`, `/docs/PROTOCOLS.md`, and `/docs/TUNING_AND_TELEMETRY.md` to synchronize all documentation with Build 6 (`v1.5.0`).
- Incremented `/app/build.gradle.kts` to `versionCode = 6` and `versionName = "1.5.0"`.

### Key Files Touched
- `/app/src/main/java/com/example/ride/BoschLiveData.kt`
- `/app/src/main/java/com/example/ride/SpeedProvider.kt`
- `/app/src/main/java/com/example/ride/RideOrchestrator.kt`
- `/app/src/main/java/com/example/logging/LoggingDatabase.kt`
- `/app/src/main/java/com/example/home/HomeScreen.kt`, `/app/src/main/java/com/example/home/HomeActionControls.kt`, `/app/src/main/java/com/example/home/HomeReadinessCard.kt`
- `/app/src/main/java/com/example/history/HistoryScreen.kt`, `/app/src/main/java/com/example/history/SessionExportHelper.kt`
- `/app/src/main/java/com/example/tuning/TuningScreen.kt`, `/app/src/main/java/com/example/tuning/TuningReplayCard.kt`, `/app/src/main/java/com/example/tuning/TuningTelemetryCard.kt`
- `/README.md`, `/FEATURE_SPECIFICATIONS.md`, `/docs/*`, `/app/build.gradle.kts`

---

## [Build 5 — v1.4.0] - 2026-11-01
**Suggested Git Commit:** `docs & refactor: add /docs architecture suite and modularize Compose screens`

### Added
- Created a dedicated `/docs` engineering documentation suite:
  - `/docs/ARCHITECTURE.md`: High-level system architecture, 50 Hz sensor-to-WLED data pipeline, foreground service lifecycles, and persistence overview.
  - `/docs/COMPONENT_MAP.md`: Package-by-package directory map and file-level responsibilities across `config`, `home`, `logging`, `patterns`, `ride`, `settings`, `statemachine`, `tuning`, and `wled`.
  - `/docs/PROTOCOLS.md`: Wire-level specifications for WLED HTTP JSON API (`/json/state`, `/presets.json`, `/json/eff`, `/json/pal`), WLED Audio-Reactive UDP v2 sync packet format (port `11988`), Bosch BLE speed provider modes, and 3-axis IMU longitudinal projection math.
  - `/docs/TUNING_AND_TELEMETRY.md`: Complete 12-parameter `StateMachineConfig` reference table, factory defaults, nonlinear braking gamma formulas, and a 5-step rider field-tuning guide.
  - `/docs/CHANGELOG.md`: Build-by-build version history and GitHub push workflow tracker.

### Refactored
- Decomposed all four large Jetpack Compose screen files (previously ~950–1,200 lines each) into focused, single-responsibility sub-components under 300 lines per main screen:
  - **Tuning (`com.example.tuning`)**: Reduced `TuningScreen.kt` from 944 lines to 198 lines by extracting `TuningControls.kt`, `TuningTelemetryCard.kt`, `TuningManualOverrideCard.kt`, and `TuningParameterSections.kt`.
  - **Home (`com.example.home`)**: Reduced `HomeScreen.kt` from 1,065 lines to 264 lines by extracting `HomeReadinessCard.kt`, `HomeVisualizerCard.kt`, and `HomeActionControls.kt`.
  - **Patterns (`com.example.patterns`)**: Reduced `PatternsScreen.kt` from 1,203 lines to 226 lines by extracting `ColorPickerSliders.kt` and `StateLookCard.kt`.
  - **Settings (`com.example.settings`)**: Reduced `SettingsScreen.kt` from 964 lines to 198 lines by extracting `WledNetworkSettingsCard.kt`, `SpeedSourceSettingsCard.kt`, and `SensorCalibrationCard.kt`.
- Synced `/app/build.gradle.kts` to `versionCode = 5` and `versionName = "1.4.0"`.

### Fixed
- Resolved a `FloatArray` vs. `List<Float>` type signature mismatch in `HomeVisualizerCard.kt` for live 16-band FFT spectrum rendering from `MusicService.fftBands`.

### Key Files Touched
- `/docs/ARCHITECTURE.md`, `/docs/COMPONENT_MAP.md`, `/docs/PROTOCOLS.md`, `/docs/TUNING_AND_TELEMETRY.md`, `/docs/CHANGELOG.md`
- `/app/src/main/java/com/example/tuning/TuningScreen.kt` (+ 4 extracted component files)
- `/app/src/main/java/com/example/home/HomeScreen.kt` (+ 3 extracted component files)
- `/app/src/main/java/com/example/patterns/PatternsScreen.kt` (+ 2 extracted component files)
- `/app/src/main/java/com/example/settings/SettingsScreen.kt` (+ 3 extracted component files)
- `/app/build.gradle.kts`

---

## [Build 4 — v1.3.0] - Prior Milestone
**Suggested Git Commit:** `feat(music): add 16-band FFT audio capture and WLED UDP port 11988 sync`

### Added
- **`MusicService` Foreground Service (`com.example.ride.MusicService`)**:
  - Added real-time audio capture supporting internal device playback (`MediaProjection` on Android 10+) with automatic fallback to `AudioRecord` microphone input.
  - Implemented a 16-band logarithmic FFT spectrum analyzer and peak frequency estimator running in a background coroutine loop.
- **WLED Audio-Reactive UDP v2 Sync (`com.example.wled.WledController`)**:
  - Implemented binary WLED UDP Audio Sync v2 packet construction (`WLEDS` header, sample raw/smoothed amplitude, peak byte, 16 FFT bin bytes, and dominant frequency) transmitted to configurable UDP port (default `11988`).
- **Home Audio Visualizer UI (`com.example.home`)**:
  - Added live 16-band frequency equalizer bar visualization and one-tap Music Mode toggle on `HomeScreen`.

### Key Files Touched
- `/app/src/main/java/com/example/ride/MusicService.kt`
- `/app/src/main/java/com/example/wled/WledController.kt`
- `/app/src/main/java/com/example/home/HomeScreen.kt`
- `/app/src/main/AndroidManifest.xml`

---

## [Build 3 — v1.2.0] - Prior Milestone
**Suggested Git Commit:** `feat(tuning): add 7Hz live state machine telemetry and manual look override`

### Added
- **Real-Time Telemetry Diagnostics (`com.example.statemachine.StateMachineTelemetry`)**:
  - Added a 7 Hz (`150 ms` UI throttle) diagnostic stream from `RideOrchestrator` exposing active `RideState`, raw vs. filtered longitudinal acceleration ($m/s^2$), speed ($km/h$), normalized brightness ($0.0\text{–}1.0$), and last state transition trigger reason.
- **Stationary Manual Look Override**:
  - Added `manualOverrideState` in `RideOrchestrator` and `TuningScreen` so riders can force `IDLE`, `CRUISING`, `BRAKING`, or `BRAKING_HOLD` looks to the physical WLED strip while parked.
- **Interactive Envelope & Threshold Sliders (`com.example.tuning`)**:
  - Added live sliders and numeric inputs for all 12 `StateMachineConfig` parameters, real-time cross-parameter validation warnings (`validate()`), and one-tap Factory Reset.

### Key Files Touched
- `/app/src/main/java/com/example/tuning/TuningScreen.kt`
- `/app/src/main/java/com/example/ride/RideOrchestrator.kt`
- `/app/src/main/java/com/example/config/AppConfig.kt`

---

## [Build 2 — v1.1.0] - Prior Milestone
**Suggested Git Commit:** `feat(telemetry): add rider safety lock, Bosch BLE/GPS speed providers, and Room logging`

### Added
- **In-Motion Rider Safety Lock (`com.example.ride.LockController`)**:
  - Automatically locks complex configuration screens when bike speed exceeds `3.0 km/h`, providing a deliberate hold-to-override safety dialog.
- **Multi-Source Speed Architecture (`com.example.ride.SpeedProvider` & `BoschBleManager`)**:
  - Added configurable speed source modes (`GPS_ONLY`, `BOSCH_PREFERRED`, `BOSCH_ONLY`) with automatic fallback when BLE telemetry drops.
- **3-Axis IMU Mount Calibration (`com.example.settings`)**:
  - Added primary axis (`X`, `Y`, `Z`) and sign (`+1`, `-1`) selection with live longitudinal acceleration readout so the phone can be mounted in any handlebar orientation.
- **SQLite Room Ride History (`com.example.logging.LoggingDatabase` & `HistoryScreen`)**:
  - Created Room entities and DAOs (`SessionEntity`, `StateIntervalEntity`, `WledIntervalEntity`, `ImuSampleEntity`, `BoschSampleEntity`) to record episodic ride history and inspect past rides in `HistoryScreen`.

### Key Files Touched
- `/app/src/main/java/com/example/ride/LockController.kt`
- `/app/src/main/java/com/example/ride/SpeedProvider.kt`
- `/app/src/main/java/com/example/ride/BoschBleManager.kt`
- `/app/src/main/java/com/example/logging/LoggingDatabase.kt`
- `/app/src/main/java/com/example/logging/HistoryScreen.kt`
- `/app/src/main/java/com/example/settings/SettingsScreen.kt`

---

## [Build 1 — v1.0.0] - Initial Foundation
**Suggested Git Commit:** `feat(core): initial 4-state ride lighting engine, WLED HTTP client, and foreground service`

### Added
- **Deterministic 4-State Lighting Engine (`com.example.statemachine.RideStateMachine`)**:
  - Built pure-Kotlin state machine managing `IDLE`, `CRUISING`, `BRAKING`, and `BRAKING_HOLD` transitions using 50 Hz `Sensor.TYPE_LINEAR_ACCELERATION` inputs, debounce/cooldown timers, nonlinear braking gamma curve (`brakingGamma`), and global brightness envelopes.
- **WLED HTTP JSON Client & Rate-Limited Orchestrator (`WledController` & `RideOrchestrator`)**:
  - Implemented `/json/state` command dispatch, `/presets.json` discovery, `/json/eff` effects, and `/json/pal` palettes, paired with 250 ms brightness ramp throttling in `RideOrchestrator` to protect ESP8266/ESP32 Wi-Fi buffers.
- **Background Execution & Atomic Config (`RideService` & `ConfigManager`)**:
  - Created `RideService` foreground service with partial wake lock support and Jetpack DataStore JSON persistence (`ConfigManager`) for `AppConfig` and state-specific `WledLook` presets.

### Key Files Touched
- `/app/src/main/java/com/example/statemachine/RideStateMachine.kt`
- `/app/src/main/java/com/example/ride/RideOrchestrator.kt`
- `/app/src/main/java/com/example/ride/RideService.kt`
- `/app/src/main/java/com/example/wled/WledController.kt`
- `/app/src/main/java/com/example/config/ConfigManager.kt`
- `/app/src/main/java/com/example/config/AppConfig.kt`
- `/app/src/main/java/com/example/MainActivity.kt`
