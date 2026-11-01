# Pretty Nights — Hardware & Network Protocols

## 1. WLED Hardware Integration

Pretty Nights communicates with WLED hardware (ESP8266 / ESP32) using both HTTP JSON REST endpoints and low-latency UDP streaming.

### Official WLED Knowledge Base References
- **WLED Knowledge Base (Home)**: [https://kno.wled.ge/](https://kno.wled.ge/)
- **JSON API (`/json/state`, `/json/eff`, `/json/pal`)**: [https://kno.wled.ge/interfaces/json-api/](https://kno.wled.ge/interfaces/json-api/)
- **Presets (`/presets.json`)**: [https://kno.wled.ge/features/presets/](https://kno.wled.ge/features/presets/)
- **Effects Catalog (`fx` IDs)**: [https://kno.wled.ge/features/effects/](https://kno.wled.ge/features/effects/)
- **Color Palettes (`fp` IDs)**: [https://kno.wled.ge/features/palettes/](https://kno.wled.ge/features/palettes/)
- **Audio Reactive & UDP Sound Sync (Port `11988`)**: [https://kno.wled.ge/advanced/audio-reactive/](https://kno.wled.ge/advanced/audio-reactive/)
- **UDP Realtime / Notifier Interfaces**: [https://kno.wled.ge/interfaces/udp-notifier/](https://kno.wled.ge/interfaces/udp-notifier/)

### A. HTTP JSON API
- **Reference**: [WLED JSON API Documentation](https://kno.wled.ge/interfaces/json-api/)
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
  - `GET http://<wledHost>/presets.json`: Returns user-saved lighting presets ([WLED Presets](https://kno.wled.ge/features/presets/)).
  - `GET http://<wledHost>/json/eff`: Array of available effect names (e.g., Solid, Rainbow, Chase — [WLED Effects](https://kno.wled.ge/features/effects/)).
  - `GET http://<wledHost>/json/pal`: Array of available color palette names ([WLED Palettes](https://kno.wled.ge/features/palettes/)).

### B. Dual-Mode Physical Transport (`WledNetworkTransport`: USB-C Ethernet + Hardened Wi-Fi + USB Serial)
To eliminate Wi-Fi drops on a bicycle and support both **charged rides (wired USB-C Ethernet)** and **charging rides (Wi-Fi)** without manual Android network switching, `WledNetworkTransport` implements strict local-interface socket binding via `ConnectivityManager`:
1. **Priority 1 — Charged Phone: USB-C to Ethernet (`NetworkCapabilities.TRANSPORT_ETHERNET`)**:
   - When a USB-C to Ethernet adapter is plugged into the phone (wired to an Ethernet-enabled WLED controller such as a WT32-ETH01 or QuinLED-Dig-Uno/Quad Ethernet), `WledNetworkTransport` automatically detects the `TRANSPORT_ETHERNET` network and binds both `OkHttpClient` (`network.socketFactory` + `network::getAllByName`) and `MusicService`'s UDP socket (`network.bindSocket`) directly to the wired interface (`eth0`/`usb0`).
2. **Priority 2 — Charging Phone: Hardened Wi-Fi (`NetworkCapabilities.TRANSPORT_WIFI`)**:
   - When the phone's USB-C port is used for charging, `WledNetworkTransport` binds `OkHttpClient` and UDP port `11988` directly to the active `TRANSPORT_WIFI` network (`wlan0`).
   - **Blocks 5G/LTE Cellular Fallback**: When connected to `WLED-AP` (`4.3.2.1`) or a bike Wi-Fi network without internet access, Android normally marks the Wi-Fi network as unvalidated and routes traffic out over 5G/LTE cellular. Explicit `Network` socket binding prevents cellular fallback completely.
   - **DNS/mDNS Cache & Fast Retry**: Caches resolved local IPs per hostname and performs an immediate 1-attempt retry on transient 2.4 GHz frame loss.
3. **Optional USB-C Serial (`115200` Baud JSON-over-Serial)**:
   - If a WLED ESP32/ESP8266 microcontroller is connected directly via USB-C OTG (`CP210x`, `CH340`, `FTDI`, or native `CDC-ACM`), `WledNetworkTransport` also mirrors the [WLED JSON API](https://kno.wled.ge/interfaces/json-api/) payload (`{"on":true,"bri":...}\n`) over USB bulk transfer.

#### Physical Interface Discovery & Priority State Flow
```text
[1] Android ConnectivityManager + UsbManager
        │
        ▼
[2] Is "Lock WLED to Local Ethernet / Wi-Fi" enabled? (Default: ON)
        ├── NO  ──► WledLinkType.SYSTEM_DEFAULT (Standard OS Routing)
        │
        └── YES
             │
             ▼
[3] Priority 1: Is USB-C Ethernet plugged in? (TRANSPORT_ETHERNET)
        ├── YES ──► WledLinkType.ETHERNET_WIRED
        │           • Binds sockets to eth0 / usb0
        │           • UI Badge: "ETHERNET"
        │
        └── NO
             │
             ▼
[4] Priority 2: Is Wi-Fi connected? (TRANSPORT_WIFI)
        ├── YES ──► WledLinkType.WIFI_BOUND
        │           • Binds sockets to wlan0 (Blocks 5G/LTE fallback)
        │           • UI Badge: "WI-FI LOCKED"
        │
        └── NO
             │
             ▼
[5] Priority 3: Is a WLED Microcontroller on USB-C OTG? (CP210x / CH340 / CDC)
        ├── YES ──► WledLinkType.USB_SERIAL_OTG
        │           • Streams JSON over 115200 baud bulk endpoint
        │           • UI Badge: "USB SERIAL"
        │
        └── NO  ──► WledLinkType.OFFLINE ("OFFLINE")
```

#### End-to-End Ride Readiness State Matrix

| Rider Scenario | Physical Link (`WledLinkType`) | Handshake (`ScreenWledStatus`) | Home Screen Readiness & Ride Behavior |
| :--- | :--- | :--- | :--- |
| **1. Charged Phone + USB-C Ethernet** | `ETHERNET_WIRED` (`eth0` / `usb0`) | `CHECKING` → `REACHABLE` (`~2–6 ms` RTT) | **PASS (`ETHERNET`)** — Start Ride unlocked; all brake/cruise looks and 50 Hz audio packets travel over the wired cable. |
| **2. Charging Phone + WLED-AP (`4.3.2.1`)** | `WIFI_BOUND` (`wlan0`) | `CHECKING` → `REACHABLE` (`~10–25 ms` RTT) | **PASS (`WI-FI LOCKED`)** — Start Ride unlocked; sockets stay locked to `wlan0` even though `WLED-AP` has no internet. |
| **3. WLED Controller Powered Off** | `ETHERNET_WIRED`, `WIFI_BOUND`, or `OFFLINE` | `CHECKING` → `UNREACHABLE` | **FAIL** — Start Ride locked (unless Log Only Mode is enabled); displays **"Re-check WLED"** button. |
| **4. Log Only Mode Toggled ON** | Any (`OFFLINE` allowed) | Bypassed (`LOG ONLY` badge) | **LOG ONLY** — Start Ride unlocked; records 50 Hz IMU, GPS, and Bosch Live Data V1.0 (`eb21`) without sending WLED network packets. |

### C. WLED Sink Rate-Limiting & UDP Throttling
- Because standard microcontrollers have limited socket buffers, `RideOrchestrator` implements an adaptive sink:
  - **State and Look Changes**: Dispatched immediately (< 50ms) to ensure instant brake light assertion.
  - **Continuous Brightness Ramps**: Rate-limited to max 1 packet per 250ms unless a significant step jump (> 0.10) occurs.
  - **Graceful Error Handling**: Network unreachability exceptions are logged at debug level (`Log.d`) to avoid logcat flooding when the bike is powered down or off-network.

---

## 2. Audio-Reactive UDP Streaming (Port 11988)

Pretty Nights includes an integrated music-reactive subsystem running inside `MusicService` that conforms to the [WLED Audio-Reactive UDP Sound Sync V2 Specification](https://kno.wled.ge/advanced/audio-reactive/):
- **Audio Capture**: Records internal system audio via `MediaProjection` (`AudioPlaybackCaptureConfiguration`) or microphone fallback via `AudioRecord` at 44.1 kHz, 16-bit mono PCM.
- **Fast Fourier Transform (FFT)**: Computes a 1024-point Hann-windowed radix-2 FFT at 50 Hz (`20 ms` frame interval), mapping frequency energy into 16 logarithmically scaled GEQ frequency bands (`45 Hz` to `16 kHz`) with software Automatic Gain Control (AGC).
- **UDP Multicast / Unicast (`84-Byte V2 Packet`)**: Broadcasts Little-Endian `00002\0` packets to `239.0.0.1:11988` (or configurable port in Settings), driving sound-reactive WLED patterns like *Dancing Shadows*, *Freqwave*, and *Gravimeter*:

| Byte Offset | Type | Field | Description |
| :--- | :--- | :--- | :--- |
| `0..5` | `char[6]` | `header` | `"00002\u0000"` (`0x30, 0x30, 0x30, 0x30, 0x32, 0x00`) |
| `6..7` | `uint8[2]` | `padding` | Alignment padding (`0x00, 0x00`) |
| `8..11` | `float32` | `sampleRaw` | Post-AGC scaled raw audio amplitude (`0..255`, Little-Endian) |
| `12..15` | `float32` | `sampleSmth` | Exponentially smoothed amplitude (`0..255`, Little-Endian) |
| `16` | `uint8` | `samplePeak` | Beat/peak flag (`1` = beat onset detected, `0` = normal) |
| `17` | `uint8` | `reserved` | Reserved byte (`0x00`) |
| `18..33` | `uint8[16]` | `fftResult[16]` | 16 logarithmic frequency bins (`0..255` per band) |
| `34..35` | `uint8[2]` | `padding` | Struct alignment padding (`0x00, 0x00`) |
| `36..39` | `float32` | `FFT_Magnitude` | Peak FFT bin magnitude (Little-Endian) |
| `40..43` | `float32` | `FFT_MajorPeak` | Dominant frequency in Hz (`20.0..20000.0`, Little-Endian) |
| `44..83` | `uint8[40]` | `reserved` | Zero-padded tail to complete the 84-byte V2 frame |

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
