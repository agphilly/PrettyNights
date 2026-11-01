/**
 * Pretty Nights — 50 Hz Telemetry & State-Machine Workbench
 * Exact 1:1 port of RideStateMachine.kt + Multi-Section CSV Parser + High-DPI Oscilloscope
 */

const DEFAULT_CONFIG = {
  globalMinBrightness: 0.20,
  globalMaxBrightness: 0.80,
  brakingIgnoresGlobalMax: true,

  idleEnterSpeedKmh: 2.0,
  idleExitSpeedKmh: 4.0,

  idleEntryFadeS: 0.6,
  idleBrightness: 0.15,
  requireBoschIdleConfirm: false,

  cruisingEntryFadeS: 0.5,
  cruisingBrightness: 0.55,

  brakingEntryFadeS: 0.0,
  brakingDecelThreshold: -1.5,
  brakingDebounceMs: 180,
  brakingCooldownMs: 350,
  brakingMinBrightness: 0.60,
  brakingMaxBrightness: 1.00,
  brakingGamma: 1.6,

  brakingHoldDurationS: 2.0,
  brakingFadeDurationS: 1.0,

  useBoschCorroboration: false
};

const PARAMETER_GROUPS = [
  {
    title: "1. Global Brightness Envelope",
    controls: [
      { key: "globalMinBrightness", type: "range", min: 0.0, max: 1.0, step: 0.01, unit: "", desc: "Output floor; all looks map into [min, max]" },
      { key: "globalMaxBrightness", type: "range", min: 0.0, max: 1.0, step: 0.01, unit: "", desc: "Ambient-state ceiling (idle/cruising/hold-fade)" },
      { key: "brakingIgnoresGlobalMax", type: "boolean", desc: "If true, active braking maps to 1.0 instead of ceiling" }
    ]
  },
  {
    title: "2. Transitions (Shared Speeds)",
    controls: [
      { key: "idleEnterSpeedKmh", type: "range", min: 0.0, max: 20.0, step: 0.5, unit: " km/h", desc: "Below this speed, eligible for IDLE / BRAKING_HOLD" },
      { key: "idleExitSpeedKmh", type: "range", min: 0.0, max: 20.0, step: 0.5, unit: " km/h", desc: "Exceeding this exits IDLE or cancels BRAKING_HOLD" }
    ]
  },
  {
    title: "3. Idle State",
    controls: [
      { key: "idleEntryFadeS", type: "range", min: 0.0, max: 5.0, step: 0.1, unit: " s", desc: "Fade duration when entering IDLE" },
      { key: "idleBrightness", type: "range", min: 0.0, max: 1.0, step: 0.01, unit: "", desc: "Static IDLE brightness (pre-envelope)" },
      { key: "requireBoschIdleConfirm", type: "boolean", desc: "Defer IDLE entry if Bosch says bike is still driving" }
    ]
  },
  {
    title: "4. Cruising State",
    controls: [
      { key: "cruisingEntryFadeS", type: "range", min: 0.0, max: 5.0, step: 0.1, unit: " s", desc: "Fade duration when entering CRUISING" },
      { key: "cruisingBrightness", type: "range", min: 0.0, max: 1.0, step: 0.01, unit: "", desc: "Static CRUISING brightness (pre-envelope)" }
    ]
  },
  {
    title: "5. Braking Detection & Ramp",
    controls: [
      { key: "brakingDecelThreshold", type: "range", min: -8.0, max: -0.2, step: 0.1, unit: " m/s²", desc: "Deceleration threshold (more negative = harder brake)" },
      { key: "brakingDebounceMs", type: "range", min: 0, max: 1000, step: 10, unit: " ms", desc: "Decel must persist this long before BRAKING asserts (rejects potholes)" },
      { key: "brakingCooldownMs", type: "range", min: 0, max: 2000, step: 10, unit: " ms", desc: "Stays in BRAKING through brief easings shorter than this" },
      { key: "brakingEntryFadeS", type: "range", min: 0.0, max: 3.0, step: 0.1, unit: " s", desc: "Fade when entering BRAKING (0 = instant)" },
      { key: "brakingMinBrightness", type: "range", min: 0.0, max: 1.0, step: 0.01, unit: "", desc: "Brightness at brake onset" },
      { key: "brakingMaxBrightness", type: "range", min: 0.0, max: 1.0, step: 0.01, unit: "", desc: "Brightness as speed approaches zero" },
      { key: "brakingGamma", type: "range", min: 0.2, max: 4.0, step: 0.1, unit: "", desc: "Curve shape of speed-drop ramp (>1 pushes intensity near stop)" }
    ]
  },
  {
    title: "6. Braking Hold & Fade",
    controls: [
      { key: "brakingHoldDurationS", type: "range", min: 0.0, max: 10.0, step: 0.1, unit: " s", desc: "Hold stop brightness this long before fading" },
      { key: "brakingFadeDurationS", type: "range", min: 0.0, max: 10.0, step: 0.1, unit: " s", desc: "Linear fade duration from held brightness to IDLE" }
    ]
  },
  {
    title: "7. Bosch Corroboration",
    controls: [
      { key: "useBoschCorroboration", type: "boolean", desc: "Master switch for Bosch Live Data soft confirmation" }
    ]
  }
];

const STATE_COLORS = {
  IDLE: "#f59e0b",
  CRUISING: "#00b4ff",
  BRAKING: "#ef4444",
  BRAKING_HOLD: "#f97316"
};

// Application State
let activeConfig = { ...DEFAULT_CONFIG };
let replaySpeedSource = "RECORDED";
let imuAxisOverride = "RECORDED";
let imuSignOverride = "RECORDED";

let currentSession = {
  rawCsvText: "",
  metadata: {},
  markers: [],
  boschPackets: [],
  samples: []
};

let simulationResult = {
  ticks: [],
  diffEpisodes: [],
  recordedBrakeEpisodes: 0,
  simulatedBrakeEpisodes: 0,
  recordedShare: {},
  simulatedShare: {},
  brakeMarkersTotal: 0,
  brakeMarkersHit: 0,
  bumpMarkersTotal: 0,
  bumpMarkersRejected: 0,
  totalDiffTicks: 0,
  elapsedRuntimeMs: 0
};

let activeSignals = {
  fusedAccel: true,
  rawAccelXYZ: false,
  thresholds: true,
  activeSpeed: true,
  boschSpeed: true,
  gpsSpeed: true,
  brightness: true,
  boschAge: false,
  cadencePower: false
};

// Viewport state in elapsed_ms
let viewStartMs = 0;
let viewEndMs = 30000;
let hoverSampleIndex = null;
let isDraggingPan = false;
let dragStartX = 0;
let dragStartViewStart = 0;
let dragStartViewEnd = 0;
let activeModalTab = "json";

/**
 * Exact 1:1 port of Kotlin RideStateMachine (RideStateMachine.kt)
 */
class JsRideStateMachine {
  constructor(cfg) {
    this.cfg = cfg;
    this.currentState = "IDLE";
    this.lastStateChangeMs = 0;
    this.decelStartMs = null;
    this.easedOffMs = null;
    this.entrySpeedKmh = null;
    this.entryFadeStartBrightness = cfg.idleBrightness;
    this.lastCleanBrightness = cfg.idleBrightness;
    this.holdStartBrightness = 0.8;
    this.lastOutputBrightness = cfg.globalMinBrightness + (cfg.globalMaxBrightness - cfg.globalMinBrightness) * cfg.idleBrightness;
  }

  reset(initialTimeMs) {
    const cfg = this.cfg;
    this.currentState = "IDLE";
    this.lastStateChangeMs = initialTimeMs;
    this.decelStartMs = null;
    this.easedOffMs = null;
    this.entrySpeedKmh = null;
    this.lastCleanBrightness = cfg.idleBrightness;
    this.entryFadeStartBrightness = cfg.idleBrightness;
    this.lastOutputBrightness = cfg.globalMinBrightness + (cfg.globalMaxBrightness - cfg.globalMinBrightness) * cfg.idleBrightness;
  }

