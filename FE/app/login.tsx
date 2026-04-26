import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, Image, StyleSheet, Text, TextInput, View } from "react-native";
import { Link, router } from "expo-router";
import { NeoButton } from "../src/components/NeoButton";
import { NeoCard } from "../src/components/NeoCard";
import { useAuth } from "../src/auth/AuthContext";
import { colors } from "../src/theme/tokens";
import logo from "../assets/brand/logo.png";

export default function LoginScreen() {
  const { signIn, signInWithKakao, loading, user } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!loading && user) {
      router.replace("/today");
    }
  }, [loading, user]);

  async function submit() {
    if (!email.trim() || !password) {
      Alert.alert("로그인", "이메일과 비밀번호를 입력하세요.");
      return;
    }
    setSubmitting(true);
    try {
      await signIn(email.trim(), password);
      router.replace("/today");
    } catch (error) {
      Alert.alert("로그인 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    } finally {
      setSubmitting(false);
    }
  }

  async function kakao() {
    setSubmitting(true);
    try {
      await signInWithKakao();
      router.replace("/today");
    } catch (error) {
      Alert.alert("카카오 로그인 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <View style={styles.screen}>
      <Image source={logo} style={styles.logo} />
      <Text style={styles.headline}>열살방</Text>
      <NeoCard tone="paper" style={styles.form}>
        <TextInput value={email} onChangeText={setEmail} placeholder="email" autoCapitalize="none" keyboardType="email-address" style={styles.input} />
        <TextInput value={password} onChangeText={setPassword} placeholder="password" secureTextEntry style={styles.input} />
        {submitting ? <ActivityIndicator color={colors.ink} /> : null}
        <NeoButton label="로그인" disabled={submitting} onPress={submit} />
        <NeoButton label="카카오로 계속" disabled={submitting} tone="pink" onPress={kakao} />
      </NeoCard>
      <Link href="/signup" style={styles.link}>계정 만들기</Link>
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
