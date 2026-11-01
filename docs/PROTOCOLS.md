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

## 3. Bosch eBike Systems (The smart system) — Live Data Interface V1.0 & Speed Provider Telemetry

Pretty Nights implements **Always-On Parallel Telemetry Capture**: both `GpsSpeedProvider` and `BoschSpeedProvider` run and record simultaneously on every ride (whether WLED is active or Log-Only mode is enabled). The configured `speedSource` (`GPS_ONLY`, `BOSCH_PREFERRED`, `BOSCH_ONLY`) only selects which velocity stream drives live `RideStateMachine` transitions.

### A. Bosch Live Data Interface V1.0 (Release v19) BLE GATT & GAP Architecture
- **Specification**: *Bosch eBike Systems — The smart system — Live Data Interface V1.0 (Release v19, 2026-04-28)*.
- **Custom 128-bit Base UUID**: `0000xxxx-eaa2-11e9-81b4-2a2ae2dbcce4` (`ebike_uuid(xxxx)`).
- **eBike Live Data Service UUID**: `0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4` (`ebike_uuid(eb20)`).
- **Live Data Characteristic UUID**: `0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4` (`ebike_uuid(eb21)`), properties `Read | Notify`, Encryption Mode 1 Level 2.
- **GAP Role & Service Solicitation (Section 2.1.3)**:
  - The Bosch eBike acts as the GAP Central (`ebike_<num>`) and the Android phone advertises as a GAP Peripheral including the Service Solicitation UUID (`0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4`) and Local Name.
  - Additionally, `BoschLiveDataClient` connects to bonded Bosch eBikes (`ebike_*` or bonded devices exposing `eb20`), performs fresh GATT service discovery (`discoverServices()`) on every connection without caching attribute handles (Section 2.1.5.5), and enables notifications via CCCD (`00002902-0000-1000-8000-00805f9b34fb`).
- **Release v19 Known Issue Workarounds (Section 6)**:
  - **LDI-003 (No disconnect on insufficient MTU)**: In Release v19, if `ATT_MTU < 247`, the eBike does not disconnect; it silently truncates notifications to `ATT_MTU - 3` bytes, corrupting the protobuf frame. `BoschLiveDataClient` immediately requests `gatt.requestMtu(247)` upon connection before enabling `eb21` notifications and tracks `negotiatedMtu` and `decodeSuccess`.
  - **LDI-002 (Unchanged fields in notifications)**: Dynamic packaging may omit unchanged fields. `BoschLiveDataProtoDecoder` tracks explicit field presence (`presentFieldsMask`) and merges only present fields into `BoschLiveDataState`.

### B. `ebike_live_data.proto` (`com.bosch.ebike.LiveData`) Schema
| Field # | Protobuf Definition | Scaling / Units |
| :--- | :--- | :--- |
| `1` | `optional uint32 speed` | `1/100 km/h` (`0..655.35 km/h`) |
| `2` | `optional int32 cadence` | `1 rpm` (`-32768..32767 rpm`) |
| `5` | `optional uint32 rider_power` | `1 W` (`0..65535 W`) |
| `9` | `optional uint32 ambient_brightness` | `1/1000 lux` (`0..4294967.295 lux`) |
| `10` | `optional uint32 battery_soc` | `1 %` (`0..100 %`) |
| `11` | `optional int64 time` | UTC seconds since `1970-01-01` |
| `12` | `optional uint32 odometer` | `1 meter` (`0..4294967295 m`) |
| `17` | `optional LightState bike_light` | `0 = INVALID`, `1 = OFF`, `2 = ON` |
| `21` | `optional bool system_locked` | System lock active |
| `22` | `optional bool charger_connected` | Charger plugged in |
| `23` | `optional bool light_reserve_state` | Battery SOC low — drive off, light reserve active |
| `24` | `optional bool diagnosis_program_active` | Diagnostic tool connected |
| `25` | `optional bool bike_not_driving` | `true` = standing still, `false` = moving |

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
