// Brand-voice + NFR-9.6.1 lint for yeolsal FE source.
//
// Rule 1 (HARD GATE — NFR-9.6.1 packed-type enforcement):
//   For any reference to `survival.<STATE>.color` or
//   `semantic.survival.<STATE>.color` (STATE in ACTIVE/YELLOW/RED/SPECTATOR),
//   the same file must also reference the matching label — either via
//   `survival.<STATE>.label` / `semantic.survival.<STATE>.label`, an
//   `accessibilityLabel=` attribute, or the literal Korean label string.
//   Violation → exit 1.
//
// Rule 2 (WARN — AVOID lexicon, PRD FR-8.8.2):
//   Print occurrences of the 8 banned words in *.ts / *.tsx files.
//   Exit code unchanged (Architecture §4.15 keeps this human-authoritative).
//
// Rule 3 (WARN — design-token literal guard):
//   Print hex / rgb / rgba / oklch literals appearing in FE/src/**/*.ts(x)
//   outside the canonical token file. Exit code unchanged.
//
// Designed for `tsx tools/brand-voice-lint.ts [paths...]`. Defaults to
// scanning FE/src and FE/app relative to the repo root.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

interface ScanRoot {
  readonly absPath: string;
  readonly label: string;
}

interface Violation {
  readonly rule: 1 | 2 | 3;
  readonly file: string;
  readonly line: number;
  readonly column: number;
  readonly message: string;
}

type SurvivalState = "ACTIVE" | "YELLOW" | "RED" | "SPECTATOR";

const STATES: readonly SurvivalState[] = ["ACTIVE", "YELLOW", "RED", "SPECTATOR"];

const SURVIVAL_LABELS: Readonly<Record<SurvivalState, string>> = {
  ACTIVE: "활동 중",
  YELLOW: "노란 카드",
  RED: "빨간 카드",
  SPECTATOR: "관전 중",
};

const AVOID_LEXICON: readonly string[] = [
  "벌금",
  "잃었다",
  "떨어졌다",
  "실패",
  "자책",
  "부담",
  "패배",
  "죄책감",
];

