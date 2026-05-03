import { MaterialIcons } from "@expo/vector-icons";
import type { BottomTabBarProps } from "@react-navigation/bottom-tabs";
import { useEffect, useRef } from "react";
import { Animated, Pressable, StyleSheet, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { palette, surface, text as textColors } from "../theme/tokens";
import { space } from "../theme/spacing";
import { useReducedMotion } from "../theme/motion";
import { useFriendRequestsQuery } from "../lib/query/hooks/feed";

const ICON: Record<string, keyof typeof MaterialIcons.glyphMap> = {
  today: "today",
  feed: "people",
  rooms: "groups",
  chat: "chat-bubble-outline",
  profile: "account-circle",
};

const LABEL: Record<string, string> = {
  today: "오늘",
  feed: "친구",
  rooms: "그룹",
  chat: "채팅",
  profile: "마이",
};

export function BottomNav({ state, navigation }: BottomTabBarProps) {
  const reduced = useReducedMotion();
  const insets = useSafeAreaInsets();
  const indicator = useRef(new Animated.Value(state.index)).current;
  // Pending friend-request count drives the unread dot on the 친구 tab so
  // the user notices new requests without first opening that tab.
  // React Query dedups across consumers — the friend screen still owns
  // the source-of-truth fetch.
  const friendRequestsQuery = useFriendRequestsQuery();
  const pendingFriendRequests = (friendRequestsQuery.data ?? []).filter(
    (r) => r.status === "PENDING",
  ).length;

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
    <View
      accessibilityRole="tablist"
      style={[styles.footer, { paddingBottom: insets.bottom + space[1] }]}
    >
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
        // Show the unread dot on the 친구 tab whenever there's at least
        // one PENDING friend request the user hasn't acted on. Currently
        // wired only to that tab; chat / room unread is surfaced inside
        // the chat tab itself (per-room NEW badge).
        const showDot = route.name === "feed" && pendingFriendRequests > 0;
        const a11yLabel = showDot
          ? `${label} — 새 친구 요청 ${pendingFriendRequests}건`
          : label;
        return (
          <Pressable
            key={route.key}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            accessibilityLabel={a11yLabel}
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
            <View style={styles.iconWrap}>
              <MaterialIcons
                name={icon}
                size={22}
                color={active ? palette.coralDeep : textColors.tertiary}
              />
              {showDot ? <View style={styles.unreadDot} /> : null}
            </View>
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
  iconWrap: {
    position: "relative",
    alignItems: "center",
    justifyContent: "center",
  },
  unreadDot: {
    position: "absolute",
    top: -2,
    right: -6,
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: palette.coralDeep,
    borderWidth: 2,
    borderColor: surface.card,
  },
  label: {
    color: textColors.tertiary,
    fontSize: 10,
    fontWeight: "700",
    letterSpacing: 0.2,
  },
  activeLabel: { color: palette.coralDeep },
});
