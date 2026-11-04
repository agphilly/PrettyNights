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
*No unreleased changes currently staged. Changes made after Build 12 (`v2.1.0`) will appear here until the next GitHub push.*

---

## [Build 12 — v2.1.0] - 2026-11-03
**Suggested Git Commit:** `feat(segments): implement WLED Segment Role Architecture (Ambient & Brake roles), single-POST sendComposedState with per-segment transitions, and multi-channel segment UI`

### Added
- **Segment Role Architecture Data Model (`DomainEnums.kt` & `ConfigManager.kt`)**:
  - Added `SegmentRoleType` (`AMBIENT`, `NOTIFICATION`), `SegmentRole` (`MAIN_FRAME` → `"Ambient"`, `BRAKE_LIGHTS` → `"Brake"`), `SegmentConfig` (including `activeInMusicMode: Boolean = false` for Phase 2 persistence), `ChannelConfig`, and `withSequentialSegmentIds()`.
  - Replaced flat `WledHardwareConfig` on `AppConfig` with `@Json(name = "channels") val channels: List<ChannelConfig> = emptyList()`, preserving seamless Moshi DataStore migration and all four ride state looks (`idleLook`, `cruisingLook`, `brakingLook`, `brakingHoldLook`).
- **Role-Based Segment Fan-Out (`RideOrchestrator.lookForSegment`)**:
  - Added `lookForSegment(role, state)` so `MAIN_FRAME` (`AMBIENT`) segments hold `cruisingLook` during `BRAKING` and `BRAKING_HOLD` while `BRAKE_LIGHTS` (`NOTIFICATION`) segments inherit ambient looks in `IDLE`/`CRUISING` and trigger `brakingLook` and `brakingHoldLook`.
- **Composed WLED State Dispatch (`WledController.sendComposedState`)**:
  - Added `sendComposedState(host, segmentLooks, brightness)` to dispatch all segment overrides in a single `/json/state` HTTP POST per tick with per-segment `tt` transition times (and no top-level `tt`/`transition` field).
- **Multi-Channel & Segment Role Settings UI (`WledHardwareConfigCard.kt`)**:
  - Rebuilt `WledHardwareConfigCard` around `List<ChannelConfig>` with `"GPIO <pin> — <ledCount> LEDs"` headers, strip coverage indicators (`"<covered> / <ledCount> LEDs configured"`), `ExposedDropdownMenuBox` role selection driven by `SegmentRole.entries.map { it.displayLabel }`, non-overlapping `[startLed, endLed)` validation, and a 10-segment per channel cap.
- **Offline WLED Effect & Palette Fallback (`WledController.kt`, `PatternsViewModel.kt`, `PatternsScreen.kt`)**:
  - Added built-in `DEFAULT_WLED_EFFECTS` and `DEFAULT_WLED_PALETTES` so Custom Looks remain selectable even when the WLED controller is offline, and added a 4th `StateLookCard` for `BRAKING_HOLD`.

### Fixed
- **50 Hz Hot-Path & Throttle Caching (`RideOrchestrator.kt`)**:
  - `evalAndSendWledSink()` reads pre-assigned `seg.wledSegmentId` values directly without hot-path allocations, caches `lastSentSegmentLooks: Map<Int, WledLook>`, and logs a single representative look (`MAIN_FRAME`/`AMBIENT` when present, or first segment entry) to `WledIntervalEntity`.
- **Hardware Provisioning Return Status (`WledController.pushHardwareAndSegments`)**:
  - Fixed `pushHardwareAndSegments()` to return `cfgOk && segOk` instead of `cfgOk || segOk` so `/json/cfg` failures are never masked.

### Key Files Touched
- `/app/src/main/java/com/example/config/DomainEnums.kt`, `/app/src/main/java/com/example/config/ConfigManager.kt`
- `/app/src/main/java/com/example/wled/WledController.kt`
- `/app/src/main/java/com/example/ride/RideOrchestrator.kt`
- `/app/src/main/java/com/example/settings/WledHardwareConfigCard.kt`, `/app/src/main/java/com/example/settings/SettingsViewModel.kt`, `/app/src/main/java/com/example/settings/SettingsScreen.kt`
- `/app/src/main/java/com/example/patterns/CustomLookSection.kt`, `/app/src/main/java/com/example/patterns/PatternsScreen.kt`, `/app/src/main/java/com/example/patterns/PatternsViewModel.kt`
- `/app/src/main/java/com/example/tuning/TuningViewModel.kt`
- `/app/src/test/java/com/example/ExampleUnitTest.kt`, `/app/src/test/java/com/example/ExampleRobolectricTest.kt`, `/app/src/test/java/com/example/RideOrchestratorTest.kt`

