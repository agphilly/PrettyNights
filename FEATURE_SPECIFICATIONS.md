# Pretty Nights — Product & Engineering Feature Specifications

**Application Name:** Pretty Nights  
**Application ID:** `com.aistudio.prettynights`  
**Platform:** Android (Kotlin, Jetpack Compose, Material Design 3, Coroutines/Flow, Room, DataStore)  

---

## 1. System Overview & Architecture

**Pretty Nights** is a smart kinetic and audio-reactive bicycle lighting controller for addressable LED strips powered by **WLED** (ESP8266/ESP32). It operates in two primary real-time modes:
1. **Kinetic Ride Mode (`RideService` + `RideOrchestrator`):** Uses GPS/Bosch speed telemetry and gravity-compensated 3-axis linear acceleration (`Sensor.TYPE_LINEAR_ACCELERATION`) at ~50 Hz to drive a 4-state lighting state machine (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`) over HTTP JSON (`/json/state`).
2. **Audio-Reactive Music Mode (`MusicService`):** Captures system audio playback via Android `MediaProjection` (`AudioPlaybackCaptureConfiguration`), computes a 1024-point Hann-windowed FFT across 16 logarithmic frequency bands, renders a live on-screen spectrum visualizer, and streams 84-byte WLED Audio-Reactive V2 packets over UDP multicast (`239.0.0.1:11988`) at 50 Hz.

```
+---------------------------------------------------------------------------------+
|                                PRETTY NIGHTS APP                                |
+---------------------------------------------------------------------------------+
|  +------------------------+  +------------------------+  +--------------------+ |
|  |       RideService      |  |      MusicService      |  |  DataStore & Room  | |
|  |  (Foreground Service)  |  |  (Foreground Service)  |  | (Config & History) | |
|  +------------------------+  +------------------------+  +--------------------+ |
|               |                           |                        |            |
|       +---------------+           +---------------+                |            |
|       |RideOrchestrator           |  Audio Engine |                |            |
|       | - Fused Speed |           | - MediaProject|                |            |
|       | - 50Hz IMU    |           | - 16-Band FFT |                |            |
|       | - StateMach.  |           | - Software AGC|                |            |
|       +---------------+           +---------------+                |            |
|               |                           |                        |            |
|               v                           v                        |            |
|     +-------------------+       +-------------------+              |            |
|     |  WledController   |       | UDP V2 Streamer   |              |            |
|     | (HTTP /json/state)|       | (239.0.0.1:11988) |              |            |
|     +-------------------+       +-------------------+              |            |
+---------------------------------------------------------------------------------+
```

---

## 2. Feature Specifications & UI States

### Feature 1: Ride & Music Cockpit (`HomeScreen`)

#### 1.1 Functional Requirements
- **FR-HOME-01 (Ride Session Control):** Users can start and stop a foreground Kinetic Ride session (`RideService`).
- **FR-HOME-02 (Music Mode Control):** Users can start and stop a foreground Audio-Reactive session (`MusicService`) after granting `RECORD_AUDIO` and `MediaProjection` screen/audio capture consent.
- **FR-HOME-03 (Mode Mutual Exclusion):** Starting Music Mode while Ride Mode is running automatically stops Ride Mode first; starting Ride Mode while Music Mode is running automatically stops Music Mode first.
- **FR-HOME-04 (Live Ride Telemetry):** Displays current speed (`km/h`), active `RideState` badge (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`), longitudinal acceleration (`m/s²`), and output brightness percentage.
- **FR-HOME-05 (Live 16-Band Spectrum Visualizer):** While Music Mode is active, renders a real-time 16-bar frequency spectrum visualizer driven by `MusicService.fftBands`.
- **FR-HOME-06 (WLED Health Indicator):** Displays reachability of the configured `wledHost` (`CHECKING`, `REACHABLE`, `UNREACHABLE`) with a manual refresh trigger.

#### 1.2 UI States & Edge Cases
| State | UI Behavior | Edge-Case Handling |
| :--- | :--- | :--- |
| **Idle / Standby** | Shows Start Ride CTA, Music Mode CTA, WLED connection status, and quick-tuning shortcuts. | If `wledHost` is unreachable, displays an error pill with troubleshooting guidance without blocking ride start. |
| **Ride Active (`IDLE` State)** | Shows live telemetry; safety lock (`LockController.isLocked`) remains `false` while stationary so rider can still adjust settings at a stoplight. | Transitions `isLocked` to `true` immediately upon entering `CRUISING`, `BRAKING`, or `BRAKING_HOLD`. |
| **Ride Active (Moving)** | `LockController.isLocked == true`; displays motion-lock badge and disables complex configuration tabs/controls. | Stopping the ride calls `lockController.forceUnlock()` to restore full UI access immediately. |
| **Music Mode Active** | Displays live 16-band equalizer animation and active UDP multicast destination (`239.0.0.1:<port>`). | If user cancels the `MediaProjection` system dialog, service aborts cleanly and UI stays in Standby. |

