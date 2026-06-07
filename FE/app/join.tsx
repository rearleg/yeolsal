import { router, useLocalSearchParams } from "expo-router";
import { useEffect, useState } from "react";
import { ScrollView, StyleSheet, TextInput, View } from "react-native";
import { ApiError } from "../src/api/client";
import { joinRoom, MIN_DAYS_LABELS } from "../src/api/rooms";
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
  // Story 6.2 AC2 — KakaoTalk share-tap auto-fill. Deep-link routes
  // /join?code=ABCD1234 directly into this screen; the useEffect below
  // hydrates the input and fires the same submit() the manual entry path
  // uses, so the share-tap flow adds zero UI surface to maintain.
  const { code: incomingCode } = useLocalSearchParams<{ code?: string }>();
  const [code, setCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  async function submit(codeOverride?: string) {
    const trimmed = (codeOverride ?? code).trim().toUpperCase();
    if (trimmed.length < 4) {
      setFormError("초대 코드를 정확히 입력하세요.");
      return;
    }
    setFormError(null);
    setSubmitting(true);
    try {
      const member = await joinRoom(trimmed);
      const minLabel = MIN_DAYS_LABELS[member.currentMinimum] ?? `${member.currentMinimum}일`;
      toast.info(`매월 ${minLabel} 회고를 남기는 그룹이에요.`);
      // Send first-time joiners to the settings page so they can pick
      // their personal minimum-days override before settling in. The
      // ?onboarding=1 flag mounts the explanatory banner, and the
      // settings page redirects back to /rooms/{id} on save.
      router.replace(`/rooms/${member.roomId}/settings?onboarding=1`);
    } catch (error) {
      // Story 6.2 AC2 — branch on the ROOM_FULL wire code so the calmer
      // "방이 가득 찼어요" toast appears instead of the generic message.
      // BE → ApiExceptionHandler.roomFull → 409 + code "ROOM_FULL".
      if (error instanceof ApiError && error.code === "ROOM_FULL") {
        toast.error("방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요.");
        return;
      }
      // Code revoked / expired surfaces as 404 NOT_FOUND with the
      // BE-set Korean message; surface a focused expired-message instead
      // of the raw BE string when the wire code matches.
      if (error instanceof ApiError && error.code === "NOT_FOUND") {
        toast.error("초대 코드가 만료되었어요.");
        return;
      }
      toast.error(error instanceof Error ? error.message : "그룹 참여에 실패했어요.");
    } finally {
      setSubmitting(false);
    }
  }

  // Story 6.2 AC2 — auto-fill + auto-submit when the deep-link router
  // hands us ?code=. Single-fire on incomingCode change (incomingCode is
  // a router param, so it changes at most once per nav).
  useEffect(() => {
    if (!incomingCode) return;
    const normalized = incomingCode.toUpperCase();
    setCode(normalized);
    void submit(normalized);
    // One-shot auto-submit on route entry — submit/setCode are stable and
    // re-firing on every render would double-post to the BE.
  }, [incomingCode]);

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
              onPress={() => void submit()}
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