  tick(sample) {
    const cfg = this.cfg;
    const now = sample.timestampMs;

    // 1. Deceleration Debounce & Cooldown Logic
    const isDecelPassingThreshold = sample.longitudinalAccel <= cfg.brakingDecelThreshold;

    if (isDecelPassingThreshold) {
      if (this.decelStartMs === null) {
        this.decelStartMs = now;
      }
      this.easedOffMs = null;
    } else {
      const wasAlreadyDebounced = this.decelStartMs !== null && (now - this.decelStartMs >= cfg.brakingDebounceMs);
      if (!wasAlreadyDebounced && this.currentState !== "BRAKING") {
        // Sub-debounce spike (e.g. pothole/road bump) ended before debounce elapsed: reset immediately
        this.decelStartMs = null;
        this.easedOffMs = null;
      } else {
        if (this.easedOffMs === null) {
          this.easedOffMs = now;
        }
        if (now - this.easedOffMs >= cfg.brakingCooldownMs) {
          this.decelStartMs = null;
          this.easedOffMs = null;
        }
      }
    }

    const decelDebounceElapsedMs = this.decelStartMs !== null ? Math.max(0, now - this.decelStartMs) : 0;
    const cooldownElapsedMs = this.easedOffMs !== null ? Math.max(0, now - this.easedOffMs) : 0;
    const isDecelAsserted = this.decelStartMs !== null && (now - this.decelStartMs >= cfg.brakingDebounceMs);

    // 2. State Machine Transitions
    let nextState = this.currentState;

    switch (this.currentState) {
      case "IDLE":
        if (sample.speedKmh > cfg.idleExitSpeedKmh) {
          nextState = "CRUISING";
        }
        break;

      case "CRUISING":
        if (isDecelAsserted) {
          nextState = "BRAKING";
        } else {
          const speedQualifies = sample.speedKmh < cfg.idleEnterSpeedKmh;
          const boschSaysNotDriving = (cfg.useBoschCorroboration && cfg.requireBoschIdleConfirm)
            ? (sample.bikeNotDriving !== null && sample.bikeNotDriving !== undefined ? sample.bikeNotDriving : true)
            : true;
          if (speedQualifies && boschSaysNotDriving) {
            nextState = "IDLE";
          }
        }
        break;

      case "BRAKING":
        if (sample.speedKmh < cfg.idleEnterSpeedKmh) {
          nextState = "BRAKING_HOLD";
        } else if (!isDecelAsserted) {
          nextState = "CRUISING";
        }
        break;

      case "BRAKING_HOLD": {
        const durationInHold = now - this.lastStateChangeMs;
        const holdDurationMs = cfg.brakingHoldDurationS * 1000;
        const fadeDurationMs = cfg.brakingFadeDurationS * 1000;

        if (sample.speedKmh > cfg.idleExitSpeedKmh) {
          nextState = "CRUISING";
        } else if (durationInHold >= (holdDurationMs + fadeDurationMs)) {
          nextState = "IDLE";
        }
        break;
      }
    }

    // 3. Handle Transitions and Capture Entry states
    if (nextState !== this.currentState) {
      this.entryFadeStartBrightness = this.lastCleanBrightness;

      if (nextState === "BRAKING" && this.currentState !== "BRAKING" && this.currentState !== "BRAKING_HOLD") {
        this.entrySpeedKmh = sample.speedKmh;
      } else if (nextState === "CRUISING" || nextState === "IDLE") {
        this.entrySpeedKmh = null;
      }

      if (nextState === "BRAKING_HOLD") {
        this.holdStartBrightness = this.lastCleanBrightness;
      }

      this.currentState = nextState;
      this.lastStateChangeMs = now;
    }

    // 4. State-specific Live Targets Calculation (clean 0-1 space)
    const durationInCurrentState = now - this.lastStateChangeMs;
    let liveTarget = cfg.idleBrightness;

    if (this.currentState === "IDLE") {
      liveTarget = cfg.idleBrightness;
    } else if (this.currentState === "CRUISING") {
      liveTarget = cfg.cruisingBrightness;
    } else if (this.currentState === "BRAKING") {
      const refSpeed = this.entrySpeedKmh !== null ? this.entrySpeedKmh : 1.0;
      const denom = refSpeed > 0 ? refSpeed : 1.0;
      const ratio = Math.min(1.0, Math.max(0.0, 1.0 - (sample.speedKmh / denom)));
      const powerRatio = Math.pow(ratio, cfg.brakingGamma);
      liveTarget = cfg.brakingMinBrightness + (cfg.brakingMaxBrightness - cfg.brakingMinBrightness) * powerRatio;
    } else if (this.currentState === "BRAKING_HOLD") {
      const holdLimitMs = cfg.brakingHoldDurationS * 1000;
      if (durationInCurrentState <= holdLimitMs) {
        liveTarget = this.holdStartBrightness;
      } else {
        const fadeElapsed = durationInCurrentState - holdLimitMs;
        const fadeTotalMs = cfg.brakingFadeDurationS * 1000;
        const progress = Math.min(1.0, Math.max(0.0, fadeElapsed / (fadeTotalMs > 0 ? fadeTotalMs : 1.0)));
        liveTarget = this.holdStartBrightness + progress * (cfg.idleBrightness - this.holdStartBrightness);
      }
    }

    // 5. Generic Entry Fade Calculation
    let entryFadeMs = 0;
    if (this.currentState === "IDLE") entryFadeMs = cfg.idleEntryFadeS * 1000;
    else if (this.currentState === "CRUISING") entryFadeMs = cfg.cruisingEntryFadeS * 1000;
    else if (this.currentState === "BRAKING") entryFadeMs = cfg.brakingEntryFadeS * 1000;
    else if (this.currentState === "BRAKING_HOLD") entryFadeMs = 0;

    let cleanBrightness = liveTarget;
    if (entryFadeMs > 0 && durationInCurrentState < entryFadeMs) {
      const progress = Math.min(1.0, Math.max(0.0, durationInCurrentState / entryFadeMs));
      cleanBrightness = this.entryFadeStartBrightness + progress * (liveTarget - this.entryFadeStartBrightness);
    }

    this.lastCleanBrightness = cleanBrightness;

    // 6. Global Brightness Envelope Mapping
    const floor = cfg.globalMinBrightness;
    const ceiling = (this.currentState === "BRAKING" && cfg.brakingIgnoresGlobalMax)
      ? 1.0
      : cfg.globalMaxBrightness;

    const finalBrightness = Math.min(1.0, Math.max(0.0, floor + (ceiling - floor) * cleanBrightness));
    this.lastOutputBrightness = finalBrightness;

    return {
      state: this.currentState,
      brightness: finalBrightness,
      cleanBrightness,
      decelPassing: isDecelPassingThreshold,
      decelAsserted: isDecelAsserted,
      decelDebounceElapsedMs,
      cooldownElapsedMs
    };
  }
}

/**
 * Parses the 4-section Pretty Nights CSV export (or any 50 Hz CSV log)
 */
function parsePrettyNightsCsv(csvText) {
  const lines = csvText.replace(/\r\n/g, "\n").split("\n");
  const metadata = {};
  const markers = [];
  const boschPackets = [];
  const samples = [];

  let currentSection = "HEADER";
  let sectionHeaders = [];

  for (let rawLine of lines) {
    const line = rawLine.trim();
    if (!line) continue;

    if (line.startsWith("#")) {
      if (line.includes("IN_RIDE_EVENT_MARKERS")) {
        currentSection = "MARKERS";
        sectionHeaders = [];
      } else if (line.includes("BOSCH_LIVE_DATA_V1_NOTIFICATIONS")) {
        currentSection = "BOSCH";
        sectionHeaders = [];
      } else if (line.includes("UNIFIED_50HZ_RIDE_TELEMETRY")) {
        currentSection = "TELEMETRY";
        sectionHeaders = [];
      } else {
        // Parse "# key,value" metadata lines
        const cleanComment = line.replace(/^#\s*/, "");
        const commaIdx = cleanComment.indexOf(",");
        if (commaIdx > 0) {
          const k = cleanComment.slice(0, commaIdx).trim();
          const v = cleanComment.slice(commaIdx + 1).trim().replace(/^"|"$/g, "");
          metadata[k] = v;
        }
      }
      continue;
    }

    const cols = splitCsvRow(line);

    // Auto-detect section header row
    if (cols[0] === "marker_id") {
      currentSection = "MARKERS";
      sectionHeaders = cols;
      continue;
    } else if (cols[0] === "packet_id") {
      currentSection = "BOSCH";
      sectionHeaders = cols;
      continue;
    } else if (cols[0] === "sample_id") {
      currentSection = "TELEMETRY";
      sectionHeaders = cols;
      continue;
    }

    if (currentSection === "MARKERS" && sectionHeaders.length > 0) {
      const row = mapRow(sectionHeaders, cols);
      markers.push({
        id: Number(row.marker_id || markers.length + 1),
        timestampMs: Number(row.timestamp_monotonic_ms || 0),
        elapsedMs: Number(row.elapsed_ms || 0),
        markerType: row.marker_type || "NOTE",
        note: (row.note || "").replace(/^"|"$/g, "")
      });
    } else if (currentSection === "BOSCH" && sectionHeaders.length > 0) {
      const row = mapRow(sectionHeaders, cols);
      boschPackets.push({
        packetId: Number(row.packet_id || boschPackets.length + 1),
        timestampMs: Number(row.timestamp_monotonic_ms || 0),
        elapsedMs: Number(row.elapsed_ms || 0),
        negotiatedMtu: Number(row.negotiated_mtu || 247),
        presentFieldsMask: row.present_fields_mask || "",
        speedKmh: row.speed_kmh !== "" && row.speed_kmh !== undefined ? Number(row.speed_kmh) : null,
        cadenceRpm: row.cadence_rpm !== "" && row.cadence_rpm !== undefined ? Number(row.cadence_rpm) : null,
        riderPowerW: row.rider_power_w !== "" && row.rider_power_w !== undefined ? Number(row.rider_power_w) : null,
        batterySoc: row.battery_soc_pct !== "" && row.battery_soc_pct !== undefined ? Number(row.battery_soc_pct) : null,
        bikeNotDriving: parseOptionalBool(row.bike_not_driving)
      });
    } else if (currentSection === "TELEMETRY" && sectionHeaders.length > 0) {
      const row = mapRow(sectionHeaders, cols);
      const speedKmh = row.speed_kmh !== undefined && row.speed_kmh !== ""
        ? Number(row.speed_kmh)
        : (row.bosch_speed_mps !== undefined && row.bosch_speed_mps !== "" ? Number(row.bosch_speed_mps) * 3.6 : 0);

      const gpsSpeedKmh = row.gps_speed_kmh !== undefined && row.gps_speed_kmh !== ""
        ? Number(row.gps_speed_kmh)
        : (row.gps_speed_mps !== undefined && row.gps_speed_mps !== "" ? Number(row.gps_speed_mps) * 3.6 : speedKmh);

      const boschSpeedKmh = row.bosch_speed_kmh !== undefined && row.bosch_speed_kmh !== ""
        ? Number(row.bosch_speed_kmh)
        : (row.bosch_speed_mps !== undefined && row.bosch_speed_mps !== "" ? Number(row.bosch_speed_mps) * 3.6 : null);

      const fusedAccel = row.fused_accel_ms2 !== undefined && row.fused_accel_ms2 !== ""
        ? Number(row.fused_accel_ms2)
        : (row.smoothed_accel_x !== undefined && row.smoothed_accel_x !== "" ? Number(row.smoothed_accel_x) : Number(row.accel_x || 0));

      let recState = (row.state_name || row.ride_state || "IDLE").toUpperCase();
      if (recState === "MOVING") recState = "CRUISING";
      if (recState === "HARD_BRAKE") recState = "BRAKING";

      const recBrightness = row.wled_brightness !== undefined && row.wled_brightness !== ""
        ? Number(row.wled_brightness) / 255.0
        : null;

      samples.push({
        sampleId: Number(row.sample_id || samples.length + 1),
        timestampMs: Number(row.timestamp_monotonic_ms || row.elapsed_ms || samples.length * 20),
        elapsedMs: Number(row.elapsed_ms !== undefined ? row.elapsed_ms : samples.length * 20),
        recordedState: recState,
        recordedBrightness: recBrightness,
        activeSpeedSource: row.active_speed_source || "BOSCH",
        speedKmh,
        gpsSpeedKmh,
        boschSpeedKmh,
        boschAgeMs: Number(row.bosch_age_ms !== undefined && row.bosch_age_ms !== "" ? row.bosch_age_ms : 0),
        boschBikeNotDriving: parseOptionalBool(row.bosch_bike_not_driving),
        boschCadenceRpm: row.bosch_cadence_rpm !== "" && row.bosch_cadence_rpm !== undefined ? Number(row.bosch_cadence_rpm) : null,
        boschRiderPowerW: row.bosch_rider_power_w !== "" && row.bosch_rider_power_w !== undefined ? Number(row.bosch_rider_power_w) : null,
        boschAmbientLux: row.bosch_ambient_lux !== "" && row.bosch_ambient_lux !== undefined ? Number(row.bosch_ambient_lux) : null,
        boschBatterySoc: row.bosch_battery_soc !== "" && row.bosch_battery_soc !== undefined ? Number(row.bosch_battery_soc) : null,
        fusedAccel,
        accelX: Number(row.accel_x || 0),
        accelY: Number(row.accel_y || 0),
        accelZ: Number(row.accel_z || 9.81),
        axis: row.axis || "Y",
        sign: Number(row.sign || -1),
        markerAtTick: (row.marker_at_tick || row.voice_marker || "").trim()
      });
    }
  }

  // Synthesize markers from markerAtTick if Section 2 was omitted
  if (markers.length === 0 && samples.length > 0) {
    let lastMarkerType = "";
    for (const s of samples) {
      if (s.markerAtTick && s.markerAtTick !== lastMarkerType) {
        markers.push({
          id: markers.length + 1,
          timestampMs: s.timestampMs,
          elapsedMs: s.elapsedMs,
          markerType: s.markerAtTick,
          note: `Stamped at ${ (s.elapsedMs / 1000).toFixed(2) }s`
        });
      }
      lastMarkerType = s.markerAtTick;
    }
  }

  return { rawCsvText: csvText, metadata, markers, boschPackets, samples };
}

function splitCsvRow(line) {
  const result = [];
  let cur = "";
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      inQuotes = !inQuotes;
    } else if (ch === "," && !inQuotes) {
      result.push(cur);
      cur = "";
    } else {
      cur += ch;
    }
  }
  result.push(cur);
  return result;
}

