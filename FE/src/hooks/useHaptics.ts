import * as Haptics from "expo-haptics";
import { useCallback } from "react";
import { useReducedMotion } from "../theme/motion";

export type HapticKind = "light" | "medium" | "success" | "warning" | "error";

export function useHaptic() {
  const reduced = useReducedMotion();
  return useCallback(
    (kind: HapticKind) => {
      if (reduced) return;
      switch (kind) {
        case "light":
          void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
          return;
        case "medium":
          void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
          return;
        case "success":
          void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
          return;
        case "warning":
          void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
          return;
        case "error":
          void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
          return;
      }
    },
    [reduced],
  );
}
