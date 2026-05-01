import { useEffect, useState } from "react";
import { ActivityIndicator, StyleSheet, TextInput, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { Link, router } from "expo-router";
import { useAuth } from "../src/auth/AuthContext";
import { Card } from "../src/components/ui/Card";
import { Button } from "../src/components/ui/Button";
import { Text } from "../src/components/ui/Text";
import { toast } from "../src/lib/toast";
import { palette, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";

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
      toast.warning("이메일과 비밀번호를 입력하세요.");
      return;
    }
    setSubmitting(true);
    try {
      await signIn(email.trim(), password);
      router.replace("/today");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "로그인에 실패했어요.");
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
      toast.error(error instanceof Error ? error.message : "카카오 로그인에 실패했어요.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <SafeAreaView style={styles.screen}>
      <View style={styles.container}>
        <View style={styles.heading}>
          <Text variant="display">열살방</Text>
          <Text variant="body" color={palette.inkMute} align="center">
            친구와 함께 오늘의 목표·할 일·회고를 가볍게 기록하세요.
          </Text>
        </View>

        <Card tone="hero" size="hero" style={styles.form}>
          <TextInput
            value={email}
            onChangeText={setEmail}
            placeholder="이메일"
            autoCapitalize="none"
            keyboardType="email-address"
            placeholderTextColor={palette.inkFaint}
            style={styles.input}
          />
          <TextInput
            value={password}
            onChangeText={setPassword}
            placeholder="비밀번호"
            secureTextEntry
            placeholderTextColor={palette.inkFaint}
            style={styles.input}
          />
          {submitting ? <ActivityIndicator color={palette.coralDeep} /> : null}
          <Button label="로그인" tone="primary" size="lg" fullWidth onPress={submit} disabled={submitting} />
          <Button label="카카오로 계속" tone="kakao" size="lg" fullWidth onPress={kakao} disabled={submitting} />
        </Card>

        <Link href="/signup" style={styles.link}>계정 만들기</Link>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: surface.page },
  container: {
    flex: 1,
    paddingHorizontal: space[5],
    paddingTop: space[10],
    gap: space[5]
  },
  heading: { alignItems: "center", gap: space[2] },
  form: { gap: space[3] },
  input: {
    minHeight: 48,
    paddingHorizontal: space[3],
    borderRadius: 12,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
    color: palette.ink,
    fontSize: 15
  },
  link: {
    color: palette.coralDeep,
    fontWeight: "700",
    textAlign: "center",
    paddingVertical: space[2]
  }
});