---

### Feature 2: Kinetic State Machine & Braking Physics (`RideStateMachine`)

#### 2.1 State Definitions & Transitions
1. **`IDLE`**
   - **Default Entry:** Session start, or from `CRUISING` when `speedKmh < idleEnterSpeedKmh` (`2.0 km/h`), or from `BRAKING_HOLD` after `brakingHoldDurationS + brakingFadeDurationS` (`2.0s + 1.0s = 3.0s`).
   - **Exit:** Transitions to `CRUISING` when `speedKmh > idleExitSpeedKmh` (`4.0 km/h`). Direct `IDLE -> BRAKING` transitions are blocked to reject stationary phone handling bumps.
2. **`CRUISING`**
   - **Entry:** From `IDLE` or `BRAKING_HOLD` when `speedKmh > idleExitSpeedKmh`, or from `BRAKING` when deceleration eases off for $\ge$ `brakingCooldownMs` (`350ms`) while `speedKmh >= idleEnterSpeedKmh`.
   - **Exit:** Priority 1 $\rightarrow$ `BRAKING` if deceleration is asserted; Priority 2 $\rightarrow$ `IDLE` if speed drops below `idleEnterSpeedKmh` (and Bosch `bikeNotDriving` confirms, when enabled).
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
  - State transitions (`stateChanged`) or WLED look changes (`lookChanged`) dispatch over HTTP **immediately**.
  - Continuous brightness-only updates during fades or braking ramps dispatch only if $\Delta t \ge 250\text{ ms}$ or $|\Delta B| \ge 0.10$.

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

### Feature 4: WLED Look & Pattern Studio (`PatternsScreen` & `WledController`)

#### 4.1 Functional Requirements
- **FR-PAT-01 (Per-State Look Profiles):** Independent configuration of `idleLook`, `cruisingLook`, and `brakingLook`.
- **FR-PAT-02 (Preset vs. Custom Segment Mode):** Each look can either reference an on-controller WLED Preset ID (`ps`) or define a custom segment (`fx` effect ID, `fp` palette ID, up to 3 hex RGB colors, `sx` speed, `ix` intensity, and `transitionMs`).
- **FR-PAT-03 (Live Controller Metadata Discovery):** Queries `/presets.json`, `/json/eff`, and `/json/pal` from `wledHost` with automatic fallback to built-in defaults when offline.

---

### Feature 5: Ride History & Telemetry Logging (`HistoryScreen` & `LoggingDatabase`)

#### 5.1 Room Database Entities
- `ride_sessions` (`RideSessionEntity`): Session start/end wall and monotonic timestamps.
- `state_intervals` (`StateIntervalEntity`): Contiguous intervals spent in each `RideState` with entry/exit brightness.
- `wled_intervals` (`WledIntervalEntity`): Contiguous intervals for each dispatched `WledLook` JSON snapshot.
- `imu_samples` (`ImuSampleEntity`): High-frequency 50 Hz 3-axis + projected acceleration samples buffered via `ConcurrentLinkedQueue` and batch-inserted every `1200ms`.

---

### Feature 6: Sensor Calibration, Settings & Developer Tuning (`SettingsScreen` & `TuningScreen`)

#### 6.1 Functional Requirements
- **FR-SET-01 (Host & Port Sanitization):** Automatically strips `http://`, `https://`, paths, queries, and ports from `wledHost` with inline user feedback; validates `audioSyncPort` in `1..65535`.
- **FR-SET-02 (Speed Source Selection):** Configures `GPS_ONLY`, `BOSCH_PREFERRED`, or `BOSCH_ONLY` with contextual warnings when Bosch hardware is not connected.
- **FR-SET-03 (Mount Axis & Polarity Calibration):** Configures handlebar forward axis (`X`, `Y`, `Z`) and deceleration sign (`-1` or `+1`), plus guided 1-tap calibration capture.
- **FR-TUN-01 (Live Telemetry & Manual Override):** `TuningScreen` displays a 6.7 Hz (`150ms` sampled) state machine telemetry panel and a station-only Manual Override bar (`IDLE`, `CRUISING`, `BRAKING` + brightness slider) throttled to 10 Hz (`100ms`).
- **FR-TUN-02 (Constraint Validation Warnings):** Displays non-blocking validation warnings if `globalMinBrightness > globalMaxBrightness`, `idleEnterSpeedKmh > idleExitSpeedKmh`, or `brakingMinBrightness > brakingMaxBrightness`.

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
