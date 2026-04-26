import { Pressable, StyleSheet, Text } from "react-native";
import { borders, colors } from "../theme/tokens";

type Props = {
  label: string;
  tone?: "green" | "pink" | "acid";
  onPress?: () => void;
  disabled?: boolean;
};

export function NeoButton({ label, tone = "green", onPress, disabled = false }: Props) {
  return (
    <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={[styles.button, styles[tone], disabled && styles.disabled]}>
      <Text style={styles.label}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 48,
    borderColor: colors.ink,
    borderWidth: borders.width,
    borderRadius: 4,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 18,
    shadowColor: colors.ink,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: 5, height: 5 },
    elevation: 6
  },
  green: { backgroundColor: colors.green },
  pink: { backgroundColor: colors.pink },
  acid: { backgroundColor: colors.acid },
  disabled: { opacity: 0.55 },
  label: {
    color: colors.ink,
    fontSize: 16,
    fontWeight: "900"
  }
});
