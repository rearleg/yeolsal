// Abstract → concrete glyph mapping for the survival packed-type icon field
// (Story 1.5 AC12). The packed-type token `semantic.survival.<STATE>.icon`
// is an abstract metaphor name; the FE renders it through MaterialIcons.
// The BE SVG renderer (Story 7.1) has its own SVG-symbol mapping keyed off
// the same abstract name in tokens.json, so FE and BE stay linked at the
// token layer without sharing glyph assets.

import type { SurvivalState } from "./types";

export const SURVIVAL_ICON_GLYPH: Readonly<Record<SurvivalState, string>> = {
  ACTIVE: "check",
  YELLOW: "warning-amber",
  RED: "pause-circle-outline",
  SPECTATOR: "visibility",
};
