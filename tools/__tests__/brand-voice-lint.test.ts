import { test } from "node:test";
import { strict as assert } from "node:assert";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { collectFiles, lintContent, lintFiles } from "../brand-voice-lint.ts";

const TOOLS_ROOT = resolve(fileURLToPath(import.meta.url), "..", "..");
const REPO_ROOT = resolve(TOOLS_ROOT, "..");

test("Rule 1 PASS: survival.<STATE>.color with sibling <Text>{survival.<STATE>.label}</Text>", () => {
  const source = `
import { useTheme } from "@/theme/useTheme";
export function Chip() {
  const t = useTheme();
  const s = t.semantic.survival.ACTIVE;
  return (
    <View accessibilityLabel={s.label}>
      <Dot style={{ backgroundColor: s.color.hex }} />
      <Text>{s.label}</Text>
    </View>
  );
}
`;
  const r = lintContent("FE/src/components/survival/SurvivalChip.tsx", source);
  assert.equal(r.hardViolations.length, 0, "no hard violations expected");
});

test("Rule 1 FAIL: survival.RED.color read without any label evidence", () => {
  const source = `
export function Bad() {
  const c = survival.RED.color;
  return <Dot style={{ backgroundColor: c.hex }} />;
}
`;
  const r = lintContent("FE/src/components/example/Bad.tsx", source);
  assert.equal(r.hardViolations.length, 1, "exactly one hard violation expected");
  const v = r.hardViolations[0]!;
  assert.equal(v.rule, 1);
  assert.equal(v.file, "FE/src/components/example/Bad.tsx");
  assert.equal(v.line, 3, "line number of the survival.RED.color reference");
  assert.match(v.message, /survival\.RED\.label/);
});

test("Rule 1 PASS: accessibilityLabel attribute satisfies the sibling-label requirement", () => {
  const source = `
export function ChipA11y() {
  return (
    <View accessibilityLabel="활동 중">
      <Dot style={{ backgroundColor: semantic.survival.ACTIVE.color.hex }} />
    </View>
  );
}
`;
  const r = lintContent("FE/src/components/survival/ChipA11y.tsx", source);
  assert.equal(r.hardViolations.length, 0);
});

test("Rule 1 PASS: literal Korean label in JSX subtree satisfies the rule", () => {
  const source = `
export function ChipK() {
  return (
    <View>
      <Dot color={survival.YELLOW.color.hex} />
      <Text>노란 카드</Text>
    </View>
  );
}
`;
  const r = lintContent("FE/src/components/survival/ChipK.tsx", source);
  assert.equal(r.hardViolations.length, 0);
});

test("Rule 2 WARN: AVOID lexicon emits warnings, exit code unchanged (no hard violations)", () => {
  const source = `
export const COPY = {
  bad: "이번 도전 실패",
};
`;
  const r = lintContent("FE/src/copy/example.ts", source);
  assert.equal(r.hardViolations.length, 0);
  const rule2 = r.warnings.filter((v) => v.rule === 2);
  assert.ok(rule2.length >= 1, "expected at least one Rule 2 warning");
  assert.match(rule2[0]!.message, /AVOID lexicon/);
});

test("Rule 3 WARN: hex/rgb/oklch literals emit warnings, exit code unchanged", () => {
  const source = `
export const PALETTE = {
  red:  "#FF0000",
  blue: "rgb(0, 0, 255)",
  ox:   "oklch(42% 0.135 25)",
};
`;
  const r = lintContent("FE/src/styles/bad.ts", source);
  assert.equal(r.hardViolations.length, 0);
  const rule3 = r.warnings.filter((v) => v.rule === 3);
  assert.ok(rule3.length >= 3, `expected >=3 rule3 warnings, got ${rule3.length}`);
});

test("Rule 3 SKIP: tokens.json is allowed to contain raw literals", () => {
  const source = `{"hex": "#FF0000"}`;
  const r = lintContent("FE/src/theme/tokens.json", source);
  const rule3 = r.warnings.filter((v) => v.rule === 3);
  assert.equal(rule3.length, 0, "tokens.json must not flag raw literals");
});

test("Clean file: no violations and no warnings", () => {
  const source = `
import { useTheme } from "@/theme/useTheme";
export function Card() {
  const t = useTheme();
  return <View style={{ padding: t.space["4"] }} />;
}
`;
  const r = lintContent("FE/src/components/ui/Card.tsx", source);
  assert.equal(r.hardViolations.length, 0);
  assert.equal(r.warnings.length, 0);
});

// Story 4.3 TOOLS-1 — Rule 3 extension for .svg files.

