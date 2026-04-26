import { StyleSheet, Text, View } from "react-native";
import { Link } from "expo-router";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { feedItems } from "../src/domain/mockData";
import { colors } from "../src/theme/tokens";

export default function FeedScreen() {
  return (
    <Screen title="친구 피드">
      <Link href="/today" style={styles.link}>오늘로 돌아가기</Link>
      {feedItems.map((item) => (
        <NeoCard key={item.id} tone={item.reflectionSubmitted ? "green" : "paper"}>
          <View style={styles.row}>
            <Text style={styles.name}>{item.name}</Text>
            <Text style={styles.badge}>{item.reflectionSubmitted ? "회고 완료" : "회고 대기"}</Text>
          </View>
          <Text style={styles.goal}>{item.goal}</Text>
          <Text style={styles.meta}>완료 todo {item.todosDone}개</Text>
        </NeoCard>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  row: { flexDirection: "row", justifyContent: "space-between", gap: 10 },
  name: { color: colors.ink, fontSize: 24, fontWeight: "900" },
  badge: { color: colors.ink, fontWeight: "900", backgroundColor: colors.pink, padding: 6 },
  goal: { marginTop: 12, color: colors.ink, fontSize: 18, fontWeight: "800" },
  meta: { marginTop: 8, color: colors.ink, fontWeight: "900" }
});
