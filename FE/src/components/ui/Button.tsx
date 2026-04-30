import { type ReactNode } from "react";
import {
  Pressable,
  StyleSheet,
  Text,
  View,
  type PressableProps,
  type ViewStyle
} from "react-native";
import { palette, semantic, text as textColors } from "../../theme/tokens";
import { elevation } from "../../theme/elevation";
import { textStyles } from "../../theme/typography";
import { radius, space } from "../../theme/spacing";
import { pressScale } from "../../theme/motion";

export type ButtonTone = "primary" | "secondary" | "ghost" | "danger" | "kakao";
export type ButtonSize = "sm" | "md" | "lg";

interface ButtonProps extends Omit<PressableProps, "style" | "children"> {
  label: string;
  tone?: ButtonTone;
  size?: ButtonSize;
  fullWidth?: boolean;
  leadingIcon?: ReactNode;
  trailingIcon?: ReactNode;
  loading?: boolean;
}

interface Tokens {
  background: string;
  pressedBackground: string;
  foreground: string;
  borderColor?: string;
  shadow?: ViewStyle;
}

function tokensForTone(tone: ButtonTone): Tokens {
  switch (tone) {
    case "primary":
      return {
        background: palette.sageDeep,
        pressedBackground: "#3F8556",
        foreground: textColors.onAccent,
        shadow: elevation.raised
      };
    case "secondary":
      return {
        background: palette.surfaceContrast,
        pressedBackground: palette.surfaceSunken,
        foreground: palette.ink,
        borderColor: palette.border
      };
    case "ghost":
      return {
        background: "transparent",
        pressedBackground: palette.surfaceSunken,
        foreground: palette.ink
      };
    case "danger":
      return {
        background: semantic.danger.fg,
        pressedBackground: "#8C2A26",
        foreground: textColors.onAccent,
        shadow: elevation.raised
      };
    case "kakao":
      return {
        background: palette.kakao,
        pressedBackground: "#E7CF00",
        foreground: palette.ink
      };
  }
}

const sizeStyles: Record<ButtonSize, { height: number; paddingHorizontal: number; gap: number; radius: number; fontSize: number }> = {
  sm: { height: 36, paddingHorizontal: space[3], gap: space[1], radius: radius.sm, fontSize: 13 },
  md: { height: 46, paddingHorizontal: space[4], gap: space[2], radius: radius.md, fontSize: 15 },
  lg: { height: 54, paddingHorizontal: space[5], gap: space[2], radius: radius.lg, fontSize: 16 }
};

export function Button({
  label,
  tone = "primary",
  size = "md",
  fullWidth = false,
  leadingIcon,
  trailingIcon,
  loading = false,
  disabled,
  ...rest
}: ButtonProps) {
  const tokens = tokensForTone(tone);
  const sz = sizeStyles[size];
  const isDisabled = disabled || loading;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !!isDisabled, busy: loading }}
      disabled={isDisabled}
      {...rest}
      style={({ pressed }) => [
        styles.base,
        {
          height: sz.height,
          paddingHorizontal: sz.paddingHorizontal,
          borderRadius: sz.radius,
          backgroundColor: pressed ? tokens.pressedBackground : tokens.background,
          borderWidth: tokens.borderColor ? 1 : 0,
          borderColor: tokens.borderColor,
          transform: [{ scale: pressed ? pressScale.active : pressScale.rest }],
          opacity: isDisabled ? 0.55 : 1
        },
        tokens.shadow,
        fullWidth ? { alignSelf: "stretch" } : null
      ]}
    >
      {leadingIcon ? <View style={{ marginRight: sz.gap }}>{leadingIcon}</View> : null}
      <Text
        style={[
          textStyles.bodyStrong,
          {
            fontSize: sz.fontSize,
            color: tokens.foreground,
            letterSpacing: 0.1
          }
        ]}
      >
        {loading ? "잠시만요" : label}
      </Text>
      {trailingIcon ? <View style={{ marginLeft: sz.gap }}>{trailingIcon}</View> : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center"
  }
});