test("Rule 3 SVG: allowlisted hex (in tokens.json) does NOT trigger a warning", () => {
  const allowlist = new Set(["#7E2C2A", "#594E48"]);
  const source = `<svg><rect fill="#7E2C2A" stroke="#594E48"/></svg>`;
  const r = lintContent("FE/src/assets/pool/stage-1.svg", source, {
    hexAllowlist: allowlist,
  });
  assert.equal(r.hardViolations.length, 0);
  const rule3 = r.warnings.filter((v) => v.rule === 3);
  assert.equal(rule3.length, 0, "allowlisted hexes must not be flagged");
});

test("Rule 3 SVG: non-allowlisted hex emits a warning per occurrence", () => {
  const allowlist = new Set(["#7E2C2A"]);
  const source = `<svg>
  <rect fill="#FF0000" stroke="#7E2C2A"/>
  <circle fill="#123456"/>
</svg>`;
  const r = lintContent("FE/src/assets/pool/bad.svg", source, {
    hexAllowlist: allowlist,
  });
  assert.equal(r.hardViolations.length, 0, "SVG never produces hard violations");
  const rule3 = r.warnings.filter((v) => v.rule === 3);
  assert.equal(rule3.length, 2, `expected 2 warnings, got ${rule3.length}`);
  assert.match(rule3[0]!.message, /#FF0000/);
  assert.match(rule3[1]!.message, /#123456/);
});

test("Rule 3 SVG: hex matching is case-insensitive against allowlist", () => {
  const allowlist = new Set(["#7E2C2A"]);
  const source = `<svg><rect fill="#7e2c2a"/></svg>`;
  const r = lintContent("FE/src/assets/pool/stage.svg", source, {
    hexAllowlist: allowlist,
  });
  const rule3 = r.warnings.filter((v) => v.rule === 3);
  assert.equal(rule3.length, 0, "lowercase hex must normalize to uppercase");
});

test("Rule 3 SVG: non-allowlisted four-digit hex emits a warning", () => {
  const r = lintContent(
    "FE/src/assets/pool/bad.svg",
    `<svg><rect fill="#F008"/></svg>`,
    { hexAllowlist: new Set<string>() },
  );
  assert.equal(r.warnings.filter((v) => v.rule === 3).length, 1);
});

test("Rule 3 SVG: unsupported CSS paint forms emit warnings", () => {
  const r = lintContent(
    "FE/src/assets/pool/bad.svg",
    `<svg><rect fill="rgb(255, 0, 0)" stroke="red"/></svg>`,
    { hexAllowlist: new Set<string>() },
  );
  assert.equal(r.warnings.filter((v) => v.rule === 3).length, 2);
});

test("Rule 3 SVG: single-quoted paint attributes are scanned", () => {
  const r = lintContent(
    "FE/src/assets/pool/bad.svg",
    `<svg><rect fill='#FF0000' stroke='none'/></svg>`,
    { hexAllowlist: new Set<string>() },
  );
  assert.equal(r.warnings.filter((v) => v.rule === 3).length, 1);
});

test("Rule 1/2: SVG files are exempt — survival.RED.color text inside SVG does NOT hard-fail", () => {
  // SVG files don't host TS/TSX semantics; the rule walker only applies
  // Rule 3 to them. A literal "survival.RED.color" string inside an SVG
  // would be cosmetic markup, not a code reference.
  const source = `<svg><!-- survival.RED.color --></svg>`;
  const r = lintContent("FE/src/assets/pool/stage.svg", source, {
    hexAllowlist: new Set<string>(),
  });
  assert.equal(r.hardViolations.length, 0);
});

test("Rule 3 SVG: recursive scanner discovers and validates the real pool assets", () => {
  const files = collectFiles([
    {
      absPath: resolve(REPO_ROOT, "FE/src/assets/pool"),
      label: "FE/src/assets/pool",
    },
  ]);
  assert.equal(files.length, 5, "all five design-source SVG assets must be discovered");
  const report = lintFiles(files);
  assert.equal(report.hardViolations.length, 0);
  assert.equal(report.warnings.length, 0, "real pool assets must use allowlisted token hexes");
});

test("Rule 1: multiple states in same file — each requires its own label evidence", () => {
  const source = `
export function Multi() {
  return (
    <View>
      <Dot color={survival.ACTIVE.color.hex} />
      <Text>{survival.ACTIVE.label}</Text>
      <Dot color={survival.RED.color.hex} />
    </View>
  );
}
`;
  const r = lintContent("FE/src/components/example/Multi.tsx", source);
  assert.equal(r.hardViolations.length, 1);
  assert.match(r.hardViolations[0]!.message, /survival\.RED\.label/);
});
