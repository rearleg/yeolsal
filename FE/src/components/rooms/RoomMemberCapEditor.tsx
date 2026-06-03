import { useEffect, useState } from "react";
import { router } from "expo-router";
import { Pressable, ScrollView, StyleSheet, View } from "react-native";
import { ApiError } from "../../api/client";
import {
  MAX_MEMBERS_MAX,
  MAX_MEMBERS_MIN,
} from "../../api/rooms";
import { useAuth } from "../../auth/AuthContext";
import { useRoomsQuery, useUpdateMemberCap } from "../../lib/query/hooks/rooms";
import { toast } from "../../lib/toast";
import { space } from "../../theme/spacing";
import { palette } from "../../theme/tokens";
import { Screen } from "../Screen";
import { Button } from "../ui/Button";
import { Skeleton } from "../ui/Skeleton";
import { Text } from "../ui/Text";

const COPY = {
  title: "그룹 정원",
  currentLabel: "이번 달 정원",
  pendingHeading: "다음 달 적용 예정",
  editorLabel: "정원 (명)",
  previewLiteral: "변경된 정원은 다음 달 1일부터 적용됩니다.",
  saveCta: "다음 달부터 적용하기",
  noChangeHint: "이번 달 정원과 같아요.",
  toastSuccess: "다음 달부터 새 정원으로 시작해요.",
  toastForbidden: "방장만 정원을 바꿀 수 있어요.",
  toastValidation: "정원은 2에서 30 사이여야 합니다.",
  toastNetwork: "잠시 후 다시 시도해 주세요.",
  nonLeaderNote: "정원 변경은 방장만 할 수 있어요.",
  errorLoading: "정원 정보를 불러올 수 없어요.",
  decreaseLabel: "정원 감소",
  increaseLabel: "정원 증가",
} as const;

function formatPendingMonth(value: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(value);
  if (match == null) return value;
  return `${match[1]}년 ${parseInt(match[2], 10)}월`;
}

interface RoomMemberCapEditorProps {
  roomId: number;
}

