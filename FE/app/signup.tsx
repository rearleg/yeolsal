import { StyleSheet, TextInput } from "react-native";
import { Link } from "expo-router";
import { NeoButton } from "../src/components/NeoButton";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { colors } from "../src/theme/tokens";

export default function SignupScreen() {
  return (
    <Screen title="가입">
      <NeoCard tone="green" style={styles.form}>
        <TextInput placeholder="닉네임" style={styles.input} />
        <TextInput placeholder="email" autoCapitalize="none" keyboardType="email-address" style={styles.input} />
        <TextInput placeholder="password" secureTextEntry style={styles.input} />
        <NeoButton label="시작하기" tone="pink" />
      </NeoCard>
      <Link href="/login" style={styles.link}>이미 계정이 있어요</Link>
    </Screen>
  );
}

const styles = StyleSheet.create({
  form: { gap: 12 },
  input: {
    minHeight: 48,
    borderWidth: 3,
    borderColor: colors.ink,
    paddingHorizontal: 12,
    backgroundColor: colors.paper,
    fontWeight: "700"
  },
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" }
});
