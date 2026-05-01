import { router } from "expo-router";
import { Pressable, ScrollView, StyleSheet, View } from "react-native";
import type { DailyFeedItem } from "../../api/types";
import { Card } from "../ui/Card";
import { Text } from "../ui/Text";
import { palette, pickRoomAccent, roomHues, semantic, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";

type FriendStatus = "empty" | "goal" | "reflection";
interface FriendChip {
  id: number;
  name: string;
  status: FriendStatus;
  todoCount: number;
}

interface Props {
  items: DailyFeedItem[];
}

export function TodayChips({ items }: Props) {
  if (items.length === 0) return null;
  const chips: FriendChip[] = items.map((f) => ({
    id: f.userId,
    name: f.nickname,
    status: f.reflectionSubmitted ? "reflection" : f.goal ? "goal" : "empty",
    todoCount: f.completedTodoCount,
  }));
  return (
    <Card tone="raised" size="md">
      <View style={styles.header}>
        <Text variant="title">친구들의 오늘</Text>
        <Text variant="caption" color={palette.inkMute}>
          {items.length}명
        </Text>
      </View>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
        {chips.map((chip) => (
          <Chip key={chip.id} chip={chip} />
        ))}
      </ScrollView>
    </Card>
  );
}

function Chip({ chip }: { chip: FriendChip }) {
  const accent = pickRoomAccent(chip.id);
  const hue = roomHues[accent];
  const dot =
    chip.status === "reflection"
      ? semantic.success.fg
      : chip.status === "goal"
        ? semantic.warning.fg
        : palette.inkFaint;
  const statusLabel =
    chip.status === "reflection" ? "회고 완료" : chip.status === "goal" ? "목표 작성" : "기록 없음";
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${chip.name} ${statusLabel}`}
      onPress={() => router.push(`/friend-profile?userId=${chip.id}`)}
      style={({ pressed }) => [
        styles.chip,
        { borderColor: hue.base },
        pressed && { opacity: 0.7 },
      ]}
    >
      <View style={[styles.avatar, { backgroundColor: hue.soft }]}>
        <Text variant="bodyStrong" color={hue.deep}>
          {chip.name.slice(0, 1)}
        </Text>
        <View style={[styles.dot, { backgroundColor: dot }]} />
      </View>
      <Text variant="caption" color={palette.ink} numberOfLines={1}>
        {chip.name}
      </Text>
      <Text variant="caption" color={palette.inkMute}>
        {chip.todoCount}개
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: space[2],
  },
  row: { gap: space[2], paddingRight: space[3] },
  chip: {
    width: 72,
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
});
