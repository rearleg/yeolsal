import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { Link } from "expo-router";
import { NeoButton } from "../src/components/NeoButton";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { apiRequest, ApiEnvelope } from "../src/api/client";
import { DailyEntryDto } from "../src/api/types";
import { colors } from "../src/theme/tokens";

export default function TodayScreen() {
  const auth = useRequireAuth();
  const [entry, setEntry] = useState<DailyEntryDto | null>(null);
  const [goal, setGoal] = useState("");
  const [todosText, setTodosText] = useState("");
  const [reflection, setReflection] = useState("");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!auth.loading && auth.user) {
      load();
    }
  }, [auth.loading, auth.user]);

  async function load() {
    setLoading(true);
    try {
      const response = await apiRequest<ApiEnvelope<DailyEntryDto | null>>("/daily-entries/today");
      setEntry(response.data);
      setGoal(response.data?.goal ?? "");
      setTodosText(response.data?.todos.map((todo) => todo.title).join("\n") ?? "");
    } catch (error) {
      Alert.alert("오늘", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function saveEntry() {
    const todos = todosText.split("\n").map((todo) => todo.trim()).filter(Boolean);
    if (!goal.trim() || todos.length === 0) {
      Alert.alert("오늘의 약속", "목표와 todo를 입력하세요.");
      return;
    }
    setSubmitting(true);
    try {
      const response = await apiRequest<ApiEnvelope<DailyEntryDto>>("/daily-entries", {
        method: "POST",
        body: JSON.stringify({ goal: goal.trim(), todos })
      });
      setEntry(response.data);
      Alert.alert("저장", "오늘 목표/todo를 게시했습니다.");
    } catch (error) {
      Alert.alert("저장 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    } finally {
      setSubmitting(false);
    }
  }

  async function toggleTodo(id: number, completed: boolean) {
    try {
      await apiRequest(`/todo-items/${id}`, {
        method: "PATCH",
        body: JSON.stringify({ completed: !completed })
      });
      await load();
    } catch (error) {
      Alert.alert("Todo", error instanceof Error ? error.message : "수정하지 못했습니다.");
    }
  }

  async function submitReflection() {
    if (!entry || !reflection.trim()) {
      Alert.alert("회고", "저장된 목표와 회고 내용을 확인하세요.");
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
      Alert.alert("회고", "회고를 제출했습니다.");
    } catch (error) {
      Alert.alert("회고 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Screen title="오늘의 약속">
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.nav}>
          <Link href="/feed" style={styles.navLink}>피드</Link>
          <Link href="/monthly" style={styles.navLink}>월간</Link>
          <Link href="/profile" style={styles.navLink}>프로필</Link>
        </View>
        {loading ? <ActivityIndicator color={colors.ink} /> : null}
        <NeoCard tone="pink" style={styles.form}>
          <Text style={styles.poster}>다음날 06:00 전 회고까지</Text>
          <TextInput value={goal} onChangeText={setGoal} placeholder="오늘 목표" multiline style={[styles.input, styles.goalInput]} />
          <TextInput value={todosText} onChangeText={setTodosText} placeholder={"todo를 줄마다 입력\n예: 수학 오답 20분"} multiline style={[styles.input, styles.todosInput]} />
        </NeoCard>
        <NeoButton label="오늘 목표/todo 게시" disabled={submitting} onPress={saveEntry} />
        {entry ? (
          <NeoCard tone="paper" style={styles.list}>
            {entry.todos.map((todo) => (
              <View key={todo.id} style={styles.todoRow}>
                <Text onPress={() => toggleTodo(todo.id, todo.completed)} style={styles.todoMark}>{todo.completed ? "DONE" : "TODO"}</Text>
                <Text style={styles.todoText}>{todo.title}</Text>
              </View>
            ))}
          </NeoCard>
        ) : null}
        <NeoCard tone={entry?.reflection ? "green" : "acid"} style={styles.form}>
          <Text style={styles.poster}>{entry?.reflection ? "회고 제출 완료" : "회고"}</Text>
          {entry?.reflection ? <Text style={styles.todoText}>{entry.reflection.body}</Text> : <TextInput value={reflection} onChangeText={setReflection} placeholder="오늘의 회고" multiline style={[styles.input, styles.todosInput]} />}
        </NeoCard>
        {!entry?.reflection ? <NeoButton label="회고 제출" disabled={submitting || !entry} tone="pink" onPress={submitReflection} /> : null}
        <NeoButton label="로그아웃" tone="acid" onPress={auth.signOut} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16, paddingBottom: 28 },
  nav: { flexDirection: "row", gap: 10 },
  navLink: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  poster: { color: colors.ink, fontWeight: "900", fontSize: 16 },
  form: { gap: 10 },
  input: {
    borderWidth: 3,
    borderColor: colors.ink,
    padding: 12,
    backgroundColor: colors.white,
    color: colors.ink,
    fontWeight: "800"
  },
  goalInput: { minHeight: 72, fontSize: 22 },
  todosInput: { minHeight: 104, fontSize: 16 },
  list: { gap: 10 },
  todoRow: { flexDirection: "row", alignItems: "center", gap: 10 },
  todoMark: { minWidth: 62, color: colors.ink, fontWeight: "900", backgroundColor: colors.acid, padding: 6 },
  todoText: { flex: 1, color: colors.ink, fontSize: 16, fontWeight: "700" }
});
