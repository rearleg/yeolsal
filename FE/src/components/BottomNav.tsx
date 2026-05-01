import { MaterialIcons } from "@expo/vector-icons";
import type { BottomTabBarProps } from "@react-navigation/bottom-tabs";
import { useEffect, useRef } from "react";
import { Animated, Pressable, StyleSheet, Text, View } from "react-native";
import { palette, surface, text as textColors } from "../theme/tokens";
import { space } from "../theme/spacing";
import { useReducedMotion } from "../theme/motion";

const ICON: Record<string, keyof typeof MaterialIcons.glyphMap> = {
  today: "today",
  feed: "people",
  rooms: "groups",
  monthly: "bar-chart",
  profile: "account-circle",
};

const LABEL: Record<string, string> = {
  today: "오늘",
  feed: "친구",
  rooms: "그룹",
  monthly: "기록",
  profile: "마이",
};

export function BottomNav({ state, navigation }: BottomTabBarProps) {
  const reduced = useReducedMotion();
  const indicator = useRef(new Animated.Value(state.index)).current;

  useEffect(() => {
    if (reduced) {
      indicator.setValue(state.index);
      return;
    }
    Animated.timing(indicator, {
      toValue: state.index,
      duration: 220,
      useNativeDriver: false,
    }).start();
  }, [state.index, indicator, reduced]);

  const tabCount = state.routes.length;
  const tabWidthPct = 100 / tabCount;
  const left = indicator.interpolate({
    inputRange: state.routes.map((_, i) => i),
    outputRange: state.routes.map((_, i) => `${i * tabWidthPct}%`),
  });

  return (
    <View accessibilityRole="tablist" style={styles.footer}>
      <Animated.View
        pointerEvents="none"
        style={[styles.indicatorWrap, { left, width: `${tabWidthPct}%` }]}
      >
        <View style={styles.dot} />
      </Animated.View>
      {state.routes.map((route, i) => {
        const active = state.index === i;
        const label = LABEL[route.name] ?? route.name;
        const icon = ICON[route.name] ?? "circle";
        return (
          <Pressable
            key={route.key}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            accessibilityLabel={label}
            onPress={() => {
              const event = navigation.emit({
                type: "tabPress",
                target: route.key,
                canPreventDefault: true,
              });
              if (!active && !event.defaultPrevented) {
                navigation.navigate(route.name);
              }
            }}
            style={({ pressed }) => [styles.item, pressed && styles.itemPressed]}
            hitSlop={8}
          >
            <MaterialIcons
              name={icon}
              size={22}
              color={active ? palette.coralDeep : textColors.tertiary}
            />
            <Text style={[styles.label, active && styles.activeLabel]}>{label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  footer: {
    minHeight: 72,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-around",
    backgroundColor: surface.card,
    borderTopWidth: 1,
    borderColor: surface.border,
    paddingHorizontal: space[2],
    paddingTop: space[2],
    paddingBottom: space[1],
  },
  indicatorWrap: {
    position: "absolute",
    top: 0,
    height: 3,
    alignItems: "center",
    justifyContent: "center",
  },
  dot: {
    width: 24,
    height: 3,
    borderRadius: 2,
    backgroundColor: palette.coralDeep,
  },
  item: {
    flex: 1,
    minWidth: 56,
    minHeight: 48,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: space[1],
    paddingVertical: space[1],
    gap: 2,
  },
  itemPressed: {
    opacity: 0.7,
  },
  label: {
    color: textColors.tertiary,
    fontSize: 10,
    fontWeight: "600",
    letterSpacing: 0.2,
  },
  activeLabel: { color: palette.coralDeep, fontWeight: "700" },
});