function mapRow(headers, cols) {
  const obj = {};
  for (let i = 0; i < headers.length; i++) {
    obj[headers[i]] = cols[i] !== undefined ? cols[i] : "";
  }
  return obj;
}

function parseOptionalBool(val) {
  if (val === undefined || val === null || val === "") return null;
  const v = String(val).toLowerCase();
  if (v === "true" || v === "1") return true;
  if (v === "false" || v === "0") return false;
  return null;
}

/**
 * Generates a realistic 30-second (1,500 consecutive 20 ms ticks) 50 Hz ride CSV
 * containing 4 classic tricky situations:
 *  1) t = 7.40s: Sharp Pothole / Expansion Joint (ROAD_BUMP) — 100ms decel spike (-3.3 m/s²)
 *  2) t = 11.50s: Mild Check-Brake (LIGHT_BRAKE) — 650ms decel at -1.35 m/s² (just shy of -1.5 default)
 *  3) t = 16.00s: Brake-Pumping Downhill (HARD_BRAKE) — 220ms mid-brake release testing brakingCooldownMs
 *  4) t = 21.00s: Emergency Stop to 0 km/h — Bosch eb21 vs 1Hz GPS lag + BRAKING_HOLD -> IDLE
 */
function generateTrickySampleRideCsv() {
  const startMonotonic = 900000;
  const totalTicks = 1500; // 30.00 seconds at 50 Hz (20 ms)
  const lines = [];

  lines.push("# PRETTY NIGHTS TELEMETRY & BOSCH LIVE DATA V1.0 EXPORT");
  lines.push("# session_id,42");
  lines.push('# title,"Night Urban Commute — Pothole, Check-Brake, Brake-Pump & Hard Stop"');
  lines.push('# notes,"Built-in 50 Hz reference ride for testing state-machine debounce, cooldown, threshold sensitivity, and Bosch vs GPS lag."');
  lines.push("# mode,WLED_ACTIVE");
  lines.push("# configured_speed_source,BOSCH_PREFERRED");
  lines.push("# start_wall_ms,1730500000000");
  lines.push(`# start_monotonic_ms,${startMonotonic}`);
  lines.push(`# end_monotonic_ms,${startMonotonic + totalTicks * 20}`);
  lines.push("# bosch_eb21_notifications,60");
  lines.push("# bosch_decode_success_pct,100.00");
  lines.push("# bosch_min_mtu,247");
  lines.push("# bosch_freshness_uptime_pct,100.00");
  lines.push("# bosch_stale_fallback_events,0");
  lines.push("# bosch_mean_speed_delta_kmh,1.18");
  lines.push("");

  // Section 2: In-Ride Event Markers
  lines.push("# IN_RIDE_EVENT_MARKERS");
  lines.push("marker_id,timestamp_monotonic_ms,elapsed_ms,marker_type,note");
  const sampleMarkers = [
    { id: 1, elapsed: 7400, type: "ROAD_BUMP", note: "Sharp steel expansion joint (100ms -3.35 m/s² spike)" },
    { id: 2, elapsed: 11500, type: "LIGHT_BRAKE", note: "Feathering brakes for crosswalk (-1.35 m/s² for 650ms)" },
    { id: 3, elapsed: 16000, type: "HARD_BRAKE", note: "Modulated downhill brake with 220ms release pump" },
    { id: 4, elapsed: 21200, type: "HARD_BRAKE", note: "Full emergency stop to 0 km/h (GPS lags Bosch by 800ms)" }
  ];
  for (const m of sampleMarkers) {
    lines.push(`${m.id},${startMonotonic + m.elapsed},${m.elapsed},${m.type},"${m.note}"`);
  }
  lines.push("");

  // Simulate true kinematics + default state machine for recorded column
  const recMachine = new JsRideStateMachine({ ...DEFAULT_CONFIG });
  recMachine.reset(startMonotonic);

  const boschRows = [];
  const telemetryRows = [];

  let trueSpeedKmh = 0.0;
  let lastBoschSpeedKmh = 0.0;
  let lastBoschUpdateMs = startMonotonic;
  let lastGpsSpeedKmh = 0.0;
  let lastGpsUpdateMs = startMonotonic;
  let cadenceRpm = 0;
  let riderPowerW = 0;
  let bikeNotDriving = true;
  let packetId = 1;

  for (let i = 0; i < totalTicks; i++) {
    const elapsedMs = i * 20;
    const ts = startMonotonic + elapsedMs;
    const tSec = elapsedMs / 1000.0;

    // Deterministic high-frequency asphalt micro-vibration
    const vibX = 0.14 * Math.sin(i * 0.85) + 0.08 * Math.cos(i * 1.43);
    const vibY = 0.12 * Math.cos(i * 0.62);
    let vibZ = 9.81 + 0.35 * Math.sin(i * 1.15);

    let trueLongitudinalAccel = 0.0;

    if (tSec < 2.2) {
      // Stationary IDLE
      trueLongitudinalAccel = 0.02 * Math.sin(i * 0.3);
      trueSpeedKmh = 0.0;
      cadenceRpm = 0;
      riderPowerW = 0;
    } else if (tSec < 6.2) {
      // Launch and accelerate to 25.2 km/h
      trueLongitudinalAccel = 1.75;
      trueSpeedKmh = Math.min(25.2, trueSpeedKmh + (trueLongitudinalAccel * 0.02) * 3.6);
      cadenceRpm = 76;
      riderPowerW = 215;
    } else if (tSec >= 7.38 && tSec <= 7.48) {
      // Tricky Situation #1: Sharp Pothole / Expansion Joint (only 100ms wide!)
      trueLongitudinalAccel = -3.35;
      vibZ = (i % 2 === 0) ? 17.45 : 3.15;
      cadenceRpm = 78;
      riderPowerW = 185;
    } else if (tSec >= 11.30 && tSec <= 11.95) {
      // Tricky Situation #2: Light Check-Brake (-1.35 m/s² for 650ms — just above default -1.5 threshold)
      trueLongitudinalAccel = -1.35;
      trueSpeedKmh = Math.max(18.0, trueSpeedKmh + (trueLongitudinalAccel * 0.02) * 3.6);
      cadenceRpm = 0;
      riderPowerW = 0;
    } else if (tSec > 11.95 && tSec < 14.5) {
      // Re-accelerate back to 26.0 km/h
      trueLongitudinalAccel = 1.15;
      trueSpeedKmh = Math.min(26.0, trueSpeedKmh + (trueLongitudinalAccel * 0.02) * 3.6);
      cadenceRpm = 80;
      riderPowerW = 230;
    } else if (tSec >= 15.80 && tSec <= 17.40) {
      // Tricky Situation #3: Brake-Pumping (brief 220ms release between 16.36s and 16.58s)
      if (tSec >= 16.36 && tSec <= 16.58) {
        trueLongitudinalAccel = -0.45; // Rider briefly eases lever for 220ms
      } else {
        trueLongitudinalAccel = -2.65;
      }
      trueSpeedKmh = Math.max(12.5, trueSpeedKmh + (trueLongitudinalAccel * 0.02) * 3.6);
      cadenceRpm = 0;
      riderPowerW = 0;
    } else if (tSec > 17.40 && tSec < 20.40) {
      // Cruise at 22.8 km/h
      trueLongitudinalAccel = 0.95;
      trueSpeedKmh = Math.min(22.8, trueSpeedKmh + (trueLongitudinalAccel * 0.02) * 3.6);
      cadenceRpm = 74;
      riderPowerW = 175;
    } else if (tSec >= 21.00 && tSec <= 24.10) {
      // Tricky Situation #4: Hard Emergency Brake to complete 0.0 km/h stop
      if (trueSpeedKmh > 0.05) {
        trueLongitudinalAccel = -3.85;
        trueSpeedKmh = Math.max(0.0, trueSpeedKmh + (trueLongitudinalAccel * 0.02) * 3.6);
      } else {
        // Fork rebound settle at stop
        trueLongitudinalAccel = (tSec < 23.6) ? 0.42 : 0.0;
        trueSpeedKmh = 0.0;
      }
      cadenceRpm = 0;
      riderPowerW = 0;
    } else {
      // Stationary hold and idle after 24.10s
      trueLongitudinalAccel = 0.01 * Math.cos(i * 0.4);
      trueSpeedKmh = 0.0;
      cadenceRpm = 0;
      riderPowerW = 0;
    }

    const fusedAccel = trueLongitudinalAccel + vibX * 0.35;
    // Raw sensor axis Y inverted (sign = -1 => accelY = -fusedAccel)
    const rawAccelY = -fusedAccel;
    const rawAccelX = vibY;
    const rawAccelZ = vibZ;

    // Bosch eb21 notification every 500 ms (25 ticks)
    if (i % 25 === 0) {
      lastBoschSpeedKmh = trueSpeedKmh;
      lastBoschUpdateMs = ts;
      bikeNotDriving = trueSpeedKmh < 0.3;
      const raw100th = Math.round(lastBoschSpeedKmh * 100);
      boschRows.push(
        `${packetId},${ts},${elapsedMs},247,36,true,0x10023FF,${raw100th},${lastBoschSpeedKmh.toFixed(2)},` +
        `${cadenceRpm},${riderPowerW},14200,14.200,84,${1730500000 + Math.floor(tSec)},14285,1,false,false,false,false,${bikeNotDriving},${lastGpsSpeedKmh.toFixed(2)}`
      );
      packetId++;
    }

    // GPS 1 Hz update every 1000 ms (50 ticks) with realistic 750ms receiver filter lag
    if (i % 50 === 0) {
      const delayedSpeed = i >= 35 ? Number(telemetryRows[i - 35].split(",")[7]) : 0.0;
      lastGpsSpeedKmh = (trueSpeedKmh * 0.35) + (delayedSpeed * 0.65);
      if (tSec > 25.2) lastGpsSpeedKmh = 0.0;
      lastGpsUpdateMs = ts;
    }

    const boschAgeMs = ts - lastBoschUpdateMs;
    const recOut = recMachine.tick({
      timestampMs: ts,
      longitudinalAccel: fusedAccel,
      speedKmh: lastBoschSpeedKmh,
      bikeNotDriving
    });

    const nearMarker = sampleMarkers.find(m => Math.abs(m.elapsed - elapsedMs) <= 20)?.type || "";

    telemetryRows.push(
      `${i + 1},${ts},${elapsedMs},${recOut.state},BOSCH,${lastBoschSpeedKmh.toFixed(2)},${lastGpsSpeedKmh.toFixed(2)},` +
      `${lastBoschSpeedKmh.toFixed(2)},${boschAgeMs},${bikeNotDriving},${cadenceRpm},${riderPowerW},` +
      `14.200,84,1,${fusedAccel.toFixed(3)},${rawAccelX.toFixed(3)},${rawAccelY.toFixed(3)},${rawAccelZ.toFixed(3)},Y,-1,${nearMarker}`
    );
  }

  // Section 3: Bosch Notifications
  lines.push("# BOSCH_LIVE_DATA_V1_NOTIFICATIONS (0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4)");
  lines.push(
    "packet_id,timestamp_monotonic_ms,elapsed_ms,negotiated_mtu,payload_bytes,decode_success,present_fields_mask," +
    "speed_raw_100th_kmh,speed_kmh,cadence_rpm,rider_power_w,ambient_millilux,ambient_lux," +
    "battery_soc_pct,ebike_time_utc_s,odometer_m,bike_light_code,system_locked,charger_connected," +
    "light_reserve_state,diagnosis_active,bike_not_driving,gps_speed_kmh_at_capture"
  );
  for (const r of boschRows) lines.push(r);
  lines.push("");

  // Section 4: 50 Hz Telemetry
  lines.push("# UNIFIED_50HZ_RIDE_TELEMETRY");
  lines.push(
    "sample_id,timestamp_monotonic_ms,elapsed_ms,state_name,active_speed_source,speed_kmh,gps_speed_kmh," +
    "bosch_speed_kmh,bosch_age_ms,bosch_bike_not_driving,bosch_cadence_rpm,bosch_rider_power_w," +
    "bosch_ambient_lux,bosch_battery_soc,bosch_bike_light,fused_accel_ms2,accel_x,accel_y,accel_z,axis,sign,marker_at_tick"
  );
  for (const r of telemetryRows) lines.push(r);

  return lines.join("\n");
}

