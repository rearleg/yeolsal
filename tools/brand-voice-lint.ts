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

const SCAN_EXT = new Set([".ts", ".tsx", ".svg"]);
const SVG_PAINT_ATTRIBUTE_RE = /\b(?:fill|stroke)\s*=\s*(["'])(.*?)\1/g;
const TOKENS_JSON_PATH = resolve(REPO_ROOT, "FE/src/theme/tokens.json");
// BE message bundles are scanned when present; current BE strings are inline.
const BE_MESSAGES_DIR = resolve(REPO_ROOT, "BE/src/main/resources");
const MESSAGES_PROPERTIES_RE = /^messages.*\.properties$/;
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

// Test fixtures legitimately contain banned terms and token examples.
function isTestFile(name: string): boolean {
  return /\.test\.tsx?$/.test(name);
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
    if (isTestFile(dirent.name)) continue;
    const dotIdx = dirent.name.lastIndexOf(".");
    if (dotIdx < 0) continue;
    const ext = dirent.name.slice(dotIdx).toLowerCase();
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

// Contributes nothing when the resources directory has no matching bundle.
function collectMessagesProperties(dir: string = BE_MESSAGES_DIR): FileEntry[] {
  let dirStat;
  try {
    dirStat = statSync(dir);
  } catch {
    return [];
  }
  if (!dirStat.isDirectory()) return [];
  let dirents;
  try {
    dirents = readdirSync(dir, { withFileTypes: true });
  } catch {
    return [];
  }
  const out: FileEntry[] = [];
  for (const dirent of dirents) {
    if (!dirent.isFile()) continue;
    if (!MESSAGES_PROPERTIES_RE.test(dirent.name)) continue;
    out.push(makeEntry(join(dir, dirent.name)));
  }
  return out;
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

type MaskState =
  | "CODE"
  | "LINE_COMMENT"
  | "BLOCK_COMMENT"
  | "SQUOTE"
  | "DQUOTE"
  | "BACKTICK"
  | "REGEX";

interface MaskCursor {
  state: MaskState;
  index: number;
  regexCharClass: boolean;
}

function canStartRegex(previous: string): boolean {
  return previous === "" || /[=(:,!&|?{};\[\]]/.test(previous);
}

function consumeLiteral(content: string, out: string[], cursor: MaskCursor): boolean {
  const ch = content[cursor.index]!;
  const next = content[cursor.index + 1];
  out.push(ch);
  if (ch === "\\" && next !== undefined) {
    out.push(next);
    cursor.index += 2;
    return false;
  }
  if (cursor.state === "REGEX") {
    if (ch === "[") cursor.regexCharClass = true;
    if (ch === "]") cursor.regexCharClass = false;
    if (ch === "/" && !cursor.regexCharClass) cursor.state = "CODE";
  } else {
    const quote = cursor.state === "SQUOTE" ? "'" : cursor.state === "DQUOTE" ? '"' : "`";
    if (ch === quote) cursor.state = "CODE";
  }
  cursor.index += 1;
  return cursor.state === "CODE";
}

function consumeComment(content: string, out: string[], cursor: MaskCursor): void {
  const ch = content[cursor.index]!;
  const next = content[cursor.index + 1];
  if (cursor.state === "LINE_COMMENT" && ch === "\n") {
    cursor.state = "CODE";
    out.push("\n");
    cursor.index += 1;
  } else if (cursor.state === "BLOCK_COMMENT" && ch === "*" && next === "/") {
    cursor.state = "CODE";
    out.push("  ");
    cursor.index += 2;
  } else {
    out.push(ch === "\n" ? "\n" : " ");
    cursor.index += 1;
  }
}

// Comments become spaces while literals remain byte-for-byte aligned.
function maskComments(content: string): string {
  const out: string[] = [];
  const cursor: MaskCursor = { state: "CODE", index: 0, regexCharClass: false };
  let previousSignificant = "";
  while (cursor.index < content.length) {
    const ch = content[cursor.index]!;
    const next = content[cursor.index + 1];
    if (cursor.state !== "CODE") {
      const priorState = cursor.state;
      if (priorState.endsWith("COMMENT")) consumeComment(content, out, cursor);
      else {
        const closed = consumeLiteral(content, out, cursor);
        if (closed) previousSignificant = "L";
      }
      continue;
    }
    if (ch === "/" && next === "/") cursor.state = "LINE_COMMENT";
    else if (ch === "/" && next === "*") cursor.state = "BLOCK_COMMENT";
    else if (ch === "'") cursor.state = "SQUOTE";
    else if (ch === '"') cursor.state = "DQUOTE";
    else if (ch === "`") cursor.state = "BACKTICK";
    else if (ch === "/" && canStartRegex(previousSignificant)) cursor.state = "REGEX";
    if (!cursor.state.endsWith("COMMENT") && cursor.state !== "CODE") {
      out.push(ch);
      cursor.index += 1;
      continue;
    }
    if (cursor.state !== "CODE") continue;
    out.push(ch);
    if (!/\s/.test(ch)) previousSignificant = ch;
    cursor.index += 1;
  }
  return out.join("");
}

function lintFileRule2(file: FileEntry, content: string): Violation[] {
  // Scan a comment-masked copy so AVOID words documented inside comments
  // (e.g. the lexicon-documentation block comments in SpectatorReadOnlyBanner /
  // SelfReviveCTA / GraceBanner) are not flagged, while string-literal user
  // copy stays in scope. Positions are identical to the raw string by
  // construction, so locate() reports the true line:column.
  const masked = maskComments(content);
  const violations: Violation[] = [];
  for (const term of AVOID_LEXICON) {
    let from = 0;
    for (;;) {
      const idx = masked.indexOf(term, from);
      if (idx < 0) break;
      from = idx + term.length;
      const pos = locate(masked, idx);
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

function isPropertiesFile(relPath: string): boolean {
  return relPath.toLowerCase().endsWith(".properties");
}

// Java properties also permit unescaped whitespace as the key/value separator.
function findPropertyValueStart(line: string): number {
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i]!;
    if (ch === "\\") {
      i += 1;
      continue;
    }
    if (ch !== "=" && ch !== ":" && !/\s/.test(ch)) continue;
    let valueStart = i;
    while (valueStart < line.length && /\s/.test(line[valueStart]!)) valueStart += 1;
    if (line[valueStart] === "=" || line[valueStart] === ":") valueStart += 1;
    while (valueStart < line.length && /\s/.test(line[valueStart]!)) valueStart += 1;
    return valueStart;
  }
  return -1;
}

// Message bundles get Rule 2 only. Unicode escapes are not decoded.
function lintPropertiesRule2(file: FileEntry, content: string): Violation[] {
  const violations: Violation[] = [];
  const lines = content.split("\n");
  let lineStart = 0;
  for (const line of lines) {
    const trimmed = line.trimStart();
    if (trimmed.length > 0 && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
      const valueStart = findPropertyValueStart(line);
      if (valueStart >= 0) {
        const value = line.slice(valueStart);
        for (const term of AVOID_LEXICON) {
          let from = 0;
          for (;;) {
            const idx = value.indexOf(term, from);
            if (idx < 0) break;
            from = idx + term.length;
            const pos = locate(content, lineStart + valueStart + idx);
            violations.push({
              rule: 2,
              file: file.relPath,
              line: pos.line,
              column: pos.column,
              message: `Rule 2 (AVOID lexicon): banned word '${term}' present in a message value — prefer the PRD §FR-8.8.x positive-frame equivalents.`,
            });
          }
        }
      }
    }
    lineStart += line.length + 1; // +1 for the "\n" consumed by split
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

// Story 4.3 TOOLS-1 — load the union of every hex value found anywhere
// in tokens.json. Used as the allowlist for .svg-file scanning. Cached
// on first call; tokens.json changes once per design system bump.
let HEX_ALLOWLIST_CACHE: ReadonlySet<string> | null = null;

function collectHexLiterals(node: unknown, out: Set<string>): void {
  if (typeof node === "string") {
    const m = node.match(/^#[0-9A-Fa-f]{3,8}$/);
    if (m) out.add(node.toUpperCase());
    return;
  }
  if (Array.isArray(node)) {
    for (const item of node) collectHexLiterals(item, out);
    return;
  }
  if (node && typeof node === "object") {
    for (const value of Object.values(node as Record<string, unknown>)) {
      collectHexLiterals(value, out);
    }
  }
}

export function loadHexAllowlist(
  path: string = TOKENS_JSON_PATH,
): ReadonlySet<string> {
  if (HEX_ALLOWLIST_CACHE && path === TOKENS_JSON_PATH) {
    return HEX_ALLOWLIST_CACHE;
  }
  const out = new Set<string>();
  try {
    const raw = readFileSync(path, "utf8");
    const data = JSON.parse(raw) as unknown;
    collectHexLiterals(data, out);
  } catch {
    // Allowlist stays empty — the lint will then flag every hex in .svg
    // which surfaces the missing tokens.json early.
  }
  const frozen: ReadonlySet<string> = out;
  if (path === TOKENS_JSON_PATH) HEX_ALLOWLIST_CACHE = frozen;
  return frozen;
}

function lintSvgRule3(
  file: FileEntry,
  content: string,
  allowlist: ReadonlySet<string>,
): Violation[] {
  const violations: Violation[] = [];
  SVG_PAINT_ATTRIBUTE_RE.lastIndex = 0;
  let match;
  while ((match = SVG_PAINT_ATTRIBUTE_RE.exec(content)) !== null) {
    const literal = match[2]!;
    if (literal === "none") continue;
    if (/^#[0-9A-Fa-f]{3,8}$/.test(literal)) {
      const hexLen = literal.length - 1;
      if (
        (hexLen === 3 || hexLen === 4 || hexLen === 6 || hexLen === 8) &&
        allowlist.has(literal.toUpperCase())
      ) {
        continue;
      }
    }
    const literalIndex = match.index + match[0].indexOf(literal);
    const pos = locate(content, literalIndex);
    violations.push({
      rule: 3,
      file: file.relPath,
      line: pos.line,
      column: pos.column,
      message: `Rule 3 (token-literal guard): SVG paint '${literal}' is not an allowlisted FE/src/theme/tokens.json hex — pick an existing token-backed hex.`,
    });
  }
  return violations;
}

function isSvgFile(relPath: string): boolean {
  return relPath.toLowerCase().endsWith(".svg");
}

export interface LintReport {
  readonly hardViolations: readonly Violation[];
  readonly warnings: readonly Violation[];
}

export function lintFiles(files: readonly FileEntry[]): LintReport {
  const hard: Violation[] = [];
  const warnings: Violation[] = [];
  const allowlist = loadHexAllowlist();
  for (const file of files) {
    if (RULE3_FILE_BLOCKLIST.has(file.relPath)) continue;
    let content: string;
    try {
      content = readFileSync(file.absPath, "utf8");
    } catch {
      continue;
    }
    if (isPropertiesFile(file.relPath)) {
      warnings.push(...lintPropertiesRule2(file, content));
      continue;
    }
    if (isSvgFile(file.relPath)) {
      // SVG asset files: only Rule 3 with allowlist applies. Rules 1
      // (survival color packed-type) and 2 (AVOID lexicon) are TS/TSX
      // concerns and don't translate to vector markup.
      warnings.push(...lintSvgRule3(file, content, allowlist));
      continue;
    }
    hard.push(...lintFileRule1(file, content));
    warnings.push(...lintFileRule2(file, content));
    warnings.push(...lintFileRule3(file, content));
  }
  return { hardViolations: hard, warnings };
}

export function lintContent(
  relPath: string,
  content: string,
  options?: { hexAllowlist?: ReadonlySet<string> },
): LintReport {
  if (RULE3_FILE_BLOCKLIST.has(relPath)) {
    return {
      hardViolations: [],
      warnings: lintFileRule2({ absPath: relPath, relPath }, content),
    };
  }
  const file: FileEntry = { absPath: relPath, relPath };
  if (isPropertiesFile(relPath)) {
    return { hardViolations: [], warnings: lintPropertiesRule2(file, content) };
  }
  if (isSvgFile(relPath)) {
    const allowlist = options?.hexAllowlist ?? loadHexAllowlist();
    return {
      hardViolations: [],
      warnings: lintSvgRule3(file, content, allowlist),
    };
  }
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
  maskComments,
  collectMessagesProperties,
  lintPropertiesRule2,
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
  // Explicit paths remain caller-scoped; the default scan includes BE bundles.
  if (argv.length === 0) {
    files.push(...collectMessagesProperties());
  }
  if (files.length === 0) {
    console.warn(
      `[brand-voice-lint] No *.ts / *.tsx / *.svg files found under: ${roots.map((r) => r.label).join(", ")}`,
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
