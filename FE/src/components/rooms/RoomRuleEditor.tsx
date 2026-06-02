import { useEffect, useState } from "react";
import { ScrollView, StyleSheet, Switch, View } from "react-native";
import { ApiError } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { useRoomRule, useUpdateRoomRule } from "../../lib/query/hooks/roomRule";
import { useRoomsQuery } from "../../lib/query/hooks/rooms";
import { toast } from "../../lib/toast";
import { space } from "../../theme/spacing";
import { palette } from "../../theme/tokens";
import { Screen } from "../Screen";
import { Button } from "../ui/Button";
import { Skeleton } from "../ui/Skeleton";
import { Text } from "../ui/Text";

const COPY = {
  title: "그룹 규칙",
  currentLabel: "이번 달 규칙",
  presetLabel: "매일 업데이트",
  weekendOn: "주말 포함",
  weekendOff: "주말 제외",
  editorLabel: "주말 포함 여부",
  editorOnHint: "주말에도 매일 업데이트가 필요해요.",
  editorOffHint: "주말은 자유롭게 쉬어가요.",
  pendingHeading: "다음 달 적용 예정",
  previewLiteral: "변경된 규칙은 다음 달 1일부터 적용됩니다.",
  saveCta: "다음 달부터 적용하기",
  noChangeHint: "이번 달 규칙과 같아요.",
  toastSuccess: "다음 달부터 새 규칙으로 시작해요.",
  toastForbidden: "방장만 규칙을 바꿀 수 있어요.",
  toastValidation: "요청 형식이 올바르지 않습니다.",
  toastNetwork: "잠시 후 다시 시도해 주세요.",
  nonLeaderNote: "규칙 변경은 방장만 할 수 있어요.",
  errorLoading: "규칙 정보를 불러올 수 없어요.",
} as const;

function describeWeekend(weekendInclude: boolean): string {
  return weekendInclude ? COPY.weekendOn : COPY.weekendOff;
}

interface RoomRuleEditorProps {
  roomId: number;
}

export function RoomRuleEditor({ roomId }: RoomRuleEditorProps) {
  const { user } = useAuth();
  const roomsQuery = useRoomsQuery();
  const ruleQuery = useRoomRule(roomId);
  const updateMut = useUpdateRoomRule();

  const room = (roomsQuery.data ?? []).find((r) => r.id === roomId) ?? null;
  const isLeader = room != null && user != null && room.ownerId === user.id;

  const current = ruleQuery.data?.current ?? null;
  const pending = ruleQuery.data?.pending ?? null;

  const initialWeekendInclude = pending?.weekendInclude ?? current?.weekendInclude ?? true;
  const [pendingWeekendInclude, setPendingWeekendInclude] = useState<boolean>(
    initialWeekendInclude,
  );
  const [editorBaseline, setEditorBaseline] = useState<boolean>(initialWeekendInclude);

  const serverWeekendInclude = pending?.weekendInclude ?? current?.weekendInclude;

  // Refresh clean editors from the server without discarding an in-progress edit.
  useEffect(() => {
    if (
      serverWeekendInclude != null &&
      (pendingWeekendInclude === editorBaseline || serverWeekendInclude === pendingWeekendInclude)
    ) {
      setPendingWeekendInclude(serverWeekendInclude);
      setEditorBaseline(serverWeekendInclude);
    }
  }, [editorBaseline, pendingWeekendInclude, serverWeekendInclude]);

  if (ruleQuery.isLoading || roomsQuery.isLoading) {
    return (
      <Screen title={COPY.title}>
        <View style={styles.skeleton}>
          <Skeleton height={72} />
          <Skeleton height={120} />
        </View>
      </Screen>
    );
  }

  if (ruleQuery.isError || roomsQuery.isError || current == null) {
    return (
      <Screen title={COPY.title}>
        <View style={styles.empty}>
          <Text variant="bodyStrong">{COPY.errorLoading}</Text>
        </View>
      </Screen>
    );
  }

  const noChange = pendingWeekendInclude === editorBaseline;

  function handleSave() {
    if (!isLeader) return;
    updateMut.mutate(
      { roomId, preset: "DAILY_UPDATE", weekendInclude: pendingWeekendInclude },
      {
        onSuccess: () => {
          toast.success(COPY.toastSuccess);
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
            {COPY.presetLabel} — {describeWeekend(current.weekendInclude)}
          </Text>
        </View>

        {pending != null ? (
          <View style={styles.card}>
            <Text variant="caption" color={palette.inkMute}>
              {COPY.pendingHeading}
            </Text>
            <Text variant="bodyStrong" style={styles.cardLine}>
              {COPY.presetLabel} — {describeWeekend(pending.weekendInclude)}
            </Text>
          </View>
        ) : null}

        {isLeader ? (
          <View style={styles.card}>
            <View style={styles.toggleRow}>
              <View style={styles.toggleText}>
                <Text variant="bodyStrong">{COPY.editorLabel}</Text>
                <Text
                  variant="caption"
                  color={palette.inkMute}
                  style={styles.toggleHint}
                >
                  {pendingWeekendInclude ? COPY.editorOnHint : COPY.editorOffHint}
                </Text>
              </View>
              <Switch
                value={pendingWeekendInclude}
                onValueChange={setPendingWeekendInclude}
                disabled={updateMut.isPending}
                accessibilityLabel={COPY.editorLabel}
              />
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
  content: {
    gap: space[3],
    paddingBottom: space[8],
  },
  skeleton: {
    gap: space[2],
  },
  empty: {
    gap: space[2],
  },
  card: {
    paddingVertical: space[3],
    paddingHorizontal: space[3],
    backgroundColor: palette.surfaceRaised,
    borderRadius: 12,
    gap: space[1],
  },
  cardLine: {
    marginTop: space[1],
  },
  toggleRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
  },
  toggleText: {
    flex: 1,
    gap: space[1],
  },
  toggleHint: {
    lineHeight: 18,
  },
  previewLine: {
    paddingHorizontal: space[1],
    lineHeight: 20,
  },
});
