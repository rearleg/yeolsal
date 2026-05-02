import { MaterialIcons } from "@expo/vector-icons";
import { router, useLocalSearchParams } from "expo-router";
import { useState } from "react";
import { Alert, Pressable, ScrollView, Share, StyleSheet, View } from "react-native";
import { type RoomInvite } from "../../src/api/rooms";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { Button } from "../../src/components/ui/Button";
import { Card } from "../../src/components/ui/Card";
import { Skeleton } from "../../src/components/ui/Skeleton";
import { Text } from "../../src/components/ui/Text";
import {
  useCreateInvite,
  useLeaveRoom,
  useRoomMembersQuery,
} from "../../src/lib/query/hooks/rooms";
import { space } from "../../src/theme/spacing";
import { palette, pickRoomAccent, roomHues, surface } from "../../src/theme/tokens";

export default function RoomDetailScreen() {
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  useRequireAuth();
  const membersQuery = useRoomMembersQuery(roomId);
  const inviteMut = useCreateInvite();
  const leaveMut = useLeaveRoom();
  const [invite, setInvite] = useState<RoomInvite | null>(null);

  const members = membersQuery.data ?? [];
  const loading = membersQuery.isLoading;

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
