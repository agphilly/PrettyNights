# Pretty Nights — Smart Bicycle WLED Lighting & Telemetry Controller

**Pretty Nights** (`com.aistudio.prettynights.gskwqp`) is an Android cockpit and lighting orchestration application built with Kotlin and Jetpack Compose (Material 3). It transforms a smartphone into an automated bicycle lighting controller that drives **WLED (ESP8266/ESP32)** addressable LED strips using real-time GPS velocity/deceleration telemetry, 40 Hz microphone FFT audio analysis, and manual preset controls.

For complete functional requirements, state-machine matrices, packet layouts, UI states, and edge-case specifications, see [**`FEATURE_SPECIFICATIONS.md`**](./FEATURE_SPECIFICATIONS.md).

---

## Key Capabilities

- **5-State Ride Mode Orchestration (`RideStateMachine` & `RideOrchestrator`)**:
  - Evaluates 500 ms GPS telemetry and derived longitudinal acceleration (`m/s²`) with hysteresis and minimum hold timers to transition between `WaitingForGps`, `Stopped`, `Cruising`, `Slowing`, and `Braking`.
  - Automatically triggers high-priority full-brightness (`255`) red brake overrides on segment `0` when deceleration drops below the configurable brake threshold.
- **40 Hz Audio-Reactive Music Mode (`MusicService`)**:
  - Captures 44.1 kHz 16-bit mono PCM audio via an `AudioRecord` foreground service (`FOREGROUND_SERVICE_TYPE_MICROPHONE`), applies a 1024-point Hann-windowed radix-2 Cooley-Tukey FFT, maps 16 logarithmic frequency channels, applies AGC and exponential smoothing, and multicasts 84-byte Little-Endian **WLED Audio Reactive V2** packets to `239.0.0.1:11988`.
- **Manual WLED Pattern & Segment Control (`PatternsScreen` & `WledController`)**:
  - Sends HTTP JSON payloads (`POST /json/state`) to `192.168.4.1` with network connectivity gating, 3-attempt exponential backoff (`200ms`, `400ms`, `800ms`), and live power, brightness, RGB color, and effect speed/intensity sliders.
- **Offline Ride History & CSV Export (`HistoryScreen` & `LoggingDatabase`)**:
  - Records ride sessions and telemetry breadcrumbs to a local SQLite **Room** database (`pretty_nights_logs.db`) and exports formatted CSV telemetry files via the Android system share sheet (`FileProvider`).
- **Live Telemetry Tuning (`TuningScreen` & `SettingsRepository`)**:
  - Persists speed and deceleration hysteresis thresholds in Jetpack **DataStore** (`ride_settings`) with real-time slider validation (`stopSpeedMph < resumeSpeedMph` and `brakeDecelMps2 < slowDecelMps2`).

---

## Keeping Version Control on GitHub

You can synchronize this project with GitHub directly from **Google AI Studio** or via a local Git/Android Studio environment.

### Workflow A: Built-In GitHub Sync in Google AI Studio (Recommended)
1. Click the **Settings / Project Menu** in the top navigation bar of Google AI Studio.
2. Select **Save to GitHub** (or **Push to GitHub**).
3. Authorize your GitHub account if prompted, select or create your target repository, choose a branch (e.g., `main`), and enter a commit message.
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
│  ┌─────────────────────────┐               ┌─────────────────────────┐  │
│  │   WledController        │               │   MusicService (DSP)    │  │
│  │   Retrofit / OkHttp     │               │   1024-pt FFT @ 40 Hz   │  │
│  └────────────┬────────────┘               └────────────┬────────────┘  │
└───────────────┼─────────────────────────────────────────┼───────────────┘
                │ HTTP POST /json/state                   │ UDP Multicast (84B)
                │ 192.168.4.1:80 (Timeout: 1.5s)          │ 239.0.0.1:11988
                ▼                                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  ESP8266 / ESP32 WLED ACCESS POINT                      │
│  • Segment 0: Brake Override (Solid FX 0, RGB [255, 0, 0], Bri 255)     │
│  • Segment 1: Main Body Patterns / Ride State Presets / Audio Sync      │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Project Architecture

| Package / Module | Responsibility |
| :--- | :--- |
| `com.example.MainActivity` | Edge-to-edge M3 scaffold, bottom navigation bar, runtime permissions (`ACCESS_FINE_LOCATION`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`), and screen routing. |
| `com.example.home` | `HomeScreen` cockpit dashboard with live GPS readout, WLED connection pill, mode segmented buttons, and Music Mode sub-mode selector. |
| `com.example.ride` | `RideStateMachine` pure hysteresis evaluator, `RideOrchestrator` foreground service (`location`), `GpsManager`, and `MusicService` (`microphone` FFT + UDP V2). |
| `com.example.patterns` | `PatternsScreen` manual WLED power, brightness, RGB color swatches, effect cards, and speed/intensity sliders. |
| `com.example.history` | `HistoryScreen` expandable ride session cards, summary metrics, and CSV export (`CsvExporter`). |
| `com.example.settings` & `tuning` | `SettingsRepository` (DataStore), `SettingsScreen` (preset mapping & brake override toggle), and `TuningScreen` (threshold sliders). |
| `com.example.wled` | `WledController`, `WledApiService` (`POST /json/state`), and Moshi models (`WledStatePayload`, `WledSegmentPayload`). |
| `com.example.logging` | Room database (`LoggingDatabase`), `RideDao`, `RideSessionEntity`, and `RideEventEntity`. |

---

## Building & Running Tests

### Run JVM & Robolectric Unit Tests
Executes unit tests covering `RideStateMachine` transitions, `MusicService.packV2Packet` 84-byte Little-Endian frame layout, and `MusicService.fftInPlace` 1024-point FFT spectral concentration:
```bash
gradle :app:testDebugUnitTest
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
3. Runs the unit test suite (`:app:testDebugUnitTest`) and publishes HTML test reports as build artifacts.
4. Compiles the debug APK (`:app:assembleDebug`) and uploads `pretty-nights-debug-apk` to the workflow run summary.
