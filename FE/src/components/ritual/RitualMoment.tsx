// RitualMoment — Story 1.7 06:00–06:05 KST 5-second sacred wrapper.
//
// Root-navigator overlay that marks the day boundary as ritual (not a
// transactional deadline). Renders nothing outside the 06:00–06:05 KST
// window or after it has already fired today (AsyncStorage idempotency).
// Inside the window: a 5-second cinematic sequence (surface darken →
// ember radial → variant text → fade out). Reduced-motion users get a
// 2-second simplified fade with no ember. Spectator users get a dimmed
// key-color treatment.
//
// Pure animation through stock RN `Animated` API (no Reanimated / Skia
// dep). Token consumption goes through `useTheme()` so the postcard
// sub-mode at the wiring site lights up the cinematic motion / serif
// typography overrides without leaf-component branching.

import { useEffect, useMemo, useRef, useState } from "react";
import {
  AccessibilityInfo,
  Animated,
  StyleSheet,
  View,
  type TextStyle,
} from "react-native";

import { getLastFiredKstDate, setLastFiredKstDate } from "../../lib/ritualStorage";
import { useReducedMotion } from "../../theme/motion";
import { useTheme } from "../../theme/useTheme";
import { Text } from "../ui/Text";
import {
  formatKstCaption,
  isInRitualWindow,
  selectRitualText,
  todayKstYmd,
} from "./ritualWindow";

export interface RitualMomentProps {
  /** Override the current time for tests + the wiring's manual smoke path. */
  readonly now?: Date;
  /**
   * When true, render the dim spectator variant (key.muted ember, lower peak
   * opacity, canvas-only surface). Story 2.1 wires the real detection.
   */
  readonly spectator?: boolean;
  /** Fires once when the animation sequence completes. */
  readonly onComplete?: () => void;
}

type GateState = "checking" | "skip" | "show";

const EMBER_DIAMETER = 360;

