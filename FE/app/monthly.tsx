import { useFocusEffect } from "expo-router";
import { useCallback, useMemo, useState } from "react";
import { ActivityIndicator, Alert, ScrollView, StyleSheet, View } from "react-native";
import { type ApiEnvelope, apiRequest } from "../src/api/client";
import type { GrassDayDto, MonthlyStatsDto } from "../src/api/types";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Card } from "../src/components/ui/Card";
import { Text } from "../src/components/ui/Text";
import { ContributionGrid } from "../src/components/grid/ContributionGrid";
import { bucketFor } from "../src/lib/bucket";
import { palette, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";

export default function MonthlyScreen() {
  const auth = useRequireAuth();
  const [stats, setStats] = useState<MonthlyStatsDto | null>(null);
  const [grass, setGrass] = useState<GrassDayDto[]>([]);
  const [loading, setLoading] = useState(true);
  const month = useMemo(() => new Date().toISOString().slice(0, 7), []);
  const totalDays = useMemo(
    () => new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate(),
    [month]
  );
  const completed = stats?.completedDailyCount ?? 0;
  const rate = totalDays === 0 ? 0 : Math.min(100, Math.round((completed / totalDays) * 100));

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
      const first = `${month}-01`;
      const last = new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)
        .toISOString()
        .slice(0, 10);
      const [statsResponse, grassResponse] = await Promise.all([
        apiRequest<ApiEnvelope<MonthlyStatsDto>>(`/stats/monthly?month=${month}`),
        apiRequest<ApiEnvelope<GrassDayDto[]>>(`/profiles/me/grass?from=${first}&to=${last}`)
      ]);
      setStats(statsResponse.data);
      setGrass(grassResponse.data);
    } catch (error) {
      Alert.alert("월간 통계", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  const reflectionCount = useMemo(() => grass.filter((d) => d.reflectionSubmitted).length, [grass]);
  const todoTotal = useMemo(() => grass.reduce((sum, d) => sum + d.completedTodoCount, 0), [grass]);
  const successCount = useMemo(() => grass.filter((d) => bucketFor(d) > 0).length, [grass]);

  return (
    <Screen title="기록">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {loading && grass.length === 0 ? <ActivityIndicator color={palette.coralDeep} /> : null}

        <View>
          <Text variant="h1">{Number(month.slice(5, 7))}월 요약</Text>
          <Text variant="bodySmall" color={palette.inkMute}>이번 달의 흐름을 확인하세요.</Text>
        </View>

        <Card tone="hero" size="lg">
          <View style={styles.overviewRow}>
            <View style={styles.overviewLeft}>
              <Text variant="caption" color={palette.inkMute}>이번 달 잔디</Text>
              <View style={styles.overviewCount}>
                <Text variant="numericDisplay" color={palette.coralDeep}>{successCount}</Text>
                <Text variant="bodySmall" color={palette.inkMute}>/ {totalDays}일</Text>
              </View>
            </View>
            <View style={styles.rateBadge}>
              <Text variant="caption" color={palette.inkMute}>달성률</Text>
              <Text variant="title" color={palette.ink}>{rate}%</Text>
            </View>
          </View>
          <View style={styles.progressTrack}>
            <View style={[styles.progressFill, { width: `${rate}%` }]} />
          </View>
        </Card>

        <Card tone="raised" size="lg">
          <View style={styles.cardHeader}>
            <Text variant="title">이번 달 잔디</Text>
            <Text variant="caption" color={palette.inkMute}>{month}</Text>
          </View>
          <ContributionGrid days={grass} />
        </Card>

        <View style={styles.metricRow}>
          <Card tone="default" size="md" style={styles.metricCard}>
            <Text variant="caption" color={palette.inkMute}>작성한 회고</Text>
            <Text variant="numericDisplay" color={palette.ink}>{reflectionCount}</Text>
          </Card>
          <Card tone="default" size="md" style={styles.metricCard}>
            <Text variant="caption" color={palette.inkMute}>완료한 할 일</Text>
            <Text variant="numericDisplay" color={palette.ink}>{todoTotal}</Text>
          </Card>
        </View>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[4], paddingBottom: space[8] },
  overviewRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: space[3]
  },
  overviewLeft: { gap: space[1], flex: 1 },
  overviewCount: { flexDirection: "row", alignItems: "baseline", gap: space[1] },
  rateBadge: {
    alignItems: "center",
    paddingVertical: space[2],
    paddingHorizontal: space[3],
    borderRadius: 12,
    backgroundColor: surface.sunken
  },
  progressTrack: {
    marginTop: space[3],
    height: 10,
    borderRadius: 5,
    backgroundColor: surface.sunken,
    overflow: "hidden"
  },
  progressFill: { height: "100%", borderRadius: 5, backgroundColor: palette.coralDeep },
  cardHeader: {
    flexDirection: "row",
    alignItems: "baseline",
    justifyContent: "space-between",
    marginBottom: space[3]
  },
  metricRow: { flexDirection: "row", gap: space[3] },
  metricCard: { flex: 1, gap: space[1] }
});
