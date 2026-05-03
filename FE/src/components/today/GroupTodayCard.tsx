import { MaterialIcons } from "@expo/vector-icons";
import { router } from "expo-router";
import { useEffect, useState } from "react";
import {
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  View,
} from "react-native";
import type { Room } from "../../api/rooms";
import {
  useGroupTodayQuery,
  useRoomsQuery,
} from "../../lib/query/hooks/rooms";
import { space } from "../../theme/spacing";
import {
  palette,
  pickRoomAccent,
  roomHues,
  semantic,
  surface,
} from "../../theme/tokens";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { EmptyState } from "../ui/EmptyState";
import { Skeleton } from "../ui/Skeleton";
import { Text } from "../ui/Text";

interface Props {
  /** Entry-date the parent page is currently rendering (yyyy-MM-dd). */
  date: string;
}

export function GroupTodayCard({ date }: Props) {
  const roomsQuery = useRoomsQuery();
  const rooms: Room[] = roomsQuery.data ?? [];
  const [selectedRoomId, setSelectedRoomId] = useState<number | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);

  // Default to the first room once the rooms list arrives, AND reset if the
  // currently-selected room disappears (e.g. user left it from another tab).
  // Without the second guard the header would silently fall back to the empty
  // "그룹 선택" placeholder while keeping a stale id in state.
  useEffect(() => {
    if (rooms.length === 0) return;
    const stillExists = rooms.some((r) => r.id === selectedRoomId);
    if (!stillExists) {
      setSelectedRoomId(rooms[0].id);
    }
  }, [rooms, selectedRoomId]);

  const todayQuery = useGroupTodayQuery(selectedRoomId, date);
  const selectedRoom = rooms.find((r) => r.id === selectedRoomId) ?? null;

  if (roomsQuery.isLoading) {
    return (
      <Card tone="raised" size="md">
        <Skeleton height={64} />
      </Card>
    );
  }
  if (rooms.length === 0) {
    return (
      <EmptyState
        title="아직 소속된 그룹이 없어요"
        description="초대 코드로 가입하거나 새 그룹을 만들어보세요."
        action={
          <View style={styles.emptyActions}>
            <Button
              label="그룹 만들기"
              tone="primary"
              size="md"
              fullWidth
              onPress={() => router.push("/(tabs)/rooms")}
            />
            <Button
              label="초대 코드로 참여"
              tone="secondary"
              size="md"
              fullWidth
              onPress={() => router.push("/join")}
            />
          </View>
        }
      />
    );
  }

  return (
    <Card tone="raised" size="md">
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="그룹 선택"
        accessibilityHint={selectedRoom ? `현재 ${selectedRoom.name}` : "그룹을 선택하세요"}
        onPress={() => setPickerOpen(true)}
        style={({ pressed }) => [styles.header, pressed && { opacity: 0.7 }]}
      >
        <Text variant="title" numberOfLines={1} style={{ flex: 1 }}>
          {selectedRoom ? `${selectedRoom.name} 오늘` : "그룹 선택"}
        </Text>
        <MaterialIcons name="expand-more" size={20} color={palette.inkMute} />
      </Pressable>

      {todayQuery.isLoading ? (
        <Skeleton height={88} />
      ) : todayQuery.isError ? (
        <Text variant="caption" color={semantic.danger.fg}>
          그룹 정보를 불러오지 못했어요.
        </Text>
      ) : (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
          {(todayQuery.data ?? []).map((m) => {
            const status = m.reflectionSubmitted ? "reflection" : m.goalSet ? "goal" : "empty";
            const accent = pickRoomAccent(m.userId);
            const hue = roomHues[accent];
            const dot =
              status === "reflection"
                ? semantic.success.fg
                : status === "goal"
                  ? semantic.warning.fg
                  : palette.inkFaint;
            // Status is currently colour-only on the dot. Surface it as part of
            // the chip's accessible label so screen readers don't lose the
            // signal a sighted user reads off the dot colour.
            const statusLabel =
              status === "reflection" ? "회고 완료" : status === "goal" ? "목표 작성" : "기록 없음";
            return (
              <View
                key={m.userId}
                style={[styles.chip, { borderColor: hue.base }]}
                accessibilityRole="text"
                accessibilityLabel={`${m.nickname} ${statusLabel}, 완료 ${m.completedTodoCount}개, ${m.currentStreak}일 연속`}
              >
                <View style={[styles.avatar, { backgroundColor: hue.soft }]}>
                  <Text variant="bodyStrong" color={hue.deep}>
                    {m.nickname.slice(0, 1)}
                  </Text>
                  <View style={[styles.dot, { backgroundColor: dot }]} />
                </View>
                <Text variant="caption" color={palette.ink} numberOfLines={1}>
                  {m.nickname}
                </Text>
                <Text variant="caption" color={palette.inkMute}>
                  {m.completedTodoCount}개 · {m.currentStreak}일
                </Text>
              </View>
            );
          })}
        </ScrollView>
      )}

      <Modal
        visible={pickerOpen}
        animationType="fade"
        transparent
        onRequestClose={() => setPickerOpen(false)}
      >
        <Pressable
          style={styles.modalScrim}
          onPress={() => setPickerOpen(false)}
          accessibilityLabel="그룹 선택 닫기"
        >
          <Pressable style={styles.modalSheet} onPress={(e) => e.stopPropagation()}>
            <View style={styles.modalHandle} />
            <Text variant="h3" style={{ marginBottom: space[2] }}>
              그룹 선택
            </Text>
            {rooms.map((room) => {
              const selected = room.id === selectedRoomId;
              return (
                <Pressable
                  key={room.id}
                  accessibilityRole="button"
                  accessibilityState={{ selected }}
                  accessibilityLabel={`${room.name} 그룹 선택`}
                  onPress={() => {
                    setSelectedRoomId(room.id);
                    setPickerOpen(false);
                  }}
                  style={({ pressed }) => [
                    styles.modalRow,
                    selected && styles.modalRowSelected,
                    pressed && { opacity: 0.85 },
                  ]}
                >
                  <Text variant="bodyStrong" style={{ flex: 1 }}>
                    {room.name}
                  </Text>
                  {selected ? (
                    <MaterialIcons name="check" size={18} color={palette.coralDeep} />
                  ) : null}
                </Pressable>
              );
            })}
          </Pressable>
        </Pressable>
      </Modal>
    </Card>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[2],
    marginBottom: space[2],
  },
  row: { gap: space[2], paddingRight: space[3] },
  chip: {
    width: 80,
    paddingVertical: space[2],
    paddingHorizontal: space[1],
    alignItems: "center",
    gap: space[1],
    borderRadius: 14,
    borderWidth: 1,
    backgroundColor: surface.card,
  },
  avatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
  },
  dot: {
    position: "absolute",
    right: -2,
    bottom: -2,
    width: 10,
    height: 10,
    borderRadius: 5,
    borderWidth: 2,
    borderColor: surface.card,
  },
  emptyActions: { gap: space[2], width: "100%" },
  modalScrim: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.35)",
    justifyContent: "flex-end",
  },
  modalSheet: {
    backgroundColor: surface.page,
    paddingHorizontal: space[4],
    paddingTop: space[2],
    paddingBottom: space[6],
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    gap: space[1],
  },
  modalHandle: {
    alignSelf: "center",
    width: 40,
    height: 4,
    borderRadius: 2,
    backgroundColor: surface.border,
    marginBottom: space[3],
  },
  modalRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: space[3],
    paddingHorizontal: space[3],
    borderRadius: 12,
  },
  modalRowSelected: {
    backgroundColor: surface.sunken,
  },
});
