import { useState } from "react";
import { StyleSheet, Text } from "react-native";
import { Link } from "expo-router";
import { GrassGrid } from "../src/components/GrassGrid";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { GrassDay, grassDays } from "../src/domain/mockData";
import { colors } from "../src/theme/tokens";

export default function ProfileScreen() {
  const [selected, setSelected] = useState<GrassDay>(grassDays[0]);

  return (
    <Screen title="내 잔디">
      <Link href="/friend-profile" style={styles.link}>친구 프로필 보기</Link>
      <NeoCard tone="dark">
        <Text style={styles.name}>나의 10살방</Text>
        <GrassGrid days={grassDays} onSelect={setSelected} />
      </NeoCard>
      <NeoCard tone="acid">
        <Text style={styles.detail}>{selected.date}</Text>
        <Text style={styles.detail}>완료 todo {selected.completedTodoCount}개</Text>
      </NeoCard>
    </Screen>
  );
}

const styles = StyleSheet.create({
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  name: { color: colors.paper, fontSize: 22, fontWeight: "900", marginBottom: 14 },
  detail: { color: colors.ink, fontSize: 20, fontWeight: "900" }
});
