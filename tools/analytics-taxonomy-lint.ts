// Story 8.5 AC12 — analytics taxonomy lint (WARN-only).
//
// Walks `FE/src`, `FE/app`, `BE/src/main/java` and extracts every event
// name passed to `captureEvent("...")` (FE TypeScript) or
// `analyticsService.capture(_, "...", _)` (BE Java). Cross-references
// each against the locked catalogue parsed from `docs/analytics.md`'s
// ```analytics-events fenced code block. Rogue names print as warnings;
// the exit code stays 0 (warn-only). The only failure mode is the doc
// missing or the fenced block missing — that's a real broken-setup case
// and exits 1.
//
// Shape mirrors `tools/brand-voice-lint.ts`: `collectFiles` walker,
// `SKIP_DIR_SEGMENTS`, per-file regex extraction, formatted-output
// report at the bottom.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = fileURLToPath(import.meta.url);
const REPO_ROOT = resolve(HERE, "..", "..");
const ANALYTICS_DOC_PATH = resolve(REPO_ROOT, "docs/analytics.md");
const ANALYTICS_SOURCE_PATH = resolve(REPO_ROOT, "FE/src/lib/analytics.ts");

const SKIP_DIR_SEGMENTS: ReadonlySet<string> = new Set([
  "node_modules",
  ".expo",
  "dist",
  "build",
  ".turbo",
  ".next",
  ".gradle",
]);

interface ScanRoot {
  readonly absPath: string;
  readonly label: string;
  readonly extensions: ReadonlySet<string>;
}

const DEFAULT_ROOTS: readonly ScanRoot[] = [
  {
    absPath: resolve(REPO_ROOT, "FE/src"),
    label: "FE/src",
    extensions: new Set([".ts", ".tsx"]),
  },
  {
    absPath: resolve(REPO_ROOT, "FE/app"),
    label: "FE/app",
    extensions: new Set([".ts", ".tsx"]),
  },
  {
    absPath: resolve(REPO_ROOT, "BE/src/main/java"),
    label: "BE/src/main/java",
    extensions: new Set([".java"]),
  },
];

interface FileEntry {
  readonly absPath: string;
  readonly relPath: string;
  readonly ext: string;
}

interface Warning {
  readonly file: string;
  readonly line: number;
  readonly column: number;
  readonly eventName: string;
  readonly source: "FE" | "BE";
}

export function collectFiles(roots: readonly ScanRoot[]): FileEntry[] {
  const out: FileEntry[] = [];
  for (const root of roots) {
    let stat;
    try {
      stat = statSync(root.absPath);
    } catch {
      continue;
    }
    if (stat.isFile()) {
      out.push(makeEntry(root.absPath));
      continue;
    }
    walkDir(root.absPath, root.extensions, out);
  }
  return out;
}

function walkDir(
  dir: string,
  extensions: ReadonlySet<string>,
  out: FileEntry[],
): void {
  let dirents;
  try {
    dirents = readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const dirent of dirents) {
    if (SKIP_DIR_SEGMENTS.has(dirent.name)) continue;
    const child = join(dir, dirent.name);
    if (dirent.isDirectory()) {
      walkDir(child, extensions, out);
      continue;
    }
    if (!dirent.isFile()) continue;
    const dotIdx = dirent.name.lastIndexOf(".");
    if (dotIdx < 0) continue;
    const ext = dirent.name.slice(dotIdx).toLowerCase();
    if (!extensions.has(ext)) continue;
    out.push(makeEntry(child));
  }
}

