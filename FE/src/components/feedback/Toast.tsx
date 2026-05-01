import { StyleSheet, View } from "react-native";
import { palette, semantic, surface } from "../../theme/tokens";
import { textStyles } from "../../theme/typography";
import { Text } from "../ui/Text";
import { elevation } from "../../theme/elevation";
import { space } from "../../theme/spacing";
import type { ToastVariant } from "../../lib/toast";

interface Props {
  variant: ToastVariant;
  message: string;
}

function accentForVariant(v: ToastVariant): string {
  switch (v) {
    case "success":
      return semantic.success.fg;
    case "warning":
      return semantic.warning.fg;
    case "danger":
      return semantic.danger.fg;
    case "info":
    default:
      return semantic.info.fg;
  }
}

export function Toast({ variant, message }: Props) {
  return (
    <View
      accessible
      accessibilityLiveRegion="polite"
      style={[styles.row, { borderLeftColor: accentForVariant(variant) }]}
    >
      <Text style={[textStyles.bodyStrong, { color: palette.ink }]}>{message}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    backgroundColor: surface.card,
    borderRadius: 12,
    borderLeftWidth: 4,
    paddingHorizontal: space[4],
    paddingVertical: space[3],
    marginVertical: space[1],
    ...elevation.raised,
  },
});
