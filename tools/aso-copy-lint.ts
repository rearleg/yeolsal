// Story 8.3 AC3 — ASO copy lint (WARN-only).
//
// Reads docs/aso-copy.md, extracts the KR + EN storefront-copy regions
// delimited by <!-- aso:copy:{kr,en}:{start,end} --> markers, and warns on:
//   - banned EN noun phrases ("revival ticket" / "second chance pass") in EN copy,
//   - a missing required EN phrase ("comeback pass"),
//   - AVOID-lexicon terms (imported from brand-voice-lint) in KR copy,
//   - a missing required KR term ("회생권").
// The exit code stays 0 (warn-only) — human release review (Story 8.4) is the
// hard gate. The ONLY failure mode (exit 1) is a broken setup: the doc missing
// or a region-marker pair absent. This mirrors analytics-taxonomy-lint's
// missing-doc / missing-fence throw.
//
// Shape mirrors tools/analytics-taxonomy-lint.ts: a docs/*.md region reader,
// locate() for file-true line:column, a pure lint function, formatWarning,
// main() warn-only, invokedDirectly IIFE guard, __testing export.

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
// Imported read-only: reusing the published lexicon is what makes the KR check
// the *same* brand-voice check — re-declaring the 8 terms here would drift.
import { __testing as brandVoice } from "./brand-voice-lint";

const HERE = fileURLToPath(import.meta.url);
const REPO_ROOT = resolve(HERE, "..", "..");
const ASO_DOC_PATH = resolve(REPO_ROOT, "docs/aso-copy.md");
const ASO_DOC_REL = "docs/aso-copy.md";

const BANNED_EN: readonly string[] = ["revival ticket", "second chance pass"];
const REQUIRED_EN = "comeback pass";
const REQUIRED_KR = "회생권";
const AVOID_LEXICON: readonly string[] = brandVoice.AVOID_LEXICON;

type AsoRule = "BANNED_EN" | "REQUIRED_EN" | "AVOID_KR" | "REQUIRED_KR";

interface AsoWarning {
  readonly rule: AsoRule;
  readonly line: number;
  readonly column: number;
  readonly term: string;
  readonly message: string;
}

interface CopyRegions {
  readonly full: string;
  readonly kr: string;
  readonly en: string;
}

function locate(content: string, index: number): { line: number; column: number } {
  let line = 1;
  let column = 1;
  for (let i = 0; i < index; i += 1) {
    if (content.charCodeAt(i) === 10) {
      line += 1;
      column = 1;
    } else {
      column += 1;
    }
  }
  return { line, column };
}

function extractRegion(full: string, name: "kr" | "en"): string {
  const startMarker = `<!-- aso:copy:${name}:start -->`;
  const endMarker = `<!-- aso:copy:${name}:end -->`;
  const start = full.indexOf(startMarker);
  const end = full.indexOf(endMarker);
  if (start < 0 || end < 0 || end < start) {
    throw new Error(
      `[aso-copy-lint] ${ASO_DOC_REL} is missing the ${name.toUpperCase()} copy-region ` +
        `marker pair (${startMarker} … ${endMarker}); the lint cannot scan storefront ` +
        "copy without it.",
    );
  }
  return full.slice(start + startMarker.length, end);
}

export function loadCopyRegions(path: string = ASO_DOC_PATH): CopyRegions {
  let full: string;
  try {
    full = readFileSync(path, "utf8");
  } catch (err) {
    throw new Error(
      `[aso-copy-lint] Cannot read ${path}: ${String(err)}. ` +
        "This file is the locked storefront copy (Story 8.3 AC1); the lint has no fallback.",
    );
  }
  return {
    full,
    kr: extractRegion(full, "kr"),
    en: extractRegion(full, "en"),
  };
}

