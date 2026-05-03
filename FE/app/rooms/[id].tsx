import { MaterialIcons } from "@expo/vector-icons";
import { router, useLocalSearchParams } from "expo-router";
import { useEffect, useState } from "react";
import { Alert, Pressable, ScrollView, Share, StyleSheet, View } from "react-native";
import {
  MIN_DAYS_LABELS,
  type MinDays,
  type RoomInvite,
} from "../../src/api/rooms";
import { useAuth } from "../../src/auth/AuthContext";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { MinDaysSegmented } from "../../src/components/rooms/MinDaysSegmented";
import { Button } from "../../src/components/ui/Button";
import { Card } from "../../src/components/ui/Card";
import { Skeleton } from "../../src/components/ui/Skeleton";
import { Text } from "../../src/components/ui/Text";
import {
  useCreateInvite,
  useLeaveRoom,
  useRoomMembersQuery,
  useRoomsQuery,
  useUpdateMyMinimum,
} from "../../src/lib/query/hooks/rooms";
import { space } from "../../src/theme/spacing";
import { palette, pickRoomAccent, roomHues, semantic, surface } from "../../src/theme/tokens";

export default function RoomDetailScreen() {
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  useRequireAuth();
  const { user } = useAuth();
  const membersQuery = useRoomMembersQuery(roomId);
  const roomsQuery = useRoomsQuery();
  const inviteMut = useCreateInvite();
  const leaveMut = useLeaveRoom();
  const updateMinMut = useUpdateMyMinimum();
  const [invite, setInvite] = useState<RoomInvite | null>(null);

  const members = membersQuery.data ?? [];
  const loading = membersQuery.isLoading;
  const me = members.find((m) => user != null && m.userId === user.id) ?? null;
  const room = (roomsQuery.data ?? []).find((r) => r.id === roomId) ?? null;
  const roomFloor: MinDays = room?.minDailyGoalDays ?? 10;
  const [pendingMin, setPendingMin] = useState<MinDays>(
    me?.currentMinimum ?? roomFloor,
  );

  // Re-sync the picker whenever the membership row arrives or changes from
  // another tab — without this, the segmented control would lock to the
  // first-seen value.
  useEffect(() => {
    if (me?.currentMinimum != null) {
      setPendingMin(me.currentMinimum);
    }
  }, [me?.currentMinimum]);

  function handleCreateInvite() {
    inviteMut.mutate(roomId, {
      onSuccess: (next) => setInvite(next),
    });
  }

  async function shareInvite() {
    if (!invite) return;
    try {
      await Share.share({ message: `열살 그룹 초대 코드: ${invite.code}` });
    } catch {
      // user dismissed share sheet
    }
  }

  function handleLeave() {
    Alert.alert("그룹 나가기", "정말 이 그룹을 나가시겠어요?", [
      { text: "취소", style: "cancel" },
      {
        text: "나가기",
        style: "destructive",
        onPress: () => {
          leaveMut.mutate(roomId, {
            onSuccess: () => router.back(),
          });
        },
      },
    ]);
  }

  const accent = pickRoomAccent(roomId);
  const hue = roomHues[accent];

  return (
    <Screen title="그룹">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <Card tone="raised" size="lg" style={{ borderColor: hue.base, borderWidth: 1 }}>
          <Text variant="label" color={hue.deep}>그룹 상세</Text>
          <Text variant="bodySmall" color={palette.inkMute} style={{ marginTop: space[1] }}>
            아래 코드를 친구에게 공유하면 같은 그룹에서 서로의 회고를 볼 수 있어요.
          </Text>

          {invite ? (
            <View style={styles.inviteBox}>
              <Text variant="display" weight="800">{invite.code}</Text>
              {invite.expiresAt ? (
                <Text variant="caption" color={palette.inkMute}>
                  유효기간: {new Date(invite.expiresAt).toLocaleDateString("ko-KR")}
                </Text>
              ) : null}
              <Button label="공유하기" tone="primary" size="md" fullWidth onPress={shareInvite} />
            </View>
          ) : (
            <View style={{ marginTop: space[3] }}>
              <Button label="초대 코드 만들기" tone="primary" size="md" fullWidth onPress={handleCreateInvite} />
            </View>
          )}
        </Card>

        {me ? (
          <Card tone="raised" size="md">
            <Text variant="title">내 최소 목표일수</Text>
            <Text variant="bodySmall" color={palette.inkMute} style={{ marginTop: space[1] }}>
              그룹 기준({MIN_DAYS_LABELS[roomFloor] ?? `${roomFloor}일`}) 이상으로만 올릴 수 있어요.
            </Text>
            {me.warningCount > 0 ? (
              <View
                style={styles.warningBadge}
                accessibilityRole="alert"
                accessibilityLabel={`경고 ${me.warningCount}회 누적되었습니다. 2회 누적 시 그룹에서 제외될 수 있습니다.`}
              >
                <Text variant="caption" color={semantic.danger.fg}>
                  ⚠️ 경고 {me.warningCount}/2 누적 중
                </Text>
              </View>
            ) : null}
            <View style={{ marginTop: space[2] }}>
              <MinDaysSegmented
                value={pendingMin}
                onChange={setPendingMin}
                minAllowed={roomFloor}
                disabled={updateMinMut.isPending}
              />
            </View>
            <View style={{ marginTop: space[2] }}>
              <Button
                label={updateMinMut.isPending ? "저장 중…" : "변경 저장"}
                tone="primary"
                size="md"
                fullWidth
                disabled={
                  updateMinMut.isPending ||
                  pendingMin === me.currentMinimum
                }
                onPress={() => updateMinMut.mutate({ roomId, minDailyGoalDays: pendingMin })}
              />
            </View>
          </Card>
        ) : null}

        <View>
          <Text variant="title" style={{ marginBottom: space[2] }}>멤버 ({members.length})</Text>
          {loading && members.length === 0 ? (
            <View style={styles.skeletonList}>
              <Skeleton height={48} />
              <Skeleton height={48} />
            </View>
          ) : (
            <View style={styles.memberList}>
              {members.map((member) => (
                <Pressable
                  key={`${member.roomId}-${member.userId}`}
                  accessibilityRole="button"
                  accessibilityLabel={`${member.nickname} 프로필 열기`}
                  onPress={() => router.push(`/friend-profile?userId=${member.userId}`)}
                >
                  <Card tone="default" size="md">
                    <View style={styles.memberRow}>
                      <View style={[styles.avatar, { backgroundColor: hue.soft }]}>
                        <Text variant="bodyStrong" color={hue.deep}>
                          {member.nickname.slice(0, 1).toUpperCase()}
                        </Text>
                      </View>
                      <View style={{ flex: 1 }}>
                        <Text variant="bodyStrong">{member.nickname}</Text>
                        <Text variant="caption" color={palette.inkMute}>
                          {member.role === "OWNER" ? "그룹장" : "멤버"}
                        </Text>
                      </View>
                    </View>
                  </Card>
                </Pressable>
              ))}
            </View>
          )}
        </View>

        <Button label="그룹 나가기" tone="ghost" size="md" fullWidth onPress={handleLeave} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  inviteBox: {
    marginTop: space[3],
    padding: space[3],
    borderRadius: 16,
    backgroundColor: surface.sunken,
    alignItems: "center",
    gap: space[2],
  },
  skeletonList: { gap: space[2] },
  memberList: { gap: space[2] },
  warningBadge: {
    marginTop: space[2],
    paddingHorizontal: space[3],
    paddingVertical: space[1],
    borderRadius: 999,
    backgroundColor: semantic.danger.bg,
    alignSelf: "flex-start",
  },
  memberRow: { flexDirection: "row", alignItems: "center", gap: space[3] },
  chatRow: { flexDirection: "row", alignItems: "center", gap: space[3] },
  avatar: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: "center",
    justifyContent: "center",
  },
});
