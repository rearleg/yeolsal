import { useEffect, useRef, useState } from "react";
import { ActivityIndicator, StyleSheet, TextInput, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { Link, router } from "expo-router";
import { useAuth } from "../src/auth/AuthContext";
import { Card } from "../src/components/ui/Card";
import { Button } from "../src/components/ui/Button";
import { Text } from "../src/components/ui/Text";
import { toast } from "../src/lib/toast";
import { getOnboardingState } from "../src/lib/onboardingState";
import { palette, semantic, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";

export default function LoginScreen() {
  const { signIn, signInWithKakao, loading, user } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const interactiveAuthStarted = useRef(false);

  useEffect(() => {
    if (!interactiveAuthStarted.current && !loading && user) {
      router.replace("/today");
    }
  }, [loading, user]);

  function clearFormError() {
    if (formError) setFormError(null);
  }

  async function submit() {
    if (!email.trim() || !password) {
      setFormError("이메일과 비밀번호를 입력하세요.");
      return;
    }
    setFormError(null);
    setSubmitting(true);
    interactiveAuthStarted.current = true;
    try {
      // Story 8.1 — the deeplink destination is stashed by AuthContext;
      // <OnboardingGate> re-routes when onboarding is owed, and the
      // onboarding screen forwards to the stashed destination after S5.
      const destination = await signIn(email.trim(), password);
      await routeAfterAuth(destination);
    } catch (error) {
      interactiveAuthStarted.current = false;
      toast.error(error instanceof Error ? error.message : "로그인에 실패했어요.");
    } finally {
      setSubmitting(false);
    }
  }

  async function kakao() {
    setSubmitting(true);
    interactiveAuthStarted.current = true;
    try {
      const destination = await signInWithKakao();
      await routeAfterAuth(destination);
    } catch (error) {
      interactiveAuthStarted.current = false;
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
            onChangeText={(v) => {
              setEmail(v);
              clearFormError();
            }}
            placeholder="이메일"
            autoCapitalize="none"
            keyboardType="email-address"
            placeholderTextColor={palette.inkFaint}
            style={styles.input}
          />
          <TextInput
            value={password}
            onChangeText={(v) => {
              setPassword(v);
              clearFormError();
            }}
            placeholder="비밀번호"
            secureTextEntry
            placeholderTextColor={palette.inkFaint}
            style={styles.input}
          />
          {formError ? (
            <Text variant="caption" color={semantic.danger.fg}>
              {formError}
            </Text>
          ) : null}
          {submitting ? <ActivityIndicator color={palette.coralDeep} /> : null}
          <Button label="로그인" tone="primary" size="lg" fullWidth onPress={submit} disabled={submitting} />
          <Button label="카카오로 계속" tone="kakao" size="lg" fullWidth onPress={kakao} disabled={submitting} />
        </Card>

        <Link href="/signup" style={styles.link}>계정 만들기</Link>
      </View>
    </SafeAreaView>
  );
}

async function routeAfterAuth(destination: string | null): Promise<void> {
  const state = await getOnboardingState();
  router.replace(state?.completedAt ? destination ?? "/today" : "/today");
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
