import { test } from "node:test";
import { strict as assert } from "node:assert";
import { lintContent } from "../brand-voice-lint.ts";

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
