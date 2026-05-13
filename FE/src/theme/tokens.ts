// Theme exports for 열살방.
//
// Story 1.5 lands the v2 Oxblood Editorial token system in `tokens.json` and
// makes it available through `useTheme()` (see ./useTheme.ts). Every v1
// Bento Modular Warm export below stays in place — it is the bridge so the
// dozens of existing screens importing `palette` / `colors` / `surface` /
// `text` / `semantic` / `roomHues` / `grassRamp` / `spacing` / `borders` /
// `typography` keep compiling unchanged. Downstream stories (1.6, 1.7, 2.1,
// 3.2, 3.4, 4.x, 6.x, 7.x) migrate their screen(s) off the v1 surface onto
// `useTheme()` one at a time; each migration deletes whichever symbols it
// no longer needs from this file.
//
// Until that lands, treat every named export below as TRANSITIONAL v1 —
// per Story 1.5 AC11 readability-exception clause, the v1-equivalent values
// stay rather than blindly inverting fg/bg into the dark v2 palette. New
// code prefers `useTheme()`; if you need a static constant outside a render,
// pull it from `tokensV2` re-exported here.

import tokensJson from "./tokens.json";

// v2 token surface for new code. `useTheme()` is the React entry point;
// `tokensV2` is the static read for non-render contexts (e.g., a
// stylesheet helper) where you need a frozen snapshot rather than the
// sub-mode-resolved value.
export const tokensV2 = tokensJson;

export const palette = {
  inkDeep: "#1B1D22",
  ink: "#2A2D33",
  inkSoft: "#4A4D55",
  inkMute: "#6B6E78",
  inkFaint: "#9CA0A8",

  surface: "#FAF8F5",
  surfaceRaised: "#F4F0E8",
  surfaceSunken: "#F0EBE3",
  surfaceContrast: "#E5DECF",
  border: "#D9D4C9",
  borderStrong: "#BFB7A4",

  paper: "#FFFFFF",

  sage: "#7DC28A",
  sageDeep: "#52A06A",
  sageSoft: "#D6ECDB",

  coral: "#F08C73",
  coralDeep: "#D2603F",
  coralSoft: "#FAE6DC",

  salmon: "#E29A7C",
  salmonDeep: "#C77E5E",
  salmonSoft: "#F4D8C9",

  periwinkle: "#9AA8E0",
  periwinkleDeep: "#7B8FCB",
  periwinkleSoft: "#DEE3F4",

  ochre: "#D5B760",
  ochreDeep: "#B89841",
  ochreSoft: "#F0E2B8",

  successFg: "#3F8556",
  successBg: "#E1F0E5",
  warningFg: "#A87A1F",
  warningBg: "#F7ECCE",
  infoFg: "#5A6FB1",
  infoBg: "#E5EAF7",
  dangerFg: "#A8332E",
  dangerBg: "#F5D5D2",

  kakao: "#FEE500",
  white: "#FFFFFF",
  black: "#0F1014"
} as const;

export type RoomAccent = "sage" | "salmon" | "periwinkle" | "ochre";

export const roomHues: Record<
  RoomAccent,
  { base: string; deep: string; soft: string }
> = {
  sage: { base: palette.sage, deep: palette.sageDeep, soft: palette.sageSoft },
  salmon: { base: palette.salmon, deep: palette.salmonDeep, soft: palette.salmonSoft },
  periwinkle: { base: palette.periwinkle, deep: palette.periwinkleDeep, soft: palette.periwinkleSoft },
  ochre: { base: palette.ochre, deep: palette.ochreDeep, soft: palette.ochreSoft }
} as const;

export const roomAccentOrder: ReadonlyArray<RoomAccent> = ["sage", "salmon", "periwinkle", "ochre"];

export function pickRoomAccent(seed: number | string): RoomAccent {
  const n = typeof seed === "number" ? seed : Array.from(String(seed)).reduce((acc, ch) => acc + ch.charCodeAt(0), 0);
  return roomAccentOrder[Math.abs(n) % roomAccentOrder.length];
}

export const text = {
  primary: palette.ink,
  secondary: palette.inkSoft,
  tertiary: palette.inkMute,
  faint: palette.inkFaint,
  inverse: palette.surface,
  onAccent: palette.surface,
  onAccentSoft: palette.ink
} as const;

export const surface = {
  page: palette.surface,
  card: palette.paper,
  raised: palette.surfaceRaised,
  sunken: palette.surfaceSunken,
  contrast: palette.surfaceContrast,
  border: palette.border,
  borderStrong: palette.borderStrong
} as const;

export const semantic = {
  success: { fg: palette.successFg, bg: palette.successBg },
  warning: { fg: palette.warningFg, bg: palette.warningBg },
  info: { fg: palette.infoFg, bg: palette.infoBg },
  danger: { fg: palette.dangerFg, bg: palette.dangerBg }
} as const;

// 5-bucket grass ramp.
// L0 = goal OR reflection missing → empty cell.
// L1..L4 = goal + reflection present, intensity by completed todo count.
export const grassRamp = {
  l0: palette.surfaceContrast,
  l1: palette.sageSoft,
  l2: "#A8D8B3",
  l3: palette.sage,
  l4: palette.sageDeep
} as const;

// ---------------------------------------------------------------------------
// Legacy named exports — kept stable so existing screens import the new look
// without touching call sites. New code should prefer `palette` / `surface` /
// `text` / `semantic` / `roomHues`.
// ---------------------------------------------------------------------------

export const colors = {
  ink: palette.ink,
  black: palette.inkDeep,
  paper: palette.surface,
  paperDim: palette.surfaceSunken,
  surface: palette.surfaceRaised,
  surfaceLow: palette.surface,
  surfaceHigh: palette.surfaceContrast,
  surfaceHighest: palette.surfaceContrast,
  white: palette.paper,

  green: palette.sage,
  greenDark: palette.sageDeep,
  greenNeon: "#A8D8B3",

  pink: palette.salmon,
  pinkDark: palette.salmonDeep,
  pinkSoft: palette.salmonSoft,

  blue: palette.periwinkleDeep,
  blueSoft: palette.periwinkleSoft,
  blueBright: palette.periwinkle,

  amber: palette.ochreDeep,
  amberSoft: palette.ochreSoft,

  gray: palette.inkMute,
  muted: palette.inkSoft,
  outline: palette.border,
  acid: palette.ochre,
  kakao: palette.kakao,
  error: palette.dangerFg
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32
} as const;

export const borders = {
  width: 1,
  radius: 20,
  shadowOffset: 4
} as const;

export const typography = {
  headline: {
    fontSize: 38,
    fontWeight: "800" as const,
    letterSpacing: -0.5
  },
  title: {
    fontSize: 28,
    fontWeight: "800" as const,
    letterSpacing: -0.3
  },
  label: {
    fontSize: 13,
    fontWeight: "700" as const,
    letterSpacing: 0.4
  }
} as const;
