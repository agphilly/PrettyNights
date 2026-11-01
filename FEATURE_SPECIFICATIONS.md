# Pretty Nights — Product & Engineering Feature Specifications

**Application Name:** Pretty Nights  
**Application ID:** `com.aistudio.prettynights.gskwqp`  
**Platform:** Android (Kotlin, Jetpack Compose, Material Design 3, Coroutines/Flow, Room, DataStore, BLE GATT/GAP)  

---

## 1. System Overview & Architecture

**Pretty Nights** is a smart kinetic and audio-reactive bicycle lighting controller and 50 Hz telemetry recorder for addressable LED strips powered by **WLED** (ESP8266/ESP32) and **Bosch eBike Systems (The smart system)**. It operates in two primary real-time modes:
1. **Kinetic Ride Mode (`RideService` + `RideOrchestrator`):** Uses parallel GPS and Bosch eBike BLE Live Data V1.0 (`eb20`/`eb21`) velocity streams together with gravity-compensated 3-axis linear acceleration (`Sensor.TYPE_LINEAR_ACCELERATION`) at ~50 Hz to drive a 4-state lighting state machine (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`) over HTTP JSON (`/json/state`). Supports **Log Only Mode (No WLED)** to capture full 50 Hz IMU + GPS + Bosch telemetry on any ride without WLED hardware connected.
2. **Audio-Reactive Music Mode (`MusicService`):** Captures system audio playback via Android `MediaProjection` (`AudioPlaybackCaptureConfiguration`), computes a 1024-point Hann-windowed FFT across 16 logarithmic frequency bands, renders a live on-screen spectrum visualizer, and streams 84-byte WLED Audio-Reactive V2 packets over UDP multicast (`239.0.0.1:11988`) at 50 Hz.

```
+---------------------------------------------------------------------------------+
|                                PRETTY NIGHTS APP                                |
+---------------------------------------------------------------------------------+
|  +------------------------+  +------------------------+  +--------------------+ |
|  |       RideService      |  |      MusicService      |  |  DataStore & Room  | |
|  |  (Foreground Service)  |  |  (Foreground Service)  |  | (Config & History) | |
|  +------------------------+  +------------------------+  +--------------------+ |
|               |                           |                        ^            |
|       +---------------+           +---------------+                |            |
|       |RideOrchestrator           |  Audio Engine |                |            |
|       | - GPS + Bosch |           | - MediaProject|                |            |
|       | - 50Hz IMU    |--------+  | - 16-Band FFT |                |            |
|       | - StateMach.  |        |  | - Software AGC|                |            |
|       +---------------+        |  +---------------+                |            |
|               |                +-----------------------------------+            |
|               v (Skipped in Log-Only Mode)                                      |
|     +-------------------+       +-------------------+                           |
|     |  WledController   |       | UDP V2 Streamer   |                           |
|     | (HTTP /json/state)|       | (239.0.0.1:11988) |                           |
|     +-------------------+       +-------------------+                           |
+---------------------------------------------------------------------------------+
```

---

## 2. Feature Specifications & UI States

### Feature 1: Ride & Music Cockpit (`HomeScreen`, `HomeReadinessCard`, `HomeActionControls`, `HomeVisualizerCard`)

#### 1.1 Functional Requirements
- **FR-HOME-01 (Ride Session Control):** Users can start and stop a foreground Kinetic Ride session (`RideService`).
- **FR-HOME-02 (Music Mode Control):** Users can start and stop a foreground Audio-Reactive session (`MusicService`) after granting `RECORD_AUDIO` and `MediaProjection` screen/audio capture consent.
- **FR-HOME-03 (Mode Mutual Exclusion):** Starting Music Mode while Ride Mode is running automatically stops Ride Mode first; starting Ride Mode while Music Mode is running automatically stops Music Mode first.
- **FR-HOME-04 (Live Ride Telemetry):** Displays current speed (`km/h`), active `RideState` badge (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`), longitudinal acceleration (`m/s²`), and output brightness percentage.
- **FR-HOME-05 (Live 16-Band Spectrum & Bike Visualizer):** Renders an interactive bicycle LED visualizer in Standard Mode and a real-time 16-bar frequency spectrum visualizer driven by `MusicService.fftBands` in Music Mode.
- **FR-HOME-06 (WLED & Bosch Readiness Banner):** Displays reachability of the configured `wledHost` (`CHECKING`, `REACHABLE`, `UNREACHABLE`) with a manual refresh trigger (or a dedicated `LOG ONLY (NO WLED)` badge when Log Only Mode is enabled), plus live Bosch BLE (`eb20`/`eb21`) connection status.
- **FR-HOME-07 (Log Only Mode Toggle at Bottom of Home):** Provides a dedicated **Log Only Mode (No WLED)** toggle card (`HomeLogOnlyModeCard`) positioned at the bottom of the Home screen (below the bike visualizer card in Standard Mode). When enabled, rides start without WLED network checks and suppress all WLED HTTP/UDP traffic while still recording 100% of 50 Hz IMU, GPS, Bosch BLE, and state machine telemetry.
- **FR-HOME-08 (Live In-Ride Event Marker Bar):** While a ride is active (both WLED-enabled and Log-Only), displays a 4-button **Stamp Live Event Marker** bar (`HARD_BRAKE`, `LIGHT_BRAKE`, `ROAD_BUMP`, `BOSCH_GLITCH`) with instant haptic/snackbar confirmation to record ground-truth timestamps for post-ride tuning and replay verification.
- **FR-HOME-09 (Streamlined Cockpit Layout):** The Home screen focuses strictly on readiness, ride/music session controls, live event stamping, the visualizer card, and the bottom Log Only Mode card; Pattern studio and Settings/Calibration are accessed via the bottom navigation bar, and Developer Tuning is accessed from the Settings screen.

