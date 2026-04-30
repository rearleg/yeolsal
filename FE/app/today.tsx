import { MaterialIcons } from "@expo/vector-icons";
import { router, useFocusEffect } from "expo-router";
import { useCallback, useMemo, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View
} from "react-native";
import { type ApiEnvelope, apiRequest } from "../src/api/client";
import type { DailyEntryDto, DailyFeedItem } from "../src/api/types";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Card } from "../src/components/ui/Card";
import { Button } from "../src/components/ui/Button";
import { Text } from "../src/components/ui/Text";
import { palette, pickRoomAccent, roomHues, semantic, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";
import { entryDateOf } from "../src/lib/calendar";

type FriendStatus = "empty" | "goal" | "reflection";

interface FriendChip {
  id: number;
  name: string;
  status: FriendStatus;
  todoCount: number;
}

export default function TodayScreen() {
  const auth = useRequireAuth();
  const [entry, setEntry] = useState<DailyEntryDto | null>(null);
  const [feed, setFeed] = useState<DailyFeedItem[]>([]);
  const [goal, setGoal] = useState("");
  const [newTodo, setNewTodo] = useState("");
  const [reflection, setReflection] = useState("");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  useFocusEffect(
    useCallback(() => {
      if (!auth.loading && auth.user) {
        load();
      }
    }, [auth.loading, auth.user])
  );

  async function load() {
    setLoading(true);
    try {
      const date = entryDateOf();
      const [todayResponse, feedResponse] = await Promise.all([
        apiRequest<ApiEnvelope<DailyEntryDto | null>>("/daily-entries/today"),
        apiRequest<ApiEnvelope<DailyFeedItem[]>>(`/feed/daily?date=${date}`)
      ]);
      setEntry(todayResponse.data);
      setFeed(feedResponse.data);
      setGoal(todayResponse.data?.goal ?? "");
    } catch (error) {
      Alert.alert("오늘", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function saveGoal() {
    if (!goal.trim()) {
      Alert.alert("오늘의 목표", "목표를 입력하세요.");
      return;
    }
    setSubmitting(true);
    try {
      const response = await apiRequest<ApiEnvelope<DailyEntryDto>>("/daily-entries/today", {
        method: "PATCH",
        body: JSON.stringify({ goal: goal.trim() })
      });
      setEntry(response.data);
      setGoal(response.data.goal);
    } catch (error) {
      Alert.alert("저장 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    } finally {
      setSubmitting(false);
    }
  }

  async function addTodo() {
    if (!newTodo.trim()) return;
    if (!entry) {
      Alert.alert("ToDo", "오늘의 목표를 먼저 저장하세요.");
      return;
    }
    setSubmitting(true);
    try {
      await apiRequest("/daily-entries/today/todo-items", {
        method: "POST",
        body: JSON.stringify({ title: newTodo.trim() })
      });
      setNewTodo("");
      await load();
    } catch (error) {
      Alert.alert("ToDo", error instanceof Error ? error.message : "추가하지 못했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  async function updateTodo(id: number, payload: { title?: string; completed?: boolean }) {
    try {
      await apiRequest(`/todo-items/${id}`, { method: "PATCH", body: JSON.stringify(payload) });
      await load();
    } catch (error) {
      Alert.alert("ToDo", error instanceof Error ? error.message : "수정하지 못했습니다.");
    }
  }

  async function deleteTodo(id: number) {
    try {
      await apiRequest(`/todo-items/${id}`, { method: "DELETE" });
      await load();
    } catch (error) {
      Alert.alert("ToDo", error instanceof Error ? error.message : "삭제하지 못했습니다.");
    }
  }

  async function submitReflection() {
    if (!entry || !reflection.trim()) {
      Alert.alert("회고", "회고 내용을 입력하세요.");
      return;
    }
    setSubmitting(true);
    try {
      await apiRequest("/reflections", {
        method: "POST",
        body: JSON.stringify({ dailyEntryId: entry.id, body: reflection.trim() })
      });
      setReflection("");
      await load();
    } catch (error) {
      Alert.alert("회고 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    } finally {
      setSubmitting(false);
    }
  }

  const chips = useMemo<FriendChip[]>(
    () =>
      feed.map((f) => ({
        id: f.userId,
        name: f.nickname,
        status: f.reflectionSubmitted ? "reflection" : f.goal ? "goal" : "empty",
        todoCount: f.completedTodoCount
      })),
    [feed]
  );

  const todos = entry?.todos ?? [];
  const completedCount = todos.filter((t) => t.completed).length;
  const reflectionDone = !!entry?.reflection;

  return (
    <Screen title="오늘">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {loading ? <ActivityIndicator color={palette.sageDeep} /> : null}

        {chips.length > 0 ? (
          <Card tone="raised" size="md">
            <View style={styles.chipHeader}>
              <Text variant="title">친구들의 오늘</Text>
              <Text variant="caption" color={palette.inkMute}>{feed.length}명</Text>
            </View>
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.chipRow}
            >
              {chips.map((chip) => <Chip key={chip.id} chip={chip} />)}
            </ScrollView>
          </Card>
        ) : null}

        <Card tone="raised" size="md">
          <View style={styles.cardHeader}>
            <View style={styles.titleRow}>
              <MaterialIcons name="flag" size={18} color={palette.sageDeep} />
              <Text variant="title">오늘의 목표</Text>
            </View>
            <Button label="저장" tone="primary" size="sm" onPress={saveGoal} disabled={submitting} />
          </View>
          <TextInput
            value={goal}
            onChangeText={setGoal}
            placeholder="오늘 어떤 하루를 보낼까요?"
            placeholderTextColor={palette.inkFaint}
            multiline
            style={styles.goalInput}
          />
        </Card>

        <Card tone="raised" size="md">
          <View style={styles.cardHeader}>
            <View style={styles.titleRow}>
              <MaterialIcons name="checklist" size={18} color={palette.ink} />
              <Text variant="title">할 일</Text>
            </View>
            <Text variant="caption" color={palette.inkMute}>
              {completedCount}/{todos.length}
            </Text>
          </View>
          <View style={styles.addRow}>
            <TextInput
              value={newTodo}
              onChangeText={setNewTodo}
              placeholder="할 일 추가"
              placeholderTextColor={palette.inkFaint}
              returnKeyType="done"
              onSubmitEditing={addTodo}
              style={styles.addInput}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="할 일 추가"
              disabled={submitting}
              onPress={addTodo}
              style={styles.addButton}
            >
              <MaterialIcons name="add" size={18} color={palette.surface} />
            </Pressable>
          </View>
          <View style={styles.todoList}>
            {todos.map((todo) => (
              <View key={todo.id} style={styles.todoItem}>
                <Pressable
                  accessibilityRole="checkbox"
                  accessibilityState={{ checked: todo.completed }}
                  onPress={() => updateTodo(todo.id, { completed: !todo.completed })}
                  style={[styles.checkbox, todo.completed && styles.checkboxDone]}
                >
                  {todo.completed ? <MaterialIcons name="check" size={14} color={palette.surface} /> : null}
                </Pressable>
                <TextInput
                  defaultValue={todo.title}
                  onEndEditing={(event) => {
                    const title = event.nativeEvent.text.trim();
                    if (title && title !== todo.title) {
                      updateTodo(todo.id, { title });
                    } else if (!title) {
                      load();
                    }
                  }}
                  style={[styles.todoInput, todo.completed && styles.todoDone]}
                />
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="할 일 삭제"
                  onPress={() => deleteTodo(todo.id)}
                  style={styles.deleteButton}
                >
                  <MaterialIcons name="close" size={16} color={palette.inkMute} />
                </Pressable>
              </View>
            ))}
          </View>
        </Card>

        <Card tone="raised" size="md">
          <View style={styles.cardHeader}>
            <View style={styles.titleRow}>
              <MaterialIcons name="edit-note" size={20} color={roomHues.salmon.deep} />
              <Text variant="title">회고</Text>
            </View>
            <Text variant="caption" color={palette.inkMute}>
              {reflectionDone ? "완료" : `${reflection.length}/500`}
            </Text>
          </View>
          {reflectionDone ? (
            <View style={styles.reflectionRead}>
              <Text variant="body" color={palette.ink}>{entry?.reflection?.body}</Text>
            </View>
          ) : (
            <View style={styles.reflectionWrap}>
              <TextInput
                value={reflection}
                onChangeText={(text) => setReflection(text.slice(0, 500))}
                placeholder="오늘을 짧게 기록해보세요."
                placeholderTextColor={palette.inkFaint}
                multiline
                style={styles.reflectionInput}
              />
              <View style={styles.reflectionActions}>
                <Button
                  label="회고 제출"
                  tone="primary"
                  size="sm"
                  onPress={submitReflection}
                  disabled={submitting || !entry}
                />
              </View>
            </View>
          )}
        </Card>
      </ScrollView>
    </Screen>
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
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${chip.name} ${chip.status === "reflection" ? "회고 완료" : chip.status === "goal" ? "목표 작성" : "기록 없음"}`}
      onPress={() => router.push(`/friend-profile?userId=${chip.id}`)}
      style={({ pressed }) => [styles.chip, { borderColor: hue.base }, pressed && { opacity: 0.7 }]}
    >
      <View style={[styles.chipAvatar, { backgroundColor: hue.soft }]}>
        <Text variant="bodyStrong" color={hue.deep}>{chip.name.slice(0, 1)}</Text>
        <View style={[styles.chipDot, { backgroundColor: dot }]} />
      </View>
      <Text variant="caption" color={palette.ink} numberOfLines={1}>{chip.name}</Text>
      <Text variant="caption" color={palette.inkMute}>{chip.todoCount}개</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  chipHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: space[2]
  },
  chipRow: { gap: space[2], paddingRight: space[3] },
  chip: {
    width: 72,
    paddingVertical: space[2],
    paddingHorizontal: space[1],
    alignItems: "center",
    gap: space[1],
    borderRadius: 14,
    borderWidth: 1,
    backgroundColor: surface.card
  },
  chipAvatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center"
  },
  chipDot: {
    position: "absolute",
    right: -2,
    bottom: -2,
    width: 10,
    height: 10,
    borderRadius: 5,
    borderWidth: 2,
    borderColor: surface.card
  },
  cardHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: space[3]
  },
  titleRow: { flexDirection: "row", alignItems: "center", gap: space[2] },
  goalInput: {
    minHeight: 56,
    color: palette.ink,
    fontSize: 18,
    fontWeight: "600",
    lineHeight: 26,
    padding: 0
  },
  addRow: { flexDirection: "row", alignItems: "center", gap: space[2] },
  addInput: {
    flex: 1,
    height: 40,
    paddingHorizontal: space[3],
    borderRadius: 12,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
    color: palette.ink,
    fontSize: 14
  },
  addButton: {
    width: 40,
    height: 40,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: palette.sageDeep
  },
  todoList: { marginTop: space[2], borderTopWidth: 1, borderTopColor: surface.border },
  todoItem: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[2],
    height: 44,
    borderBottomWidth: 1,
    borderBottomColor: surface.border
  },
  checkbox: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 1.5,
    borderColor: palette.borderStrong,
    alignItems: "center",
    justifyContent: "center"
  },
  checkboxDone: { borderColor: palette.sageDeep, backgroundColor: palette.sageDeep },
  todoInput: {
    flex: 1,
    color: palette.ink,
    fontSize: 14,
    fontWeight: "500",
    padding: 0
  },
  todoDone: { color: palette.inkMute, textDecorationLine: "line-through" },
  deleteButton: { width: 28, height: 40, alignItems: "center", justifyContent: "center" },
  reflectionRead: {
    backgroundColor: surface.sunken,
    borderRadius: 12,
    padding: space[3]
  },
  reflectionWrap: {
    backgroundColor: surface.sunken,
    borderRadius: 12,
    overflow: "hidden"
  },
  reflectionInput: {
    minHeight: 96,
    padding: space[3],
    color: palette.ink,
    fontSize: 14,
    lineHeight: 20
  },
  reflectionActions: {
    flexDirection: "row",
    justifyContent: "flex-end",
    padding: space[2]
  }
});
