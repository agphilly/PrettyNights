# Pretty Nights — Hardware & Network Protocols

## 1. WLED Hardware Integration

Pretty Nights communicates with WLED hardware (ESP8266 / ESP32) using both HTTP JSON REST endpoints and low-latency UDP streaming.

### A. HTTP JSON API
- **Endpoint**: `http://<wledHost>/json/state` (Default hostname: `prettylights.local`, port 80).
- **Primary Payloads**:
  ```json
  {
    "on": true,
    "bri": 255,
    "transition": 3,
    "seg": [
      {
        "id": 0,
        "fx": 0,
        "sx": 128,
        "ix": 128,
        "pal": 0,
        "col": [
          [255, 120, 0]
        ]
      }
    ]
  }
  ```
- **Preset Triggering**:
  `{"ps": <presetId>, "bri": <briInt>}`
- **Metadata Endpoints**:
  - `GET http://<wledHost>/presets.json`: Returns user-saved lighting presets.
  - `GET http://<wledHost>/json/eff`: Array of available effect names (e.g., Solid, Rainbow, Chase).
  - `GET http://<wledHost>/json/pal`: Array of available color palette names.

### B. WLED Sink Rate-Limiting & UDP Throttling
- Because standard microcontrollers have limited socket buffers, `RideOrchestrator` implements an adaptive sink:
  - **State and Look Changes**: Dispatched immediately (< 50ms) to ensure instant brake light assertion.
  - **Continuous Brightness Ramps**: Rate-limited to max 1 packet per 250ms unless a significant step jump (> 0.10) occurs.
  - **Graceful Error Handling**: Network unreachability exceptions are logged at debug level (`Log.d`) to avoid logcat flooding when the bike is powered down or off-network.

---

## 2. Audio-Reactive UDP Streaming (Port 11988)

Pretty Nights includes an integrated music-reactive subsystem running inside `MusicService`:
- **Audio Capture**: Records microphone or internal system audio via `AudioRecord` / `MediaProjection` at 44.1 kHz, 16-bit PCM.
- **Fast Fourier Transform (FFT)**: Computes real-time frequency energy split into 16 logarithmically scaled frequency bands (Sub-bass, Bass, Mid, High, Presence).
- **UDP Broadcast**: Sends formatted audio reactive synchronization packets to WLED listening on port 11988 (configurable in Settings), driving sound-reactive WLED patterns like *Dancing Shadows*, *Freqwave*, and *Gravimeter*.

---

## 3. Bosch eBike BLE & Speed Provider Telemetry

Pretty Nights abstracts vehicle velocity through `SpeedProvider`:
- **GPS Speed Provider**:
  - Uses Android `LocationManager` (`GPS_PROVIDER`) with 500ms update intervals.
  - Calculates ground speed in m/s, applying a low-pass filter to reject GPS multi-path jitter when stopped at traffic lights.
- **Bosch BLE Provider**:
  - Connects to Bosch Kiox / Nyon or smart eBike systems using Bluetooth LE GATT.
  - Subscribes to standard Cycling Speed & Cadence (CSCS) GATT service (`0x1816`) characteristic `0x2A5B` to obtain true wheel-revolution speed without GPS satellite latency.
- **Selection Modes**:
  - `GPS_ONLY`: Default mode for standard bikes.
  - `BOSCH_PREFERRED`: Uses Bosch wheel speed when connected; falls back to GPS smoothly on signal drop.
  - `BOSCH_ONLY`: Strictly uses calibrated wheel speed.

---

## 4. IMU Sensor Calibration & Coordinate Projection

- **Sensor**: Android Linear Acceleration (`Sensor.TYPE_LINEAR_ACCELERATION`), which removes Earth's 9.81 m/s² gravity vector.
- **Mounting Flexibility**:
  - Phone mounts differ (handlebar stem, top tube, quad lock in portrait or landscape).
  - The user selects the active forward/reverse axis: `X`, `Y`, or `Z`.
  - The user selects polarity: `+1` (normal) or `-1` (inverted).
- **Projection Formula**:
  $$\text{Forward Acceleration } a = \text{rawAxis} \times \text{polaritySign}$$
  - $a < \text{brakingDecelThreshold}$ (negative value) signals active vehicle deceleration.