---

## [Build 11 — v2.0.0] - 2026-11-01
**Suggested Git Commit:** `refactor(arch): serialize RideOrchestrator interval/lifecycle channels, adopt typed model enums & AppContainer ViewModels, and enforce Kotlin-JS state-machine parity`

### Added
- **`AppContainer` & Screen ViewModels (`AppContainer.kt` + 5 ViewModels)**:
  - Replaced `PrettyNightsApp.instance` across all Compose screens and services with `AppContainer` (`PrettyNightsApp.container` / `Context.appContainer`) and 5 screen ViewModels (`HomeViewModel`, `PatternsViewModel`, `HistoryViewModel`, `SettingsViewModel`, `TuningViewModel`) paired with stateless `*Content` composables (`HomeContent`, `PatternsContent`, `HistoryContent`, `SettingsContent`, `TuningContent`).
- **Kotlin <-> JavaScript State-Machine Parity Suite (`RideStateMachineParityTest.kt` & `tools/telemetry-workbench/parity.test.js`)**:
  - Extracted `DEFAULT_CONFIG` and `JsRideStateMachine` into `tools/telemetry-workbench/ride-state-machine.js` shared between `index.html` and Node.js.
  - Added `RideStateMachineParityTest` generating and verifying `tools/telemetry-workbench/fixtures/parity.json` across 4 scripted 50 Hz rides (`hard_stop`, `road_bump_rejection`, `slow_roll_to_idle`, `brake_then_release`), and wired `node tools/telemetry-workbench/parity.test.js` into `.github/workflows/android-ci.yml`.
- **Orchestrator & ViewModel Unit Test Suites (`RideOrchestratorTest.kt` & `ViewModelsTest.kt`)**:
  - Added concurrency stress tests verifying single-writer interval integrity under random IO delays and back-to-back `start()`/`stop()` command serialization, plus ViewModel unit and Robolectric Compose tests.

### Changed / Refactored
- **End-to-End Typed Domain Enums & Shared `AppJson.moshi` (`DomainEnums.kt`, `ConfigManager.kt`, `LoggingDatabase.kt`, `SpeedProvider.kt`)**:
  - Promoted `SpeedSourceMode`, `SensorAxis`, `RideMarkerType`, `Io13PinRole`, `SegmentTarget`, and `ActiveSpeedSource` directly into `AppConfig`, `WledHardwareConfig`, `WledLook`, `SessionEntity`, `RideMarkerEntity`, and `SpeedProvider`, backed by Moshi `@ToJson`/`@FromJson` adapters (`DomainEnumJson`), a single shared `AppJson.moshi` instance, and Room `EnumConverters`.

### Fixed
- **`RideOrchestrator` Sequential Interval Writer Channel (Item 1)**:
  - Replaced interleaved `scope.launch` + `intervalMutex` blocks with a single `Channel<IntervalOp>(Channel.UNLIMITED)` consumer coroutine that exclusively owns `openState` and `openWled`, guaranteeing `insert` always completes before `close` regardless of SQLite dispatch latency.
- **`RideOrchestrator` Sequential `start()` / `stop()` Command Channel (Item 2)**:
  - Replaced async `start()`/`stop()` launches with a sequential `Channel<LifecycleCmd>(Channel.UNLIMITED)` consumer loop and reactive `isRunningFlow: StateFlow<Boolean>`.
- **Safe Enum Fallbacks (Item 4)**:
  - Changed `SpeedSourceMode.fromWireValue` unknown fallback from `BOSCH_PREFERRED` to `GPS_ONLY` and replaced `RideMarkerType.fromWireValue`'s silent `BOSCH_GLITCH` fallback with `RideMarkerType.fromWireValueOrNull(raw): RideMarkerType?`.
