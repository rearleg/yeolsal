// Story 4.3 FE-3 — Stage 2 (foundation + 1 stone layer, "첫 켜").

import Svg, { Path, Rect } from "react-native-svg";
import { tokensV2 } from "@/theme/tokens";

const STROKE_DEFAULT = tokensV2.color.stroke.default.hex;

export function Stage2() {
  return (
    <Svg width={96} height={96} viewBox="0 0 96 96">
      <Path
        d="M 14 82 A 34 6 0 1 0 82 82 A 34 6 0 1 0 14 82"
        fill="none"
        stroke={STROKE_DEFAULT}
        strokeWidth={2}
      />
      <Rect
        x={22}
        y={66}
        width={52}
        height={12}
        rx={3}
        fill="none"
        stroke={STROKE_DEFAULT}
        strokeWidth={2}
      />
    </Svg>
  );
}
