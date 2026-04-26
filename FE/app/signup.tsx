import { useState } from "react";
import { ActivityIndicator, Alert, StyleSheet, TextInput } from "react-native";
import { Link, router } from "expo-router";
import { NeoButton } from "../src/components/NeoButton";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { useAuth } from "../src/auth/AuthContext";
import { colors } from "../src/theme/tokens";

export default function SignupScreen() {
  const { signUp } = useAuth();
  const [nickname, setNickname] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    if (!nickname.trim() || !email.trim() || password.length < 8) {
      Alert.alert("가입", "닉네임, 이메일, 8자 이상 비밀번호를 입력하세요.");
      return;
    }
    setSubmitting(true);
    try {
      await signUp(email.trim(), password, nickname.trim());
      router.replace("/today");
    } catch (error) {
      Alert.alert("가입 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Screen title="가입" showFooter={false}>
      <NeoCard tone="green" style={styles.form}>
        <TextInput value={nickname} onChangeText={setNickname} placeholder="닉네임" style={styles.input} />
        <TextInput value={email} onChangeText={setEmail} placeholder="email" autoCapitalize="none" keyboardType="email-address" style={styles.input} />
        <TextInput value={password} onChangeText={setPassword} placeholder="password" secureTextEntry style={styles.input} />
        {submitting ? <ActivityIndicator color={colors.ink} /> : null}
        <NeoButton label="시작하기" disabled={submitting} tone="pink" onPress={submit} />
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
    borderColor: colors.black,
    paddingHorizontal: 12,
    backgroundColor: colors.white,
    fontWeight: "700"
  },
  link: { color: colors.black, fontWeight: "900", textDecorationLine: "underline", textTransform: "uppercase" }
});
