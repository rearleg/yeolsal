import { useState } from "react";
import { ActivityIndicator, StyleSheet, TextInput } from "react-native";
import { Link, router } from "expo-router";
import { Screen } from "../src/components/Screen";
import { Card } from "../src/components/ui/Card";
import { Button } from "../src/components/ui/Button";
import { Text } from "../src/components/ui/Text";
import { useAuth } from "../src/auth/AuthContext";
import { toast } from "../src/lib/toast";
import { palette, semantic, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";

export default function SignupScreen() {
  const { signUp } = useAuth();
  const [nickname, setNickname] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  function clearFormError() {
    if (formError) setFormError(null);
  }

  async function submit() {
    if (!nickname.trim() || !email.trim() || password.length < 8) {
      setFormError("닉네임, 이메일, 8자 이상 비밀번호를 입력하세요.");
      return;
    }
    setFormError(null);
    setSubmitting(true);
    try {
      const destination = await signUp(email.trim(), password, nickname.trim());
      router.replace(destination ?? "/today");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "가입에 실패했어요.");
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
          onChangeText={(v) => {
            setNickname(v);
            clearFormError();
          }}
          placeholder="닉네임"
          placeholderTextColor={palette.inkFaint}
          style={styles.input}
        />
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
          placeholder="비밀번호 (8자 이상)"
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
    color: palette.coralDeep,
    fontWeight: "700",
    textAlign: "center",
    paddingVertical: space[3]
  }
});
