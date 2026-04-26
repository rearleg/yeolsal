export const colors = {
  ink: "#1A1C18",
  black: "#000000",
  paper: "#FAFAF2",
  paperDim: "#DADAD3",
  surface: "#EEEEE6",
  surfaceLow: "#F4F4EC",
  surfaceHigh: "#E3E3DB",
  white: "#FFFFFF",
  green: "#22C55E",
  greenDark: "#006E2F",
  greenNeon: "#6BFF8F",
  pink: "#E10080",
  pinkDark: "#B40065",
  pinkSoft: "#FFD9E3",
  gray: "#5E5E5E",
  muted: "#6D7B6C",
  acid: "#6BFF8F",
  kakao: "#FEE500",
  error: "#BA1A1A"
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32
} as const;

export const borders = {
  width: 3,
  radius: 0,
  shadowOffset: 6
} as const;

export const typography = {
  headline: {
    fontSize: 38,
    fontWeight: "900" as const,
    letterSpacing: 0
  },
  title: {
    fontSize: 28,
    fontWeight: "900" as const,
    letterSpacing: 0
  },
  label: {
    fontSize: 13,
    fontWeight: "900" as const,
    letterSpacing: 0.7
  }
} as const;
