// PoolStack (Story 4.3 AC3, AC4, AC6, AC8).
//
// Renders the room point pool as a 5-stage growing artifact ("stone tower"
// metaphor). Purely presentational — the consumer threads the current
// `total` via the `total` prop (sourced from Story 4.1's useRoomPoints
// hook in WalletScreen). The pool surface is its own — it does NOT
// reference `semantic.survival.*` colors.
//
// Animation contract (AC4):
//   - Cross-fade between stages over `motion.duration.normal` (250ms),
//     opacity only, useNativeDriver: true. Reduced-motion → instant set.
//   - Ember "+N" glow when total increases: opacity sawtooth 0→1 (150ms)
//     / hold (250ms) / 1→0 (250ms), ~650ms total. Reduced-motion → glow
//     is suppressed entirely (no static "+N" because that would clutter
//     the artifact permanently).
//
// Ratchet contract (AC6):
//   - `lastSeenStage` ref monotonically tracks max(prev, current). The
//     displayed stage NEVER goes backwards. This is defence-in-depth on
//     top of Story 4.1's BE-side `RoomPointPoolService.applyDelta`
//     negative-delta guard.
//   - `__DEV__` build warns once per regression event; production builds
//     silently ratchet (no user-facing message).

import { useEffect, useRef, useState, type ReactElement } from "react";
import {
  AccessibilityInfo,
  Animated,
  Easing,
  StyleSheet,
  View,
} from "react-native";
import { POOL_STAGE_THRESHOLDS, stageFor, type PoolStageRange } from "@/theme/pool-stages";
import { tokensV2 } from "@/theme/tokens";
import { useTheme } from "@/theme/useTheme";
import { Text } from "../ui/Text";
import { Stage1 } from "./poolStages/Stage1";
import { Stage2 } from "./poolStages/Stage2";
import { Stage3 } from "./poolStages/Stage3";
import { Stage4 } from "./poolStages/Stage4";
import { Stage5 } from "./poolStages/Stage5";

export interface PoolStackProps {
  readonly total: number;
  /** Optional override for the wrapping View's testID — useful for
   *  WalletScreen mounting in tests. */
  readonly testID?: string;
}

type Stage = PoolStageRange["stage"];

const STAGE_COMPONENTS: Readonly<Record<Stage, () => ReactElement>> = {
  1: Stage1,
  2: Stage2,
  3: Stage3,
  4: Stage4,
  5: Stage5,
};

// Parse the cubic-bezier(a, b, c, d) string from theme.motion.easing.entry.
// tokensV2 ships the literal "cubic-bezier(0, 0, 0.2, 1)" — extract the
// four numbers so we can hand them to Easing.bezier(...).
function parseCubicBezier(literal: string): readonly [number, number, number, number] {
  const match = literal.match(/cubic-bezier\(([^)]+)\)/);
  if (!match) return [0, 0, 0.2, 1];
  const parts = match[1].split(",").map((s) => Number.parseFloat(s.trim()));
  if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) {
    return [0, 0, 0.2, 1];
  }
  return [parts[0], parts[1], parts[2], parts[3]] as const;
}

