import { type PropsWithChildren } from "react";
import { StyleSheet, View, type ViewStyle } from "react-native";
import { surface } from "../../theme/tokens";

type SurfaceTone = "page" | "raised" | "sunken" | "contrast";

interface SurfaceProps {
  tone?: SurfaceTone;
  style?: ViewStyle | ViewStyle[];
}

const toneBackground: Record<SurfaceTone, string> = {
  page: surface.page,
  raised: surface.raised,
  sunken: surface.sunken,
  contrast: surface.contrast
};

export function Surface({ tone = "page", style, children }: PropsWithChildren<SurfaceProps>) {
  return <View style={[styles.base, { backgroundColor: toneBackground[tone] }, style]}>{children}</View>;
}

const styles = StyleSheet.create({
  base: { flex: 1 }
});
