// Typography for Bento Modular Warm.
// KR primary: Wanted Sans (variable). Latin primary: Inter.
// Until font files are bundled via expo-font, the fontFamily strings here are
// names the system will fall back from gracefully on iOS/Android. Wiring
// expo-font happens in a later phase; styles below are stable so consuming
// components don't need to change when fonts land.

import type { TextStyle } from "react-native";
import { palette } from "./tokens";

export const fontFamilies = {
  sans: "WantedSans",
  sansFallback: "Inter",
  numeric: "Inter",
  system: undefined
} as const;

export const fontWeights = {
  regular: "400",
  medium: "500",
  semibold: "600",
  bold: "700",
  extrabold: "800"
} as const satisfies Record<string, TextStyle["fontWeight"]>;

type Style = Pick<
  TextStyle,
  "fontFamily" | "fontSize" | "fontWeight" | "letterSpacing" | "lineHeight" | "color"
>;

const sansFamily: Style["fontFamily"] = fontFamilies.sans;
const numericFamily: Style["fontFamily"] = fontFamilies.numeric;

// Hero numerals (streak count, todo count) live a touch tighter and bolder.
export const textStyles = {
  display: {
    fontFamily: sansFamily,
    fontSize: 38,
    fontWeight: fontWeights.extrabold,
    letterSpacing: -0.6,
    lineHeight: 44,
    color: palette.ink
  },
  h1: {
    fontFamily: sansFamily,
    fontSize: 28,
    fontWeight: fontWeights.extrabold,
    letterSpacing: -0.4,
    lineHeight: 34,
    color: palette.ink
  },
  h2: {
    fontFamily: sansFamily,
    fontSize: 22,
    fontWeight: fontWeights.bold,
    letterSpacing: -0.2,
    lineHeight: 28,
    color: palette.ink
  },
  h3: {
    fontFamily: sansFamily,
    fontSize: 18,
    fontWeight: fontWeights.bold,
    letterSpacing: -0.1,
    lineHeight: 24,
    color: palette.ink
  },
  title: {
    fontFamily: sansFamily,
    fontSize: 16,
    fontWeight: fontWeights.bold,
    letterSpacing: 0,
    lineHeight: 22,
    color: palette.ink
  },
  body: {
    fontFamily: sansFamily,
    fontSize: 15,
    fontWeight: fontWeights.regular,
    letterSpacing: 0,
    lineHeight: 22,
    color: palette.inkSoft
  },
  bodyStrong: {
    fontFamily: sansFamily,
    fontSize: 15,
    fontWeight: fontWeights.semibold,
    letterSpacing: 0,
    lineHeight: 22,
    color: palette.ink
  },
  bodySmall: {
    fontFamily: sansFamily,
    fontSize: 13,
    fontWeight: fontWeights.regular,
    letterSpacing: 0,
    lineHeight: 18,
    color: palette.inkSoft
  },
  label: {
    fontFamily: sansFamily,
    fontSize: 12,
    fontWeight: fontWeights.semibold,
    letterSpacing: 0.2,
    lineHeight: 16,
    color: palette.inkMute
  },
  caption: {
    fontFamily: sansFamily,
    fontSize: 11,
    fontWeight: fontWeights.medium,
    letterSpacing: 0.1,
    lineHeight: 14,
    color: palette.inkMute
  },
  numericDisplay: {
    fontFamily: numericFamily,
    fontSize: 34,
    fontWeight: fontWeights.extrabold,
    letterSpacing: -0.5,
    lineHeight: 38,
    color: palette.ink
  },
  numericTabular: {
    fontFamily: numericFamily,
    fontSize: 14,
    fontWeight: fontWeights.semibold,
    letterSpacing: 0,
    lineHeight: 18,
    color: palette.ink
  }
} as const satisfies Record<string, Style>;

export type TextVariant = keyof typeof textStyles;
