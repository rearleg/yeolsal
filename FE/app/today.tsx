import { StyleSheet, Text, View } from "react-native";
import { Link } from "expo-router";
import { NeoButton } from "../src/components/NeoButton";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { todayTodos } from "../src/domain/mockData";
import { colors } from "../src/theme/tokens";

export default function TodayScreen() {
  return (
    <Screen title="오늘의 약속">
      <View style={styles.nav}>
        <Link href="/feed" style={styles.navLink}>피드</Link>
        <Link href="/monthly" style={styles.navLink}>월간</Link>
        <Link href="/profile" style={styles.navLink}>프로필</Link>
      </View>
      <NeoCard tone="pink">
        <Text style={styles.poster}>다음날 06:00 전 회고까지</Text>
        <Text style={styles.goal}>수학 오답 + 독서 + 운동 인증</Text>
      </NeoCard>
      <NeoCard tone="paper" style={styles.list}>
        {todayTodos.map((todo) => (
          <View key={todo.id} style={styles.todoRow}>
            <Text style={styles.todoMark}>{todo.done ? "DONE" : "TODO"}</Text>
            <Text style={styles.todoText}>{todo.title}</Text>
          </View>
        ))}
      </NeoCard>
      <NeoButton label="오늘 목표/todo 게시" />
      <NeoButton label="회고 제출" tone="pink" />
    </Screen>
  );
}

const styles = StyleSheet.create({
  nav: { flexDirection: "row", gap: 10 },
  navLink: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  poster: { color: colors.ink, fontWeight: "900", fontSize: 16 },
  goal: { color: colors.ink, fontWeight: "900", fontSize: 34, marginTop: 8 },
  list: { gap: 10 },
  todoRow: { flexDirection: "row", alignItems: "center", gap: 10 },
  todoMark: { minWidth: 62, color: colors.ink, fontWeight: "900", backgroundColor: colors.acid, padding: 6 },
  todoText: { flex: 1, color: colors.ink, fontSize: 16, fontWeight: "700" }
});
