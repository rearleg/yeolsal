import { router, useLocalSearchParams } from "expo-router";
import { useEffect, useState } from "react";
import { Pressable, ScrollView, StyleSheet, View } from "react-native";
import { type MinDays } from "../../../src/api/rooms";
import { useAuth } from "../../../src/auth/AuthContext";
import { useRequireAuth } from "../../../src/auth/useRequireAuth";
import { Screen } from "../../../src/components/Screen";
import { RoomMinimumSettings } from "../../../src/components/rooms/RoomMinimumSettings";
import { RecordVisibilityToggle } from "../../../src/components/survival/RecordVisibilityToggle";
import { Skeleton } from "../../../src/components/ui/Skeleton";
import { Text } from "../../../src/components/ui/Text";
import {
  useRoomMembersQuery,
  useRoomsQuery,
  useUpdateMyMinimum,
} from "../../../src/lib/query/hooks/rooms";
import { space } from "../../../src/theme/spacing";
import { palette } from "../../../src/theme/tokens";

/**
 * Plan PR K: dedicated room settings page. The detail screen now leads
 * with members + chat, and the minimum-days picker lives here. This
 * route is also the landing page right after a fresh join — passing
 * {@code onboarding=1} mounts a banner that nudges first-time users
 * to set their personal minimum before continuing.
 */
export default function RoomSettingsScreen() {
  const params = useLocalSearchParams<{ id: string; onboarding?: string }>();
  const roomId = Number(params.id);
  const onboarding = params.onboarding === "1";
  useRequireAuth();
  const { user } = useAuth();
  const membersQuery = useRoomMembersQuery(roomId);
  const roomsQuery = useRoomsQuery();
  const updateMinMut = useUpdateMyMinimum();

  const members = membersQuery.data ?? [];
  const me = members.find((m) => user != null && m.userId === user.id) ?? null;
  const room = (roomsQuery.data ?? []).find((r) => r.id === roomId) ?? null;
  const roomFloor: MinDays = room?.minDailyGoalDays ?? 10;
  const currentMin: MinDays = me?.currentMinimum ?? roomFloor;
  const [pendingMin, setPendingMin] = useState<MinDays>(currentMin);

  // Re-sync the picker whenever the persisted membership row arrives
  // or changes from another tab — without this, the segmented control
  // would lock to the first-seen value.
  useEffect(() => {
    if (me?.currentMinimum != null) {
      setPendingMin(me.currentMinimum);
    }
  }, [me?.currentMinimum]);

  function handleSubmit(min: MinDays) {
    updateMinMut.mutate(
      { roomId, minDailyGoalDays: min },
      {
        onSuccess: () => {
          if (onboarding) {
            // After the first-time setup, drop the user onto the room
            // detail rather than leaving them on the settings page.
            router.replace(`/rooms/${roomId}`);
          }
        },
      },
    );
  }

  if (membersQuery.isLoading || roomsQuery.isLoading) {
    return (
      <Screen title="그룹 설정">
        <View style={styles.skeleton}>
          <Skeleton height={48} />
          <Skeleton height={120} />
        </View>
      </Screen>
    );
  }

  if (!me) {
    return (
      <Screen title="그룹 설정">
        <View style={styles.notMember}>
          <Text variant="bodyStrong">접근 권한이 없습니다.</Text>
          <Text variant="bodySmall" color={palette.inkMute}>
            먼저 그룹에 참여해야 설정 화면을 볼 수 있어요.
          </Text>
        </View>
      </Screen>
    );
  }

  return (
    <Screen title="그룹 설정">
      <ScrollView contentContainerStyle={styles.content}>
        <RoomMinimumSettings
          value={pendingMin}
          onChange={setPendingMin}
          currentMinimum={currentMin}
          roomFloor={roomFloor}
          saving={updateMinMut.isPending}
          onboarding={onboarding}
          onSubmit={handleSubmit}
        />
        <RecordVisibilityToggle roomId={roomId} />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="그룹 규칙 편집"
          onPress={() => router.push(`/rooms/${roomId}/settings/rule`)}
          style={({ pressed }) => [
            styles.ruleRow,
            pressed && styles.ruleRowPressed,
          ]}
        >
          <View style={styles.ruleRowText}>
            <Text variant="bodyStrong">그룹 규칙</Text>
            <Text variant="caption" color={palette.inkMute}>
              매일 업데이트 · 주말 포함 여부
            </Text>
          </View>
          <Text variant="bodyStrong" color={palette.inkMute}>
            ›
          </Text>
        </Pressable>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  skeleton: { gap: space[2] },
  notMember: { gap: space[2] },
  ruleRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
    paddingVertical: space[3],
    paddingHorizontal: space[3],
    backgroundColor: palette.surfaceRaised,
    borderRadius: 12,
  },
  ruleRowPressed: {
    backgroundColor: palette.surfaceSunken,
  },
  ruleRowText: { flex: 1, gap: space[1] },
});
