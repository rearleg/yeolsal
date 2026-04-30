import { useEffect, useRef } from "react";
import { Animated, type ViewStyle } from "react-native";
import { radius as radiusTokens } from "../../theme/spacing";
import { palette } from "../../theme/tokens";
import { useReducedMotion } from "../../theme/motion";

interface SkeletonProps {
  width?: ViewStyle["width"];
  height?: number;
  radius?: number;
  style?: ViewStyle;
}

export function Skeleton({ width = "100%", height = 16, radius = radiusTokens.md, style }: SkeletonProps) {
  const reduced = useReducedMotion();
  const opacity = useRef(new Animated.Value(0.5)).current;

  useEffect(() => {
    if (reduced) {
      opacity.setValue(0.7);
      return;
    }
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(opacity, { toValue: 1, duration: 550, useNativeDriver: true }),
        Animated.timing(opacity, { toValue: 0.5, duration: 550, useNativeDriver: true }),
      ])
    );
    loop.start();
    return () => loop.stop();
  }, [opacity, reduced]);

  return (
    <Animated.View
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[
        {
          width,
          height,
          borderRadius: radius,
          backgroundColor: palette.surfaceContrast,
          opacity,
        },
        style,
      ]}
    />
  );
}
