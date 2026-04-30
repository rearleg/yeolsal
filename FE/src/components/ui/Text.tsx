import { Text as RNText, type TextProps as RNTextProps, type TextStyle } from "react-native";
import { textStyles, type TextVariant } from "../../theme/typography";

interface TextProps extends RNTextProps {
  variant?: TextVariant;
  color?: string;
  align?: TextStyle["textAlign"];
  weight?: TextStyle["fontWeight"];
  numberOfLines?: number;
}

export function Text({ variant = "body", color, align, weight, style, ...rest }: TextProps) {
  const base = textStyles[variant];
  const composed: TextStyle = {
    ...base,
    ...(color !== undefined && { color }),
    ...(align !== undefined && { textAlign: align }),
    ...(weight !== undefined && { fontWeight: weight })
  };
  return <RNText {...rest} style={[composed, style]} />;
}
