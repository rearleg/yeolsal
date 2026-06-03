import { useEffect, useState } from "react";
import { router } from "expo-router";
import { Modal, Pressable, ScrollView, StyleSheet, View } from "react-native";
import { ApiError } from "../../api/client";
import { type RoomMember } from "../../api/rooms";
import { useAuth } from "../../auth/AuthContext";
import {
  useRoomMembersQuery,
  useRoomsQuery,
  useTransferLeadership,
} from "../../lib/query/hooks/rooms";
import { toast } from "../../lib/toast";
import { space } from "../../theme/spacing";
import { palette } from "../../theme/tokens";
import { Screen } from "../Screen";
import { Button } from "../ui/Button";
import { Skeleton } from "../ui/Skeleton";
import { Text } from "../ui/Text";

const COPY = {
  title: "방장 양도",
  currentLeaderLabel: "현재 방장",
  eligibleHeading: "양도할 멤버 선택",
  emptyEligible: "양도할 수 있는 멤버가 없어요.",
  confirmCta: "양도하기",
  cancelCta: "취소",
  toastSuccessSuffix: "님에게 방장을 양도했어요.",
  toastForbidden: "방장만 양도할 수 있어요.",
  toastValidation: "대상 멤버를 다시 확인해 주세요.",
  toastIneligible: "지금은 양도가 어려운 상태예요. 다시 확인해 주세요.",
  toastNetwork: "잠시 후 다시 시도해 주세요.",
  nonLeaderNote: "방장 양도는 방장만 할 수 있어요.",
  errorLoading: "멤버 목록을 불러올 수 없어요.",
} as const;

function isEligible(member: RoomMember): boolean {
  return (
    member.survivalStatus === "ACTIVE" || member.survivalStatus === "YELLOW"
  );
}

function confirmCopy(nickname: string): string {
  return `${nickname}님에게 방장을 양도할까요? 양도 후에는 본인이 되돌릴 수 없어요.`;
}

interface LeaderTransferPickerProps {
  roomId: number;
}

export function LeaderTransferPicker({ roomId }: LeaderTransferPickerProps) {
  const { user } = useAuth();
  const roomsQuery = useRoomsQuery();
  const membersQuery = useRoomMembersQuery(roomId);
  const transferMut = useTransferLeadership();

  const [pendingTarget, setPendingTarget] = useState<RoomMember | null>(null);

  useEffect(() => {
    setPendingTarget(null);
  }, [roomId]);

  const room = (roomsQuery.data ?? []).find((r) => r.id === roomId) ?? null;
  const isLeader = room != null && user != null && room.ownerId === user.id;

  if (roomsQuery.isLoading || membersQuery.isLoading) {
    return (
      <Screen title={COPY.title}>
        <View style={styles.skeleton}>
          <Skeleton height={48} />
          <Skeleton height={120} />
        </View>
      </Screen>
    );
  }

  if (roomsQuery.isError || membersQuery.isError || room == null) {
    return (
      <Screen title={COPY.title}>
        <View style={styles.empty}>
          <Text variant="bodyStrong">{COPY.errorLoading}</Text>
        </View>
      </Screen>
    );
  }

  const members = membersQuery.data ?? [];
  const currentLeader = members.find((m) => m.userId === room.ownerId) ?? null;
  const eligible = members.filter(
    (m) => m.userId !== room.ownerId && isEligible(m),
  );

  function handleConfirm() {
    if (!isLeader || pendingTarget == null) {
      setPendingTarget(null);
      return;
    }
    const target = pendingTarget;
    transferMut.mutate(
      { roomId, targetUserId: target.userId },
      {
        onSuccess: () => {
          toast.success(`${target.nickname}${COPY.toastSuccessSuffix}`);
          setPendingTarget(null);
          router.back();
        },
        onError: (err) => {
          setPendingTarget(null);
          if (err instanceof ApiError) {
            if (err.status === 403) {
              toast.error(COPY.toastForbidden);
              return;
            }
            if (err.status === 409) {
              toast.error(COPY.toastIneligible);
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
            {COPY.currentLeaderLabel}
          </Text>
          <Text variant="bodyStrong" style={styles.cardLine}>
            {currentLeader?.nickname ?? "—"}
          </Text>
        </View>

        <Text variant="caption" color={palette.inkMute}>
          {COPY.eligibleHeading}
        </Text>

        {eligible.length === 0 ? (
          <Text variant="bodySmall" color={palette.inkMute}>
            {COPY.emptyEligible}
          </Text>
        ) : (
          eligible.map((m) => {
            const rowContent = (
              <>
                <View style={styles.memberRowText}>
                  <Text variant="bodyStrong">{m.nickname}</Text>
                  <Text variant="caption" color={palette.inkMute}>
                    멤버
                  </Text>
                </View>
                {isLeader ? (
                  <Text variant="bodyStrong" color={palette.inkMute}>
                    ›
                  </Text>
                ) : null}
              </>
            );
            return isLeader ? (
              <Pressable
                key={m.userId}
                accessibilityRole="button"
                accessibilityLabel={`${m.nickname}에게 방장 양도`}
                disabled={transferMut.isPending}
                onPress={() => setPendingTarget(m)}
                style={({ pressed }) => [
                  styles.memberRow,
                  pressed && styles.memberRowPressed,
                ]}
              >
                {rowContent}
              </Pressable>
            ) : (
              <View
                key={m.userId}
                accessibilityRole="text"
                accessibilityLabel={m.nickname}
                style={styles.memberRow}
              >
                {rowContent}
              </View>
            );
          })
        )}

        {!isLeader ? (
          <Text variant="caption" color={palette.inkMute}>
            {COPY.nonLeaderNote}
          </Text>
        ) : null}

        <Modal
          visible={pendingTarget != null}
          transparent
          animationType="fade"
          onRequestClose={() => setPendingTarget(null)}
        >
          <View style={styles.modalBackdrop}>
            <View
              accessibilityRole="alert"
              accessibilityViewIsModal
              style={styles.modalCard}
            >
              {pendingTarget != null ? (
                <Text variant="body">
                  {confirmCopy(pendingTarget.nickname)}
                </Text>
              ) : null}
              <View style={styles.modalActions}>
                <Button
                  label={COPY.cancelCta}
                  tone="ghost"
                  fullWidth
                  onPress={() => setPendingTarget(null)}
                  disabled={transferMut.isPending}
                />
                <Button
                  label={COPY.confirmCta}
                  tone="primary"
                  fullWidth
                  onPress={handleConfirm}
                  disabled={transferMut.isPending}
                  loading={transferMut.isPending}
                />
              </View>
            </View>
          </View>
        </Modal>
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
  memberRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
    paddingVertical: space[3],
    paddingHorizontal: space[3],
    backgroundColor: palette.surfaceRaised,
    borderRadius: 12,
    minHeight: 48,
  },
  memberRowPressed: { backgroundColor: palette.surfaceSunken },
  memberRowText: { flex: 1, gap: space[1] },
  modalBackdrop: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.4)",
    justifyContent: "center",
    paddingHorizontal: space[4],
  },
  modalCard: {
    backgroundColor: palette.surfaceRaised,
    borderRadius: 16,
    padding: space[4],
    gap: space[3],
  },
  modalActions: { flexDirection: "row", gap: space[2] },
});
