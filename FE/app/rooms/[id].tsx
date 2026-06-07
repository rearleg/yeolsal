import { MaterialIcons } from "@expo/vector-icons";
import { useQueries } from "@tanstack/react-query";
import { router, useLocalSearchParams } from "expo-router";
import { useMemo, useState } from "react";
import { Alert, Pressable, ScrollView, Share, StyleSheet, View } from "react-native";
import { apiRequest, type ApiEnvelope } from "../../src/api/client";
import {
  MIN_DAYS_LABELS,
  type MemberTodayDto,
  type MinDays,
  type RoomInvite,
} from "../../src/api/rooms";
import type { GrassDayDto } from "../../src/api/types";
import { useAuth } from "../../src/auth/AuthContext";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { InviteCodeSheet } from "../../src/components/rooms/InviteCodeSheet";
import { RoomMemberCard } from "../../src/components/rooms/RoomMemberCard";
import { Button } from "../../src/components/ui/Button";
import { Card } from "../../src/components/ui/Card";
import { Skeleton } from "../../src/components/ui/Skeleton";
import { Text } from "../../src/components/ui/Text";
import { bucketFor } from "../../src/lib/bucket";
import { entryDateOf } from "../../src/lib/calendar";
import {
  useCreateInvite,
  useGroupTodayQuery,
  useLeaveRoom,
  useRoomMembersQuery,
  useRoomsQuery,
} from "../../src/lib/query/hooks/rooms";
import { useKakaoShare } from "../../src/lib/query/hooks/useKakaoShare";
import { toast } from "../../src/lib/toast";
import { useQueryClient } from "@tanstack/react-query";
import { qk } from "../../src/lib/query/keys";
import { useRealtimeSubscription } from "../../src/lib/realtime/client";
import { space } from "../../src/theme/spacing";
import { palette, pickRoomAccent, roomHues, semantic } from "../../src/theme/tokens";
import { SubModeProvider } from "../../src/providers/SubModeProvider";
import { shouldShowWelcomeWindow, WelcomeWindow } from "../../src/components/welcome";
import { FriendGiftSurfaces } from "../../src/components/revival/FriendGiftSurfaces";

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

  // Realtime member-add events: when someone joins via invite code, the BE
  // publishes a frame on /topic/rooms.{id}.members. Invalidate the members
  // and rooms caches so the new member appears immediately without a manual
  // refresh. Subscription scoped to the room screen — closing it stops
  // listening, mirroring the chat subscription's lifetime.
  const qcMembers = useQueryClient();
  const memberDestination = Number.isFinite(roomId) && roomId > 0
    ? `/topic/rooms.${roomId}.members`
    : null;
  useRealtimeSubscription<unknown>(memberDestination, () => {
    qcMembers.invalidateQueries({ queryKey: qk.roomMembers(roomId) });
    qcMembers.invalidateQueries({ queryKey: qk.rooms });
  });

  const members = membersQuery.data ?? [];
  const loading = membersQuery.isLoading;
  const me = members.find((m) => user != null && m.userId === user.id) ?? null;
  const room = (roomsQuery.data ?? []).find((r) => r.id === roomId) ?? null;
  const roomFloor: MinDays = room?.minDailyGoalDays ?? 10;

  // Story 1.6 — J0 WelcomeWindow surface. Gated by the single-seat predicate
  // shouldShowWelcomeWindow so the rule (leader + grace not yet ended) lives
  // in one place. CTA-B sets a transient query-cache flag the Today tab reads
  // to render the warm-tone "첫 잔디 — 곧 함께 채워질 거예요" tagline (AC6).
  const showWelcome = shouldShowWelcomeWindow({
    currentUserId: user?.id ?? null,
    ownerId: room?.ownerId ?? null,
    memberCount: members.length,
    roomCreatedAt: room?.createdAt ?? null,
  });
  const graceEndsAt = room?.createdAt
    ? new Date(new Date(room.createdAt).getTime() + 14 * 24 * 60 * 60 * 1000)
    : null;
  function handleWelcomeStartToday() {
    qcMembers.setQueryData(qk.soloLeaderTagline(roomId), true);
    router.push("/(tabs)/today");
  }

  // Today's per-member snapshot (goal/todos/reflection). Falls into the same
  // cache as the home tab's GroupTodayCard so cross-tab navigation reuses
  // whichever query was warmed first.
  const today = useMemo(() => entryDateOf(), []);
  const groupTodayQuery = useGroupTodayQuery(roomId, today);
  const todayByUser = useMemo(() => {
    const map = new Map<number, MemberTodayDto>();
    for (const m of groupTodayQuery.data ?? []) {
      map.set(m.userId, m);
    }
    return map;
  }, [groupTodayQuery.data]);

  // Current-month window for the per-member achievement bar. Caps the
  // denominator at the actual length of the month so February doesn't show
  // an impossible "31일 목표".
  const { monthFirst, monthLast, monthDayCount } = useMemo(() => {
    const year = Number(today.slice(0, 4));
    const m = Number(today.slice(5, 7));
    const last = new Date(year, m, 0);
    return {
      monthFirst: `${today.slice(0, 7)}-01`,
      monthLast: last.toISOString().slice(0, 10),
      monthDayCount: last.getDate(),
    };
  }, [today]);

  // Fan out per-member grass for the current month. The query key matches
  // the existing useFriendGrassQuery shape so an open friend-profile screen
  // and this card share a single cache entry.
  const memberGrassQueries = useQueries({
    queries: members.map((m) => ({
      queryKey: ["friendGrass", m.userId, monthFirst, monthLast] as const,
      queryFn: () =>
        apiRequest<ApiEnvelope<GrassDayDto[]>>(
          `/profiles/${m.userId}/grass?from=${monthFirst}&to=${monthLast}`,
        ).then((r) => r.data),
      enabled:
        Number.isFinite(m.userId) && m.userId > 0 && Boolean(monthFirst && monthLast),
    })),
  });
  const monthSuccessByIndex = useMemo(
    () =>
      memberGrassQueries.map((q) =>
        q.data == null ? null : q.data.filter((d) => bucketFor(d) > 0).length,
      ),
    [memberGrassQueries],
  );

  function handleCreateInvite() {
    inviteMut.mutate(roomId, {
      onSuccess: (next) => setInvite(next),
    });
  }

  // Story 6.2 AC1/AC4 — split share into Kakao SDK (primary) + plain
  // Share.share (secondary / SDK-failure fallback). The Kakao SDK path
  // forwards the Story 6.1 preview-card image so the recipient sees a
  // rich preview card; the generic path keeps share working even on
  // devices without KakaoTalk or when the SDK rejects the call.
  const kakaoShare = useKakaoShare();
  // Cap at 1 so the description never reads "0명이 함께 살아남는 중" while
  // the members query is still loading (the user themselves is always a
  // member of the room they're sharing from).
  const memberCount = Math.max(1, members.length);
  const roomName = room?.name ?? "그룹";

  function shareInviteKakao() {
    if (!invite) return;
    kakaoShare.mutate(
      { invite, roomName, memberCount },
      {
        onError: () => {
          toast.info("KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요.");
          shareInviteGeneric();
        },
      },
    );
  }

  async function shareInviteGeneric() {
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
        {showWelcome && graceEndsAt != null && room != null ? (
          <SubModeProvider subMode="postcard">
            <WelcomeWindow
              roomName={room.name}
              memberCount={members.length}
              graceEndsAt={graceEndsAt}
              onTapStartToday={handleWelcomeStartToday}
            />
          </SubModeProvider>
        ) : null}
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

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="이 그룹의 Wallet 열기"
          onPress={() => router.push(`/wallet/${roomId}`)}
        >
          <Card tone="raised" size="md">
            <View style={styles.chatRow}>
              <MaterialIcons name="account-balance-wallet" size={20} color={palette.coralDeep} />
              <View style={{ flex: 1 }}>
                <Text variant="bodyStrong">Wallet</Text>
                <Text variant="caption" color={palette.inkMute}>
                  무료 회생권, 개인 포인트, 그룹 포인트를 한곳에서 확인하세요.
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
              {members.map((member, idx) => {
                const denominator = Math.min(member.currentMinimum, monthDayCount);
                return (
                  <RoomMemberCard
                    key={`${member.roomId}-${member.userId}`}
                    member={member}
                    hue={hue}
                    today={todayByUser.get(member.userId) ?? null}
                    monthSuccess={monthSuccessByIndex[idx] ?? null}
                    monthDenominator={denominator}
                    onPress={() =>
                      router.push(`/friend-profile?userId=${member.userId}`)
                    }
                  />
                );
              })}
            </View>
          )}
        </View>

        <Button label="그룹 나가기" tone="ghost" size="md" fullWidth onPress={handleLeave} />

        {/* Story 3.2 — friend-gift surfaces: modal deep-link, RevivalSequence,
            7-day echo footnote. The wrapper reads SecureStore pending slots on
            mount and decides what to render. */}
        {Number.isFinite(roomId) && roomId > 0 ? (
          <FriendGiftSurfaces roomId={roomId} />
        ) : null}
      </ScrollView>

      <InviteCodeSheet
        visible={inviteSheetVisible}
        invite={invite}
        isCreating={inviteMut.isPending}
        onCreate={handleCreateInvite}
        onShareKakao={shareInviteKakao}
        onShareGeneric={shareInviteGeneric}
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
  chatRow: { flexDirection: "row", alignItems: "center", gap: space[3] },
});