- **Workspace Cleanup (Item 7)**:
  - Removed temporary code-review PDF attachments from `public/assets/aistudio/` and bumped `app/build.gradle.kts` to `versionCode = 11` / `versionName = "2.0.0"`.

### Key Files Touched
- `/app/src/main/java/com/example/AppContainer.kt`, `/app/src/main/java/com/example/PrettyNightsApp.kt`, `/app/src/main/java/com/example/MainActivity.kt`
- `/app/src/main/java/com/example/config/DomainEnums.kt`, `/app/src/main/java/com/example/config/ConfigManager.kt`, `/app/src/main/java/com/example/logging/LoggingDatabase.kt`
- `/app/src/main/java/com/example/ride/RideOrchestrator.kt`, `/app/src/main/java/com/example/ride/SpeedProvider.kt`, `/app/src/main/java/com/example/ride/RideService.kt`, `/app/src/main/java/com/example/ride/MusicService.kt`
- `/app/src/main/java/com/example/home/HomeViewModel.kt`, `/app/src/main/java/com/example/home/HomeScreen.kt`
- `/app/src/main/java/com/example/patterns/PatternsViewModel.kt`, `/app/src/main/java/com/example/patterns/PatternsScreen.kt`
- `/app/src/main/java/com/example/history/HistoryViewModel.kt`, `/app/src/main/java/com/example/history/HistoryScreen.kt`
- `/app/src/main/java/com/example/settings/SettingsViewModel.kt`, `/app/src/main/java/com/example/settings/SettingsScreen.kt`
- `/app/src/main/java/com/example/tuning/TuningViewModel.kt`, `/app/src/main/java/com/example/tuning/TuningScreen.kt`
- `/tools/telemetry-workbench/ride-state-machine.js`, `/tools/telemetry-workbench/parity.test.js`, `/tools/telemetry-workbench/fixtures/parity.json`
- `/app/src/test/java/com/example/RideOrchestratorTest.kt`, `/app/src/test/java/com/example/RideStateMachineParityTest.kt`, `/app/src/test/java/com/example/ViewModelsTest.kt`

---

## [Build 10 — v1.9.0] - 2026-11-01
**Suggested Git Commit:** `refactor(core): fix RideOrchestrator lifecycle/interval races, remove SpeedProvider casts, add typed enums, split StateLookCard, and optimize History SQL summary`

### Added
- **Typed Domain Enums (`/app/src/main/java/com/example/config/DomainEnums.kt`)**:
  - Added `SpeedSourceMode` (`GPS_ONLY`, `BOSCH_PREFERRED`, `BOSCH_ONLY`), `SensorAxis` (`X`, `Y`, `Z`), `RideMarkerType` (`HARD_BRAKE`, `LIGHT_BRAKE`, `ROAD_BUMP`, `BOSCH_GLITCH`), `Io13PinRole` (`PUSHBUTTON`, `REED_SWITCH`, `STRIP_CH3`, `DISABLED`), and `SegmentTarget` (`BOTH`, `SEG_0_IO16`, `SEG_1_IO2`) with backwards-compatible wire string helpers.
- **Modular Pattern Editor Sections (`PresetLookSection.kt` & `CustomLookSection.kt`)**:
  - Extracted WLED preset selection and custom segment/effect/palette/RGB editors into dedicated files, reducing `StateLookCard.kt` from 784 lines to 281 lines.
- **Lightweight SQL Session Summary (`ImuSessionSummary` in `LoggingDatabase.kt` & `SessionExportHelper.kt`)**:
  - Added `ImuSampleDao.getSessionSummary(sessionId)` and `SessionExportHelper.computeBoschReportFromSummary(...)` so expanding a session card in `HistoryScreen` executes a single-row SQL summary query instead of loading all 50 Hz `ImuSampleEntity` rows into heap memory.

### Fixed
- **`RideOrchestrator` Start/Stop & Interval Concurrency**:
  - Serialized `start()` and `stop()` via `lifecycleMutex` so a rapid `stop()` during startup cannot leave the 50 Hz accelerometer listener registered indefinitely.
  - Serialized `StateIntervalEntity` and `WledIntervalEntity` open/close database operations via `intervalMutex` and synchronous sensor-thread transition tracking (`lastLoggedState`, `lastLoggedLook`).
  - Marked cross-thread fields `@Volatile` and reused a single class-level `Moshi` / `wledLookAdapter` instance instead of rebuilding `Moshi.Builder()` on every WLED transition.