#### 1.2 UI States & Edge Cases
| State | UI Behavior | Edge-Case Handling |
| :--- | :--- | :--- |
| **Idle / Standby** | Shows Readiness Card, Mode Selector, Start Ride / Music Mode CTA, Bike/Audio Visualizer, and bottom Log Only Mode toggle card. | If `wledHost` is unreachable, displays an error pill without blocking ride start; enabling Log Only Mode switches the readiness pill to `LOG ONLY (NO WLED)`. |
| **Ride Active (`IDLE` State)** | Shows live telemetry and the 4-button **Stamp Live Event Marker** bar; safety lock (`LockController.isLocked`) remains `false` while stationary so rider can still adjust settings at a stoplight. | Transitions `isLocked` to `true` immediately upon entering `CRUISING`, `BRAKING`, or `BRAKING_HOLD`. |
| **Ride Active (Moving)** | `LockController.isLocked == true`; displays motion-lock badge and disables configuration tabs while keeping the **Stop Ride** button and **Live Event Marker** buttons accessible on Home. | Stopping the ride calls `lockController.forceUnlock()` to restore full UI access immediately. |
| **Music Mode Active** | Displays live 16-band equalizer animation and active UDP multicast destination (`239.0.0.1:<port>`). | If user cancels the `MediaProjection` system dialog, service aborts cleanly and UI stays in Standby. |

---

### Feature 2: Kinetic State Machine & Braking Physics (`RideStateMachine`)

#### 2.1 State Definitions & Transitions
1. **`IDLE`**
   - **Default Entry:** Session start, or from `CRUISING` when `speedKmh < idleEnterSpeedKmh` (`2.0 km/h`) (and when `useBoschCorroboration && requireBoschIdleConfirm` is enabled, once Bosch `bike_not_driving` field 25 confirms standstill), or from `BRAKING_HOLD` after `brakingHoldDurationS + brakingFadeDurationS` (`2.0s + 1.0s = 3.0s`).
   - **Exit:** Transitions to `CRUISING` when `speedKmh > idleExitSpeedKmh` (`4.0 km/h`). Direct `IDLE -> BRAKING` transitions are blocked to reject stationary phone handling bumps.
2. **`CRUISING`**
   - **Entry:** From `IDLE` or `BRAKING_HOLD` when `speedKmh > idleExitSpeedKmh`, or from `BRAKING` when deceleration eases off for $\ge$ `brakingCooldownMs` (`350ms`) while `speedKmh >= idleEnterSpeedKmh`.
   - **Exit:** Priority 1 $\rightarrow$ `BRAKING` if deceleration is asserted; Priority 2 $\rightarrow$ `IDLE` if speed drops below `idleEnterSpeedKmh` (and Bosch `bikeNotDriving` confirms, when `requireBoschIdleConfirm` is enabled).