export function RitualMoment({
  now,
  spectator = false,
  onComplete,
}: RitualMomentProps) {
  const theme = useTheme();
  const reduced = useReducedMotion();

  // Capture `now` once so the variant + caption stay stable across re-renders.
  const nowRef = useRef<Date>(now ?? new Date());
  const evalNow = nowRef.current;

  const [gate, setGate] = useState<GateState>("checking");

  const surfaceOpacity = useRef(new Animated.Value(0)).current;
  const emberOpacity = useRef(new Animated.Value(0)).current;
  // Reduced-motion path makes the text visible immediately (no opacity ramp).
  const textOpacity = useRef(new Animated.Value(reduced ? 1 : 0)).current;

  const variantText = useMemo(() => selectRitualText(evalNow), [evalNow]);
  const caption = useMemo(() => formatKstCaption(evalNow), [evalNow]);

  // -------- Gate decision (window + idempotency) -----------------------
  useEffect(() => {
    let cancelled = false;

    if (!isInRitualWindow(evalNow)) {
      setGate("skip");
      return;
    }

    void (async () => {
      const last = await getLastFiredKstDate();
      if (cancelled) return;
      const today = todayKstYmd(evalNow);
      if (last === today) {
        setGate("skip");
        return;
      }
      // Fire-and-forget per AC4: a write failure must not break the
      // sequence. We bias toward "ritual happened" to avoid users seeing
      // it twice on the same day.
      setLastFiredKstDate(today).catch(() => undefined);
      setGate("show");
    })();

    return () => {
      cancelled = true;
    };
  }, [evalNow]);

  // -------- Animation sequencing ---------------------------------------
  useEffect(() => {
    if (gate !== "show") return undefined;

    if (reduced) {
      // T+0 announce — the reduced-motion path seeds textOpacity to 1, so
      // the headline is already visible at mount; speaking it immediately
      // matches what the user can see.
      AccessibilityInfo.announceForAccessibility(`${caption}, ${variantText}`);

      // 1s fade-in + 1s display + immediate dismiss = 2s total (AC3).
      Animated.timing(surfaceOpacity, {
        toValue: 1,
        duration: 1000,
        useNativeDriver: true,
      }).start();

      const t = setTimeout(() => {
        onComplete?.();
        setGate("skip");
      }, 2000);
      return () => clearTimeout(t);
    }

    // Standard motion: hold the announce until the 200ms surface fade-in
    // settles (T+0.2s, Story 1.7 FE-1.7). Speaking it at T+0 would beat
    // the pixels onto the screen.
    const announceTimer = setTimeout(() => {
      AccessibilityInfo.announceForAccessibility(`${caption}, ${variantText}`);
    }, 200);

    const emberPeak = spectator ? 0.18 : 0.3;
    const seq = Animated.sequence([
      Animated.timing(surfaceOpacity, {
        toValue: 1,
        duration: 200,
        useNativeDriver: true,
      }),
      Animated.parallel([
        Animated.timing(emberOpacity, {
          toValue: emberPeak,
          duration: 500,
          useNativeDriver: true,
        }),
        Animated.timing(textOpacity, {
          toValue: 1,
          duration: 500,
          useNativeDriver: true,
        }),
      ]),
      Animated.delay(3300),
      Animated.timing(emberOpacity, {
        toValue: 0,
        duration: 500,
        useNativeDriver: true,
      }),
      Animated.timing(surfaceOpacity, {
        toValue: 0,
        duration: 500,
        useNativeDriver: true,
      }),
    ]);

    seq.start(({ finished }) => {
      if (finished) {
        onComplete?.();
        setGate("skip");
      }
    });

    return () => {
      clearTimeout(announceTimer);
      seq.stop();
    };
  }, [
    gate,
    reduced,
    spectator,
    caption,
    variantText,
    onComplete,
    surfaceOpacity,
    emberOpacity,
    textOpacity,
  ]);

  if (gate !== "show") return null;

  const emberColor = spectator ? theme.color.key.muted.hex : theme.color.key.glow.hex;
  const overlayBgColor = spectator
    ? theme.color.bg.canvas.hex
    : theme.color.bg.overlay.hex;

  const serif = theme.typography["display.serif"];
  const bodyLg = theme.typography["body.lg"];

  const headlineStyle: TextStyle = reduced
    ? {
        fontSize: bodyLg.size,
        lineHeight: bodyLg.lineHeight,
        fontWeight: String(bodyLg.weight) as TextStyle["fontWeight"],
      }
    : {
        fontFamily: serif.family,
        fontWeight: String(serif.weight) as TextStyle["fontWeight"],
        fontSize: 36,
        lineHeight: 44,
      };

  return (
    <View
      testID="ritual-root"
      accessibilityViewIsModal
      accessibilityRole="alert"
      // Android does not honor accessibilityViewIsModal; declaring the
      // overlay as accessibility-important + non-hidden gives TalkBack
      // a clear signal that focus belongs inside this subtree (AC6).
      importantForAccessibility="yes"
      accessibilityElementsHidden={false}
      pointerEvents="auto"
      style={[
        StyleSheet.absoluteFill,
        styles.root,
        { backgroundColor: theme.color.bg.canvas.hex },
      ]}
    >
      <Animated.View
        testID="ritual-surface"
        style={[
          StyleSheet.absoluteFill,
          { backgroundColor: overlayBgColor, opacity: surfaceOpacity },
        ]}
      />

      {!reduced && (
        <Animated.View
          testID="ritual-ember"
          style={[
            styles.ember,
            { backgroundColor: emberColor, opacity: emberOpacity },
          ]}
        />
      )}

      <View style={styles.center} pointerEvents="none">
        <Animated.View style={{ opacity: textOpacity }}>
          <Text
            testID="ritual-text"
            variant="display"
            align="center"
            color={theme.color.text.primary.hex}
            style={headlineStyle}
          >
            {variantText}
          </Text>
          <Text
            testID="ritual-caption"
            variant="bodySmall"
            align="center"
            color={theme.color.text.secondary.hex}
            style={styles.caption}
          >
            {caption}
          </Text>
        </Animated.View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    zIndex: 9999,
    elevation: 32,
    justifyContent: "center",
    alignItems: "center",
  },
  ember: {
    position: "absolute",
    width: EMBER_DIAMETER,
    height: EMBER_DIAMETER,
    borderRadius: EMBER_DIAMETER / 2,
    top: "50%",
    left: "50%",
    marginLeft: -EMBER_DIAMETER / 2,
    marginTop: -EMBER_DIAMETER / 2,
  },
  center: {
    paddingHorizontal: 24,
    alignItems: "center",
  },
  caption: {
    marginTop: 8,
  },
});
