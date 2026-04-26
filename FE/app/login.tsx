import { Image, StyleSheet, Text, TextInput, View } from "react-native";
import { Link } from "expo-router";
import { NeoButton } from "../src/components/NeoButton";
import { NeoCard } from "../src/components/NeoCard";
import { colors } from "../src/theme/tokens";
import logo from "../assets/brand/logo.png";

export default function LoginScreen() {
  return (
    <View style={styles.screen}>
      <Image source={logo} style={styles.logo} />
      <Text style={styles.headline}>열살방</Text>
      <NeoCard tone="paper" style={styles.form}>
        <TextInput placeholder="email" autoCapitalize="none" keyboardType="email-address" style={styles.input} />
        <TextInput placeholder="password" secureTextEntry style={styles.input} />
        <NeoButton label="로그인" />
        <NeoButton label="카카오로 계속" tone="pink" />
      </NeoCard>
      <Link href="/signup" style={styles.link}>계정 만들기</Link>
      <Link href="/today" style={styles.link}>MVP 둘러보기</Link>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: 16,
    backgroundColor: colors.paper,
    padding: 24
  },
  logo: { width: 164, height: 164, resizeMode: "contain" },
  headline: { color: colors.ink, fontSize: 44, fontWeight: "900" },
  form: { width: "100%", gap: 12 },
  input: {
    minHeight: 48,
    borderWidth: 3,
    borderColor: colors.ink,
    paddingHorizontal: 12,
    backgroundColor: colors.white,
    fontWeight: "700"
  },
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" }
});