3. **`BRAKING`**
   - **Entry:** From `CRUISING` when calibrated longitudinal acceleration $\le$ `brakingDecelThreshold` (`-1.5 m/s²`) continuously for $\ge$ `brakingDebounceMs` (`180ms`).
   - **Dynamic Gamma Brightness Formula:**
     - Captures `entrySpeedKmh` at braking onset.
     - Computes normalized speed reduction ratio:
       $$\text{ratio} = \text{clamp}\left(1.0 - \frac{v_{\text{current}}}{\max(v_{\text{entry}}, 1.0)}, 0.0, 1.0\right)$$
     - Applies non-linear gamma exponent (`brakingGamma = 1.6`):
       $$B_{\text{clean}} = B_{\text{brakingMin}} + (B_{\text{brakingMax}} - B_{\text{brakingMin}}) \cdot \text{ratio}^{\gamma}$$
4. **`BRAKING_HOLD`**
   - **Entry:** From `BRAKING` when `speedKmh < idleEnterSpeedKmh` (`2.0 km/h`), capturing `holdStartBrightness = lastCleanBrightness`.
   - **Hold Phase (`t <= brakingHoldDurationS`):** Holds output steady at `holdStartBrightness` for `2.0s`.
   - **Fade Phase (`brakingHoldDurationS < t < brakingHoldDurationS + brakingFadeDurationS`):** Linearly fades from `holdStartBrightness` down to `idleBrightness` over `1.0s`.
   - **Early Roll-Out:** If `speedKmh > idleExitSpeedKmh` (`4.0 km/h`) at any point during hold or fade, immediately transitions to `CRUISING`.

#### 2.2 Global Brightness Envelope & WLED Sink Throttling
- **Envelope Mapping:**
  $$B_{\text{final}} = \text{clamp}\left(B_{\text{globalMin}} + (B_{\text{ceiling}} - B_{\text{globalMin}}) \cdot B_{\text{clean}}, 0.0, 1.0\right)$$
  where $B_{\text{ceiling}} = 1.0$ if `currentState == BRAKING && brakingIgnoresGlobalMax`, else `globalMaxBrightness` (`0.80`).
- **Sink Throttling (`RideOrchestrator.evalAndSendWledSink`):**
  - When `config.logOnlyMode == true`, WLED network dispatch is completely bypassed while state and look intervals continue to be evaluated and logged.
  - When `config.logOnlyMode == false`, state transitions (`stateChanged`) or WLED look changes (`lookChanged`) dispatch over HTTP **immediately**; continuous brightness-only updates during fades or braking ramps dispatch only if $\Delta t \ge 250\text{ ms}$ or $|\Delta B| \ge 0.10$.

---

### Feature 3: Audio-Reactive Music Engine (`MusicService`)

#### 3.1 Audio Capture & DSP Specification
- **Capture Configuration:** Android 10+ `AudioPlaybackCaptureConfiguration` capturing `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN` at `44,100 Hz`, 16-bit PCM Mono.
- **Windowing & Framing:** Reads `882 samples` (`20ms`) per tick into a `1024-sample` sliding window buffer.
- **Volume & Beat Detection:**
  - `sampleRaw`: Peak amplitude in the 20ms frame scaled to `0..255`.
  - `sampleAvg`: Exponential moving average ($\alpha = 0.1$).
  - `samplePeak`: Beat flag (`1` if `sampleAvg > 1.5 * rolling50FrameAvg` and `sampleAvg > 5.0`, else `0`).
  - `sampleAgc`: Software automatic gain control targeting `128` with gain clamped to `[0.1, 10.0]`.
- **1024-Point Hann FFT & 16 Logarithmic Bands:**
  - Applies Hann window $w[n] = 0.5 \cdot (1 - \cos(2\pi n / 1023))$ and in-place Radix-2 FFT.
  - Maps RMS bin magnitudes into 16 logarithmic frequency bands (`20 Hz` to `20,000 Hz`).

