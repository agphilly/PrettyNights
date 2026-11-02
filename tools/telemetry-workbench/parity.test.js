/**
 * Pretty Nights — Kotlin <-> JavaScript State Machine Parity Test
 * Run with: node tools/telemetry-workbench/parity.test.js
 */

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { JsRideStateMachine, DEFAULT_CONFIG } = require("./ride-state-machine.js");

const fixturePath = path.join(__dirname, "fixtures", "parity.json");
const fixture = JSON.parse(fs.readFileSync(fixturePath, "utf8"));

// 1. Verify DEFAULT_CONFIG matches Kotlin's default StateMachineConfig() field-by-field
const configKeys = new Set([
  ...Object.keys(DEFAULT_CONFIG),
  ...Object.keys(fixture.config)
]);

for (const key of configKeys) {
  assert.ok(key in DEFAULT_CONFIG, `Missing key '${key}' in JS DEFAULT_CONFIG`);
  assert.ok(key in fixture.config, `Missing key '${key}' in Kotlin fixture.config`);
  const jsVal = DEFAULT_CONFIG[key];
  const ktVal = fixture.config[key];
  if (typeof jsVal === "number" && typeof ktVal === "number") {
    assert.ok(
      Math.abs(jsVal - ktVal) <= 1e-5,
      `Config mismatch for '${key}': JS=${jsVal} vs Kotlin=${ktVal}`
    );
  } else {
    assert.strictEqual(jsVal, ktVal, `Config mismatch for '${key}': JS=${jsVal} vs Kotlin=${ktVal}`);
  }
}

// 2. Verify all 4 scripted ride scenarios tick-by-tick
assert.ok(Array.isArray(fixture.scenarios) && fixture.scenarios.length >= 4, "Expected at least 4 scenarios in parity.json");

let totalTicksVerified = 0;

for (const scenario of fixture.scenarios) {
  const sm = new JsRideStateMachine(fixture.config);
  const firstTimestamp = scenario.ticks.length > 0 ? scenario.ticks[0].timestampMs : 0;
  sm.reset(firstTimestamp);

  for (let i = 0; i < scenario.ticks.length; i++) {
    const tick = scenario.ticks[i];
    const out = sm.tick({
      timestampMs: tick.timestampMs,
      longitudinalAccel: tick.longitudinalAccel,
      speedKmh: tick.speedKmh,
      bikeNotDriving: tick.bikeNotDriving ?? null
    });

    assert.strictEqual(
      out.state,
      tick.expectedState,
      `[${scenario.name}] tick #${i} (@${tick.timestampMs}ms): expected state ${tick.expectedState}, got ${out.state}`
    );

    const diff = Math.abs(out.brightness - tick.expectedBrightness);
    assert.ok(
      diff <= 0.001,
      `[${scenario.name}] tick #${i} (@${tick.timestampMs}ms): expected brightness ${tick.expectedBrightness}, got ${out.brightness} (diff=${diff})`
    );
    totalTicksVerified++;
  }
}

console.log(
  `PASS: Verified DEFAULT_CONFIG (${configKeys.size} fields) and ${fixture.scenarios.length} scenarios (${totalTicksVerified} ticks) against Kotlin RideStateMachine.`
);
