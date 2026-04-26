import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { NeoButton } from "../src/components/NeoButton";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { apiRequest, ApiEnvelope } from "../src/api/client";
import { DailyEntryDto } from "../src/api/types";
import { colors, typography } from "../src/theme/tokens";

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
        <View style={styles.heroTitle}>
          <Text style={styles.liveStamp}>DEADLINE: BY 6 AM TOMORROW</Text>
          <Text style={styles.pageHeadline}>Daily{`\n`}Gigs</Text>
        </View>
        {loading ? <ActivityIndicator color={colors.ink} /> : null}

        <View style={styles.focusCard}>
          <View style={styles.focusHeader}>
            <Text style={styles.focusHeaderText}>TODAY'S FOCUS</Text>
            <View style={styles.iconBadge}>
              <MaterialIcons name="local-fire-department" size={30} color={colors.black} />
            </View>
          </View>
          <View style={styles.focusBody}>
            <View style={styles.goalRow}>
              <View style={styles.pinkBar} />
              <TextInput value={goal} onChangeText={setGoal} placeholder="오늘 목표" multiline style={styles.goalInput} />
            </View>
            <View style={styles.metricGrid}>
              <View style={styles.metric}>
                <MaterialIcons name="wb-sunny" size={28} color={colors.black} />
                <Text style={styles.metricLabel}>WAKE</Text>
                <Text style={styles.metricValue}>06:30</Text>
              </View>
              <View style={[styles.metric, styles.metricGreen]}>
                <MaterialIcons name="timer" size={28} color={colors.black} />
                <Text style={styles.metricLabel}>FOCUS</Text>
                <Text style={styles.metricValue}>TODAY</Text>
              </View>
              <View style={[styles.metric, styles.metricPink]}>
                <MaterialIcons name="bedtime" size={28} color={colors.black} />
                <Text style={styles.metricLabel}>SEAL</Text>
                <Text style={styles.metricValue}>06:00</Text>
              </View>
            </View>
          </View>
        </View>

        <View style={styles.hitHeaderWrap}>
          <Text style={styles.hitHeader}>THE HIT LIST</Text>
        </View>
        <TextInput value={todosText} onChangeText={setTodosText} placeholder={"todo를 줄마다 입력\n예: 수학 오답 20분"} multiline style={styles.todosInput} />
        <NeoButton label="오늘 목표/todo 게시" disabled={submitting} onPress={saveEntry} />

        {entry ? (
          <View style={styles.todoList}>
            {entry.todos.map((todo) => (
              <Pressable
                key={todo.id}
                accessibilityRole="checkbox"
                accessibilityState={{ checked: todo.completed }}
                onPress={() => toggleTodo(todo.id, todo.completed)}
                style={[styles.todoRow, todo.completed && styles.todoCompleted]}
              >
                {todo.completed ? <View style={styles.doneRibbon}><Text style={styles.doneRibbonText}>DONE</Text></View> : null}
                <View style={[styles.checkbox, todo.completed && styles.checkboxDone]}>
                  {todo.completed ? <MaterialIcons name="check" size={22} color={colors.black} /> : null}
                </View>
                <Text style={[styles.todoText, todo.completed && styles.todoTextDone]}>{todo.title}</Text>
              </Pressable>
            ))}
          </View>
        ) : null}

        <NeoCard tone="surface" style={styles.reflectionCard}>
          <Text style={styles.reflectionTape}>BRAIN DUMP</Text>
          {entry?.reflection ? <Text style={styles.reflectionText}>{entry.reflection.body}</Text> : <TextInput value={reflection} onChangeText={setReflection} placeholder="Scribble your thoughts here..." multiline style={styles.reflectionInput} />}
        </NeoCard>
        {!entry?.reflection ? <NeoButton label="회고 제출" disabled={submitting || !entry} tone="pink" onPress={submitReflection} /> : null}
        <NeoButton label="로그아웃" tone="black" onPress={auth.signOut} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 18, paddingBottom: 34 },
  heroTitle: { marginTop: 4, gap: 12 },
  liveStamp: {
    alignSelf: "flex-start",
    color: colors.paper,
    backgroundColor: colors.pink,
    borderWidth: 3,
    borderColor: colors.black,
    paddingHorizontal: 10,
    paddingVertical: 6,
    fontSize: 12,
    fontWeight: "900",
    transform: [{ rotate: "-2deg" }],
    shadowColor: colors.black,
    shadowOpacity: 1,
    shadowRadius: 0,
    shadowOffset: { width: 3, height: 3 },
    elevation: 3
  },
  pageHeadline: { color: colors.black, fontSize: 48, lineHeight: 52, fontWeight: typography.headline.fontWeight, textTransform: "uppercase" },
  focusCard: { borderWidth: 4, borderColor: colors.black, backgroundColor: colors.surface, shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 8, height: 8 }, elevation: 9 },
  focusHeader: { minHeight: 76, backgroundColor: colors.green, borderBottomWidth: 4, borderColor: colors.black, padding: 14, flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  focusHeaderText: { color: colors.black, backgroundColor: colors.green, borderWidth: 2, borderColor: colors.black, paddingHorizontal: 8, paddingVertical: 4, fontSize: 28, fontWeight: "900", textTransform: "uppercase", transform: [{ rotate: "-2deg" }] },
  iconBadge: { width: 48, height: 48, borderRadius: 24, borderWidth: 2, borderColor: colors.black, backgroundColor: colors.paper, alignItems: "center", justifyContent: "center", shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 2, height: 2 }, elevation: 3 },
  focusBody: { padding: 18, gap: 20 },
  goalRow: { flexDirection: "row", alignItems: "stretch", gap: 14 },
  pinkBar: { width: 16, minHeight: 88, borderWidth: 2, borderColor: colors.black, backgroundColor: colors.pink },
  goalInput: { flex: 1, minHeight: 88, color: colors.black, fontSize: 34, lineHeight: 38, fontWeight: "900", textTransform: "uppercase", padding: 0 },
  metricGrid: { flexDirection: "row", gap: 10, borderTopWidth: 4, borderColor: colors.black, paddingTop: 16 },
  metric: { flex: 1, alignItems: "center", gap: 4, borderWidth: 2, borderColor: colors.black, backgroundColor: colors.surfaceHigh, paddingVertical: 10, shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 2, height: 2 }, elevation: 3 },
  metricGreen: { backgroundColor: colors.greenNeon },
  metricPink: { backgroundColor: colors.pinkSoft },
  metricLabel: { color: colors.black, fontSize: 12, fontWeight: "900" },
  metricValue: { color: colors.black, fontSize: 13, fontWeight: "900" },
  hitHeaderWrap: { alignItems: "flex-start" },
  hitHeader: { color: colors.paper, backgroundColor: colors.pink, borderWidth: 4, borderColor: colors.black, paddingHorizontal: 12, paddingVertical: 6, fontSize: 26, fontWeight: "900", textTransform: "uppercase", shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 4, height: 4 }, elevation: 4, transform: [{ rotate: "1deg" }] },
  todosInput: { minHeight: 92, borderWidth: 4, borderColor: colors.black, backgroundColor: colors.white, padding: 12, color: colors.black, fontSize: 16, fontWeight: "800", shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 4, height: 4 }, elevation: 4 },
  todoList: { gap: 14 },
  todoRow: { minHeight: 64, flexDirection: "row", alignItems: "center", gap: 14, borderWidth: 4, borderColor: colors.black, backgroundColor: colors.surface, padding: 14, shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 4, height: 4 }, elevation: 4 },
  todoCompleted: { backgroundColor: colors.surfaceHigh },
  doneRibbon: { position: "absolute", top: -12, right: -10, zIndex: 2, backgroundColor: colors.black, borderWidth: 2, borderColor: colors.greenNeon, paddingHorizontal: 8, paddingVertical: 2, transform: [{ rotate: "12deg" }] },
  doneRibbonText: { color: colors.paper, fontSize: 11, fontWeight: "900" },
  checkbox: { width: 34, height: 34, alignItems: "center", justifyContent: "center", borderWidth: 4, borderColor: colors.black, backgroundColor: colors.paper, shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 2, height: 2 }, elevation: 3 },
  checkboxDone: { backgroundColor: colors.greenNeon },
  todoText: { flex: 1, color: colors.black, fontSize: 16, fontWeight: "900", textTransform: "uppercase" },
  todoTextDone: { textDecorationLine: "line-through", opacity: 0.58 },
  reflectionCard: { gap: 12, marginTop: 10 },
  reflectionTape: { alignSelf: "flex-start", marginTop: -30, color: colors.black, backgroundColor: colors.pinkSoft, borderWidth: 4, borderColor: colors.black, paddingHorizontal: 12, paddingVertical: 5, fontWeight: "900", transform: [{ rotate: "-3deg" }] },
  reflectionInput: { minHeight: 120, borderBottomWidth: 4, borderBottomColor: colors.black, borderStyle: "dashed", color: colors.black, fontSize: 16, fontWeight: "700", padding: 8 },
  reflectionText: { color: colors.black, fontSize: 16, fontWeight: "800", lineHeight: 24 }
});
