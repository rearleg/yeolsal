// Story 4.3 FE-3 — Stage 3 (foundation + 2 stone layers, "여러 켜").

import Svg, { Path, Rect } from "react-native-svg";
import { tokensV2 } from "@/theme/tokens";

const STROKE_DEFAULT = tokensV2.color.stroke.default.hex;
const KEY_DEFAULT = tokensV2.color.key.default.hex;

export function Stage3() {
  return (
    <Svg width={96} height={96} viewBox="0 0 96 96">
      <Path
        d="M 12 84 A 36 6 0 1 0 84 84 A 36 6 0 1 0 12 84"
        fill="none"
        stroke={STROKE_DEFAULT}
        strokeWidth={2}
      />
      <Rect
        x={20}
        y={68}
        width={56}
        height={12}
        rx={3}
        fill="none"
        stroke={STROKE_DEFAULT}
        strokeWidth={2}
      />
      <Rect
        x={24}
        y={52}
        width={48}
        height={12}
        rx={3}
        fill="none"
        stroke={KEY_DEFAULT}
        strokeWidth={2}
      />
    </Svg>
  );
}