export function RoomMemberCapEditor({ roomId }: RoomMemberCapEditorProps) {
  const { user } = useAuth();
  const roomsQuery = useRoomsQuery();
  const updateMut = useUpdateMemberCap();

  const room = (roomsQuery.data ?? []).find((r) => r.id === roomId) ?? null;
  const isLeader = room != null && user != null && room.ownerId === user.id;

  const serverBaseline =
    room?.pendingMaxMembers ?? room?.maxMembers ?? MAX_MEMBERS_MIN;
  const [pendingCap, setPendingCap] = useState<number>(serverBaseline);
  const [editorBaseline, setEditorBaseline] = useState<number>(serverBaseline);
  const [draftRoomId, setDraftRoomId] = useState<number>(roomId);

  useEffect(() => {
    if (draftRoomId !== roomId) {
      setPendingCap(serverBaseline);
      setEditorBaseline(serverBaseline);
      setDraftRoomId(roomId);
    }
  }, [draftRoomId, roomId, serverBaseline]);

  useEffect(() => {
    if (
      (pendingCap === editorBaseline || serverBaseline === pendingCap) &&
      serverBaseline !== editorBaseline
    ) {
      setPendingCap(serverBaseline);
      setEditorBaseline(serverBaseline);
    }
  }, [serverBaseline, editorBaseline, pendingCap]);

  if (roomsQuery.isLoading) {
    return (
      <Screen title={COPY.title}>
        <View style={styles.skeleton}>
          <Skeleton height={72} />
          <Skeleton height={120} />
        </View>
      </Screen>
    );
  }

  if (roomsQuery.isError || room == null) {
    return (
      <Screen title={COPY.title}>
        <View style={styles.empty}>
          <Text variant="bodyStrong">{COPY.errorLoading}</Text>
        </View>
      </Screen>
    );
  }

  const noChange = pendingCap === editorBaseline;

  function adjust(delta: number) {
    setPendingCap((current) => {
      const next = current + delta;
      if (next < MAX_MEMBERS_MIN) return MAX_MEMBERS_MIN;
      if (next > MAX_MEMBERS_MAX) return MAX_MEMBERS_MAX;
      return next;
    });
  }

  function handleSave() {
    if (!isLeader) return;
    updateMut.mutate(
      { roomId, maxMembers: pendingCap },
      {
        onSuccess: () => {
          toast.success(COPY.toastSuccess);
          router.back();
        },
        onError: (err) => {
          if (err instanceof ApiError) {
            if (err.status === 403) {
              toast.error(COPY.toastForbidden);
              return;
            }
            if (err.status === 400) {
              toast.error(COPY.toastValidation);
              return;
            }
            toast.error(err.message);
            return;
          }
          toast.error(COPY.toastNetwork);
        },
      },
    );
  }

  return (
    <Screen title={COPY.title}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.card}>
          <Text variant="caption" color={palette.inkMute}>
            {COPY.currentLabel}
          </Text>
          <Text variant="bodyStrong" style={styles.cardLine}>
            {room.maxMembers}명
          </Text>
        </View>

        {room.pendingMaxMembers != null
          && room.pendingMaxMembersEffectiveFromMonth != null ? (
          <View style={styles.card}>
            <Text variant="caption" color={palette.inkMute}>
              {COPY.pendingHeading}
            </Text>
            <Text variant="bodyStrong" style={styles.cardLine}>
              {room.pendingMaxMembers}명 (
              {formatPendingMonth(room.pendingMaxMembersEffectiveFromMonth)}부터)
            </Text>
          </View>
        ) : null}

        {isLeader ? (
          <View style={styles.card}>
            <Text variant="bodyStrong">{COPY.editorLabel}</Text>
            <View style={styles.stepperRow}>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={COPY.decreaseLabel}
                disabled={pendingCap <= MAX_MEMBERS_MIN || updateMut.isPending}
                onPress={() => adjust(-1)}
                style={({ pressed }) => [
                  styles.stepperBtn,
                  pressed && styles.stepperPressed,
                  pendingCap <= MAX_MEMBERS_MIN && styles.stepperDisabled,
                ]}
              >
                <Text variant="bodyStrong">−</Text>
              </Pressable>
              <Text variant="display" style={styles.stepperValue}>
                {pendingCap}
              </Text>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={COPY.increaseLabel}
                disabled={pendingCap >= MAX_MEMBERS_MAX || updateMut.isPending}
                onPress={() => adjust(1)}
                style={({ pressed }) => [
                  styles.stepperBtn,
                  pressed && styles.stepperPressed,
                  pendingCap >= MAX_MEMBERS_MAX && styles.stepperDisabled,
                ]}
              >
                <Text variant="bodyStrong">+</Text>
              </Pressable>
            </View>
          </View>
        ) : (
          <Text variant="caption" color={palette.inkMute}>
            {COPY.nonLeaderNote}
          </Text>
        )}

        <Text
          variant="bodySmall"
          color={palette.inkMute}
          accessibilityRole="text"
          style={styles.previewLine}
        >
          {COPY.previewLiteral}
        </Text>

        {isLeader ? (
          <Button
            label={COPY.saveCta}
            tone="primary"
            size="lg"
            fullWidth
            onPress={handleSave}
            disabled={noChange || updateMut.isPending}
            loading={updateMut.isPending}
            accessibilityHint={noChange ? COPY.noChangeHint : undefined}
          />
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  skeleton: { gap: space[2] },
  empty: { gap: space[2] },
  card: {
    paddingVertical: space[3],
    paddingHorizontal: space[3],
    backgroundColor: palette.surfaceRaised,
    borderRadius: 12,
    gap: space[1],
  },
  cardLine: { marginTop: space[1] },
  stepperRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: space[4],
    marginTop: space[2],
  },
  stepperBtn: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: palette.surfaceSunken,
    alignItems: "center",
    justifyContent: "center",
  },
  stepperPressed: { opacity: 0.7 },
  stepperDisabled: { opacity: 0.4 },
  stepperValue: { minWidth: 80, textAlign: "center" },
  previewLine: { paddingHorizontal: space[1], lineHeight: 20 },
});
