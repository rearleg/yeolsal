// Story 8.1 AC2 — 5-dot progress row for the onboarding carousel.
// Decorative surface: the load-bearing a11y lives on the footer buttons
// (story OOS #20), so the row is hidden from assistive tech. Dot state
// changes render instantly — no animation — which also satisfies the
// AC11 reduced-motion requirement by construction.

import { StyleSheet, View } from "react-native";
import { useTheme } from "../../theme/useTheme";

const DOT_SIZE = 8;

interface OnboardingDotIndicatorProps {
  readonly total: number;
  /** 1-based index of the active screen. */
  readonly current: number;
}

export function OnboardingDotIndicator({
  total,
  current,
}: OnboardingDotIndicatorProps) {
  const theme = useTheme();
  const filled = theme.color.ember.default.hex;
  const outline = theme.color.stroke.default.hex;
  return (
    <View
      style={styles.row}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      testID="onboarding-dots"
    >
      {Array.from({ length: total }, (_, index) => {
        const isActive = index + 1 === current;
        return (
          <View
            key={index}
            testID={isActive ? "onboarding-dot-active" : "onboarding-dot"}
            style={[
              styles.dot,
              isActive
                ? { backgroundColor: filled }
                : { borderWidth: 1, borderColor: outline },
            ]}
          />
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: "row",
    gap: 8,
    alignItems: "center",
    justifyContent: "center",
  },
  dot: {
    width: DOT_SIZE,
    height: DOT_SIZE,
    borderRadius: DOT_SIZE / 2,
  },
});
