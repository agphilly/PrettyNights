# Pretty Nights — 50 Hz Telemetry & State-Machine Workbench

A standalone, zero-dependency web application for inspecting exported **Pretty Nights** 50 Hz ride CSV logs (`prettynights_session_<id>.csv`), highlighting multi-track sensor signals, and tuning `RideStateMachine` parameters with instant **Before (Recorded) vs. After (Simulated)** state diffing.

---

## 1. How to Run or Host

### Option A: Open Locally in Any Desktop Browser
No build step or server is required. Simply open `/tools/telemetry-workbench/index.html` directly in Chrome, Firefox, Safari, or Edge.

### Option B: Host on GitHub Pages
1. In your GitHub repository, open **Settings → Pages** (or deploy via GitHub Actions).
2. Point the static site root to `/tools/telemetry-workbench` (or the repository root and navigate to `/tools/telemetry-workbench/`).
3. All CSV parsing and 50 Hz simulation runs 100% locally in browser memory—your ride logs are never uploaded to any external server.

---

## 2. Core Features

1. **Multi-Track High-DPI Oscilloscope (`<canvas>`)**:
   - **Track 1 — BEFORE (Recorded State)**: Color-coded state ribbon (`IDLE`, `CRUISING`, `BRAKING`, `BRAKING_HOLD`) recorded during the physical bike ride.
   - **Track 2 — AFTER (Simulated State)**: Live-recomputed state ribbon evaluated by an exact 1:1 port of `RideStateMachine.kt` in under 5 ms whenever you drag a slider.
   - **Track 3 — DIFF (`Before ≠ After`)**: Violet highlight band marking exact 20 ms ticks where your tuned configuration diverges from the recorded ride.
   - **Track 4 — Acceleration & Thresholds (`m/s²`)**: Plots `fused_accel_ms2`, optional raw `accel_x/y/z`, the live `brakingDecelThreshold` line, and shaded yellow/cyan windows showing active `brakingDebounceMs` and `brakingCooldownMs` timers.
   - **Track 5 — Speed (`km/h`), Bosch `eb21` & Brightness (`0–100%`)**: Overlays active speed, Bosch `eb21` wheel speed, 1 Hz GPS speed, the `bosch_age_ms` sawtooth, cadence/power, and Recorded vs. Simulated WLED brightness curves.
2. **Jump-to-Event & Diff Bookmarks**:
   - Click any chip in the **Jump to Tricky Moment** bar (`ROAD_BUMP`, `LIGHT_BRAKE`, `HARD_BRAKE`, or computed `Diff #1`, `Diff #2`...) to automatically zoom the graph to a focused window around that event.
3. **Built-In 50 Hz Reference Ride**:
   - Starts pre-loaded with a 30-second (`1,500`-tick) 50 Hz reference ride containing 4 classic field tuning scenarios:
     1. **`t = 7.40s` (`ROAD_BUMP`)**: Sharp expansion joint (`100 ms` `-3.35 m/s²` spike + `17.45 m/s²` vertical `accel_z` jolt) rejected by `brakingDebounceMs = 180 ms`.
     2. **`t = 11.50s` (`LIGHT_BRAKE`)**: Mild crosswalk check-brake (`-1.35 m/s²` for `650 ms`)—try raising `brakingDecelThreshold` from `-1.5` to `-1.2 m/s²` to watch the `AFTER` ribbon flip to `BRAKING` and spawn a purple Diff badge.
     3. **`t = 16.00s` (`HARD_BRAKE`)**: Downhill brake-pumping with a `220 ms` lever release—held solid by `brakingCooldownMs = 350 ms`.
     4. **`t = 21.00s` (`HARD_BRAKE` to Stop)**: Emergency stop to `0.0 km/h` illustrating 1 Hz GPS receiver lag (`~800 ms` behind Bosch `eb21` wheel speed) followed by `BRAKING_HOLD` and fade to `IDLE`.

---

## 3. Round-Tripping Tuned Parameters to the Android App

1. Click **Sync Config (JSON / Kotlin)** in the top-right bar of the Web Workbench.
2. Copy the **StateMachineConfig JSON** (or download `prettynights_statemachine_config.json`).
3. In the Pretty Nights Android app, open **Settings → Developer / Tuning** and locate the **Web Workbench Config Sync (JSON)** card.
4. Tap **Import / Paste JSON**, paste the JSON from the Web Workbench, and tap **Apply Tuned Config** to persist the new parameters directly into Jetpack DataStore.