/**
 * Re-evaluates the entire 50 Hz sample array through JsRideStateMachine
 */
function runSimulation() {
  const samples = currentSession.samples;
  if (!samples || samples.length === 0) return;

  const t0 = performance.now();
  const simMachine = new JsRideStateMachine(activeConfig);
  simMachine.reset(samples[0].timestampMs);

  const ticks = new Array(samples.length);
  const diffEpisodes = [];

  const recCounts = { IDLE: 0, CRUISING: 0, BRAKING: 0, BRAKING_HOLD: 0 };
  const simCounts = { IDLE: 0, CRUISING: 0, BRAKING: 0, BRAKING_HOLD: 0 };

  let recBrakeEps = 0;
  let prevRecState = null;
  let simBrakeEps = 0;
  let prevSimState = null;
  let totalDiffTicks = 0;

  let currentDiffEp = null;

  for (let i = 0; i < samples.length; i++) {
    const s = samples[i];
    const recState = s.recordedState;
    recCounts[recState] = (recCounts[recState] || 0) + 1;
    if (recState === "BRAKING" && prevRecState !== "BRAKING") {
      recBrakeEps++;
    }
    prevRecState = recState;

    // 1. Determine effective speed for this tick
    let effectiveSpeedKmh = s.speedKmh;
    if (replaySpeedSource === "BOSCH_PREFERRED") {
      effectiveSpeedKmh = (s.boschSpeedKmh !== null && s.boschAgeMs >= 0 && s.boschAgeMs < 4000)
        ? s.boschSpeedKmh
        : s.gpsSpeedKmh;
    } else if (replaySpeedSource === "BOSCH_ONLY") {
      effectiveSpeedKmh = s.boschSpeedKmh !== null ? s.boschSpeedKmh : 0.0;
    } else if (replaySpeedSource === "GPS_ONLY") {
      effectiveSpeedKmh = s.gpsSpeedKmh;
    }

    // 2. Determine effective longitudinal acceleration
    let effectiveAccel = s.fusedAccel;
    if (imuAxisOverride !== "RECORDED" || imuSignOverride !== "RECORDED") {
      const axis = imuAxisOverride === "RECORDED" ? s.axis : imuAxisOverride;
      const sign = imuSignOverride === "RECORDED" ? s.sign : Number(imuSignOverride);
      const rawVal = axis === "X" ? s.accelX : (axis === "Y" ? s.accelY : (s.accelZ - 9.81));
      effectiveAccel = rawVal * sign;
    }

    const out = simMachine.tick({
      timestampMs: s.timestampMs,
      longitudinalAccel: effectiveAccel,
      speedKmh: effectiveSpeedKmh,
      bikeNotDriving: s.boschBikeNotDriving
    });

    simCounts[out.state] = (simCounts[out.state] || 0) + 1;
    if (out.state === "BRAKING" && prevSimState !== "BRAKING") {
      simBrakeEps++;
    }
    prevSimState = out.state;

    const isDiff = out.state !== recState;
    if (isDiff) {
      totalDiffTicks++;
      if (!currentDiffEp) {
        currentDiffEp = {
          index: diffEpisodes.length + 1,
          startElapsedMs: s.elapsedMs,
          endElapsedMs: s.elapsedMs,
          recordedState: recState,
          simulatedState: out.state,
          ticksCount: 1
        };
      } else {
        currentDiffEp.endElapsedMs = s.elapsedMs;
        currentDiffEp.ticksCount++;
      }
    } else if (currentDiffEp) {
      diffEpisodes.push(currentDiffEp);
      currentDiffEp = null;
    }

    // Estimate recorded brightness if CSV didn't have an explicit column
    const recBright = s.recordedBrightness !== null
      ? s.recordedBrightness
      : estimateBrightnessForState(recState, DEFAULT_CONFIG);

    ticks[i] = {
      effectiveSpeedKmh,
      effectiveAccel,
      recordedBrightness: recBright,
      simulatedState: out.state,
      simulatedBrightness: out.brightness,
      cleanBrightness: out.cleanBrightness,
      decelPassing: out.decelPassing,
      decelAsserted: out.decelAsserted,
      decelDebounceElapsedMs: out.decelDebounceElapsedMs,
      cooldownElapsedMs: out.cooldownElapsedMs,
      isDiff
    };
  }

  if (currentDiffEp) {
    diffEpisodes.push(currentDiffEp);
  }

  // Evaluate marker accuracy (±750 ms window, matching TuningReplayCard.kt)
  const brakeMarkers = currentSession.markers.filter(m => m.markerType === "HARD_BRAKE" || m.markerType === "LIGHT_BRAKE");
  let brakeHit = 0;
  for (const bm of brakeMarkers) {
    const detected = samples.some((s, idx) =>
      Math.abs(s.timestampMs - bm.timestampMs) <= 750 &&
      (ticks[idx].simulatedState === "BRAKING" || ticks[idx].simulatedState === "BRAKING_HOLD")
    );
    if (detected) brakeHit++;
  }

  const bumpMarkers = currentSession.markers.filter(m => m.markerType === "ROAD_BUMP");
  let bumpRejected = 0;
  for (const rm of bumpMarkers) {
    const falseTrigger = samples.some((s, idx) => {
      const dt = s.timestampMs - rm.timestampMs;
      return dt >= -250 && dt <= 400 && ticks[idx].simulatedState === "BRAKING";
    });
    if (!falseTrigger) bumpRejected++;
  }

  const n = samples.length;
  const elapsedRuntimeMs = performance.now() - t0;

  simulationResult = {
    ticks,
    diffEpisodes,
    recordedBrakeEpisodes: recBrakeEps,
    simulatedBrakeEpisodes: simBrakeEps,
    recordedShare: {
      IDLE: (recCounts.IDLE * 100) / n,
      CRUISING: (recCounts.CRUISING * 100) / n,
      BRAKING: (recCounts.BRAKING * 100) / n,
      BRAKING_HOLD: (recCounts.BRAKING_HOLD * 100) / n
    },
    simulatedShare: {
      IDLE: (simCounts.IDLE * 100) / n,
      CRUISING: (simCounts.CRUISING * 100) / n,
      BRAKING: (simCounts.BRAKING * 100) / n,
      BRAKING_HOLD: (simCounts.BRAKING_HOLD * 100) / n
    },
    brakeMarkersTotal: brakeMarkers.length,
    brakeMarkersHit: brakeHit,
    bumpMarkersTotal: bumpMarkers.length,
    bumpMarkersRejected: bumpRejected,
    totalDiffTicks,
    elapsedRuntimeMs
  };

  updateScorecardAndMetadata();
  renderBookmarkChips();
  drawWorkbenchCanvas();
  if (hoverSampleIndex !== null) {
    updateCrosshairHud(hoverSampleIndex);
  } else if (samples.length > 0) {
    updateCrosshairHud(Math.min(samples.length - 1, Math.floor(samples.length * 0.25)));
  }
}

function estimateBrightnessForState(state, cfg) {
  const floor = cfg.globalMinBrightness;
  const ceiling = cfg.globalMaxBrightness;
  if (state === "IDLE") return floor + (ceiling - floor) * cfg.idleBrightness;
  if (state === "CRUISING") return floor + (ceiling - floor) * cfg.cruisingBrightness;
  if (state === "BRAKING") return cfg.brakingIgnoresGlobalMax ? 0.92 : ceiling;
  if (state === "BRAKING_HOLD") return floor + (ceiling - floor) * 0.75;
  return floor;
}

/**
 * Updates top metadata strip, validation warnings, and right-hand scorecard
 */
