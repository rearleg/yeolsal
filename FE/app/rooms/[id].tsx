import { MaterialIcons } from "@expo/vector-icons";
import { router, useLocalSearchParams } from "expo-router";
import { useState } from "react";
import { Alert, Pressable, ScrollView, Share, StyleSheet, View } from "react-native";
import {
  MIN_DAYS_LABELS,
  type MinDays,
  type RoomInvite,
} from "../../src/api/rooms";
import { useAuth } from "../../src/auth/AuthContext";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { InviteCodeSheet } from "../../src/components/rooms/InviteCodeSheet";
import { Button } from "../../src/components/ui/Button";
import { Card } from "../../src/components/ui/Card";
import { Skeleton } from "../../src/components/ui/Skeleton";
import { Text } from "../../src/components/ui/Text";
import {
  useCreateInvite,
  useLeaveRoom,
  useRoomMembersQuery,
  useRoomsQuery,
} from "../../src/lib/query/hooks/rooms";
import { space } from "../../src/theme/spacing";
import { palette, pickRoomAccent, roomHues, semantic } from "../../src/theme/tokens";

export default function RoomDetailScreen() {
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  useRequireAuth();
  const { user } = useAuth();
  const membersQuery = useRoomMembersQuery(roomId);
  const roomsQuery = useRoomsQuery();
  const inviteMut = useCreateInvite();
  const leaveMut = useLeaveRoom();
  const [invite, setInvite] = useState<RoomInvite | null>(null);
  const [inviteSheetVisible, setInviteSheetVisible] = useState<boolean>(false);

  const members = membersQuery.data ?? [];
  const loading = membersQuery.isLoading;
  const me = members.find((m) => user != null && m.userId === user.id) ?? null;
  const room = (roomsQuery.data ?? []).find((r) => r.id === roomId) ?? null;
  const roomFloor: MinDays = room?.minDailyGoalDays ?? 10;

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
    <Screen title="">
      <View style={styles.headerRow}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="뒤로 가기"
          hitSlop={12}
          onPress={() => router.back()}
          style={styles.headerIcon}
        >
          <MaterialIcons name="arrow-back" size={22} color={palette.ink} />
        </Pressable>
        <Text variant="h2" numberOfLines={1} style={styles.headerTitle}>
          {room?.name ?? "그룹"}
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="멤버 추가"
          hitSlop={12}
          onPress={() => setInviteSheetVisible(true)}
          style={styles.headerIcon}
        >
          <MaterialIcons name="person-add-alt" size={22} color={palette.ink} />
        </Pressable>
      </View>

      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="그룹 채팅 열기"
          onPress={() => router.push(`/rooms/${roomId}/chat`)}
        >
          <Card tone="raised" size="md">
            <View style={styles.chatRow}>
              <MaterialIcons name="chat-bubble-outline" size={20} color={palette.coralDeep} />
              <View style={{ flex: 1 }}>
                <Text variant="bodyStrong">그룹 채팅</Text>
                <Text variant="caption" color={palette.inkMute}>
                  멤버들과 메시지를 주고받아요.
                </Text>
              </View>
              <MaterialIcons name="chevron-right" size={20} color={palette.inkMute} />
            </View>
          </Card>
        </Pressable>

        {me ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="그룹 설정 열기"
            onPress={() => router.push(`/rooms/${roomId}/settings`)}
          >
            <Card tone="raised" size="md">
              <View style={styles.chatRow}>
                <MaterialIcons name="tune" size={20} color={palette.coralDeep} />
                <View style={{ flex: 1 }}>
                  <Text variant="bodyStrong">
                    내 최소 목표일수 · {MIN_DAYS_LABELS[me.currentMinimum] ?? `${me.currentMinimum}일`}
                  </Text>
                  <Text variant="caption" color={palette.inkMute}>
                    그룹 기준({MIN_DAYS_LABELS[roomFloor] ?? `${roomFloor}일`}) 이상으로 변경할 수 있어요.
                  </Text>
                </View>
                <MaterialIcons name="chevron-right" size={20} color={palette.inkMute} />
              </View>
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
            </Card>
          </Pressable>
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

      <InviteCodeSheet
        visible={inviteSheetVisible}
        invite={invite}
        isCreating={inviteMut.isPending}
        onCreate={handleCreateInvite}
        onShare={shareInvite}
        onClose={() => setInviteSheetVisible(false)}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  headerRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: space[3],
    paddingTop: space[1],
    paddingBottom: space[2],
    gap: space[2],
  },
  headerIcon: {
    padding: space[1],
  },
  headerTitle: {
    flex: 1,
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