#### 3.2 WLED Audio Sync V2 UDP Packet Schema (84 Bytes, Little-Endian)
Multicast at `50 Hz` (`20ms` interval) to `239.0.0.1:<audioSyncPort>` (default port `11988`):
- Bytes `0..5`: `"00002\0"` protocol header (`0x30, 0x30, 0x30, 0x30, 0x32, 0x00`)
- Bytes `6..37`: `myVals[32]` (`uint8[32]` waveform samples)
- Bytes `38..41`: `sampleAgc` (`int32`)
- Bytes `42..45`: `sampleRaw` (`int32`)
- Bytes `46..49`: `sampleAvg` (`float32`)
- Byte `50`: `samplePeak` (`uint8`)
- Byte `51`: `0x00` reserved padding (`uint8`)
- Bytes `52..67`: `fftResult[16]` (`uint8[16]` 16-band spectrum)
- Bytes `68..75`: `fftMagnitude` (`float64`)
- Bytes `76..83`: `fftMajorPeak` (`float64` dominant frequency in Hz)

---

### Feature 4: WLED Look & Pattern Studio (`PatternsScreen`, `StateLookCard`, `ColorPickerSliders`, & `WledController`)

#### 4.1 Functional Requirements
- **FR-PAT-01 (Per-State Look Profiles):** Independent configuration of `idleLook`, `cruisingLook`, and `brakingLook`.
- **FR-PAT-02 (Preset vs. Custom Segment Mode):** Each look can either reference an on-controller WLED Preset ID (`ps`) or define a custom segment (`fx` effect ID, `fp` palette ID, up to 3 hex RGB colors, `sx` speed, `ix` intensity, and `transitionMs`).
- **FR-PAT-03 (Live Controller Metadata Discovery):** Queries `/presets.json`, `/json/eff`, and `/json/pal` from `wledHost` with automatic fallback to built-in defaults when offline.

---

### Feature 5: Always-On Telemetry Logging, Bosch Live Data V1.0 & Ride History (`HistoryScreen`, `LoggingDatabase`, `SessionExportHelper`)

#### 5.1 Room Database Entities (`LoggingDatabase`)
- `sessions` (`SessionEntity`): Session start/end wall and monotonic timestamps, `title`, `notes`, `logOnlyMode`, and `configuredSpeedSource`.
- `state_intervals` (`StateIntervalEntity`): Contiguous intervals spent in each `RideState` with entry/exit brightness.
- `wled_intervals` (`WledIntervalEntity`): Contiguous intervals for each dispatched `WledLook` JSON snapshot.
- `imu_samples` (`ImuSampleEntity`): Unified 50 Hz ride telemetry rows containing 3-axis + projected acceleration, `speedKmh`, `gpsSpeedKmh`, `boschSpeedKmh`, `boschAgeMs`, `boschBikeNotDriving`, `boschCadenceRpm`, `boschRiderPowerW`, `boschAmbientLux`, `boschBatterySoc`, `boschBikeLight`, `activeSpeedSource`, and `stateName`, buffered via `ConcurrentLinkedQueue` and batch-inserted every `1200ms`.
- `bosch_samples` (`BoschSampleEntity`): Every raw `com.bosch.ebike.LiveData` notification received on characteristic `0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4`, storing `negotiatedMtu`, `payloadSizeBytes`, `decodeSuccess`, `presentFieldsMask`, decoded fields (`1, 2, 5, 9, 10, 11, 12, 17, 21–25`), and simultaneous `gpsSpeedKmhAtCapture`.
- `ride_markers` (`RideMarkerEntity`): Live rider-stamped ground-truth event markers (`HARD_BRAKE`, `LIGHT_BRAKE`, `ROAD_BUMP`, `BOSCH_GLITCH`).

#### 5.2 History & Export Requirements
- **FR-HIS-01 (Bosch Live Data V1.0 Reliability Audit Card):** Computes per-session Bosch packet count, valid protobuf decode percentage, minimum negotiated `ATT_MTU` (`LDI-003` truncation warning if `< 247`), freshness uptime (`< 4s`), mean/max notification cadence, mean `|Bosch - GPS|` speed delta, and `bike_not_driving` transition count.
- **FR-HIS-02 (Post-Ride Title & Session Notes):** Allows editing session title and structured field notes for every recorded ride.
- **FR-HIS-03 (Unified 4-Section CSV Export):** Shares a complete `.csv` file via Android `FileProvider` containing session/Bosch headers, `# IN_RIDE_EVENT_MARKERS`, `# BOSCH_LIVE_DATA_V1_NOTIFICATIONS`, and `# UNIFIED_50HZ_RIDE_TELEMETRY`.

---

### Feature 6: Sensor Calibration, Settings & Developer Tuning (`SettingsScreen`, `TuningScreen`, `TuningReplayCard`)

