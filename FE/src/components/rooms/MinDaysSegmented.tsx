import { Pressable, StyleSheet, View } from "react-native";
import { MIN_DAYS_LABELS, MIN_DAYS_OPTIONS, type MinDays } from "../../api/rooms";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Text } from "../ui/Text";

interface Props {
  value: MinDays;
  onChange: (next: MinDays) => void;
  /**
   * Hard floor for the user's choice. The on-device picker hides options
   * below this value so a user choosing their own override on top of a
   * group-wide minimum cannot drop below it. Left undefined for the
   * group-creation case where any whitelist value is permitted.
   */
  minAllowed?: MinDays;
  /** Visually disable the entire control without changing layout. */
  disabled?: boolean;
}

export function MinDaysSegmented({ value, onChange, minAllowed, disabled }: Props) {
  return (
    <View
      style={[styles.container, disabled && styles.containerDisabled]}
      accessibilityRole="radiogroup"
      accessibilityLabel="최소 목표일수 선택"
    >
      {MIN_DAYS_OPTIONS.map((option) => {
        const blocked = minAllowed != null && option < minAllowed;
        const selected = option === value;
        const label = MIN_DAYS_LABELS[option];
        // Help screen-reader users understand why a given option is greyed
        // out — "그룹 최소 기준보다 낮음" matches the visible reason text used
        // elsewhere in the rooms surface.
        const a11yLabel = blocked
          ? `${label} (그룹 최소 기준보다 낮음)`
          : `최소 목표일수 ${label}`;
        return (
          <Pressable
            key={option}
            accessibilityRole="radio"
            accessibilityState={{ selected, disabled: blocked || disabled }}
            accessibilityLabel={a11yLabel}
            disabled={blocked || disabled}
            onPress={() => onChange(option)}
            style={({ pressed }) => [
              styles.pill,
              selected && styles.pillSelected,
              blocked && styles.pillBlocked,
              pressed && !blocked && !disabled && { opacity: 0.85 },
            ]}
          >
            <Text
              variant="bodyStrong"
              color={selected ? palette.paper : blocked ? palette.inkFaint : palette.ink}
            >
              {label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    gap: space[1],
    padding: space[1],
    borderRadius: 12,
    backgroundColor: surface.sunken,
  },
  containerDisabled: {
    opacity: 0.6,
  },
  pill: {
    flex: 1,
    paddingVertical: space[2],
    borderRadius: 10,
    alignItems: "center",
    justifyContent: "center",
  },
  pillSelected: {
    backgroundColor: palette.coral,
  },
  pillBlocked: {
    opacity: 0.5,
  },
});
