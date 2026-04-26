import { PropsWithChildren } from "react";
import { StyleSheet, View, ViewStyle } from "react-native";
import { borders, colors } from "../theme/tokens";

type Props = PropsWithChildren<{
  style?: ViewStyle;
  tone?: "paper" | "green" | "pink" | "dark" | "acid" | "white" | "surface";
}>;

export function NeoCard({ children, style, tone = "paper" }: Props) {
  return <View style={[styles.base, styles[tone], style]}>{children}</View>;
}

const styles = StyleSheet.create({
  base: {
    borderColor: colors.black,
    borderWidth: borders.width,
    borderRadius: borders.radius,
    padding: 16,
    shadowColor: colors.black,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: borders.shadowOffset, height: borders.shadowOffset },
    elevation: 8
  },
  paper: { backgroundColor: colors.paper },
  green: { backgroundColor: colors.green },
  pink: { backgroundColor: colors.pink },
  dark: { backgroundColor: colors.black },
  acid: { backgroundColor: colors.greenNeon },
  white: { backgroundColor: colors.white },
  surface: { backgroundColor: colors.surface }
});