#### 6.1 Functional Requirements
- **FR-SET-01 (Host & Port Sanitization):** Automatically strips `http://`, `https://`, paths, queries, and ports from `wledHost` with inline user feedback; validates `audioSyncPort` in `1..65535`.
- **FR-SET-02 (Speed Source Selection):** Configures `GPS_ONLY`, `BOSCH_PREFERRED`, or `BOSCH_ONLY` for live state-machine control while keeping parallel GPS + Bosch recording always active.
- **FR-SET-03 (Mount Axis & Polarity Calibration):** Configures handlebar forward axis (`X`, `Y`, `Z`) and deceleration sign (`-1` or `+1`), plus guided 1-tap calibration capture.
- **FR-TUN-01 (Live Telemetry & Manual Override):** `TuningScreen` (accessed from Settings) displays a 6.7 Hz (`150ms` sampled) state machine + Bosch `eb21` telemetry panel and a stationary Manual Override bar (`IDLE`, `CRUISING`, `BRAKING` + brightness slider) throttled to 10 Hz (`100ms`).
- **FR-TUN-02 (Constraint Validation Warnings):** Displays non-blocking validation warnings if `globalMinBrightness > globalMaxBrightness`, `idleEnterSpeedKmh > idleExitSpeedKmh`, or `brakingMinBrightness > brakingMaxBrightness`.
- **FR-TUN-03 (In-App Ride Replay Simulator):** `TuningReplayCard` replays any recorded 50 Hz ride session through `RideStateMachine` using the current tuning sliders and a selectable replay speed stream (`RECORDED`, `BOSCH_EB21`, or `GPS_ONLY`), reporting total simulated braking episodes, `HARD_BRAKE`/`LIGHT_BRAKE` detection accuracy (`±750ms`), and `ROAD_BUMP` false-positive rejection accuracy.

---

## 3. Acceptance & Verification Matrix

| Test ID | Feature Under Test | Verification Method | Expected Result |
| :--- | :--- | :--- | :--- |
| **VER-01** | `IDLE -> CRUISING` Hysteresis | Tick `RideStateMachine` at `3.5 km/h` then `4.2 km/h`, then drop to `3.0 km/h` and `1.8 km/h`. | Enters `CRUISING` only above `4.0 km/h`, stays `CRUISING` at `3.0 km/h`, returns to `IDLE` at `1.8 km/h`. |
| **VER-02** | Braking Debounce & Cooldown | Apply `-2.0 m/s²` for `100ms` (reject) vs `200ms` (assert), then ease off for `300ms` (hold) vs `360ms` (release). | Rejects `< 180ms` spikes; stays `BRAKING` during `< 350ms` easing; returns to `CRUISING` after `350ms`. |
| **VER-03** | Gamma Braking & Global Ceiling | Enter `BRAKING` at `20 km/h` with `brakingIgnoresGlobalMax = true` and decelerate to `2.0 km/h`. | Output brightness ramps non-linearly ($\gamma = 1.6$) and exceeds `0.80` global ceiling up to `1.00`. |
| **VER-04** | `BRAKING_HOLD` Hold & Fade Phases | Drop speed to `1.5 km/h` while braking and advance time by `1.0s`, `2.5s`, and `3.1s`. | Reports `holdFadePhase = "hold"` at `1.0s`, `"fade"` at `2.5s`, and transitions to `IDLE` after `3.0s`. |
| **VER-05** | Motion Safety Lock (`LockController`) | Update `LockController` with `(IDLE, true)`, `(CRUISING, true)`, `(BRAKING, true)`, and `forceUnlock()`. | `isLocked` is `false` in `IDLE`, `true` in `CRUISING`/`BRAKING`, and `false` when stopped. |
| **VER-06** | WLED V2 84-Byte Audio Packet & FFT | Run 1024-point Hann FFT on a synthetic 1000 Hz sine wave and pack V2 buffer. | Packet is exactly 84 bytes, starts with `"00002\0"`, and dominant peak frequency resolves to ~`1000 Hz`. |
| **VER-07** | Bosch Live Data V1.0 Protobuf & Replay | Decode `com.bosch.ebike.LiveData` wire payload (`speed`, `cadence`, `rider_power`, `battery_soc`, `bike_not_driving`) and run `TuningReplayCard` simulation. | Decodes all V1.0 fields accurately, tracks `presentFieldsMask`, and scores brake/bump markers deterministically. |
