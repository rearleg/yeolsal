import { useState } from "react";
import { ActivityIndicator, Alert, StyleSheet, TextInput } from "react-native";
import { Link, router } from "expo-router";
import { Screen } from "../src/components/Screen";
import { Card } from "../src/components/ui/Card";
import { Button } from "../src/components/ui/Button";
import { Text } from "../src/components/ui/Text";
import { useAuth } from "../src/auth/AuthContext";
import { palette, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";

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
      <Card tone="hero" size="hero" style={styles.form}>
        <Text variant="bodySmall" color={palette.inkMute}>
          오늘부터 친구와 함께 잔디를 채워보세요.
        </Text>
        <TextInput
          value={nickname}
          onChangeText={setNickname}
          placeholder="닉네임"
          placeholderTextColor={palette.inkFaint}
          style={styles.input}
        />
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
          placeholder="비밀번호 (8자 이상)"
          secureTextEntry
          placeholderTextColor={palette.inkFaint}
          style={styles.input}
        />
        {submitting ? <ActivityIndicator color={palette.sageDeep} /> : null}
        <Button label="시작하기" tone="primary" size="lg" fullWidth onPress={submit} disabled={submitting} />
      </Card>
      <Link href="/login" style={styles.link}>이미 계정이 있어요</Link>
    </Screen>
  );
}

const styles = StyleSheet.create({
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
    color: palette.sageDeep,
    fontWeight: "700",
    textAlign: "center",
    paddingVertical: space[3]
  }
});
