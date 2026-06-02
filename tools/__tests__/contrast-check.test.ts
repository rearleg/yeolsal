import { test } from "node:test";
import { strict as assert } from "node:assert";
import {
  buildCanonicalPairs,
  buildPoolStackAssetPairs,
  buildPoolStackPairs,
  contrastRatio,
  evaluatePairs,
  relativeLuminance,
  type ColorValue,
  type TokensSubset,
} from "../contrast-check.ts";

function color(hex: string): ColorValue {
  return { oklch: `oklch(synthetic ${hex})`, hex };
}

test("relativeLuminance: black = 0, white = 1", () => {
  assert.equal(relativeLuminance("#000000"), 0);
  assert.ok(Math.abs(relativeLuminance("#FFFFFF") - 1) < 1e-9);
});

test("contrastRatio: white on black = 21:1", () => {
  const r = contrastRatio("#FFFFFF", "#000000");
  assert.ok(Math.abs(r - 21) < 1e-3, `expected ~21, got ${r}`);
});

test("contrastRatio: same color = 1:1", () => {
  assert.equal(contrastRatio("#7E2C2A", "#7E2C2A"), 1);
});

test("contrastRatio: short-form hex is normalized", () => {
  const r = contrastRatio("#FFF", "#000");
  assert.ok(Math.abs(r - 21) < 1e-3);
});

test("evaluatePairs: synthetic near-identical fg/bg surfaces as FAIL", () => {
  const tokens: TokensSubset = {
    color: {
      bg: {
        canvas: color("#FFFFFF"),
        surface: color("#FFFFFF"),
        elevated: color("#FFFFFF"),
        overlay: color("#000000"),
        inverse: color("#000000"),
      },
      text: {
        primary: color("#FEFEFE"),
        secondary: color("#FEFEFE"),
        tertiary: color("#FEFEFE"),
        disabled: color("#FEFEFE"),
        inverse: color("#000000"),
      },
      key: {
        default: color("#000000"),
        glow: color("#000000"),
        deep: color("#000000"),
        muted: color("#000000"),
        line: color("#000000"),
      },
    },
    subMode: {
      quiet: {},
    },
  };
  const results = evaluatePairs(buildCanonicalPairs(tokens));
  const primaryOnCanvas = results.find((r) => r.name === "text.primary on bg.canvas (body)");
  assert.ok(primaryOnCanvas, "primary-on-canvas pair must exist");
  assert.equal(primaryOnCanvas.pass, false, "near-identical fg/bg must FAIL AA");
});

test("evaluatePairs: canonical synthetic v2 oxblood passes AA on body pairs", () => {
  const tokens: TokensSubset = {
    color: {
      bg: {
        canvas: color("#1B1715"),
        surface: color("#241F1C"),
        elevated: color("#2D2724"),
        overlay: color("#100D0BC8"),
        inverse: color("#F4F0EB"),
      },
      text: {
        primary: color("#F4F0EB"),
        secondary: color("#B7B0A6"),
        tertiary: color("#7B756B"),
        disabled: color("#504C46"),
        inverse: color("#241F1C"),
      },
      key: {
        default: color("#7E2C2A"),
        glow: color("#9B3633"),
        deep: color("#5E2120"),
        muted: color("#5B3A39"),
        line: color("#B14342"),
      },
    },
    subMode: {
      quiet: {
        "color.bg.canvas": color("#15110F"),
        "color.bg.surface": color("#1E1916"),
        "color.text.primary": color("#D8D1C7"),
        "color.text.secondary": color("#928B81"),
      },
    },
  };
  const results = evaluatePairs(buildCanonicalPairs(tokens));
  const failures = results.filter((r) => !r.pass);
  assert.equal(
    failures.length,
    0,
    `expected zero AA failures on the canonical v2 palette, got: ${failures.map((f) => f.name).join(", ")}`,
  );
});

// Story 4.3 TOOLS-2 — POOL_STACK_PAIRS regression tests.

