import { router } from "expo-router";
import { useState } from "react";
import { ScrollView, StyleSheet, TextInput, View } from "react-native";
import { joinRoom } from "../src/api/rooms";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Button } from "../src/components/ui/Button";
import { Card } from "../src/components/ui/Card";
import { Text } from "../src/components/ui/Text";
import { toast } from "../src/lib/toast";
import { space } from "../src/theme/spacing";
import { palette, semantic, surface } from "../src/theme/tokens";

export default function JoinRoomScreen() {
  useRequireAuth();
  const [code, setCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  async function submit() {
    const trimmed = code.trim().toUpperCase();
    if (trimmed.length < 4) {
      setFormError("초대 코드를 정확히 입력하세요.");
      return;
    }
    setFormError(null);
    setSubmitting(true);
    try {
      const member = await joinRoom(trimmed);
      router.replace(`/rooms/${member.roomId}`);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "그룹 참여에 실패했어요.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Screen title="그룹 참여">
      <ScrollView contentContainerStyle={styles.content}>
        <Card tone="raised" size="lg">
          <Text variant="h2">초대 코드 입력</Text>
          <Text variant="bodySmall" color={palette.inkMute} style={{ marginTop: space[1] }}>
            친구가 공유한 8자리 영숫자 코드를 입력하세요.
          </Text>
          <TextInput
            value={code}
            onChangeText={(v) => {
              setCode(v.toUpperCase());
              if (formError) setFormError(null);
            }}
            placeholder="ABCD1234"
            placeholderTextColor={palette.inkFaint}
            autoCapitalize="characters"
            autoCorrect={false}
            maxLength={32}
            style={styles.input}
            accessibilityLabel="초대 코드"
          />
          {formError ? (
            <Text variant="caption" color={semantic.danger.fg} style={{ marginTop: space[2] }}>
              {formError}
            </Text>
          ) : null}
          <View style={{ marginTop: space[2] }}>
            <Button
              label={submitting ? "참여 중…" : "참여하기"}
              tone="primary"
              size="md"
              fullWidth
              disabled={submitting}
              onPress={submit}
            />
          </View>
        </Card>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  input: {
    minHeight: 56,
    marginTop: space[3],
    paddingHorizontal: space[3],
    borderRadius: 12,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
    color: palette.ink,
    fontSize: 24,
    letterSpacing: 4,
    textAlign: "center",
  },
});
