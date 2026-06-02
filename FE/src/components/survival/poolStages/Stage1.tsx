// Story 4.3 FE-3 — Stage 1 (foundation only, "토대").
//
// Empty seedling: a single foundation arc near the bottom of the artifact.
// Stroke pulls from `stroke.subtle` (dim, recessed) so the early-stage
// metaphor reads as quiet potential rather than absence.

import Svg, { Path } from "react-native-svg";
import { tokensV2 } from "@/theme/tokens";

const STROKE_DEFAULT = tokensV2.color.stroke.default.hex;

export function Stage1() {
  return (
    <Svg width={96} height={96} viewBox="0 0 96 96">
      <Path
        d="M 16 78 A 32 6 0 1 0 80 78 A 32 6 0 1 0 16 78"
        fill="none"
        stroke={STROKE_DEFAULT}
        strokeWidth={2}
      />
    </Svg>
  );
}
