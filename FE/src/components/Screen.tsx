import { PropsWithChildren } from "react";
import { Image, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { colors } from "../theme/tokens";
import logo from "../../assets/brand/logo.png";

type Props = PropsWithChildren<{
  title: string;
  showLogo?: boolean;
}>;

export function Screen({ children, title, showLogo = true }: Props) {
  return (
    <SafeAreaView style={styles.screen}>
      <View style={styles.header}>
        {showLogo ? <Image source={logo} style={styles.logo} /> : null}
        <Text style={styles.title}>{title}</Text>
      </View>
      {children}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    gap: 16,
    backgroundColor: colors.paper,
    padding: 18
  },
  header: {
    minHeight: 54,
    flexDirection: "row",
    alignItems: "center",
    gap: 12
  },
  logo: {
    width: 44,
    height: 44,
    resizeMode: "contain"
  },
  title: {
    flex: 1,
    color: colors.ink,
    fontSize: 28,
    fontWeight: "900"
  }
});
