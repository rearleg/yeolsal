// Story 4.3 FE-3 — Stage 4 (foundation + 3 stone layers, "형상").
//
// Stage 4 is the PRD §3.1 KPI success bar — by the Day-30 metric an active
// room reaches ≥50 pool points here. Strokes shift to `key.default`
// (oxblood) so the artifact reads as a recognizable, intentional form.

import Svg, { Path, Rect } from "react-native-svg";
import { tokensV2 } from "@/theme/tokens";

const STROKE_DEFAULT = tokensV2.color.stroke.default.hex;
const KEY_DEFAULT = tokensV2.color.key.default.hex;

export function Stage4() {
  return (
    <Svg width={96} height={96} viewBox="0 0 96 96">
      <Path
        d="M 12 86 A 36 6 0 1 0 84 86 A 36 6 0 1 0 12 86"
        fill="none"
        stroke={STROKE_DEFAULT}
        strokeWidth={2}
      />
      <Rect
        x={20}
        y={70}
        width={56}
        height={12}
        rx={3}
        fill="none"
        stroke={KEY_DEFAULT}
        strokeWidth={2}
      />
      <Rect
        x={24}
        y={54}
        width={48}
        height={12}
        rx={3}
        fill="none"
        stroke={KEY_DEFAULT}
        strokeWidth={2}
      />
      <Rect
        x={28}
        y={38}
        width={40}
        height={12}
        rx={3}
        fill="none"
        stroke={KEY_DEFAULT}
        strokeWidth={2}
      />
    </Svg>
  );
}
