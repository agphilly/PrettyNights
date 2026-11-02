/**
 * Pretty Nights — Shared 50 Hz RideStateMachine (Browser & Node.js)
 * Exact 1:1 port of Kotlin RideStateMachine (RideStateMachine.kt)
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

class JsRideStateMachine {
  constructor(cfg = DEFAULT_CONFIG) {
    this.cfg = { ...DEFAULT_CONFIG, ...cfg };
    this.currentState = "IDLE";
    this.lastStateChangeMs = 0;
    this.decelStartMs = null;
    this.easedOffMs = null;
    this.entrySpeedKmh = null;
    this.entryFadeStartBrightness = this.cfg.idleBrightness;
    this.lastCleanBrightness = this.cfg.idleBrightness;
    this.holdStartBrightness = 0.8;
    this.lastOutputBrightness = this.cfg.globalMinBrightness + (this.cfg.globalMaxBrightness - this.cfg.globalMinBrightness) * this.cfg.idleBrightness;
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

if (typeof module !== "undefined") {
  module.exports = { JsRideStateMachine, DEFAULT_CONFIG };
}
