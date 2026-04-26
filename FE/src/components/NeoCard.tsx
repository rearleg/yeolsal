import { PropsWithChildren } from "react";
import { StyleSheet, View, ViewStyle } from "react-native";
import { borders, colors } from "../theme/tokens";

type Props = PropsWithChildren<{
  style?: ViewStyle;
  tone?: "paper" | "green" | "pink" | "dark" | "acid";
}>;

export function NeoCard({ children, style, tone = "paper" }: Props) {
  return <View style={[styles.base, styles[tone], style]}>{children}</View>;
}

const styles = StyleSheet.create({
  base: {
    borderColor: colors.ink,
    borderWidth: borders.width,
    borderRadius: borders.radius,
    padding: 16,
    shadowColor: colors.ink,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: borders.shadowOffset, height: borders.shadowOffset },
    elevation: 8
  },
  paper: { backgroundColor: colors.paper },
  green: { backgroundColor: colors.green },
  pink: { backgroundColor: colors.pink },
  dark: { backgroundColor: colors.ink },
  acid: { backgroundColor: colors.acid }
});
