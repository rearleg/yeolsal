// Story 8.3 AC9 — aso-copy-lint helper tests.
// Uses node:test like the brand-voice + analytics-taxonomy tools tests.
// Imports the tool extensionless so this file adds 0 new TS5097 errors
// (the pre-existing 3 are the accepted Story 8.2 baseline).

import { test } from "node:test";
import { strict as assert } from "node:assert";
import { mkdtempSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { loadCopyRegions, lintRegions, __testing } from "../aso-copy-lint";
import { __testing as brandVoice } from "../brand-voice-lint";

const AVOID = __testing.AVOID_LEXICON;

test("case 1: live docs/aso-copy.md lints clean (0 warnings)", () => {
  const regions = loadCopyRegions(__testing.ASO_DOC_PATH);
  const warnings = lintRegions(regions.kr, regions.en, regions.full, AVOID);
  assert.equal(warnings.length, 0, "the locked storefront copy must be clean");
});

test("case 2: EN region with 'revival ticket' → 1 banned WARN at correct line:column", () => {
  const en = "intro comeback pass\nrevival ticket here";
  const kr = "회생권으로 다시 부를 수 있어요";
  const warnings = lintRegions(kr, en, en, AVOID);
  assert.equal(warnings.length, 1);
  const w = warnings[0]!;
  assert.equal(w.rule, "BANNED_EN");
  assert.equal(w.term, "revival ticket");
  assert.equal(w.line, 2);
  assert.equal(w.column, 1);
});

test("case 3: EN region with 'Second Chance Pass' → 1 banned WARN (case-insensitive)", () => {
  const en = "comeback pass intro\nUse a Second Chance Pass instead";
  const kr = "회생권으로 다시 부를 수 있어요";
  const warnings = lintRegions(kr, en, en, AVOID);
  assert.equal(warnings.length, 1);
  assert.equal(warnings[0]!.rule, "BANNED_EN");
  assert.equal(warnings[0]!.term, "second chance pass");
});

test("case 4: EN region with no 'comeback pass' → 1 missing-required WARN", () => {
  const en = "Just survive together, all the way.";
  const kr = "회생권으로 다시 부를 수 있어요";
  const warnings = lintRegions(kr, en, en, AVOID);
  assert.equal(warnings.length, 1);
  assert.equal(warnings[0]!.rule, "REQUIRED_EN");
});

test("case 5: KR region with '실패' → 1 AVOID-lexicon WARN (imported lexicon is wired)", () => {
  const kr = "회생권 실패 예시";
  const en = "comeback pass here";
  const warnings = lintRegions(kr, en, kr, AVOID);
  assert.equal(warnings.length, 1);
  assert.equal(warnings[0]!.rule, "AVOID_KR");
  assert.equal(warnings[0]!.term, "실패");
});

test("case 6: KR region with no '회생권' → 1 missing-required WARN", () => {
  const kr = "함께 끝까지 가요";
  const en = "comeback pass here";
  const warnings = lintRegions(kr, en, kr, AVOID);
  assert.equal(warnings.length, 1);
  assert.equal(warnings[0]!.rule, "REQUIRED_KR");
});

test("case 7: doc missing the en markers → loadCopyRegions throws (broken setup → exit 1)", () => {
  const dir = mkdtempSync(join(tmpdir(), "yeosal-aso-"));
  const path = join(dir, "aso-copy.md");
  writeFileSync(
    path,
    "<!-- aso:copy:kr:start -->\n회생권\n<!-- aso:copy:kr:end -->\n",
    "utf8",
  );
  assert.throws(() => loadCopyRegions(path), /marker/i);
});

test("case 8: AVOID lexicon parity — lint reuses brand-voice-lint's lexicon (no drift)", () => {
  assert.deepEqual(
    [...__testing.AVOID_LEXICON],
    [...brandVoice.AVOID_LEXICON],
    "lexicons must be equal in content and order",
  );
  assert.equal(__testing.AVOID_LEXICON.length, 8);
  // Same array reference proves literal reuse, not a copy.
  assert.equal(__testing.AVOID_LEXICON, brandVoice.AVOID_LEXICON);
});