// Map a hit at `index` within `region` to a file-true line:column. When the
// region is a substring of `full` (the live-doc case), positions are reported
// against the whole file; synthetic test strings (region not in `full`) fall
// back to locating within the region itself.
function regionBase(full: string, region: string): { base: string; offset: number } {
  const idx = full.indexOf(region);
  if (idx >= 0) return { base: full, offset: idx };
  return { base: region, offset: 0 };
}

export function lintRegions(
  kr: string,
  en: string,
  full: string,
  avoidLexicon: readonly string[],
): AsoWarning[] {
  const warnings: AsoWarning[] = [];
  const enLower = en.toLowerCase();
  const krBase = regionBase(full, kr);
  const enBase = regionBase(full, en);

  // EN — banned noun phrases (case-insensitive).
  for (const phrase of BANNED_EN) {
    const needle = phrase.toLowerCase();
    let from = enLower.indexOf(needle);
    while (from >= 0) {
      const pos = locate(enBase.base, enBase.offset + from);
      warnings.push({
        rule: "BANNED_EN",
        line: pos.line,
        column: pos.column,
        term: phrase,
        message: `EN storefront copy uses banned phrase '${phrase}'; use '${REQUIRED_EN}' instead.`,
      });
      from = enLower.indexOf(needle, from + needle.length);
    }
  }

  // EN — required phrase must be present.
  if (!enLower.includes(REQUIRED_EN.toLowerCase())) {
    const pos = locate(enBase.base, enBase.offset);
    warnings.push({
      rule: "REQUIRED_EN",
      line: pos.line,
      column: pos.column,
      term: REQUIRED_EN,
      message: `EN storefront copy must use '${REQUIRED_EN}'.`,
    });
  }

  // KR — AVOID lexicon (imported from brand-voice-lint).
  for (const term of avoidLexicon) {
    let from = kr.indexOf(term);
    while (from >= 0) {
      const pos = locate(krBase.base, krBase.offset + from);
      warnings.push({
        rule: "AVOID_KR",
        line: pos.line,
        column: pos.column,
        term,
        message: `KR storefront copy uses AVOID-lexicon term '${term}' (brand voice, Architecture §5.5).`,
      });
      from = kr.indexOf(term, from + term.length);
    }
  }

  // KR — required term must be present.
  if (!kr.includes(REQUIRED_KR)) {
    const pos = locate(krBase.base, krBase.offset);
    warnings.push({
      rule: "REQUIRED_KR",
      line: pos.line,
      column: pos.column,
      term: REQUIRED_KR,
      message: `KR storefront copy must use '${REQUIRED_KR}'.`,
    });
  }

  return warnings;
}

function formatWarning(w: AsoWarning): string {
  return `[WARN] ${ASO_DOC_REL}:${w.line}:${w.column} — ${w.message}`;
}

function main(argv: readonly string[]): number {
  const arg0 = argv[0];
  let regions: CopyRegions;
  try {
    regions = loadCopyRegions(arg0 ? resolve(process.cwd(), arg0) : ASO_DOC_PATH);
  } catch (err) {
    console.error(String(err));
    return 1;
  }
  const warnings = lintRegions(regions.kr, regions.en, regions.full, AVOID_LEXICON);
  for (const w of warnings) {
    console.log(formatWarning(w));
  }
  console.log(
    `[aso-copy-lint] scanned ${ASO_DOC_REL} (KR + EN regions): ${warnings.length} warning(s).`,
  );
  return 0;
}

const invokedDirectly = (() => {
  try {
    const argv1 = process.argv[1];
    if (!argv1) return false;
    return HERE === resolve(argv1);
  } catch {
    return false;
  }
})();

if (invokedDirectly) {
  const code = main(process.argv.slice(2));
  process.exit(code);
}

export const __testing = {
  ASO_DOC_PATH,
  ASO_DOC_REL,
  BANNED_EN,
  REQUIRED_EN,
  REQUIRED_KR,
  AVOID_LEXICON,
  loadCopyRegions,
  lintRegions,
  formatWarning,
};
