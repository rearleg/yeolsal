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
      <View style={styles.hero}>
        <View style={styles.shapeGreen} />
        <View style={styles.shapePink} />
        <Image source={logo} style={styles.logo} />
      </View>
      <View style={styles.wordmark}>
        <Text style={styles.headline}>YEOLSALBANG</Text>
        <Text style={styles.stamp}>ENTER THE ZINE</Text>
      </View>
      <NeoCard tone="white" style={styles.form}>
        <TextInput value={email} onChangeText={setEmail} placeholder="email" autoCapitalize="none" keyboardType="email-address" style={styles.input} />
        <TextInput value={password} onChangeText={setPassword} placeholder="password" secureTextEntry style={styles.input} />
        {submitting ? <ActivityIndicator color={colors.ink} /> : null}
        <NeoButton label="로그인" disabled={submitting} onPress={submit} />
        <NeoButton label="카카오로 계속" disabled={submitting} tone="kakao" onPress={kakao} />
      </NeoCard>
      <Link href="/signup" style={styles.link}>계정 만들기</Link>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: "center",
    justifyContent: "flex-start",
    gap: 16,
    backgroundColor: colors.paper,
    padding: 24,
    paddingTop: 64
  },
  hero: {
    width: "100%",
    height: 250,
    alignItems: "center",
    justifyContent: "center",
    borderWidth: 4,
    borderColor: colors.black,
    backgroundColor: colors.surfaceHigh,
    overflow: "hidden",
    shadowColor: colors.black,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: 8, height: 8 },
    elevation: 8
  },
  shapeGreen: {
    position: "absolute",
    width: 126,
    height: 126,
    left: -20,
    top: 34,
    backgroundColor: colors.green,
    borderWidth: 3,
    borderColor: colors.black,
    transform: [{ rotate: "13deg" }]
  },
  shapePink: {
    position: "absolute",
    width: 152,
    height: 152,
    right: -28,
    bottom: 14,
    borderRadius: 76,
    backgroundColor: colors.pink,
    borderWidth: 3,
    borderColor: colors.black,
    transform: [{ rotate: "-8deg" }]
  },
  logo: { width: 172, height: 172, resizeMode: "contain" },
  wordmark: { alignItems: "center", marginTop: -10 },
  headline: {
    color: colors.pink,
    fontSize: 40,
    fontWeight: "900",
    fontStyle: "italic",
    textShadowColor: colors.green,
    textShadowOffset: { width: 4, height: 4 },
    textShadowRadius: 0
  },
  stamp: {
    marginTop: 8,
    color: colors.ink,
    backgroundColor: colors.greenNeon,
    borderWidth: 2,
    borderColor: colors.black,
    paddingHorizontal: 12,
    paddingVertical: 5,
    fontWeight: "900",
    letterSpacing: 0.8,
    transform: [{ rotate: "2deg" }]
  },
  form: { width: "100%", gap: 12 },
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
