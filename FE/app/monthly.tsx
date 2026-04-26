import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, Alert, StyleSheet, Text, View } from "react-native";
import { Link } from "expo-router";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { apiRequest, ApiEnvelope } from "../src/api/client";
import { GrassDayDto, MonthlyStatsDto } from "../src/api/types";
import { colors } from "../src/theme/tokens";

export default function MonthlyScreen() {
  const auth = useRequireAuth();
  const [stats, setStats] = useState<MonthlyStatsDto | null>(null);
  const [grass, setGrass] = useState<GrassDayDto[]>([]);
  const [loading, setLoading] = useState(true);
  const month = useMemo(() => new Date().toISOString().slice(0, 7), []);

  useEffect(() => {
    if (!auth.loading && auth.user) {
      load();
    }
  }, [auth.loading, auth.user]);

  async function load() {
    setLoading(true);
    try {
      const first = `${month}-01`;
      const last = new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).toISOString().slice(0, 10);
      const [statsResponse, grassResponse] = await Promise.all([
        apiRequest<ApiEnvelope<MonthlyStatsDto>>(`/stats/monthly?month=${month}`),
        apiRequest<ApiEnvelope<GrassDayDto[]>>(`/profiles/me/grass?from=${first}&to=${last}`)
      ]);
      setStats(statsResponse.data);
      setGrass(grassResponse.data);
    } catch (error) {
      Alert.alert("월간", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <Screen title="월간 카운트">
      <Link href="/today" style={styles.link}>오늘로 돌아가기</Link>
      {loading ? <ActivityIndicator color={colors.ink} /> : null}
      <NeoCard tone="dark">
        <Text style={styles.label}>{month}</Text>
        <Text style={styles.count}>{stats?.completedDailyCount ?? 0}</Text>
        <Text style={styles.caption}>성공한 날</Text>
      </NeoCard>
      <View style={styles.blocks}>
        {grass.map((day) => (
          <View key={day.date} style={[styles.block, day.missionCompleted ? styles.done : styles.empty]} />
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