const COLOR_LITERAL_RE =
  /(#[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{2})?)?\b|\brgb\(|\brgba\(|\boklch\()/g;

const HERE = fileURLToPath(import.meta.url);
const REPO_ROOT = resolve(HERE, "..", "..");

const DEFAULT_ROOTS: readonly ScanRoot[] = [
  { absPath: resolve(REPO_ROOT, "FE/src"), label: "FE/src" },
  { absPath: resolve(REPO_ROOT, "FE/app"), label: "FE/app" },
];

const SCAN_EXT = new Set([".ts", ".tsx"]);
const SKIP_DIR_SEGMENTS = new Set([
  "node_modules",
  ".expo",
  "dist",
  "build",
  ".turbo",
  ".next",
]);

interface FileEntry {
  readonly absPath: string;
  readonly relPath: string;
}

export function collectFiles(roots: readonly ScanRoot[]): FileEntry[] {
  const entries: FileEntry[] = [];
  for (const root of roots) {
    let rootStat;
    try {
      rootStat = statSync(root.absPath);
    } catch {
      continue;
    }
    if (rootStat.isFile()) {
      entries.push(makeEntry(root.absPath));
      continue;
    }
    walkDir(root.absPath, entries);
  }
  return entries;
}

function walkDir(dir: string, entries: FileEntry[]): void {
  let dirents;
  try {
    dirents = readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const dirent of dirents) {
    if (SKIP_DIR_SEGMENTS.has(dirent.name)) continue;
    const childAbs = join(dir, dirent.name);
    if (dirent.isDirectory()) {
      walkDir(childAbs, entries);
      continue;
    }
    if (!dirent.isFile()) continue;
    const dotIdx = dirent.name.lastIndexOf(".");
    if (dotIdx < 0) continue;
    const ext = dirent.name.slice(dotIdx);
    if (!SCAN_EXT.has(ext)) continue;
    entries.push(makeEntry(childAbs));
  }
}

function makeEntry(absPath: string): FileEntry {
  return {
    absPath,
    relPath: relative(REPO_ROOT, absPath) || absPath,
  };
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

interface SurvivalReference {
  readonly state: SurvivalState;
  readonly index: number;
  readonly raw: string;
}

function findSurvivalColorReferences(content: string): SurvivalReference[] {
  const re =
    /(?:semantic\s*\.\s*)?survival\s*\.\s*(ACTIVE|YELLOW|RED|SPECTATOR)\s*\.\s*color/g;
  const refs: SurvivalReference[] = [];
  let match;
  while ((match = re.exec(content)) !== null) {
    refs.push({
      state: match[1] as SurvivalState,
      index: match.index,
      raw: match[0],
    });
  }
  return refs;
}

function hasLabelEvidenceForState(content: string, state: SurvivalState): boolean {
  const labelExprRe = new RegExp(
    `(?:semantic\\s*\\.\\s*)?survival\\s*\\.\\s*${state}\\s*\\.\\s*label`,
  );
  if (labelExprRe.test(content)) return true;
  if (content.includes(SURVIVAL_LABELS[state])) return true;
  if (/accessibilityLabel\s*=/.test(content)) return true;
  return false;
}

function lintFileRule1(file: FileEntry, content: string): Violation[] {
  const refs = findSurvivalColorReferences(content);
  if (refs.length === 0) return [];
  const violations: Violation[] = [];
  for (const ref of refs) {
    if (hasLabelEvidenceForState(content, ref.state)) continue;
    const pos = locate(content, ref.index);
    violations.push({
      rule: 1,
      file: file.relPath,
      line: pos.line,
      column: pos.column,
      message:
        `Rule 1 (NFR-9.6.1) violation: '${ref.raw}' is read without a sibling label. ` +
        `Render survival.${ref.state}.label as <Text> (or set accessibilityLabel) in the same return block, ` +
        `or use <SurvivalChip state="${ref.state}" />.`,
    });
  }
  return violations;
}

function lintFileRule2(file: FileEntry, content: string): Violation[] {
  const violations: Violation[] = [];
  for (const term of AVOID_LEXICON) {
    let from = 0;
    for (;;) {
      const idx = content.indexOf(term, from);
      if (idx < 0) break;
      from = idx + term.length;
      const pos = locate(content, idx);
      violations.push({
        rule: 2,
        file: file.relPath,
        line: pos.line,
        column: pos.column,
        message: `Rule 2 (AVOID lexicon): banned word '${term}' present — prefer the PRD §FR-8.8.x positive-frame equivalents.`,
      });
    }
  }
  return violations;
}

function lintFileRule3(file: FileEntry, content: string): Violation[] {
  const violations: Violation[] = [];
  let match;
  COLOR_LITERAL_RE.lastIndex = 0;
  while ((match = COLOR_LITERAL_RE.exec(content)) !== null) {
    const literal = match[0];
    if (literal.startsWith("#")) {
      const hexLen = literal.length - 1;
      if (hexLen !== 3 && hexLen !== 6 && hexLen !== 8) continue;
    }
    const pos = locate(content, match.index);
    violations.push({
      rule: 3,
      file: file.relPath,
      line: pos.line,
      column: pos.column,
      message: `Rule 3 (token-literal guard): raw color literal '${literal}' — move into FE/src/theme/tokens.json and read via useTheme().`,
    });
  }
  return violations;
}

const RULE3_FILE_BLOCKLIST = new Set([
  "FE/src/theme/tokens.json",
]);

export interface LintReport {
  readonly hardViolations: readonly Violation[];
  readonly warnings: readonly Violation[];
}

export function lintFiles(files: readonly FileEntry[]): LintReport {
  const hard: Violation[] = [];
  const warnings: Violation[] = [];
  for (const file of files) {
    if (RULE3_FILE_BLOCKLIST.has(file.relPath)) continue;
    let content: string;
    try {
      content = readFileSync(file.absPath, "utf8");
    } catch {
      continue;
    }
    hard.push(...lintFileRule1(file, content));
    warnings.push(...lintFileRule2(file, content));
    warnings.push(...lintFileRule3(file, content));
  }
  return { hardViolations: hard, warnings };
}

export function lintContent(relPath: string, content: string): LintReport {
  if (RULE3_FILE_BLOCKLIST.has(relPath)) {
    return {
      hardViolations: [],
      warnings: lintFileRule2({ absPath: relPath, relPath }, content),
    };
  }
  const file: FileEntry = { absPath: relPath, relPath };
  return {
    hardViolations: lintFileRule1(file, content),
    warnings: [...lintFileRule2(file, content), ...lintFileRule3(file, content)],
  };
}

export function formatViolation(v: Violation): string {
  const tag = v.rule === 1 ? "[ERR]" : "[WARN]";
  return `${tag} ${v.file}:${v.line}:${v.column} — ${v.message}`;
}

export const __testing = {
  STATES,
  SURVIVAL_LABELS,
  AVOID_LEXICON,
};

function resolveRootsFromArgv(argv: readonly string[]): ScanRoot[] {
  if (argv.length === 0) return [...DEFAULT_ROOTS];
  return argv.map((arg) => {
    const abs = resolve(process.cwd(), arg);
    return { absPath: abs, label: arg };
  });
}

function main(argv: readonly string[]): number {
  const roots = resolveRootsFromArgv(argv);
  const files = collectFiles(roots);
  if (files.length === 0) {
    console.warn(
      `[brand-voice-lint] No *.ts / *.tsx files found under: ${roots.map((r) => r.label).join(", ")}`,
    );
    return 0;
  }
  const report = lintFiles(files);
  for (const v of report.warnings) {
    console.log(formatViolation(v));
  }
  for (const v of report.hardViolations) {
    console.error(formatViolation(v));
  }
  console.log(
    `[brand-voice-lint] scanned ${files.length} file(s): ` +
      `${report.hardViolations.length} HARD violation(s), ` +
      `${report.warnings.length} warning(s).`,
  );
  return report.hardViolations.length === 0 ? 0 : 1;
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
