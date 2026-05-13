// WCAG 2.2 AA contrast verifier for the v2 Oxblood Editorial palette.
//
// Reads FE/src/theme/tokens.json, computes relative-luminance contrast ratios
// (per WCAG 2.x § 1.4.3) for the canonical text-on-surface pairs (and the
// quiet sub-mode override pairs), and exits non-zero on any failure.
//
// The hex value already in tokens.json is the RN-runtime representation; the
// oklch is the authored source. The contrast check uses hex (the value
// surfaces actually paint with) as the truth.

import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = fileURLToPath(import.meta.url);
const REPO_ROOT = resolve(dirname(HERE), "..");
const DEFAULT_TOKENS_PATH = resolve(REPO_ROOT, "FE/src/theme/tokens.json");

export interface ColorValue {
  readonly oklch: string;
  readonly hex: string;
}

export interface TokensSubset {
  readonly color: {
    readonly bg: Record<string, ColorValue>;
    readonly text: Record<string, ColorValue>;
    readonly key: Record<string, ColorValue>;
  };
  readonly subMode: {
    readonly quiet: Record<string, ColorValue | unknown>;
  };
}

export interface PairSpec {
  readonly name: string;
  readonly fg: ColorValue;
  readonly bg: ColorValue;
  readonly minRatio: number;
  readonly note?: string;
}

export interface PairResult extends PairSpec {
  readonly ratio: number;
  readonly pass: boolean;
}

const RGB_FROM_HEX_CACHE = new Map<string, readonly [number, number, number]>();

function rgbFromHex(hex: string): readonly [number, number, number] {
  const cached = RGB_FROM_HEX_CACHE.get(hex);
  if (cached) return cached;
  let body = hex.replace(/^#/, "");
  if (body.length === 3) {
    body = body
      .split("")
      .map((c) => c + c)
      .join("");
  }
  if (body.length !== 6 && body.length !== 8) {
    throw new Error(`contrast-check: unsupported hex length: ${hex}`);
  }
  const r = parseInt(body.slice(0, 2), 16) / 255;
  const g = parseInt(body.slice(2, 4), 16) / 255;
  const b = parseInt(body.slice(4, 6), 16) / 255;
  const tuple: readonly [number, number, number] = [r, g, b];
  RGB_FROM_HEX_CACHE.set(hex, tuple);
  return tuple;
}

function srgbToLinear(channel: number): number {
  return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
}

export function relativeLuminance(hex: string): number {
  const [r, g, b] = rgbFromHex(hex);
  const lr = srgbToLinear(r);
  const lg = srgbToLinear(g);
  const lb = srgbToLinear(b);
  return 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
}

export function contrastRatio(hexA: string, hexB: string): number {
  const la = relativeLuminance(hexA);
  const lb = relativeLuminance(hexB);
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

function isColorValue(v: unknown): v is ColorValue {
  if (!v || typeof v !== "object") return false;
  const o = v as Record<string, unknown>;
  return typeof o.oklch === "string" && typeof o.hex === "string";
}

export function loadTokens(path: string = DEFAULT_TOKENS_PATH): TokensSubset {
  const raw = readFileSync(path, "utf8");
  const data = JSON.parse(raw) as TokensSubset;
  return data;
}

function applyQuietOverride(base: ColorValue, override: unknown): ColorValue {
  return isColorValue(override) ? override : base;
}

const AA_NORMAL_TEXT = 4.5;
const AA_LARGE_TEXT = 3.0;

export function buildCanonicalPairs(tokens: TokensSubset): PairSpec[] {
  const text = tokens.color.text;
  const bg = tokens.color.bg;
  const key = tokens.color.key;

  const pairs: PairSpec[] = [
    {
      name: "text.primary on bg.canvas (body)",
      fg: text.primary!,
      bg: bg.canvas!,
      minRatio: AA_NORMAL_TEXT,
    },
    {
      name: "text.primary on bg.surface (body)",
      fg: text.primary!,
      bg: bg.surface!,
      minRatio: AA_NORMAL_TEXT,
    },
    {
      name: "text.primary on bg.elevated (body)",
      fg: text.primary!,
      bg: bg.elevated!,
      minRatio: AA_NORMAL_TEXT,
    },
    {
      name: "text.secondary on bg.canvas (body)",
      fg: text.secondary!,
      bg: bg.canvas!,
      minRatio: AA_NORMAL_TEXT,
    },
    {
      name: "text.primary on key.default (CTA fill, body)",
      fg: text.primary!,
      bg: key.default!,
      minRatio: AA_NORMAL_TEXT,
    },
    {
      name: "text.inverse on bg.inverse (Final-3 paper sheet, body)",
      fg: text.inverse!,
      bg: bg.inverse!,
      minRatio: AA_NORMAL_TEXT,
    },
    {
      name: "text.tertiary on bg.canvas (caption — large/bold target)",
      fg: text.tertiary!,
      bg: bg.canvas!,
      minRatio: AA_LARGE_TEXT,
      note: "Scoped to caption >= 18pt or bold >= 14pt per AC9.",
    },
  ];

  const quietOverride = tokens.subMode.quiet;
  const quietBgCanvas = applyQuietOverride(bg.canvas!, quietOverride["color.bg.canvas"]);
  const quietBgSurface = applyQuietOverride(bg.surface!, quietOverride["color.bg.surface"]);
  const quietTextPrimary = applyQuietOverride(
    text.primary!,
    quietOverride["color.text.primary"],
  );
  const quietTextSecondary = applyQuietOverride(
    text.secondary!,
    quietOverride["color.text.secondary"],
  );

  pairs.push(
    {
      name: "(quiet) text.primary on bg.canvas (body)",
      fg: quietTextPrimary,
      bg: quietBgCanvas,
      minRatio: AA_NORMAL_TEXT,
    },
    {
      name: "(quiet) text.primary on bg.surface (body)",
      fg: quietTextPrimary,
      bg: quietBgSurface,
      minRatio: AA_NORMAL_TEXT,
    },
    {
      name: "(quiet) text.secondary on bg.canvas (body)",
      fg: quietTextSecondary,
      bg: quietBgCanvas,
      minRatio: AA_NORMAL_TEXT,
    },
  );

  return pairs;
}

export function evaluatePairs(pairs: readonly PairSpec[]): PairResult[] {
  return pairs.map((p) => {
    const ratio = contrastRatio(p.fg.hex, p.bg.hex);
    return { ...p, ratio, pass: ratio >= p.minRatio };
  });
}

export function formatResult(r: PairResult): string {
  const status = r.pass ? "PASS" : "FAIL";
  const r2 = r.ratio.toFixed(2);
  const min = r.minRatio.toFixed(2);
  const head = `[${status}] ${r.name}: ${r.fg.hex} on ${r.bg.hex} = ${r2}:1 (min ${min}:1)`;
  return r.note ? `${head} — ${r.note}` : head;
}

function main(argv: readonly string[]): number {
  const path = argv[0] ?? DEFAULT_TOKENS_PATH;
  const tokens = loadTokens(path);
  const pairs = buildCanonicalPairs(tokens);
  const results = evaluatePairs(pairs);
  const failures: PairResult[] = [];
  for (const r of results) {
    const line = formatResult(r);
    if (r.pass) {
      console.log(line);
    } else {
      console.error(line);
      failures.push(r);
    }
  }
  console.log(
    `[contrast-check] evaluated ${results.length} pair(s): ` +
      `${results.length - failures.length} pass, ${failures.length} fail.`,
  );
  return failures.length === 0 ? 0 : 1;
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
