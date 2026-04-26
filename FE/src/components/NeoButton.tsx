import { Pressable, StyleSheet, Text } from "react-native";
import { borders, colors, typography } from "../theme/tokens";

type Props = {
  label: string;
  tone?: "green" | "pink" | "acid" | "white" | "kakao" | "black";
  onPress?: () => void;
  disabled?: boolean;
};

export function NeoButton({ label, tone = "green", onPress, disabled = false }: Props) {
  return (
    <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={[styles.button, styles[tone], disabled && styles.disabled]}>
      <Text style={[styles.label, tone === "black" && styles.labelOnDark]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 48,
    borderColor: colors.black,
    borderWidth: borders.width,
    borderRadius: 0,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 18,
    shadowColor: colors.black,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: 5, height: 5 },
    elevation: 6
  },
  green: { backgroundColor: colors.green },
  pink: { backgroundColor: colors.pink },
  acid: { backgroundColor: colors.greenNeon },
  white: { backgroundColor: colors.white },
  kakao: { backgroundColor: colors.kakao },
  black: { backgroundColor: colors.black },
  disabled: { opacity: 0.55 },
  label: {
    color: colors.ink,
    fontSize: typography.label.fontSize,
    fontWeight: typography.label.fontWeight,
    letterSpacing: typography.label.letterSpacing,
    textTransform: "uppercase"
  },
  labelOnDark: { color: colors.paper }
});
