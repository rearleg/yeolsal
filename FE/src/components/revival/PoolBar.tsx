// PoolBar (Story 3.4 AC1 §3 + AC5 — D2.bento Wallet "room point pool").
//
// Renders the room point pool fill ratio as a compositor-friendly
// `transform: scaleX` animation (project-context web/performance rule —
// animate transform NOT width). Subscribes to `/topic/rooms.{roomId}.points`
// via the singleton RealtimeClient so live increments (Story 3.1 self-
// revival, Story 3.2 friend-gift) animate the bar without a full screen
// re-mount.
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
import { useQueryClient } from "@tanstack/react-query";
import { getRealtimeClient } from "../../lib/realtime/client";
import { qk } from "../../lib/query/keys";
import { useTheme } from "../../theme/useTheme";
import { palette } from "../../theme/tokens";

export interface PoolBarProps {
  readonly roomId: number;
  readonly total: number;
  /** v1 placeholder threshold per Story 3.4 critical note 3. Story 4.3 wires
   *  the real per-room threshold table; until then callers pass a constant. */
  readonly max: number;
}

interface PointPoolChangePayload {
  readonly roomId: number;
  readonly totalAfter: number;
  readonly lastEventAt?: string;
}

const ANIMATION_DURATION_MS = 600;

function clampRatio(total: number, max: number): number {
  if (!Number.isFinite(total) || !Number.isFinite(max) || max <= 0) return 0;
  if (total <= 0) return 0;
  if (total >= max) return 1;
  return total / max;
}

export function PoolBar({ roomId, total, max }: PoolBarProps) {
  const theme = useTheme();
  const qc = useQueryClient();
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

  // STOMP subscription via the singleton — Story 3.4 AC5 explicitly
  // forbids opening a new WS from inside the leaf component.
  useEffect(() => {
    if (!Number.isFinite(roomId) || roomId <= 0) return;
    const client = getRealtimeClient();
    const sub = client.subscribe(`/topic/rooms.${roomId}.points`, (frame) => {
      try {
        const payload = JSON.parse(frame.body) as PointPoolChangePayload;
        // Defence guard: the topic is room-scoped server-side, but pin
        // the consumer to our roomId so a broker fanout misconfig or a
        // race during roomId change does not spend a BE round-trip on a
        // foreign room.
        if (payload?.roomId !== roomId) return;
        if (typeof payload?.totalAfter === "number") {
          // Invalidate the cross-room aggregation so the headline metric on
          // the Wallet refreshes from the BE. The local `total` prop updates
          // on the next render; the animation hook above handles the tween.
          qc.invalidateQueries({ queryKey: qk.meSurvival });
        }
      } catch {
        // Malformed payload — broker will retry on next event.
      }
    });
    return () => sub.unsubscribe();
  }, [roomId, qc]);

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