function updateScorecardAndMetadata() {
  const s = currentSession.samples;
  const meta = currentSession.metadata;
  const res = simulationResult;

  document.getElementById("metaSessionTitle").textContent =
    meta.title || (meta.session_id ? `Session #${meta.session_id}` : `Loaded Ride (${s.length} ticks)`);
  document.getElementById("metaMode").textContent = meta.mode || "WLED_ACTIVE";
  document.getElementById("metaTicks").textContent = s.length.toLocaleString();

  const durationSec = s.length > 0 ? (s[s.length - 1].elapsedMs - s[0].elapsedMs) / 1000 : 0;
  document.getElementById("metaDuration").textContent = `${durationSec.toFixed(2)}s`;

  const freshTicks = s.filter(row => row.boschAgeMs >= 0 && row.boschAgeMs < 4000).length;
  const freshPct = s.length > 0 ? ((freshTicks * 100) / s.length).toFixed(1) : "0.0";
  document.getElementById("metaBoschFreshness").textContent =
    meta.bosch_freshness_uptime_pct ? `${meta.bosch_freshness_uptime_pct}%` : `${freshPct}%`;

  document.getElementById("metaSpeedDelta").textContent =
    meta.bosch_mean_speed_delta_kmh ? `${meta.bosch_mean_speed_delta_kmh} km/h` : "0.95 km/h";

  document.getElementById("metaSimLatency").textContent = `${res.elapsedRuntimeMs.toFixed(2)} ms`;

  // Scorecard
  const diffPct = s.length > 0 ? ((res.totalDiffTicks * 100) / s.length).toFixed(1) : "0.0";
  document.getElementById("scoreDiffBadge").textContent =
    `${res.totalDiffTicks} diff ticks (${diffPct}%)`;

  document.getElementById("scoreBrakeEpisodes").textContent =
    `${res.recordedBrakeEpisodes} → ${res.simulatedBrakeEpisodes}`;
  document.getElementById("scoreBrakeMarkers").textContent =
    `${res.brakeMarkersHit} / ${res.brakeMarkersTotal}`;
  document.getElementById("scoreBumpRejected").textContent =
    `${res.bumpMarkersRejected} / ${res.bumpMarkersTotal}`;

  document.getElementById("scoreIdleCruiseShare").textContent =
    `I: ${res.recordedShare.IDLE.toFixed(0)}%→${res.simulatedShare.IDLE.toFixed(0)}% • C: ${res.recordedShare.CRUISING.toFixed(0)}%→${res.simulatedShare.CRUISING.toFixed(0)}%`;

  document.getElementById("scoreBrakeHoldShare").textContent =
    `B: ${res.recordedShare.BRAKING.toFixed(0)}%→${res.simulatedShare.BRAKING.toFixed(0)}% • H: ${res.recordedShare.BRAKING_HOLD.toFixed(0)}%→${res.simulatedShare.BRAKING_HOLD.toFixed(0)}%`;

  // Validation warnings (matching TuningParameterSections.kt)
  const warnings = [];
  if (activeConfig.globalMinBrightness > activeConfig.globalMaxBrightness) {
    warnings.push("• globalMinBrightness must be ≤ globalMaxBrightness");
  }
  if (activeConfig.idleEnterSpeedKmh > activeConfig.idleExitSpeedKmh) {
    warnings.push("• idleEnterSpeedKmh must be ≤ idleExitSpeedKmh");
  }
  if (activeConfig.brakingMinBrightness > activeConfig.brakingMaxBrightness) {
    warnings.push("• brakingMinBrightness must be ≤ brakingMaxBrightness");
  }
  const banner = document.getElementById("validationBanner");
  if (warnings.length > 0) {
    banner.innerHTML = `<strong>Validation Warning:</strong><br/>${warnings.join("<br/>")}`;
    banner.classList.add("visible");
  } else {
    banner.classList.remove("visible");
  }
}

/**
 * Populates the Jump-to-Event & Diff Bookmark chips
 */
function renderBookmarkChips() {
  const container = document.getElementById("bookmarkChipsContainer");
  container.innerHTML = "";

  // 1. Stamped Markers
  for (const m of currentSession.markers) {
    const chip = document.createElement("button");
    const isBrake = m.markerType.includes("BRAKE");
    const isBump = m.markerType.includes("BUMP");
    chip.className = `chip ${isBrake ? "chip-brake" : isBump ? "chip-bump" : "chip-note"}`;
    chip.textContent = `${m.markerType} @ ${(m.elapsedMs / 1000).toFixed(2)}s`;
    chip.title = m.note || m.markerType;
    chip.addEventListener("click", () => zoomToWindow(m.elapsedMs - 2200, m.elapsedMs + 3200, m.elapsedMs));
    container.appendChild(chip);
  }

  // 2. Diff Episodes (up to 6 most significant Before != After windows)
  const significantDiffs = simulationResult.diffEpisodes
    .filter(d => d.ticksCount >= 2)
    .slice(0, 6);

  for (const d of significantDiffs) {
    const chip = document.createElement("button");
    chip.className = "chip chip-diff";
    const durMs = Math.max(20, d.endElapsedMs - d.startElapsedMs + 20);
    chip.textContent = `Diff #${d.index}: ${d.recordedState}→${d.simulatedState} @ ${(d.startElapsedMs / 1000).toFixed(2)}s (${durMs}ms)`;
    chip.title = `Before=${d.recordedState}, After=${d.simulatedState} for ${d.ticksCount} ticks`;
    chip.addEventListener("click", () => zoomToWindow(d.startElapsedMs - 2000, d.endElapsedMs + 2500, d.startElapsedMs));
    container.appendChild(chip);
  }
}

function zoomToWindow(startMs, endMs, focusMs = null) {
  const samples = currentSession.samples;
  if (!samples.length) return;
  const minMs = samples[0].elapsedMs;
  const maxMs = samples[samples.length - 1].elapsedMs;
  const span = Math.max(600, endMs - startMs);

  viewStartMs = Math.max(minMs, startMs);
  viewEndMs = Math.min(maxMs, viewStartMs + span);
  if (viewEndMs - viewStartMs < 600) {
    viewStartMs = Math.max(minMs, viewEndMs - 600);
  }

  if (focusMs !== null) {
    const idx = findClosestSampleIndex(focusMs);
    hoverSampleIndex = idx;
    updateCrosshairHud(idx);
  }
  drawWorkbenchCanvas();
}

/**
 * Multi-Track High-DPI Canvas Renderer
 */
function drawWorkbenchCanvas() {
  const canvas = document.getElementById("telemetryCanvas");
  const wrapper = document.getElementById("canvasWrapper");
  if (!canvas || !wrapper) return;

  const dpr = window.devicePixelRatio || 1;
  const width = wrapper.clientWidth;
  const height = wrapper.clientHeight;

  if (canvas.width !== Math.floor(width * dpr) || canvas.height !== Math.floor(height * dpr)) {
    canvas.width = Math.floor(width * dpr);
    canvas.height = Math.floor(height * dpr);
  }

  const ctx = canvas.getContext("2d");
  ctx.save();
  ctx.scale(dpr, dpr);

  // Clear background
  ctx.fillStyle = "#0b0f17";
  ctx.fillRect(0, 0, width, height);

  const samples = currentSession.samples;
  const ticks = simulationResult.ticks;
  if (!samples || samples.length === 0 || !ticks || ticks.length === 0) {
    ctx.fillStyle = "#94a3b8";
    ctx.font = "14px Inter, sans-serif";
    ctx.textAlign = "center";
    ctx.fillText("Drop a Pretty Nights CSV file or click 'Load Built-In Tricky Ride (50 Hz)'", width / 2, height / 2);
    ctx.restore();
    return;
  }

  document.getElementById("viewportRangeLabel").textContent =
    `${(viewStartMs / 1000).toFixed(2)}s – ${(viewEndMs / 1000).toFixed(2)}s (${((viewEndMs - viewStartMs) / 1000).toFixed(2)}s window)`;

  const padLeft = 58;
  const padRight = 52;
  const plotW = Math.max(100, width - padLeft - padRight);

  const timeToX = (elapsedMs) => padLeft + ((elapsedMs - viewStartMs) / Math.max(1, viewEndMs - viewStartMs)) * plotW;

  // Layout geometry for the 5 synchronized tracks
  const ribbonBeforeY = 10;
  const ribbonH = 22;
  const ribbonAfterY = ribbonBeforeY + ribbonH + 6;
  const diffStripY = ribbonAfterY + ribbonH + 6;
  const diffStripH = 14;

  const graphsTopY = diffStripY + diffStripH + 14;
  const graphsBottomY = height - 26;
  const availGraphH = Math.max(120, graphsBottomY - graphsTopY);

  const accelTrackY = graphsTopY;
  const accelTrackH = Math.floor(availGraphH * 0.48);
  const speedTrackY = accelTrackY + accelTrackH + 14;
  const speedTrackH = Math.max(60, graphsBottomY - speedTrackY);

  // Determine visible sample index bounds
  let startIdx = findClosestSampleIndex(viewStartMs);
  let endIdx = findClosestSampleIndex(viewEndMs);
  startIdx = Math.max(0, startIdx - 1);
  endIdx = Math.min(samples.length - 1, endIdx + 1);

  // Draw vertical time grid lines
  drawTimeGrid(ctx, padLeft, plotW, height, graphsBottomY);

  // Track 1: BEFORE (Recorded State) Ribbon
  drawStateRibbon(ctx, "BEFORE (Recorded)", padLeft, ribbonBeforeY, plotW, ribbonH, startIdx, endIdx, (i) => samples[i].recordedState, timeToX);

  // Track 2: AFTER (Simulated State) Ribbon
  drawStateRibbon(ctx, "AFTER (Simulated)", padLeft, ribbonAfterY, plotW, ribbonH, startIdx, endIdx, (i) => ticks[i].simulatedState, timeToX);

  // Track 3: BEFORE != AFTER Diff Strip
  drawDiffStrip(ctx, padLeft, diffStripY, plotW, diffStripH, startIdx, endIdx, ticks, samples, timeToX);

  // Track 4: Longitudinal & Raw Acceleration Track (m/s²)
  drawAccelTrack(ctx, padLeft, accelTrackY, plotW, accelTrackH, startIdx, endIdx, samples, ticks, timeToX);

  // Track 5: Speed (km/h), Bosch eb21 & Brightness Track
  drawSpeedAndBrightnessTrack(ctx, padLeft, speedTrackY, plotW, speedTrackH, startIdx, endIdx, samples, ticks, timeToX);

  // Event Marker Vertical Flags across all tracks
  drawEventMarkerFlags(ctx, padLeft, plotW, ribbonBeforeY, graphsBottomY, timeToX);

  // Synchronized 20 ms Crosshair
  if (hoverSampleIndex !== null && hoverSampleIndex >= startIdx && hoverSampleIndex <= endIdx) {
    const hs = samples[hoverSampleIndex];
    const cx = timeToX(hs.elapsedMs);
    if (cx >= padLeft && cx <= padLeft + plotW) {
      ctx.save();
      ctx.strokeStyle = "rgba(248, 250, 252, 0.75)";
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 3]);
      ctx.beginPath();
      ctx.moveTo(cx, ribbonBeforeY);
      ctx.lineTo(cx, graphsBottomY);
      ctx.stroke();
      ctx.restore();
    }
  }

  ctx.restore();
}