- **Eliminated `FusedSpeedProvider` Downcasts**:
  - Promoted `gpsSpeedMps`, `activeSpeedSource`, `boschLiveState`, `addBoschPacketListener`, and `removeBoschPacketListener` onto the `SpeedProvider` interface, removing all `speedProvider as? FusedSpeedProvider` casts in `RideOrchestrator.kt`.

### Key Files Touched
- `/app/src/main/java/com/example/config/DomainEnums.kt`
- `/app/src/main/java/com/example/ride/RideOrchestrator.kt`
- `/app/src/main/java/com/example/ride/SpeedProvider.kt`
- `/app/src/main/java/com/example/patterns/StateLookCard.kt`
- `/app/src/main/java/com/example/patterns/PresetLookSection.kt`
- `/app/src/main/java/com/example/patterns/CustomLookSection.kt`
- `/app/src/main/java/com/example/logging/LoggingDatabase.kt`
- `/app/src/main/java/com/example/history/SessionExportHelper.kt`
- `/app/src/main/java/com/example/history/HistoryScreen.kt`
- `/app/src/test/java/com/example/ExampleUnitTest.kt`

---

## [Build 9 — v1.8.0] - 2026-11-01
**Suggested Git Commit:** `feat(hardware): rename Calibrate tab to Config, add NV-LC616WL GPIO/power/segment provisioning and per-state segment routing`

### Added
- **NaveiTech NV-LC616WL Hardware Specification (`/docs/HARDWARE_SPEC.md`)**:
  - Documented electrical ratings (`DC 5–12–24V` 15A max, `5V 3A` power-only USB-C, 20A Mini 297 blade fuse, `IP20` enclosure), terminal GPIO pinout (`IO16` Ch1, `IO2` Ch2, `IO13` Aux), RJ45 Ethernet + Wi-Fi dual-mode bicycle wiring diagrams, and recommended WLED firmware settings.
- **NV-LC616WL Hardware, I/O & Multi-Segment Provisioning Card (`WledHardwareConfigCard.kt` & `WledController.kt`)**:
  - Added direct `/json/cfg` and `/json/state` provisioning in the **Config** tab:
    - **Power Supply Current Limiter**: One-tap presets for **USB-C 5V (`2,500 mA`)**, **Battery Pack (`8,000 mA`)**, and **Max Terminal (`15,000 mA`)** plus a fine slider (`500..15,000 mA`).
    - **Dual-Channel LED Bus Config (`IO16` / `GPIO 16` & `IO2` / `GPIO 2`)**: Per-channel LED counts, strip protocols (`WS2812B RGB` vs. `SK6812 RGBW`), color orders (`GRB` / `RGB`), reverse-direction toggles, and zone labels.
    - **Auxiliary `IO13` (`GPIO 13`) Role Selector**: Configurable as `Pushbutton`, `Reed Switch`, `3rd LED Strip`, or `Disabled`.
    - **Live Controller Info & One-Tap Segment Partitioning**: Added **Read Info** (`GET /json/info`) and **Push Hardware & Segments to WLED** (`POST /json/cfg` + `POST /json/state`) with a live computed address map (`Seg 0: 0..N`, `Seg 1: N..N+M`).
- **Per-State Segment Routing in `Patterns` (`StateLookCard.kt` & `WledLook.targetSegments`)**:
  - Added a **Target NV-LC616WL Output Zone** selector (`Both (IO16 + IO2)`, `Seg 0 (IO16)`, `Seg 1 (IO2)`) to each ride state look (`IDLE`, `CRUISING`, `BRAKING`).

### Changed / Refactored
- Renamed the 4th bottom navigation tab in `MainActivity.kt` from `Calibrate` to **`Config`** and updated `SettingsScreen.kt` header to **System & Hardware Config**.
- Incremented `/app/build.gradle.kts` to `versionCode = 9` and `versionName = "1.8.0"`.

