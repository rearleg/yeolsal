// Motion tokens for Bento Modular Warm.
// Spring config tuned for subtle, "settled" press feedback on bento cells.
// All durations are in ms. Easing uses the standard cubic-bezier shape.
// Components MUST gate animations behind useReducedMotion() so accessibility
// settings actually disable motion instead of just shortening it.

import { useEffect, useState } from "react";
import { AccessibilityInfo } from "react-native";

export const durations = {
  instant: 0,
  fast: 140,
  normal: 220,
  slow: 360
} as const;

export type DurationKey = keyof typeof durations;

// Cubic bezier control points. RN's Animated API takes a custom easing
// function; consumers can pass these into Easing.bezier(...).
export const easings = {
  standard: [0.2, 0.8, 0.2, 1] as const,
  accelerate: [0.4, 0, 1, 1] as const,
  decelerate: [0, 0, 0.2, 1] as const
};

export const springs = {
  gentle: { stiffness: 240, damping: 24, mass: 1.0 },
  snappy: { stiffness: 320, damping: 22, mass: 0.8 },
  press: { stiffness: 420, damping: 28, mass: 0.6 }
} as const;

export type SpringKey = keyof typeof springs;

export const pressScale = {
  active: 0.98,
  rest: 1.0
} as const;

export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);

  useEffect(() => {
    let cancelled = false;
    AccessibilityInfo.isReduceMotionEnabled().then((value) => {
      if (!cancelled) {
        setReduced(value);
      }
    });
    const sub = AccessibilityInfo.addEventListener("reduceMotionChanged", setReduced);
    return () => {
      cancelled = true;
      sub.remove();
    };
  }, []);

  return reduced;
}
