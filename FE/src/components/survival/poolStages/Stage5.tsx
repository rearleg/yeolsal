// Story 4.3 FE-3 — Stage 5 (foundation + 3 stone layers + keystone, "완성").
//
// Stage 5 is the keystone — the 2× over-achievement mark (≥100 pool points)
// that should feel meaningfully different from "success" (stage 4).
//
// AC5 contrast note: `color.ember.default` (#D89F62) measures only 1.95:1
// against `surface.sunken` (#F0EBE3), below the AC5 ≥3:1 requirement. Per
// the TOOLS-2 escape hatch, the keystone uses `color.ember.subtle`
// (#A48064 — 3.03:1 against #F0EBE3) which stays in the same ember
// family while passing the WCAG 2.2 graphics threshold. Flagged in the
// Dev Agent Record for a future design-team revisit of the v2 ember
// ramp; raise via bmad-correct-course if the design team picks a new
// ember.default tone.

import Svg, { Circle, Path, Rect } from "react-native-svg";
import { tokensV2 } from "@/theme/tokens";

const STROKE_DEFAULT = tokensV2.color.stroke.default.hex;
const KEY_DEFAULT = tokensV2.color.key.default.hex;
const EMBER_KEYSTONE = tokensV2.color.ember.subtle.hex;

export function Stage5() {
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
      <Circle
        cx={48}
        cy={24}
        r={9}
        fill={EMBER_KEYSTONE}
        stroke={KEY_DEFAULT}
        strokeWidth={2}
      />
    </Svg>
  );
}
