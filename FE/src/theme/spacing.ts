// 4-based spacing scale + radius scale for Bento Modular Warm.
// Cells use larger radii (lg/xl) so bento composition feels deliberate.

export const space = {
  px: 1,
  0: 0,
  1: 4,
  2: 8,
  3: 12,
  4: 16,
  5: 20,
  6: 24,
  7: 28,
  8: 32,
  10: 40,
  12: 48,
  14: 56,
  16: 64,
  20: 80
} as const;

export type SpaceKey = keyof typeof space;

export const radius = {
  none: 0,
  xs: 4,
  sm: 8,
  md: 12,
  lg: 20,
  xl: 28,
  pill: 999
} as const;

export type RadiusKey = keyof typeof radius;

export const layout = {
  pageHorizontal: space[5],
  cardGap: space[3],
  sectionGap: space[6],
  headerHeight: 56
} as const;
