// Elevation tokens for Bento Modular Warm.
// Two-tier shadow system: subtle ambient + soft directional. We render shadows
// via React Native's shadow* props on iOS and `elevation` on Android. Both fall
// back to no-op on web. Hairline border is used for low-contrast separation
// where shadow alone would be invisible (e.g. on raised surface vs page).

import type { ViewStyle } from "react-native";
import { palette } from "./tokens";

const shadowColor = palette.inkDeep;

export const elevation = {
  none: {
    shadowColor: "transparent",
    shadowOpacity: 0,
    shadowRadius: 0,
    shadowOffset: { width: 0, height: 0 },
    elevation: 0
  } satisfies ViewStyle,
  flat: {
    shadowColor,
    shadowOpacity: 0.04,
    shadowRadius: 1,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1
  } satisfies ViewStyle,
  raised: {
    shadowColor,
    shadowOpacity: 0.06,
    shadowRadius: 2,
    shadowOffset: { width: 0, height: 1 },
    elevation: 2
  } satisfies ViewStyle,
  hero: {
    shadowColor,
    shadowOpacity: 0.08,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 8 },
    elevation: 6
  } satisfies ViewStyle,
  pressed: {
    shadowColor,
    shadowOpacity: 0.04,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 2 },
    elevation: 1
  } satisfies ViewStyle
} as const;

export type ElevationKey = keyof typeof elevation;

export const hairline = {
  borderWidth: 1,
  borderColor: palette.border
} satisfies ViewStyle;

export const hairlineStrong = {
  borderWidth: 1,
  borderColor: palette.borderStrong
} satisfies ViewStyle;
