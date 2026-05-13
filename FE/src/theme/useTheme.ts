// useTheme hook + SubMode React context (Story 1.5 AC7).
//
// Returns a ResolvedTheme: a deep clone of tokens.json (minus the subMode
// block) with the active sub-mode's flat override dictionary applied on top.
// Override keys use dot-paths into the base tree; the resolver walks each
// path, creating intermediate objects when needed, and writes the leaf value.
//
// CRITICAL — UX cross-cutting rule #9 (load-bearing):
//   Leaf components MUST NOT read the sub-mode string. They consume the
//   resolved theme via useTheme(). Only SubModeProvider may set the context
//   value. useSubMode() is exported for the provider/hook plumbing only.

import { createContext, useContext, useMemo } from "react";
import tokensJson from "./tokens.json";

type TokensJson = typeof tokensJson;

export type SubModeId = "editorial" | "bento" | "quiet" | "postcard" | "plate";

export type ResolvedTheme = Omit<TokensJson, "subMode">;

const SUB_MODE_IDS: readonly SubModeId[] = [
  "editorial",
  "bento",
  "quiet",
  "postcard",
  "plate",
];

export function isSubModeId(value: unknown): value is SubModeId {
  return typeof value === "string" && (SUB_MODE_IDS as readonly string[]).includes(value);
}

function deepCloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function applyOverridePath(
  target: Record<string, unknown>,
  path: string,
  value: unknown,
): void {
  const segments = path.split(".");
  if (segments.length === 0) return;
  let node: Record<string, unknown> = target;
  for (let i = 0; i < segments.length - 1; i += 1) {
    const key = segments[i] as string;
    const next = node[key];
    if (typeof next !== "object" || next === null || Array.isArray(next)) {
      const fresh: Record<string, unknown> = {};
      node[key] = fresh;
      node = fresh;
    } else {
      node = next as Record<string, unknown>;
    }
  }
  const leafKey = segments[segments.length - 1] as string;
  node[leafKey] = value;
}

export function resolveTheme(subMode: SubModeId | null): ResolvedTheme {
  const { subMode: _omit, ...base } = tokensJson;
  void _omit;
  const cloned = deepCloneJson(base) as unknown as ResolvedTheme;
  if (subMode === null) return cloned;
  const overrides = tokensJson.subMode[subMode] as Readonly<Record<string, unknown>>;
  for (const path of Object.keys(overrides)) {
    applyOverridePath(cloned as unknown as Record<string, unknown>, path, overrides[path]);
  }
  return cloned;
}

const SubModeContext = createContext<SubModeId | null>(null);

export const SubModeContextProvider = SubModeContext.Provider;

export function useSubMode(): SubModeId | null {
  return useContext(SubModeContext);
}

export function useTheme(): ResolvedTheme {
  const subMode = useSubMode();
  return useMemo(() => resolveTheme(subMode), [subMode]);
}
