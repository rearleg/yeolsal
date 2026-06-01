// PoolBar (Story 3.4 AC1 §3 + AC5; Story 4.1 AC8 refactor).
//
// Renders the room point pool fill ratio as a compositor-friendly
// `transform: scaleX` animation (project-context web/performance rule —
// animate transform NOT width).
//
// Story 4.1 AC8 — the inline `/topic/rooms.{id}.points` subscribe + the
// per-frame `qk.meSurvival` invalidate were both moved out of this leaf
// component into the `useRoomPoints(roomId)` domain hook
// (`FE/src/lib/query/hooks/roomPoints.ts`). The parent now owns the
// subscription + dedupe-by-sourceRevivalEventId merge into `qk.roomPoints`
// and passes the latest `total` down as a prop. PoolBar stays purely
// presentational — `total` change drives the fill animation, nothing more.
//
// Lives in `components/revival/` (alongside FriendGiftBadge) — the pool
// is a revival-economy concern that the Wallet surface mounts but other
// revival surfaces (Story 4.x phase-2 promise UI) can reuse.

import { useEffect, useRef, useState } from "react";
import {
  AccessibilityInfo,
  Animated,
  Easing,
  StyleSheet,
  View,
} from "react-native";
import { useTheme } from "../../theme/useTheme";
import { palette } from "../../theme/tokens";

export interface PoolBarProps {
  readonly total: number;
  /** v1 placeholder threshold per Story 3.4 critical note 3. Story 4.3 wires
   *  the real per-room threshold table; until then callers pass a constant. */
  readonly max: number;
}

const ANIMATION_DURATION_MS = 600;

function clampRatio(total: number, max: number): number {
  if (!Number.isFinite(total) || !Number.isFinite(max) || max <= 0) return 0;
  if (total <= 0) return 0;
  if (total >= max) return 1;
  return total / max;
}

export function PoolBar({ total, max }: PoolBarProps) {
  const theme = useTheme();
  // null = not yet resolved; gating the animation effect on a settled
  // value prevents a reduce-motion user from seeing the first 600ms tween
  // before the async AccessibilityInfo lookup flips the state.
  const [reduceMotion, setReduceMotion] = useState<boolean | null>(null);
  const fill = useRef(new Animated.Value(clampRatio(total, max))).current;

  // Track current animation handle so unmount cancels in-flight tweens
  // (RN's Animated otherwise keeps the timer alive past the component).
  const animationRef = useRef<Animated.CompositeAnimation | null>(null);

  useEffect(() => {
    let mounted = true;
    AccessibilityInfo.isReduceMotionEnabled()
      .then((value) => {
        if (mounted) setReduceMotion(value);
      })
      .catch(() => {
        // Native AccessibilityInfo can fail in test rigs / web — treat as
        // motion-enabled (the more visually rich default).
        if (mounted) setReduceMotion(false);
      });
    const subscription = AccessibilityInfo.addEventListener(
      "reduceMotionChanged",
      (value: boolean) => {
        if (mounted) setReduceMotion(value);
      },
    );
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, []);

  // Animate fill on every (total, max) change. Reduced-motion: instant set.
  // Skip until reduceMotion is settled so reduce-motion users never see
  // the first tween.
  useEffect(() => {
    if (reduceMotion == null) return;
    const nextRatio = clampRatio(total, max);
    if (animationRef.current) {
      animationRef.current.stop();
    }
    if (reduceMotion) {
      fill.setValue(nextRatio);
      return;
    }
    // useNativeDriver:false so the StyleSheet's transformOrigin:"left"
    // anchors the scaleX growth to the left edge of the bar. The native
    // driver ignores transformOrigin (web/JS-driver only style) and would
    // animate from the center.
    const handle = Animated.timing(fill, {
      toValue: nextRatio,
      duration: ANIMATION_DURATION_MS,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: false,
    });
    animationRef.current = handle;
    handle.start(() => {
      animationRef.current = null;
    });
  }, [total, max, reduceMotion, fill]);

  // Cancel in-flight animation on unmount to avoid stranded timers.
  useEffect(() => {
    return () => {
      if (animationRef.current) {
        animationRef.current.stop();
        animationRef.current = null;
      }
    };
  }, []);

  const radius = typeof theme.radius.default === "number"
    ? theme.radius.default
    : 12;

  return (
    <View
      style={[styles.track, { borderRadius: radius }]}
      accessibilityRole="progressbar"
      accessibilityLabel={`그룹 포인트 ${total}점`}
    >
      <Animated.View
        style={[
          styles.fill,
          {
            borderRadius: radius,
            transform: [{ scaleX: fill }],
          },
        ]}
        testID="poolbar-fill"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  track: {
    height: 10,
    backgroundColor: palette.surfaceContrast,
    overflow: "hidden",
  },
  fill: {
    width: "100%",
    height: "100%",
    backgroundColor: palette.coralDeep,
    transformOrigin: "left",
  },
});