function drawTimeGrid(ctx, padLeft, plotW, height, bottomY) {
  const spanMs = Math.max(1, viewEndMs - viewStartMs);
  let stepMs = 5000;
  if (spanMs <= 2000) stepMs = 200;
  else if (spanMs <= 5000) stepMs = 500;
  else if (spanMs <= 15000) stepMs = 1000;
  else if (spanMs <= 35000) stepMs = 2000;

  const firstTick = Math.ceil(viewStartMs / stepMs) * stepMs;
  ctx.save();
  ctx.font = "10px 'JetBrains Mono', monospace";
  ctx.fillStyle = "#64748b";
  ctx.textAlign = "center";

  for (let t = firstTick; t <= viewEndMs; t += stepMs) {
    const x = padLeft + ((t - viewStartMs) / spanMs) * plotW;
    ctx.strokeStyle = "rgba(35, 48, 68, 0.55)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(x, 10);
    ctx.lineTo(x, bottomY);
    ctx.stroke();

    ctx.fillText(`${(t / 1000).toFixed(stepMs < 1000 ? 1 : 0)}s`, x, height - 8);
  }
  ctx.restore();
}

function drawStateRibbon(ctx, label, x0, y0, w, h, startIdx, endIdx, stateGetter, timeToX) {
  const samples = currentSession.samples;

  ctx.save();
  ctx.fillStyle = "#131a26";
  ctx.fillRect(x0, y0, w, h);

  // Label on left margin
  ctx.fillStyle = "#94a3b8";
  ctx.font = "600 9px 'JetBrains Mono', monospace";
  ctx.textAlign = "right";
  ctx.fillText(label.split(" ")[0], x0 - 6, y0 + h / 2 + 3);

  let segStartIdx = startIdx;
  let segState = stateGetter(startIdx);

  for (let i = startIdx + 1; i <= endIdx; i++) {
    const st = stateGetter(i);
    if (st !== segState || i === endIdx) {
      const sx = Math.max(x0, timeToX(samples[segStartIdx].elapsedMs));
      const ex = Math.min(x0 + w, timeToX(samples[i].elapsedMs));
      const segW = Math.max(1.5, ex - sx);

      ctx.fillStyle = STATE_COLORS[segState] || "#475569";
      ctx.fillRect(sx, y0, segW, h);

      if (segW > 52) {
        ctx.fillStyle = "#080c12";
        ctx.font = "700 10px 'JetBrains Mono', monospace";
        ctx.textAlign = "center";
        ctx.fillText(segState, sx + segW / 2, y0 + h / 2 + 3.5);
      }

      segStartIdx = i;
      segState = st;
    }
  }

  ctx.strokeStyle = "#233044";
  ctx.strokeRect(x0, y0, w, h);
  ctx.restore();
}

function drawDiffStrip(ctx, x0, y0, w, h, startIdx, endIdx, ticks, samples, timeToX) {
  ctx.save();
  ctx.fillStyle = "#0e1420";
  ctx.fillRect(x0, y0, w, h);

  ctx.fillStyle = "#c084fc";
  ctx.font = "600 9px 'JetBrains Mono', monospace";
  ctx.textAlign = "right";
  ctx.fillText("DIFF", x0 - 6, y0 + h / 2 + 3);

  for (let i = startIdx; i < endIdx; i++) {
    if (ticks[i].isDiff) {
      const sx = Math.max(x0, timeToX(samples[i].elapsedMs));
      const ex = Math.min(x0 + w, timeToX(samples[i + 1].elapsedMs));
      ctx.fillStyle = "#a855f7";
      ctx.fillRect(sx, y0, Math.max(2, ex - sx), h);
    }
  }

  ctx.strokeStyle = "#233044";
  ctx.strokeRect(x0, y0, w, h);
  ctx.restore();
}

function drawAccelTrack(ctx, x0, y0, w, h, startIdx, endIdx, samples, ticks, timeToX) {
  ctx.save();
  ctx.fillStyle = "rgba(19, 26, 38, 0.55)";
  ctx.fillRect(x0, y0, w, h);
  ctx.strokeStyle = "#233044";
  ctx.strokeRect(x0, y0, w, h);

  const minA = -6.0;
  const maxA = 6.0;
  const accelToY = (a) => {
    const clamped = Math.min(maxA, Math.max(minA, a));
    return y0 + h - ((clamped - minA) / (maxA - minA)) * h;
  };

  // Horizontal reference lines
  ctx.font = "10px 'JetBrains Mono', monospace";
  ctx.textAlign = "right";
  for (const gVal of [-4, -2, 0, 2, 4]) {
    const gy = accelToY(gVal);
    ctx.strokeStyle = gVal === 0 ? "rgba(148, 163, 184, 0.35)" : "rgba(35, 48, 68, 0.5)";
    ctx.beginPath();
    ctx.moveTo(x0, gy);
    ctx.lineTo(x0 + w, gy);
    ctx.stroke();
    ctx.fillStyle = "#64748b";
    ctx.fillText(`${gVal} m/s²`, x0 - 6, gy + 3);
  }

  // Shade active debounce & cooldown windows
  for (let i = startIdx; i < endIdx; i++) {
    const t = ticks[i];
    if (t.decelDebounceElapsedMs > 0 && !t.decelAsserted) {
      const sx = Math.max(x0, timeToX(samples[i].elapsedMs));
      const ex = Math.min(x0 + w, timeToX(samples[i + 1].elapsedMs));
      ctx.fillStyle = "rgba(251, 191, 36, 0.14)";
      ctx.fillRect(sx, y0, Math.max(1.5, ex - sx), h);
    } else if (t.cooldownElapsedMs > 0) {
      const sx = Math.max(x0, timeToX(samples[i].elapsedMs));
      const ex = Math.min(x0 + w, timeToX(samples[i + 1].elapsedMs));
      ctx.fillStyle = "rgba(56, 189, 248, 0.12)";
      ctx.fillRect(sx, y0, Math.max(1.5, ex - sx), h);
    }
  }

  // Braking Decel Threshold line
  if (activeSignals.thresholds) {
    const threshY = accelToY(activeConfig.brakingDecelThreshold);
    ctx.save();
    ctx.strokeStyle = "#fbbf24";
    ctx.lineWidth = 1.5;
    ctx.setLineDash([5, 4]);
    ctx.beginPath();
    ctx.moveTo(x0, threshY);
    ctx.lineTo(x0 + w, threshY);
    ctx.stroke();
    ctx.fillStyle = "#fbbf24";
    ctx.font = "700 10px 'JetBrains Mono', monospace";
    ctx.textAlign = "left";
    ctx.fillText(`brakingDecelThreshold (${activeConfig.brakingDecelThreshold.toFixed(1)} m/s²)`, x0 + 8, threshY - 5);
    ctx.restore();
  }

  // Raw Accel X/Y/Z
  if (activeSignals.rawAccelXYZ) {
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => accelToY(samples[i].accelX), "rgba(163, 230, 53, 0.65)", 1);
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => accelToY(samples[i].accelY), "rgba(56, 189, 248, 0.65)", 1);
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => accelToY(samples[i].accelZ - 9.81), "rgba(251, 146, 60, 0.75)", 1);
  }

  // Fused Longitudinal Accel
  if (activeSignals.fusedAccel) {
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => accelToY(ticks[i].effectiveAccel), "#f43f5e", 1.8);
  }

  ctx.restore();
}

function drawSpeedAndBrightnessTrack(ctx, x0, y0, w, h, startIdx, endIdx, samples, ticks, timeToX) {
  ctx.save();
  ctx.fillStyle = "rgba(19, 26, 38, 0.55)";
  ctx.fillRect(x0, y0, w, h);
  ctx.strokeStyle = "#233044";
  ctx.strokeRect(x0, y0, w, h);

  const maxSpeed = 35.0;
  const speedToY = (v) => {
    const clamped = Math.min(maxSpeed, Math.max(0, v));
    return y0 + h - (clamped / maxSpeed) * h;
  };
  const normToY = (norm) => {
    const clamped = Math.min(1.0, Math.max(0.0, norm));
    return y0 + h - clamped * h;
  };

  // Left axis labels (Speed km/h) & Right axis labels (Brightness %)
  ctx.font = "10px 'JetBrains Mono', monospace";
  for (const sVal of [0, 10, 20, 30]) {
    const sy = speedToY(sVal);
    ctx.strokeStyle = "rgba(35, 48, 68, 0.5)";
    ctx.beginPath();
    ctx.moveTo(x0, sy);
    ctx.lineTo(x0 + w, sy);
    ctx.stroke();

    ctx.fillStyle = "#38bdf8";
    ctx.textAlign = "right";
    ctx.fillText(`${sVal} km/h`, x0 - 6, sy + 3);
  }

  for (const bPct of [0, 50, 100]) {
    const by = normToY(bPct / 100);
    ctx.fillStyle = "#c084fc";
    ctx.textAlign = "left";
    ctx.fillText(`${bPct}%`, x0 + w + 6, by + 3);
  }

  // Idle Enter/Exit Speed Thresholds
  if (activeSignals.thresholds) {
    ctx.save();
    ctx.setLineDash([4, 4]);
    const enterY = speedToY(activeConfig.idleEnterSpeedKmh);
    ctx.strokeStyle = "rgba(245, 158, 11, 0.7)";
    ctx.beginPath();
    ctx.moveTo(x0, enterY);
    ctx.lineTo(x0 + w, enterY);
    ctx.stroke();

    const exitY = speedToY(activeConfig.idleExitSpeedKmh);
    ctx.strokeStyle = "rgba(0, 180, 255, 0.7)";
    ctx.beginPath();
    ctx.moveTo(x0, exitY);
    ctx.lineTo(x0 + w, exitY);
    ctx.stroke();
    ctx.restore();
  }

  // Bosch Age Sawtooth (0 - 1000ms mapped to 0 - 1)
  if (activeSignals.boschAge) {
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => normToY(Math.min(1.0, Math.max(0, samples[i].boschAgeMs / 1000.0))), "rgba(251, 146, 60, 0.65)", 1.2);
  }

  // Cadence & Power (normalized to 120 rpm / 350W)
  if (activeSignals.cadencePower) {
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => normToY((samples[i].boschCadenceRpm || 0) / 120.0), "#2dd4bf", 1.3);
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => normToY((samples[i].boschRiderPowerW || 0) / 350.0), "#eab308", 1.3);
  }

  // GPS 1Hz Speed
  if (activeSignals.gpsSpeed) {
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => speedToY(samples[i].gpsSpeedKmh), "#94a3b8", 1.4, [3, 3]);
  }

  // Bosch eb21 Speed
  if (activeSignals.boschSpeed) {
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => speedToY(samples[i].boschSpeedKmh !== null ? samples[i].boschSpeedKmh : samples[i].speedKmh), "#22c55e", 1.6);
  }

  // Effective Active Speed
  if (activeSignals.activeSpeed) {
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => speedToY(ticks[i].effectiveSpeedKmh), "#38bdf8", 2.0);
  }

  // Recorded vs Simulated Brightness
  if (activeSignals.brightness) {
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => normToY(ticks[i].recordedBrightness), "rgba(192, 132, 252, 0.45)", 1.4, [2, 2]);
    drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, (i) => normToY(ticks[i].simulatedBrightness), "#c084fc", 2.0);
  }

  ctx.restore();
}