function poolStackTokens(): TokensSubset {
  return {
    color: {
      bg: {
        canvas: color("#1B1715"),
        surface: color("#241F1C"),
        elevated: color("#2D2724"),
        overlay: color("#100D0BC8"),
        inverse: color("#F4F0EB"),
      },
      text: {
        primary: color("#F4F0EB"),
        secondary: color("#B7B0A6"),
        tertiary: color("#7B756B"),
        disabled: color("#504C46"),
        inverse: color("#241F1C"),
      },
      key: {
        default: color("#7E2C2A"),
        glow: color("#9B3633"),
        deep: color("#5E2120"),
        muted: color("#5B3A39"),
        line: color("#B14342"),
      },
      ember: {
        default: color("#D89F62"),
        subtle: color("#A48064"),
      },
      stroke: {
        subtle: color("#3B3633"),
        default: color("#594E48"),
        strong: color("#8A7C72"),
        key: color("#B14342"),
      },
    },
    subMode: { quiet: {} },
  };
}

test("PoolStack: buildPoolStackPairs emits 3 pairs (key + ember + stroke)", () => {
  const pairs = buildPoolStackPairs(poolStackTokens());
  assert.equal(pairs.length, 3, `expected 3 pairs, got ${pairs.length}`);
  assert.match(pairs[0]!.name, /key\.default/);
  assert.match(pairs[1]!.name, /ember\.subtle/);
  assert.match(pairs[2]!.name, /stroke\.default/);
  for (const p of pairs) {
    assert.equal(p.minRatio, 3.0, `min ratio must be 3.0 (graphics threshold), got ${p.minRatio}`);
  }
});

test("PoolStack: all 3 pairs pass ≥3:1 against #F0EBE3 (D2 Bento card surface)", () => {
  const results = evaluatePairs(buildPoolStackPairs(poolStackTokens()));
  const failures = results.filter((r) => !r.pass);
  assert.equal(
    failures.length,
    0,
    `expected zero PoolStack contrast failures, got: ${failures.map((f) => `${f.name} (${f.ratio.toFixed(2)}:1)`).join(", ")}`,
  );
});

test("PoolStack: ember.subtle barely clears 3:1 (escape-hatch tone for failed ember.default)", () => {
  // Sanity-check the dev-agent-record claim that the substitute ember
  // tone passes WCAG 2.x §1.4.11. If the design team eventually retones
  // ember.subtle and this assertion starts failing, that's the signal
  // to revisit the AC5 escape hatch and pick a new keystone color.
  const r = contrastRatio("#A48064", "#F0EBE3");
  assert.ok(r >= 3.0, `ember.subtle × surface.sunken must be >= 3:1, got ${r.toFixed(3)}`);
});

test("PoolStack: missing required token fails instead of silently omitting a pair", () => {
  const tokens = poolStackTokens();
  delete (tokens.color.ember as Record<string, ColorValue>).subtle;
  assert.throws(
    () => buildPoolStackPairs(tokens, "#F0EBE3"),
    /required PoolStack token missing: color\.ember\.subtle/,
  );
});

test("PoolStack: SVG asset paints are checked against the wallet surface", () => {
  const pairs = buildPoolStackAssetPairs(
    [`<svg><rect fill="#D89F62" stroke="#7E2C2A"/></svg>`],
    "#F0EBE3",
  );
  const failures = evaluatePairs(pairs).filter((r) => !r.pass);
  assert.equal(failures.length, 1);
  assert.match(failures[0]!.name, /#D89F62/);
});

test("buildCanonicalPairs: quiet sub-mode adds 3 override pairs", () => {
  const tokens: TokensSubset = {
    color: {
      bg: {
        canvas: color("#000000"),
        surface: color("#111111"),
        elevated: color("#222222"),
        overlay: color("#000000"),
        inverse: color("#FFFFFF"),
      },
      text: {
        primary: color("#FFFFFF"),
        secondary: color("#CCCCCC"),
        tertiary: color("#888888"),
        disabled: color("#444444"),
        inverse: color("#000000"),
      },
      key: {
        default: color("#7E2C2A"),
        glow: color("#000000"),
        deep: color("#000000"),
        muted: color("#000000"),
        line: color("#000000"),
      },
    },
    subMode: { quiet: {} },
  };
  const pairs = buildCanonicalPairs(tokens);
  const quietPairs = pairs.filter((p) => p.name.startsWith("(quiet)"));
  assert.equal(quietPairs.length, 3, "expected 3 quiet override pairs");
});
