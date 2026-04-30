import { MaterialIcons } from "@expo/vector-icons";
import { router, usePathname } from "expo-router";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { palette, surface, text as textColors } from "../theme/tokens";
import { space } from "../theme/spacing";

type Tab = {
  label: string;
  icon: keyof typeof MaterialIcons.glyphMap;
  href: "/today" | "/feed" | "/monthly" | "/profile";
  match: string[];
};

const tabs: Tab[] = [
  { label: "오늘", icon: "today", href: "/today", match: ["/today"] },
  { label: "친구", icon: "groups", href: "/feed", match: ["/feed", "/friend-profile"] },
  { label: "기록", icon: "bar-chart", href: "/monthly", match: ["/monthly"] },
  { label: "마이", icon: "account-circle", href: "/profile", match: ["/profile"] }
];

export function BottomNav() {
  const pathname = usePathname();

  return (
    <View style={styles.footer}>
      {tabs.map((tab) => {
        const active = tab.match.some((path) => pathname.startsWith(path));
        return (
          <Pressable
            key={tab.label}
            accessibilityRole="button"
            accessibilityLabel={tab.label}
            accessibilityState={{ selected: active }}
            onPress={() => router.replace(tab.href)}
            style={({ pressed }) => [styles.item, pressed && styles.itemPressed]}
          >
            <MaterialIcons name={tab.icon} size={22} color={active ? palette.sageDeep : textColors.tertiary} />
            <Text style={[styles.label, active && styles.activeLabel]}>{tab.label}</Text>
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
    paddingBottom: space[1]
  },
  item: {
    minWidth: 56,
    minHeight: 48,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: space[1],
    paddingVertical: space[1],
    gap: 2
  },
  itemPressed: {
    opacity: 0.7
  },
  label: {
    color: textColors.tertiary,
    fontSize: 10,
    fontWeight: "600",
    letterSpacing: 0.2
  },
  activeLabel: { color: palette.sageDeep, fontWeight: "700" }
});