### Key Files Touched
- `/docs/HARDWARE_SPEC.md`, `/docs/COMPONENT_MAP.md`, `/docs/CHANGELOG.md`, `/README.md`
- `/app/src/main/java/com/example/settings/WledHardwareConfigCard.kt`, `/app/src/main/java/com/example/settings/SettingsScreen.kt`
- `/app/src/main/java/com/example/wled/WledController.kt`
- `/app/src/main/java/com/example/patterns/StateLookCard.kt`
- `/app/src/main/java/com/example/config/ConfigManager.kt`, `/app/src/main/java/com/example/MainActivity.kt`, `/app/build.gradle.kts`

---

## [Build 8 — v1.7.0] - 2026-11-01
**Suggested Git Commit:** `feat(workbench): add standalone 50 Hz Web Telemetry Workbench and Android JSON/Kotlin config sync`

### Added
- **Standalone 50 Hz Web Telemetry & State-Machine Workbench (`/tools/telemetry-workbench/`)**:
  - Built a zero-dependency browser/GitHub Pages analysis and tuning console (`index.html`, `styles.css`, `workbench.js`, `README.md`) that parses 4-section Pretty Nights CSV exports (`Session Metadata`, `IN_RIDE_EVENT_MARKERS`, `BOSCH_LIVE_DATA_V1_NOTIFICATIONS`, and `UNIFIED_50HZ_RIDE_TELEMETRY`).
  - Ported `RideStateMachine` (`RideStateMachine.kt`) 1:1 to JavaScript (`JsRideStateMachine`) so dragging any of the 20 parameter sliders re-evaluates an entire 50 Hz ride in `< 5 ms`.
  - Implemented a synchronized 5-track high-DPI `<canvas>` oscilloscope with mouse-wheel zoom, drag-to-pan, and a 20 ms crosshair inspection HUD:
    1. **BEFORE (Recorded State)** ribbon (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`).
    2. **AFTER (Simulated State)** ribbon computed live from current slider settings.
    3. **DIFF (`Before ≠ After`)** violet highlight band pinpointing every tick where tuning changes state behavior.
    4. **Acceleration & Thresholds (`m/s²`)** with live `brakingDecelThreshold` overlay and shaded debounce/cooldown active windows.
    5. **Speed (`km/h`), Bosch `eb21` & Brightness (`0–100%`)** with toggleable overlays for active speed, Bosch speed, 1 Hz GPS speed, `bosch_age_ms` sawtooth, cadence/power, and Recorded vs. Simulated brightness curves.
  - Added **Jump-to-Event & Diff Bookmark Chips** (`ROAD_BUMP`, `LIGHT_BRAKE`, `HARD_BRAKE`, and computed `Diff #1..N` episodes) plus a built-in 30-second (`1,500`-tick) 50 Hz tricky-situations reference ride.
- **Android Web Workbench Config Sync (`TuningConfigSyncCard.kt` & `TuningScreen.kt`)**:
  - Added `TuningConfigSyncCard` and `StateMachineConfigSerializer` to **Settings → Developer / Tuning** so riders can copy/share `StateMachineConfig` JSON or Kotlin snippets and paste/import JSON tuned in the Web Workbench directly into Jetpack DataStore.

### Key Files Touched
- `/tools/telemetry-workbench/index.html`, `/tools/telemetry-workbench/styles.css`, `/tools/telemetry-workbench/workbench.js`, `/tools/telemetry-workbench/README.md`
- `/app/src/main/java/com/example/tuning/TuningConfigSyncCard.kt`, `/app/src/main/java/com/example/tuning/TuningScreen.kt`
- `/README.md`, `/docs/COMPONENT_MAP.md`, `/docs/CHANGELOG.md`, `/app/build.gradle.kts`

---

## [Build 7 — v1.6.0] - 2026-11-01
**Suggested Git Commit:** `feat(wled): add dual-mode USB-C Ethernet & Wi-Fi local socket binding and official WLED documentation links`

### Added
- **Dual-Mode Physical Transport & Local Interface Binding (`WledNetworkTransport.kt`)**:
  - Added automatic `ConnectivityManager` interface detection and socket binding supporting both rider power workflows without manual Android network switching:
    1. **Phone Charged -> USB-C to Ethernet (`TRANSPORT_ETHERNET`)**: Automatically prioritizes wired USB-C Ethernet adapters (`eth0`/`usb0`) for zero-interference HTTP state transitions and 50 Hz UDP Audio Sync.
    2. **Phone Charging -> Hardened Wi-Fi (`TRANSPORT_WIFI`)**: Binds `OkHttpClient` (`network.socketFactory` + `network::getAllByName`) and `MusicService` `DatagramSocket` (`network.bindSocket`) directly to the Wi-Fi interface (`wlan0`), completely blocking Android from falling back to 5G/LTE mobile data when `WLED-AP` (`4.3.2.1`) has no internet access.
    3. **USB-C Serial Mirroring (`115200` baud)**: Added optional JSON-over-Serial (`UsbManager` bulk transfer for CP210x, CH340, FTDI, and ESP32 CDC-ACM) when a microcontroller is plugged directly into the phone's USB-C port.
- **Settings & Home Live Transport UI (`WledNetworkSettingsCard.kt` & `HomeReadinessCard.kt`)**:
  - Added a live **Active Link Banner** displaying the active physical transport (`USB-C Ethernet (Wired)`, `Wi-Fi Bound (No 5G Fallback)`, or `USB-C Serial`), local interface name, local IPv4 address, and round-trip ping latency (`ms`).
  - Added one-tap host preset chips for **WLED-AP (`4.3.2.1`)**, **Wired IP (`192.168.1.50`)**, and **mDNS (`prettylights.local`)**, plus a toggle to lock WLED traffic to local Ethernet/Wi-Fi.
- **Official WLED Knowledge Base Links (`/README.md` & `/docs/PROTOCOLS.md`)**:
  - Linked official WLED documentation ([https://kno.wled.ge/](https://kno.wled.ge/)) for JSON API, Presets, Effects, Palettes, and Audio-Reactive UDP Sound Sync V2.

### Fixed
- **Prevented `TransactionTooLargeException` on Long Ride CSV Exports (`SessionExportHelper.kt`, `AndroidManifest.xml`, `res/xml/file_paths.xml`)**:
  - Replaced raw `Intent.EXTRA_TEXT` string sharing (which hits Android's 1 MB Binder IPC cap on rides > 3 minutes) with buffered disk streaming (`cacheDir/exports/prettynights_session_<id>.csv`) shared via `FileProvider` (`Intent.EXTRA_STREAM`).
- **Throttled Foreground Service Notification Updates (`RideService.kt`)**:
  - Throttled `NotificationManager.notify()` from 50 Hz (every accelerometer tick) down to immediate state transitions (`IDLE`/`CRUISING`/`BRAKING`/`BRAKING_HOLD`) or a maximum of `1 Hz` (`1000 ms`) for speed readouts, eliminating Binder IPC spam and GC churn.
- **Added SQLite Composite Indexes on High-Rate Telemetry Tables (`LoggingDatabase.kt`)**:
  - Added composite `(sessionId, timestampMonotonicMs)` / `(sessionId, startTimeMonotonicMs)` indexes to `imu_samples`, `bosch_samples`, `ride_markers`, `state_intervals`, and `wled_intervals` (Room v3) to eliminate full-table scans when loading History cards or the Tuning Replay Simulator.

### Key Files Touched
- `/app/src/main/java/com/example/wled/WledNetworkTransport.kt`
- `/app/src/main/java/com/example/wled/WledController.kt`
- `/app/src/main/java/com/example/ride/MusicService.kt`, `/app/src/main/java/com/example/ride/RideService.kt`
- `/app/src/main/java/com/example/history/SessionExportHelper.kt`, `/app/src/main/java/com/example/history/HistoryScreen.kt`
- `/app/src/main/java/com/example/logging/LoggingDatabase.kt`
- `/app/src/main/java/com/example/settings/WledNetworkSettingsCard.kt`
- `/app/src/main/java/com/example/home/HomeReadinessCard.kt`, `/app/src/main/java/com/example/home/HomeScreen.kt`
- `/app/src/main/java/com/example/config/ConfigManager.kt`, `/app/src/main/java/com/example/PrettyNightsApp.kt`
- `/app/src/main/AndroidManifest.xml`, `/app/src/main/res/xml/file_paths.xml`, `/app/src/main/res/values/strings.xml`, `/app/build.gradle.kts`
- `/README.md`, `/docs/PROTOCOLS.md`, `/docs/COMPONENT_MAP.md`, `/docs/CHANGELOG.md`

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
