import { MaterialIcons } from "@expo/vector-icons";
import { router, usePathname } from "expo-router";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { colors } from "../theme/tokens";

type Tab = {
  label: string;
  icon: keyof typeof MaterialIcons.glyphMap;
  href: "/today" | "/feed" | "/monthly" | "/profile";
  match: string[];
};

const tabs: Tab[] = [
  { label: "GIGS", icon: "home", href: "/today", match: ["/today"] },
  { label: "ZINE", icon: "auto-stories", href: "/feed", match: ["/feed", "/friend-profile"] },
  { label: "POST", icon: "add-box", href: "/today", match: [] },
  { label: "ME", icon: "person", href: "/profile", match: ["/profile", "/monthly"] }
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
            onPress={() => router.push(tab.href)}
            style={[styles.item, active && styles.activeItem]}
          >
            <MaterialIcons name={tab.icon} size={24} color={colors.black} />
            <Text style={styles.label}>{tab.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  footer: {
    minHeight: 78,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-around",
    backgroundColor: colors.paper,
    borderTopWidth: 4,
    borderColor: colors.black,
    shadowColor: colors.black,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: 0, height: -4 },
    elevation: 10,
    paddingHorizontal: 8,
    paddingTop: 8
  },
  item: {
    minWidth: 62,
    minHeight: 54,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 8,
    paddingVertical: 6,
    opacity: 0.78
  },
  activeItem: {
    backgroundColor: colors.pink,
    borderWidth: 2,
    borderColor: colors.black,
    opacity: 1,
    shadowColor: colors.black,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: 4, height: 4 },
    elevation: 5,
    transform: [{ translateY: -8 }]
  },
  label: {
    marginTop: 2,
    color: colors.black,
    fontSize: 12,
    fontWeight: "900",
    textTransform: "uppercase"
  }
});