export function PoolStack({ total, testID }: PoolStackProps) {
  const theme = useTheme();

  // null = not-yet-resolved; gating the animation effect on a settled
  // value prevents a reduce-motion user from seeing the first tween
  // before the async AccessibilityInfo lookup flips the state.
  const [reduceMotion, setReduceMotion] = useState<boolean | null>(null);

  const currentStage = stageFor(total);

  // Ratchet ref + dedup-warn ref. Lazy-init to the current stage so a
  // non-zero mount (e.g., a room with prior pool history loading
  // `total=47`) doesn't trigger a "regression" on the first effect run.
  const lastSeenStageRef = useRef<Stage>(currentStage);
  const prevObservedStageRef = useRef<Stage>(currentStage);

  // The visible stage NEVER goes backwards. Use the ref's current value
  // alongside the just-computed currentStage to derive display.
  const displayStage: Stage = Math.max(currentStage, lastSeenStageRef.current) as Stage;

  // Cross-fade state — when displayStage changes, the previous stage
  // fades out while the next fades in. `prevStage` is the outgoing
  // stage, kept mounted until the fade-out animation completes.
  const [prevStage, setPrevStage] = useState<Stage | null>(null);
  const fadeInOpacity = useRef(new Animated.Value(1)).current;
  const fadeOutOpacity = useRef(new Animated.Value(0)).current;
  const animationRef = useRef<Animated.CompositeAnimation | null>(null);
  const renderedStageRef = useRef<Stage>(displayStage);

  // Delta glow ("+N") state.
  const highWaterTotalRef = useRef<number | null>(Number.isFinite(total) ? total : null);
  const [glowDelta, setGlowDelta] = useState<number | null>(null);
  const glowOpacity = useRef(new Animated.Value(0)).current;
  const glowAnimationRef = useRef<Animated.CompositeAnimation | null>(null);

  // Resolve AccessibilityInfo on mount + listen to changes.
  useEffect(() => {
    let mounted = true;
    let preferenceEventReceived = false;
    AccessibilityInfo.isReduceMotionEnabled()
      .then((value) => {
        if (mounted && !preferenceEventReceived) setReduceMotion(value);
      })
      .catch(() => {
        if (mounted) setReduceMotion(false);
      });
    const subscription = AccessibilityInfo.addEventListener(
      "reduceMotionChanged",
      (value: boolean) => {
        if (mounted) {
          preferenceEventReceived = true;
          setReduceMotion(value);
        }
      },
    );
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, []);

  // Warn after commit so discarded renders do not mutate refs or emit noise.
  useEffect(() => {
    const prevObservedStage = prevObservedStageRef.current;
    if (__DEV__ && currentStage < lastSeenStageRef.current) {
      console.warn(
        `[PoolStack] regression observed: prev=${lastSeenStageRef.current} next=${currentStage} total=${total}`,
      );
    }
    if (currentStage !== prevObservedStage) {
      prevObservedStageRef.current = currentStage;
    }
  }, [currentStage, total]);

  // Update ratchet ref after render.
  useEffect(() => {
    if (currentStage > lastSeenStageRef.current) {
      lastSeenStageRef.current = currentStage;
    }
  }, [currentStage]);

  // Cross-fade effect — driven by displayStage changes.
  useEffect(() => {
    if (reduceMotion == null) return;
    if (reduceMotion) {
      if (animationRef.current) {
        animationRef.current.stop();
        animationRef.current = null;
      }
      renderedStageRef.current = displayStage;
      fadeInOpacity.setValue(1);
      fadeOutOpacity.setValue(0);
      setPrevStage(null);
      return;
    }
    if (renderedStageRef.current === displayStage) return;

    const outgoing = renderedStageRef.current;
    renderedStageRef.current = displayStage;

    if (animationRef.current) {
      animationRef.current.stop();
    }

    // Mount both stages and cross-fade.
    setPrevStage(outgoing);
    fadeInOpacity.setValue(0);
    fadeOutOpacity.setValue(1);

    const duration = theme.motion.duration.normal;
    const bezier = parseCubicBezier(theme.motion.easing.entry);

    const composite = Animated.parallel([
      Animated.timing(fadeOutOpacity, {
        toValue: 0,
        duration,
        easing: Easing.bezier(...bezier),
        useNativeDriver: true,
      }),
      Animated.timing(fadeInOpacity, {
        toValue: 1,
        duration,
        easing: Easing.bezier(...bezier),
        useNativeDriver: true,
      }),
    ]);
    animationRef.current = composite;
    composite.start(({ finished }) => {
      if (animationRef.current !== composite) return;
      animationRef.current = null;
      if (finished) {
        setPrevStage(null);
      }
    });
  }, [displayStage, reduceMotion, fadeInOpacity, fadeOutOpacity, theme]);

  // Delta glow effect — fires whenever `total` increases. Independent of
  // the cross-fade; a delta that doesn't cross a stage boundary still
  // triggers the glow, and a delta that does cross runs both in parallel.
  useEffect(() => {
    const highWaterTotal = highWaterTotalRef.current;
    if (!Number.isFinite(total)) return;
    if (highWaterTotal == null) {
      highWaterTotalRef.current = total;
      return;
    }
    if (total <= highWaterTotal) {
      if (reduceMotion) {
        if (glowAnimationRef.current) {
          glowAnimationRef.current.stop();
          glowAnimationRef.current = null;
        }
        setGlowDelta(null);
        glowOpacity.setValue(0);
      }
      return;
    }
    highWaterTotalRef.current = total;
    if (reduceMotion == null) return;

    // Only react to positive deltas. Negative/zero deltas are ignored
    // (BE invariant — Story 4.1 forbids decrement) and a fresh mount
    // (prev === current) doesn't glow.
    const delta = total - highWaterTotal;

    if (reduceMotion) {
      // AC4 — reduced-motion suppresses the glow entirely.
      setGlowDelta(null);
      glowOpacity.setValue(0);
      return;
    }

    if (glowAnimationRef.current) {
      glowAnimationRef.current.stop();
    }

    setGlowDelta(delta);
    glowOpacity.setValue(0);

    const fast = theme.motion.duration.fast;
    const normal = theme.motion.duration.normal;

    const sequence = Animated.sequence([
      Animated.timing(glowOpacity, {
        toValue: 1,
        duration: fast,
        useNativeDriver: true,
      }),
      Animated.delay(normal),
      Animated.timing(glowOpacity, {
        toValue: 0,
        duration: normal,
        useNativeDriver: true,
      }),
    ]);
    glowAnimationRef.current = sequence;
    sequence.start(({ finished }) => {
      if (glowAnimationRef.current !== sequence) return;
      glowAnimationRef.current = null;
      if (finished) {
        setGlowDelta(null);
      }
    });
  }, [total, reduceMotion, glowOpacity, theme]);

  // Cancel in-flight animation handles on unmount.
  useEffect(() => {
    return () => {
      if (animationRef.current) {
        animationRef.current.stop();
        animationRef.current = null;
      }
      if (glowAnimationRef.current) {
        glowAnimationRef.current.stop();
        glowAnimationRef.current = null;
      }
    };
  }, []);

  const Current = STAGE_COMPONENTS[displayStage];
  const Prev = prevStage != null ? STAGE_COMPONENTS[prevStage] : null;

  const accessibilityLabel = POOL_STAGE_THRESHOLDS[displayStage - 1].label;

  return (
    <View
      accessibilityRole="image"
      accessibilityLabel={accessibilityLabel}
      testID={testID}
      style={styles.container}
    >
      {Prev != null ? (
        <Animated.View
          style={[styles.stageLayer, { opacity: fadeOutOpacity }]}
          pointerEvents="none"
          testID="pool-stack-prev-layer"
        >
          <Prev />
        </Animated.View>
      ) : null}
      <Animated.View
        style={[styles.stageLayer, { opacity: fadeInOpacity }]}
        pointerEvents="none"
        testID="pool-stack-current-layer"
      >
        <Current />
      </Animated.View>
      {glowDelta != null ? (
        <Animated.View
          style={[styles.glow, { opacity: glowOpacity }]}
          pointerEvents="none"
          testID="pool-stack-delta-glow"
        >
          <Text variant="caption" color={tokensV2.color.ember.default.hex}>
            {`+${glowDelta}`}
          </Text>
        </Animated.View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    width: 96,
    height: 96,
    alignSelf: "center",
  },
  stageLayer: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
  },
  glow: {
    position: "absolute",
    top: 0,
    right: 0,
    paddingHorizontal: 4,
    paddingVertical: 2,
  },
});