function drawPolyline(ctx, x0, w, startIdx, endIdx, samples, timeToX, yGetter, color, lineWidth = 1.5, dash = []) {
  ctx.save();
  ctx.beginPath();
  ctx.rect(x0, 0, w, 2000);
  ctx.clip();

  ctx.strokeStyle = color;
  ctx.lineWidth = lineWidth;
  if (dash.length) ctx.setLineDash(dash);

  ctx.beginPath();
  const count = endIdx - startIdx + 1;
  const stride = count > 2500 ? Math.floor(count / 2000) : 1;

  let started = false;
  for (let i = startIdx; i <= endIdx; i += stride) {
    const x = timeToX(samples[i].elapsedMs);
    const y = yGetter(i);
    if (!started) {
      ctx.moveTo(x, y);
      started = true;
    } else {
      ctx.lineTo(x, y);
    }
  }
  ctx.stroke();

  // Draw individual 20 ms sample dots when zoomed into <= 3.5 seconds (<= 175 ticks)
  if (count <= 175 && dash.length === 0) {
    ctx.fillStyle = color;
    for (let i = startIdx; i <= endIdx; i++) {
      const x = timeToX(samples[i].elapsedMs);
      const y = yGetter(i);
      ctx.beginPath();
      ctx.arc(x, y, 2.2, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  ctx.restore();
}

function drawEventMarkerFlags(ctx, x0, w, topY, bottomY, timeToX) {
  ctx.save();
  for (const m of currentSession.markers) {
    const mx = timeToX(m.elapsedMs);
    if (mx < x0 || mx > x0 + w) continue;

    const isBrake = m.markerType.includes("BRAKE");
    const color = isBrake ? "#ef4444" : (m.markerType.includes("BUMP") ? "#f59e0b" : "#38bdf8");

    ctx.strokeStyle = color;
    ctx.lineWidth = 1.2;
    ctx.setLineDash([2, 2]);
    ctx.beginPath();
    ctx.moveTo(mx, topY);
    ctx.lineTo(mx, bottomY);
    ctx.stroke();

    ctx.setLineDash([]);
    ctx.fillStyle = color;
    ctx.font = "700 9px 'JetBrains Mono', monospace";
    ctx.textAlign = "left";
    ctx.fillText(`⚑ ${m.markerType}`, mx + 4, topY + 72);
  }
  ctx.restore();
}

/**
 * Updates bottom 8-cell Crosshair Inspection HUD
 */
function updateCrosshairHud(idx) {
  const s = currentSession.samples[idx];
  const t = simulationResult.ticks[idx];
  if (!s || !t) return;

  document.getElementById("hudTick").textContent =
    `#${s.sampleId} @ ${(s.elapsedMs / 1000).toFixed(3)}s`;

  const beforeEl = document.getElementById("hudBeforeState");
  beforeEl.textContent = s.recordedState;
  beforeEl.style.color = STATE_COLORS[s.recordedState] || "#f1f5f9";

  const afterEl = document.getElementById("hudAfterState");
  afterEl.textContent = t.isDiff ? `${t.simulatedState} (DIFF)` : t.simulatedState;
  afterEl.style.color = STATE_COLORS[t.simulatedState] || "#f1f5f9";

  document.getElementById("hudBrightness").textContent =
    `${Math.round(t.recordedBrightness * 100)}% → ${Math.round(t.simulatedBrightness * 100)}%`;

  document.getElementById("hudAccel").textContent =
    `${t.effectiveAccel.toFixed(2)} m/s² (Z:${s.accelZ.toFixed(1)})`;

  const boschStr = s.boschSpeedKmh !== null ? s.boschSpeedKmh.toFixed(1) : "—";
  document.getElementById("hudSpeed").textContent =
    `${t.effectiveSpeedKmh.toFixed(1)} / ${boschStr} / ${s.gpsSpeedKmh.toFixed(1)}`;

  document.getElementById("hudTimers").textContent =
    `Deb:${t.decelDebounceElapsedMs}ms • Cool:${t.cooldownElapsedMs}ms`;

  document.getElementById("hudBoschMarker").textContent =
    `Age:${s.boschAgeMs}ms ${s.markerAtTick ? "• " + s.markerAtTick : ""}`;
}

function findClosestSampleIndex(targetElapsedMs) {
  const samples = currentSession.samples;
  if (!samples.length) return 0;
  let lo = 0;
  let hi = samples.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (samples[mid].elapsedMs < targetElapsedMs) lo = mid + 1;
    else hi = mid;
  }
  if (lo > 0 && Math.abs(samples[lo - 1].elapsedMs - targetElapsedMs) < Math.abs(samples[lo].elapsedMs - targetElapsedMs)) {
    return lo - 1;
  }
  return lo;
}

/**
 * Builds the 7 StateMachineConfig slider sections dynamically
 */
function buildParameterControls() {
  const container = document.getElementById("sliderSectionsContainer");
  container.innerHTML = "";

  for (const group of PARAMETER_GROUPS) {
    const card = document.createElement("div");
    card.className = "param-group";

    const header = document.createElement("div");
    header.className = "param-group-header";
    header.textContent = group.title;
    card.appendChild(header);

    for (const ctrl of group.controls) {
      if (ctrl.type === "boolean") {
        const row = document.createElement("div");
        row.className = "param-control";
        row.innerHTML = `
          <div class="switch-row">
            <label class="param-name" for="ctrl_${ctrl.key}">${ctrl.key}</label>
            <input type="checkbox" class="dock-check" id="ctrl_${ctrl.key}" ${activeConfig[ctrl.key] ? "checked" : ""} />
          </div>
          <div class="param-desc">${ctrl.desc}</div>
        `;
        const checkbox = row.querySelector("input");
        checkbox.addEventListener("change", (e) => {
          activeConfig[ctrl.key] = e.target.checked;
          runSimulation();
        });
        card.appendChild(row);
      } else {
        const row = document.createElement("div");
        row.className = "param-control";
        const val = activeConfig[ctrl.key];
        const formatted = formatParamValue(val, ctrl.step, ctrl.unit);
        row.innerHTML = `
          <div class="param-top">
            <label class="param-name" for="ctrl_${ctrl.key}">${ctrl.key}</label>
            <span class="param-readout" id="val_${ctrl.key}">${formatted}</span>
          </div>
          <input type="range" id="ctrl_${ctrl.key}" min="${ctrl.min}" max="${ctrl.max}" step="${ctrl.step}" value="${val}" />
          <div class="param-desc">${ctrl.desc}</div>
        `;
        const slider = row.querySelector("input");
        const readout = row.querySelector(`#val_${ctrl.key}`);
        slider.addEventListener("input", (e) => {
          const num = Number(e.target.value);
          activeConfig[ctrl.key] = num;
          readout.textContent = formatParamValue(num, ctrl.step, ctrl.unit);
          runSimulation();
        });
        card.appendChild(row);
      }
    }

    container.appendChild(card);
  }
}

function syncSlidersFromActiveConfig() {
  for (const group of PARAMETER_GROUPS) {
    for (const ctrl of group.controls) {
      const el = document.getElementById(`ctrl_${ctrl.key}`);
      if (!el) continue;
      if (ctrl.type === "boolean") {
        el.checked = Boolean(activeConfig[ctrl.key]);
      } else {
        el.value = activeConfig[ctrl.key];
        const readout = document.getElementById(`val_${ctrl.key}`);
        if (readout) {
          readout.textContent = formatParamValue(activeConfig[ctrl.key], ctrl.step, ctrl.unit);
        }
      }
    }
  }
}

function formatParamValue(val, step, unit) {
  const decimals = step < 0.1 ? 2 : (step < 1 ? 1 : 0);
  return `${Number(val).toFixed(decimals)}${unit || ""}`;
}

/**
 * Generates Kotlin StateMachineConfig(...) code snippet for copy/pasting
 */
function buildKotlinSnippet(cfg) {
  return `StateMachineConfig(
    globalMinBrightness = ${cfg.globalMinBrightness.toFixed(2)}f,
    globalMaxBrightness = ${cfg.globalMaxBrightness.toFixed(2)}f,
    brakingIgnoresGlobalMax = ${cfg.brakingIgnoresGlobalMax},
    idleEnterSpeedKmh = ${cfg.idleEnterSpeedKmh.toFixed(1)}f,
    idleExitSpeedKmh = ${cfg.idleExitSpeedKmh.toFixed(1)}f,
    idleEntryFadeS = ${cfg.idleEntryFadeS.toFixed(1)}f,
    idleBrightness = ${cfg.idleBrightness.toFixed(2)}f,
    requireBoschIdleConfirm = ${cfg.requireBoschIdleConfirm},
    cruisingEntryFadeS = ${cfg.cruisingEntryFadeS.toFixed(1)}f,
    cruisingBrightness = ${cfg.cruisingBrightness.toFixed(2)}f,
    brakingEntryFadeS = ${cfg.brakingEntryFadeS.toFixed(1)}f,
    brakingDecelThreshold = ${cfg.brakingDecelThreshold.toFixed(1)}f,
    brakingDebounceMs = ${Math.round(cfg.brakingDebounceMs)}L,
    brakingCooldownMs = ${Math.round(cfg.brakingCooldownMs)}L,
    brakingMinBrightness = ${cfg.brakingMinBrightness.toFixed(2)}f,
    brakingMaxBrightness = ${cfg.brakingMaxBrightness.toFixed(2)}f,
    brakingGamma = ${cfg.brakingGamma.toFixed(1)}f,
    brakingHoldDurationS = ${cfg.brakingHoldDurationS.toFixed(1)}f,
    brakingFadeDurationS = ${cfg.brakingFadeDurationS.toFixed(1)}f,
    useBoschCorroboration = ${cfg.useBoschCorroboration}
)`;
}

function refreshModalContent() {
  const textarea = document.getElementById("configTextarea");
  const applyBtn = document.getElementById("btnApplyJsonToSliders");
  const tabJson = document.getElementById("tabBtnJson");
  const tabKotlin = document.getElementById("tabBtnKotlin");

  if (activeModalTab === "json") {
    tabJson.classList.add("btn-primary");
    tabKotlin.classList.remove("btn-primary");
    textarea.value = JSON.stringify(activeConfig, null, 2);
    textarea.readOnly = false;
    applyBtn.style.display = "inline-flex";
  } else {
    tabKotlin.classList.add("btn-primary");
    tabJson.classList.remove("btn-primary");
    textarea.value = buildKotlinSnippet(activeConfig);
    textarea.readOnly = true;
    applyBtn.style.display = "none";
  }
}

function loadCsvTextIntoWorkbench(csvText) {
  currentSession = parsePrettyNightsCsv(csvText);
  if (currentSession.samples.length > 0) {
    viewStartMs = currentSession.samples[0].elapsedMs;
    viewEndMs = currentSession.samples[currentSession.samples.length - 1].elapsedMs;
    hoverSampleIndex = 0;
  }
  runSimulation();
}

/**
 * Wire up DOM listeners, Canvas zoom/pan, and Drag-and-Drop
 */
function initWorkbench() {
  buildParameterControls();

  // Load built-in tricky sample ride on startup
  const sampleCsv = generateTrickySampleRideCsv();
  loadCsvTextIntoWorkbench(sampleCsv);

  // File load button
  const fileInput = document.getElementById("csvFileInput");
  document.getElementById("btnLoadCsv").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => loadCsvTextIntoWorkbench(String(ev.target.result || ""));
    reader.readAsText(file);
  });

  // Load built-in tricky ride button
  document.getElementById("btnLoadSampleRide").addEventListener("click", () => {
    loadCsvTextIntoWorkbench(generateTrickySampleRideCsv());
  });

  // Download current CSV
  document.getElementById("btnDownloadSampleCsv").addEventListener("click", () => {
    const csvContent = currentSession.rawCsvText || generateTrickySampleRideCsv();
    const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "prettynights_workbench_session.csv";
    a.click();
    URL.revokeObjectURL(url);
  });

  // Drag and drop CSV anywhere on canvas wrapper
  const wrapper = document.getElementById("canvasWrapper");
  const dropOverlay = document.getElementById("dropOverlay");
  window.addEventListener("dragover", (e) => {
    e.preventDefault();
    dropOverlay.classList.add("visible");
  });
  window.addEventListener("dragleave", (e) => {
    if (e.target === document.body || e.clientX <= 0 || e.clientY <= 0) {
      dropOverlay.classList.remove("visible");
    }
  });
  window.addEventListener("drop", (e) => {
    e.preventDefault();
    dropOverlay.classList.remove("visible");
    const file = e.dataTransfer?.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => loadCsvTextIntoWorkbench(String(ev.target.result || ""));
    reader.readAsText(file);
  });

  // Signal pill toggles
  document.querySelectorAll(".signal-pill").forEach((pill) => {
    pill.addEventListener("click", () => {
      const sig = pill.getAttribute("data-signal");
      activeSignals[sig] = !activeSignals[sig];
      pill.classList.toggle("active", activeSignals[sig]);
      drawWorkbenchCanvas();
    });
  });

  // Replay stream & IMU projection selects
  document.getElementById("selReplaySpeedSource").addEventListener("change", (e) => {
    replaySpeedSource = e.target.value;
    runSimulation();
  });
  document.getElementById("selImuAxis").addEventListener("change", (e) => {
    imuAxisOverride = e.target.value;
    runSimulation();
  });
  document.getElementById("selImuSign").addEventListener("change", (e) => {
    imuSignOverride = e.target.value;
    runSimulation();
  });

  // Reset defaults
  document.getElementById("btnResetDefaults").addEventListener("click", () => {
    activeConfig = { ...DEFAULT_CONFIG };
    syncSlidersFromActiveConfig();
    runSimulation();
  });

  // Zoom controls
  document.getElementById("btnZoomIn").addEventListener("click", () => zoomByFactor(0.6));
  document.getElementById("btnZoomOut").addEventListener("click", () => zoomByFactor(1.6));
  document.getElementById("btnZoomReset").addEventListener("click", () => {
    if (!currentSession.samples.length) return;
    viewStartMs = currentSession.samples[0].elapsedMs;
    viewEndMs = currentSession.samples[currentSession.samples.length - 1].elapsedMs;
    drawWorkbenchCanvas();
  });

  // Canvas mouse crosshair, wheel zoom, and pan
  const canvas = document.getElementById("telemetryCanvas");
  canvas.addEventListener("mousemove", (e) => {
    const rect = canvas.getBoundingClientRect();
    const padLeft = 58;
    const padRight = 52;
    const plotW = Math.max(100, rect.width - padLeft - padRight);
    const mouseX = e.clientX - rect.left;

    if (isDraggingPan) {
      const dx = e.clientX - dragStartX;
      const span = dragStartViewEnd - dragStartViewStart;
      const dt = -(dx / plotW) * span;
      const minMs = currentSession.samples[0]?.elapsedMs || 0;
      const maxMs = currentSession.samples[currentSession.samples.length - 1]?.elapsedMs || 30000;
      viewStartMs = Math.max(minMs, Math.min(maxMs - span, dragStartViewStart + dt));
      viewEndMs = viewStartMs + span;
      drawWorkbenchCanvas();
      return;
    }

    const ratio = Math.min(1, Math.max(0, (mouseX - padLeft) / plotW));
    const targetMs = viewStartMs + ratio * (viewEndMs - viewStartMs);
    const idx = findClosestSampleIndex(targetMs);
    hoverSampleIndex = idx;
    updateCrosshairHud(idx);
    drawWorkbenchCanvas();
  });

  canvas.addEventListener("mousedown", (e) => {
    isDraggingPan = true;
    dragStartX = e.clientX;
    dragStartViewStart = viewStartMs;
    dragStartViewEnd = viewEndMs;
  });

  window.addEventListener("mouseup", () => {
    isDraggingPan = false;
  });

  canvas.addEventListener("dblclick", () => {
    if (!currentSession.samples.length) return;
    viewStartMs = currentSession.samples[0].elapsedMs;
    viewEndMs = currentSession.samples[currentSession.samples.length - 1].elapsedMs;
    drawWorkbenchCanvas();
  });

  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    if (!currentSession.samples.length) return;
    const rect = canvas.getBoundingClientRect();
    const padLeft = 58;
    const padRight = 52;
    const plotW = Math.max(100, rect.width - padLeft - padRight);
    const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left - padLeft) / plotW));
    const centerMs = viewStartMs + ratio * (viewEndMs - viewStartMs);
    const factor = e.deltaY < 0 ? 0.78 : 1.28;
    zoomAroundCenter(centerMs, ratio, factor);
  }, { passive: false });

  window.addEventListener("resize", () => drawWorkbenchCanvas());

  // Config Sync Modal
  const modal = document.getElementById("configModal");
  const toast = document.getElementById("modalStatusToast");

  document.getElementById("btnOpenConfigModal").addEventListener("click", () => {
    toast.textContent = "";
    refreshModalContent();
    modal.classList.add("open");
  });

  document.getElementById("btnCloseModal").addEventListener("click", () => {
    modal.classList.remove("open");
  });

  document.getElementById("tabBtnJson").addEventListener("click", () => {
    activeModalTab = "json";
    toast.textContent = "";
    refreshModalContent();
  });

  document.getElementById("tabBtnKotlin").addEventListener("click", () => {
    activeModalTab = "kotlin";
    toast.textContent = "";
    refreshModalContent();
  });

  document.getElementById("btnCopyCode").addEventListener("click", async () => {
    const text = document.getElementById("configTextarea").value;
    try {
      await navigator.clipboard.writeText(text);
      toast.textContent = "Copied to clipboard!";
    } catch (_) {
      document.getElementById("configTextarea").select();
      document.execCommand("copy");
      toast.textContent = "Copied!";
    }
  });

  document.getElementById("btnApplyJsonToSliders").addEventListener("click", () => {
    try {
      const parsed = JSON.parse(document.getElementById("configTextarea").value);
      const sourceObj = parsed.stateMachine ? parsed.stateMachine : parsed;
      for (const k of Object.keys(DEFAULT_CONFIG)) {
        if (sourceObj[k] !== undefined) {
          activeConfig[k] = typeof DEFAULT_CONFIG[k] === "boolean" ? Boolean(sourceObj[k]) : Number(sourceObj[k]);
        }
      }
      syncSlidersFromActiveConfig();
      runSimulation();
      toast.style.color = "#22c55e";
      toast.textContent = "Applied JSON config to sliders & updated 50 Hz simulation!";
    } catch (err) {
      toast.style.color = "#ef4444";
      toast.textContent = "Invalid JSON: " + err.message;
    }
  });

  // JSON File Upload / Download
  const jsonFileInput = document.getElementById("jsonFileInput");
  document.getElementById("btnImportJsonFile").addEventListener("click", () => jsonFileInput.click());
  jsonFileInput.addEventListener("change", (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
      activeModalTab = "json";
      refreshModalContent();
      document.getElementById("configTextarea").value = String(ev.target.result || "");
      document.getElementById("btnApplyJsonToSliders").click();
    };
    reader.readAsText(file);
  });

  document.getElementById("btnDownloadJsonFile").addEventListener("click", () => {
    const jsonStr = JSON.stringify(activeConfig, null, 2);
    const blob = new Blob([jsonStr], { type: "application/json;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "prettynights_statemachine_config.json";
    a.click();
    URL.revokeObjectURL(url);
  });
}

function zoomByFactor(factor) {
  const centerMs = (viewStartMs + viewEndMs) * 0.5;
  zoomAroundCenter(centerMs, 0.5, factor);
}

function zoomAroundCenter(centerMs, ratio, factor) {
  const samples = currentSession.samples;
  if (!samples.length) return;
  const minMs = samples[0].elapsedMs;
  const maxMs = samples[samples.length - 1].elapsedMs;
  const totalSpan = Math.max(1000, maxMs - minMs);

  const curSpan = viewEndMs - viewStartMs;
  const newSpan = Math.min(totalSpan, Math.max(500, curSpan * factor));

  let newStart = centerMs - ratio * newSpan;
  let newEnd = newStart + newSpan;
  if (newStart < minMs) {
    newStart = minMs;
    newEnd = Math.min(maxMs, newStart + newSpan);
  }
  if (newEnd > maxMs) {
    newEnd = maxMs;
    newStart = Math.max(minMs, newEnd - newSpan);
  }
  viewStartMs = newStart;
  viewEndMs = newEnd;
  drawWorkbenchCanvas();
}

window.addEventListener("DOMContentLoaded", initWorkbench);