function makeEntry(absPath: string): FileEntry {
  const dotIdx = absPath.lastIndexOf(".");
  const ext = dotIdx >= 0 ? absPath.slice(dotIdx).toLowerCase() : "";
  return {
    absPath,
    relPath: relative(REPO_ROOT, absPath) || absPath,
    ext,
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

/**
 * Parses the locked event catalogue from `docs/analytics.md`'s
 * ```analytics-events fenced code block. Throws if the doc or the
 * fenced block is missing (only failure mode for the lint helper).
 */
export function loadCatalogue(path: string = ANALYTICS_DOC_PATH): readonly string[] {
  let raw: string;
  try {
    raw = readFileSync(path, "utf8");
  } catch (err) {
    throw new Error(
      `[analytics-taxonomy-lint] Cannot read ${path}: ${String(err)}.\n` +
        `  This file is the locked event catalogue (Story 8.5 AC12); ` +
        `the lint helper has no fallback.`,
    );
  }
  const fenceRe = /```analytics-events\s*\n([\s\S]*?)\n```/m;
  const match = fenceRe.exec(raw);
  if (!match) {
    throw new Error(
      `[analytics-taxonomy-lint] ${relative(REPO_ROOT, path)} has no ` +
        "```analytics-events fenced block — the lint helper cannot validate " +
        "rogue events without the doc-side catalogue.",
    );
  }
  const lines = match[1]!
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith("#"));
  return lines;
}

const FE_CAPTURE_RE = /\bcaptureEvent\s*\(\s*["']([^"']+)["']/g;
// BE: `analyticsService.capture("42", "signup.completed", props)` — the
// first arg is the distinctId, the second is the event name. The regex
// captures the second quoted arg.
const BE_CAPTURE_RE =
  /\b[A-Za-z_$][\w$]*\s*\.\s*capture\s*\(\s*[^,]+,\s*["']([^"']+)["']/g;

export function loadTypedCatalogue(
  path: string = ANALYTICS_SOURCE_PATH,
): readonly string[] {
  const raw = readFileSync(path, "utf8");
  const match = /export const ANALYTICS_EVENTS\s*=\s*\[([\s\S]*?)\]\s*as const/m.exec(raw);
  if (!match) {
    throw new Error(
      `[analytics-taxonomy-lint] ${relative(REPO_ROOT, path)} has no ANALYTICS_EVENTS const catalogue.`,
    );
  }
  return [...match[1]!.matchAll(/["']([^"']+)["']/g)].map((entry) => entry[1]!);
}

export function lintContent(
  file: FileEntry,
  content: string,
  catalogue: ReadonlySet<string>,
): Warning[] {
  const warnings: Warning[] = [];
  const isJava = file.ext === ".java";
  const re = isJava ? BE_CAPTURE_RE : FE_CAPTURE_RE;
  re.lastIndex = 0;
  let match;
  while ((match = re.exec(content)) !== null) {
    const eventName = match[1]!;
    if (catalogue.has(eventName)) continue;
    const literalDoubleQuote = '"' + eventName + '"';
    const literalSingleQuote = "'" + eventName + "'";
    const at = (() => {
      const a = content.indexOf(literalDoubleQuote, match.index);
      if (a >= 0) return a;
      return content.indexOf(literalSingleQuote, match.index);
    })();
    const pos = locate(content, at >= 0 ? at : match.index);
    warnings.push({
      file: file.relPath,
      line: pos.line,
      column: pos.column,
      eventName,
      source: isJava ? "BE" : "FE",
    });
  }
  return warnings;
}

export function lintFiles(
  files: readonly FileEntry[],
  catalogue: readonly string[],
): readonly Warning[] {
  const set: ReadonlySet<string> = new Set(catalogue);
  const out: Warning[] = [];
  for (const file of files) {
    let content: string;
    try {
      content = readFileSync(file.absPath, "utf8");
    } catch {
      continue;
    }
    out.push(...lintContent(file, content, set));
  }
  return out;
}

function formatWarning(w: Warning): string {
  return `[WARN] ${w.file}:${w.line}:${w.column} — rogue ${w.source} event '${w.eventName}' is not in docs/analytics.md catalogue. ` +
    "Add it to (a) the §3 table, (b) the ```analytics-events fenced block, " +
    "and (c) ANALYTICS_EVENTS in FE/src/lib/analytics.ts.";
}

function resolveRootsFromArgv(argv: readonly string[]): readonly ScanRoot[] {
  if (argv.length === 0) return [...DEFAULT_ROOTS];
  return argv.map((arg) => {
    const abs = resolve(process.cwd(), arg);
    const isJava = arg.includes("BE/");
    return {
      absPath: abs,
      label: arg,
      extensions: new Set(isJava ? [".java"] : [".ts", ".tsx"]),
    };
  });
}

function main(argv: readonly string[]): number {
  let catalogue: readonly string[];
  let typedCatalogue: readonly string[];
  try {
    catalogue = loadCatalogue();
    typedCatalogue = loadTypedCatalogue();
  } catch (err) {
    console.error(String(err));
    return 1;
  }
  const roots = resolveRootsFromArgv(argv);
  const files = collectFiles(roots);
  if (files.length === 0 || roots.some((root) => !rootHasFiles(root, files))) {
    console.error("[analytics-taxonomy-lint] one or more scan roots are missing or empty.");
    return 1;
  }
  if (!sameCatalogue(catalogue, typedCatalogue)) {
    console.error(
      "[analytics-taxonomy-lint] docs/analytics.md and ANALYTICS_EVENTS differ.",
    );
    return 1;
  }
  const warnings = lintFiles(files, catalogue);
  for (const w of warnings) {
    console.log(formatWarning(w));
  }
  console.log(
    `[analytics-taxonomy-lint] scanned ${files.length} file(s) against ` +
      `${catalogue.length} catalogue event(s): ${warnings.length} warning(s).`,
  );
  return 0;
}

function rootHasFiles(root: ScanRoot, files: readonly FileEntry[]): boolean {
  const prefix = root.absPath.endsWith("/") ? root.absPath : `${root.absPath}/`;
  return files.some(
    (file) => file.absPath === root.absPath || file.absPath.startsWith(prefix),
  );
}

function sameCatalogue(
  documented: readonly string[],
  typed: readonly string[],
): boolean {
  return (
    documented.length === typed.length &&
    documented.every((eventName, index) => typed[index] === eventName)
  );
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
  ANALYTICS_DOC_PATH,
  ANALYTICS_SOURCE_PATH,
  FE_CAPTURE_RE,
  BE_CAPTURE_RE,
  sameCatalogue,
};
