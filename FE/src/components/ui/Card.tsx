import { type PropsWithChildren } from "react";
import { StyleSheet, View, type ViewStyle } from "react-native";
import { palette, roomHues, surface, type RoomAccent } from "../../theme/tokens";
import { elevation, hairline } from "../../theme/elevation";
import { radius, space } from "../../theme/spacing";

export type CardTone = "default" | "raised" | "sunken" | "hero" | "accent" | "outline";
export type CardSize = "sm" | "md" | "lg" | "hero";

interface CardProps {
  tone?: CardTone;
  size?: CardSize;
  accent?: RoomAccent;
  style?: ViewStyle | ViewStyle[];
}

const padding: Record<CardSize, number> = {
  sm: space[3],
  md: space[4],
  lg: space[5],
  hero: space[6]
};

const radiusFor: Record<CardSize, number> = {
  sm: radius.md,
  md: radius.lg,
  lg: radius.lg,
  hero: radius.xl
};

export function Card({ tone = "default", size = "md", accent, style, children }: PropsWithChildren<CardProps>) {
  const accentTokens = accent ? roomHues[accent] : null;
  const toneStyle: ViewStyle = (() => {
    switch (tone) {
      case "raised":
        return { backgroundColor: surface.card, ...elevation.raised };
      case "sunken":
        return { backgroundColor: surface.sunken };
      case "hero":
        return { backgroundColor: surface.card, ...elevation.hero };
      case "accent":
        return {
          backgroundColor: accentTokens?.soft ?? palette.coralSoft,
          borderWidth: 1,
          borderColor: accentTokens?.base ?? palette.coral
        };
      case "outline":
        return { backgroundColor: surface.card, ...hairline };
      case "default":
      default:
        return { backgroundColor: surface.card, ...elevation.flat, ...hairline };
    }
  })();

  return (
    <View
      style={[
        styles.base,
        { padding: padding[size], borderRadius: radiusFor[size] },
        toneStyle,
        style
      ]}
    >
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  base: {
    overflow: "hidden"
  }
});
