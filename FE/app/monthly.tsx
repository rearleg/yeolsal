import { StyleSheet, Text, View } from "react-native";
import { Link } from "expo-router";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { colors } from "../src/theme/tokens";

export default function MonthlyScreen() {
  return (
    <Screen title="월간 카운트">
      <Link href="/today" style={styles.link}>오늘로 돌아가기</Link>
      <NeoCard tone="dark">
        <Text style={styles.label}>2026.04</Text>
        <Text style={styles.count}>18</Text>
        <Text style={styles.caption}>성공한 날</Text>
      </NeoCard>
      <View style={styles.blocks}>
        {Array.from({ length: 30 }, (_, index) => (
          <View key={index} style={[styles.block, index % 5 === 0 ? styles.empty : styles.done]} />
        ))}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  label: { color: colors.green, fontSize: 18, fontWeight: "900" },
  count: { color: colors.paper, fontSize: 86, fontWeight: "900" },
  caption: { color: colors.pink, fontSize: 22, fontWeight: "900" },
  blocks: { flexDirection: "row", flexWrap: "wrap", gap: 7 },
  block: { width: 34, height: 34, borderColor: colors.ink, borderWidth: 3 },
  done: { backgroundColor: colors.green },
  empty: { backgroundColor: colors.paper }
});
