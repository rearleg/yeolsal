import { router, useLocalSearchParams } from "expo-router";
import { useEffect, useState } from "react";
import { Alert, Pressable, ScrollView, Share, StyleSheet, View } from "react-native";
import {
  createInvite,
  leaveRoom,
  listMembers,
  type RoomInvite,
  type RoomMember,
} from "../../src/api/rooms";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { Button } from "../../src/components/ui/Button";
import { Card } from "../../src/components/ui/Card";
import { Skeleton } from "../../src/components/ui/Skeleton";
import { Text } from "../../src/components/ui/Text";
import { space } from "../../src/theme/spacing";
import { palette, pickRoomAccent, roomHues, surface } from "../../src/theme/tokens";

export default function RoomDetailScreen() {
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  const auth = useRequireAuth();
  const [members, setMembers] = useState<RoomMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [invite, setInvite] = useState<RoomInvite | null>(null);

  useEffect(() => {
    if (!auth.loading && auth.user && Number.isFinite(roomId)) {
      void load();
    }
  }, [auth.loading, auth.user, roomId]);

  async function load() {
    setLoading(true);
    try {
      setMembers(await listMembers(roomId));
    } catch (error) {
      Alert.alert("그룹", error instanceof Error ? error.message : "멤버를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function handleCreateInvite() {
    try {
      const next = await createInvite(roomId);
      setInvite(next);
    } catch (error) {
      Alert.alert("초대 코드 생성 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    }
  }

  async function shareInvite() {
    if (!invite) return;
    try {
      await Share.share({ message: `열살 그룹 초대 코드: ${invite.code}` });
    } catch {
      // user dismissed share sheet
    }
  }

  async function handleLeave() {
    Alert.alert("그룹 나가기", "정말 이 그룹을 나가시겠어요?", [
      { text: "취소", style: "cancel" },
      {
        text: "나가기",
        style: "destructive",
        onPress: async () => {
          try {
            await leaveRoom(roomId);
            router.back();
          } catch (error) {
            Alert.alert("나가기 실패", error instanceof Error ? error.message : "다시 시도하세요.");
          }
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
  avatar: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: "center",
    justifyContent: "center",
  },
});
