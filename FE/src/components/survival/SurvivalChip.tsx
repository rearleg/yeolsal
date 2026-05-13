// SurvivalChip — the ONLY allowed entry point for displaying survival state
// in the FE source tree (Story 1.5 AC6, NFR-9.6.1).
//
// Why this exists: NFR-9.6.1 forbids "color is the sole survival signal".
// The chip composes color + icon + label as a non-splittable View, with
// accessibilityLabel set on the wrapper. Downstream stories import this
// primitive instead of constructing per-screen ad-hoc badges.

import { MaterialIcons } from "@expo/vector-icons";
import { StyleSheet, View } from "react-native";
import { useTheme } from "../../theme/useTheme";
import { Text } from "../ui/Text";
import { SURVIVAL_ICON_GLYPH } from "./iconMap";
import type { SurvivalState } from "./types";

export interface SurvivalChipProps {
  readonly state: SurvivalState;
}

const DOT_SIZE = 8;
const ICON_SIZE = 14;
const GAP = 6;

export function SurvivalChip({ state }: SurvivalChipProps) {
  const theme = useTheme();
  const survival = theme.semantic.survival[state];
  const dotColor = survival.color.hex;
  const label = survival.label;
  const glyph = SURVIVAL_ICON_GLYPH[state];

  return (
    <View
      style={styles.row}
      accessibilityLabel={label}
      accessibilityRole="text"
      testID={`survival-chip-${state}`}
    >
      <View
        style={[styles.dot, { backgroundColor: dotColor }]}
        testID={`survival-chip-${state}-dot`}
      />
      <MaterialIcons
        name={glyph as never}
        size={ICON_SIZE}
        color={dotColor}
        testID={`survival-chip-${state}-icon`}
      />
      <Text testID={`survival-chip-${state}-label`}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: GAP,
  },
  dot: {
    width: DOT_SIZE,
    height: DOT_SIZE,
    borderRadius: DOT_SIZE / 2,
  },
});
