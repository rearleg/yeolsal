import { PropsWithChildren } from "react";
import { Image, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { colors } from "../theme/tokens";
import logo from "../../assets/brand/logo.png";
import { BottomNav } from "./BottomNav";

type Props = PropsWithChildren<{
  title: string;
  showLogo?: boolean;
  showFooter?: boolean;
}>;

export function Screen({ children, title, showLogo = true, showFooter = true }: Props) {
  return (
    <SafeAreaView style={styles.screen}>
      <View pointerEvents="none" style={styles.halftoneA} />
      <View pointerEvents="none" style={styles.halftoneB} />
      <View style={styles.header}>
        <View style={styles.leftHeader}>
          <View style={styles.menuButton}>
            <Text style={styles.menuGlyph}>≡</Text>
          </View>
          <Text style={styles.brand}>YEOLSALBANG</Text>
        </View>
        {showLogo ? <Image accessibilityLabel={title} source={logo} style={styles.logo} /> : null}
      </View>
      <View style={styles.content}>{children}</View>
      {showFooter ? <BottomNav /> : null}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.paper
  },
  header: {
    minHeight: 68,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 12,
    borderBottomWidth: 4,
    borderColor: colors.black,
    backgroundColor: colors.paper,
    paddingHorizontal: 14,
    paddingVertical: 10,
    shadowColor: colors.black,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: 4, height: 4 },
    elevation: 8
  },
  leftHeader: { flexDirection: "row", alignItems: "center", gap: 12, flex: 1 },
  menuButton: {
    width: 38,
    height: 38,
    alignItems: "center",
    justifyContent: "center",
    borderWidth: 3,
    borderColor: colors.black,
    backgroundColor: colors.white,
    shadowColor: colors.black,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: 2, height: 2 },
    elevation: 3
  },
  menuGlyph: { color: colors.black, fontSize: 28, fontWeight: "900", lineHeight: 30 },
  logo: {
    width: 42,
    height: 42,
    borderWidth: 2,
    borderColor: colors.black,
    borderRadius: 21,
    resizeMode: "contain"
  },
  brand: {
    color: colors.black,
    fontSize: 27,
    fontWeight: "900",
    fontStyle: "italic",
    letterSpacing: 0,
    textShadowColor: colors.pink,
    textShadowOffset: { width: 2, height: 2 },
    textShadowRadius: 0
  },
  content: {
    flex: 1,
    paddingHorizontal: 18,
    paddingTop: 24
  },
  halftoneA: {
    position: "absolute",
    top: 92,
    right: -28,
    width: 116,
    height: 116,
    borderRadius: 58,
    borderWidth: 3,
    borderColor: colors.black,
    backgroundColor: colors.pink,
    opacity: 0.16,
    transform: [{ rotate: "-10deg" }]
  },
  halftoneB: {
    position: "absolute",
    bottom: 44,
    left: -36,
    width: 120,
    height: 88,
    borderWidth: 3,
    borderColor: colors.black,
    backgroundColor: colors.green,
    opacity: 0.14,
    transform: [{ rotate: "12deg" }]
  }
});
