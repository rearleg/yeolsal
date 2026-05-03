import { router } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, ScrollView, StyleSheet, View } from "react-native";
import type { GrassDayDto } from "../../src/api/types";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { Card } from "../../src/components/ui/Card";
import { Text } from "../../src/components/ui/Text";
import { Button } from "../../src/components/ui/Button";
import { ContributionGrid } from "../../src/components/grid/ContributionGrid";
import { DayDetailCard } from "../../src/components/grid/DayDetailCard";
import { bucketFor } from "../../src/lib/bucket";
import { entryDateOf, rollingRange } from "../../src/lib/calendar";
import {
  useGrassQuery,
  useMonthlyStatsQuery,
  useProfileQuery,
} from "../../src/lib/query/hooks/profile";
import { palette, surface } from "../../src/theme/tokens";
import { space } from "../../src/theme/spacing";

const ROLLING_DAYS = 365;

export default function ProfileScreen() {
  const auth = useRequireAuth();
  const [selected, setSelected] = useState<GrassDayDto | null>(null);

  const { from, to, today } = useMemo(() => {
    const t = entryDateOf();
    const range = rollingRange(t, ROLLING_DAYS);
    return { from: range[0], to: range[range.length - 1], today: t };
  }, []);

  const month = useMemo(() => today.slice(0, 7), [today]);
  const { monthFirst, monthLast, monthDayCount } = useMemo(() => {
    const year = Number(month.slice(0, 4));
    const m = Number(month.slice(5, 7));
    const last = new Date(year, m, 0);
    return {
      monthFirst: `${month}-01`,
      monthLast: last.toISOString().slice(0, 10),
      monthDayCount: last.getDate(),
    };
  }, [month]);

  const profileQuery = useProfileQuery();
  const grassQuery = useGrassQuery(from, to);
  const monthlyStatsQuery = useMonthlyStatsQuery(month);
  const profile = profileQuery.data ?? null;
  const grass = useMemo(() => grassQuery.data ?? [], [grassQuery.data]);
  const loading = profileQuery.isLoading || grassQuery.isLoading;

  useEffect(() => {
    if (selected || grass.length === 0) return;
    const todays = grass.find((d) => d.date === today);
    setSelected(todays ?? grass[grass.length - 1] ?? null);
  }, [grass, selected, today]);

  const stats = useMemo(() => {
    let success = 0;
    let totalTodos = 0;
    let streak = 0;
    let streakRunning = true;
    const today = entryDateOf();
    for (let i = grass.length - 1; i >= 0; i -= 1) {
      const day = grass[i];
      const bucket = bucketFor(day);
      if (bucket > 0) {
        success += 1;
      }
      totalTodos += day.completedTodoCount;
      if (streakRunning) {
        if (bucket > 0) {
          streak += 1;
        } else if (i === grass.length - 1 && day.date === today) {
          // today not yet recorded — skip without breaking the run
        } else {
          streakRunning = false;
        }
      }
    }
    return { success, totalTodos, streak };
  }, [grass]);

  // Slice this-month days off the rolling window so the monthly summary stays
  // consistent with the (already-loaded) /grass response — no extra request.
  const monthGrass = useMemo(
    () => grass.filter((d) => d.date >= monthFirst && d.date <= monthLast),
    [grass, monthFirst, monthLast],
  );
  const monthSuccess = useMemo(
    () => monthGrass.filter((d) => bucketFor(d) > 0).length,
    [monthGrass],
  );
  const monthReflectionCount = useMemo(
    () => monthGrass.filter((d) => d.reflectionSubmitted).length,
    [monthGrass],
  );
  const monthTodoTotal = useMemo(
    () => monthGrass.reduce((sum, d) => sum + d.completedTodoCount, 0),
    [monthGrass],
  );
  // Prefer BE-computed monthly stat when present (it counts entries that
  // satisfied the daily mission rule); otherwise fall back to the local
  // success bucket count so the card still renders during a slow stats fetch.
  const monthCompleted = monthlyStatsQuery.data?.completedDailyCount ?? monthSuccess;
  const monthRate = monthDayCount === 0
    ? 0
    : Math.min(100, Math.round((monthCompleted / monthDayCount) * 100));

  const initial = (profile?.nickname ?? "나").slice(0, 1);

  return (
    <Screen title="마이">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {loading && grass.length === 0 ? <ActivityIndicator color={palette.coralDeep} /> : null}

        <Card tone="hero" size="hero">
          <View style={styles.identityRow}>
            <View style={styles.avatar}>
              <Text variant="h1" color={palette.coralDeep}>{initial}</Text>
            </View>
            <View style={styles.identityText}>
              <Text variant="h2">{profile?.nickname ?? "나의 잔디"}</Text>
              <Text variant="bodySmall" color={palette.inkMute}>{profile?.email ?? ""}</Text>
            </View>
          </View>
          <View style={styles.metricsRow}>
            <Metric label="연속 성공" value={`${stats.streak}일`} />
            <View style={styles.metricDivider} />
            <Metric label="성공한 날" value={`${stats.success}일`} />
            <View style={styles.metricDivider} />
            <Metric label="완료한 할 일" value={`${stats.totalTodos}개`} />
          </View>
        </Card>

        <Card tone="raised" size="lg">
          <View style={styles.cardHeader}>
            <View>
              <Text variant="title">{Number(month.slice(5, 7))}월 요약</Text>
              <Text variant="caption" color={palette.inkMute}>
                이번 달의 흐름을 한눈에 확인하세요.
              </Text>
            </View>
          </View>
          <View style={styles.overviewRow}>
            <View style={styles.overviewLeft}>
              <Text variant="caption" color={palette.inkMute}>이번 달 잔디</Text>
              <View style={styles.overviewCount}>
                <Text variant="numericDisplay" color={palette.coralDeep}>{monthCompleted}</Text>
                <Text variant="bodySmall" color={palette.inkMute}>/ {monthDayCount}일</Text>
              </View>
            </View>
            <View style={styles.rateBadge}>
              <Text variant="caption" color={palette.inkSoft}>달성률</Text>
              <Text variant="title" color={palette.ink}>{monthRate}%</Text>
            </View>
          </View>
          <View style={styles.progressTrack}>
            <View style={[styles.progressFill, { width: `${monthRate}%` }]} />
          </View>
          <View style={styles.metricRow}>
            <View style={styles.miniMetric}>
              <Text variant="caption" color={palette.inkMute}>작성한 회고</Text>
              <Text variant="numericTabular" color={palette.ink}>{monthReflectionCount}</Text>
            </View>
            <View style={styles.miniMetricDivider} />
            <View style={styles.miniMetric}>
              <Text variant="caption" color={palette.inkMute}>완료한 할 일</Text>
              <Text variant="numericTabular" color={palette.ink}>{monthTodoTotal}</Text>
            </View>
          </View>
        </Card>

        <Card tone="raised" size="lg">
          <View style={styles.cardHeader}>
            <View>
              <Text variant="title">잔디</Text>
              <Text variant="caption" color={palette.inkMute}>
                최근 365일 · 오늘 {selected ? friendlyToday(selected.date) : ""}
              </Text>
            </View>
          </View>
          <ContributionGrid days={grass} selectedDate={selected?.date ?? null} onSelect={setSelected} />
        </Card>

        <DayDetailCard day={selected} />

        <View style={{ gap: space[2] }}>
          <Button
            label="알림 설정"
            tone="secondary"
            size="md"
            fullWidth
            onPress={() => router.push("/notification-settings")}
          />
          <Button label="로그아웃" tone="ghost" size="md" fullWidth onPress={auth.signOut} />
        </View>
      </ScrollView>
    </Screen>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.metricItem}>
      <Text variant="numericTabular" color={palette.ink}>{value}</Text>
      <Text variant="caption" color={palette.inkMute}>{label}</Text>
    </View>
  );
}

function friendlyToday(iso: string): string {
  const [, m, d] = iso.split("-");
  return `${Number.parseInt(m, 10)}/${Number.parseInt(d, 10)}`;
}

const styles = StyleSheet.create({
  content: { gap: space[4], paddingBottom: space[8] },
  identityRow: { flexDirection: "row", alignItems: "center", gap: space[3] },
  avatar: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: palette.coralSoft
  },
  identityText: { gap: 2, flex: 1 },
  metricsRow: {
    flexDirection: "row",
    alignItems: "center",
    marginTop: space[4],
    paddingTop: space[3],
    borderTopWidth: 1,
    borderTopColor: surface.border
  },
  metricDivider: { width: 1, height: 28, backgroundColor: surface.border, marginHorizontal: space[1] },
  metricItem: { flex: 1, gap: 2, alignItems: "center" },
  cardHeader: {
    flexDirection: "row",
    alignItems: "flex-start",
    justifyContent: "space-between",
    marginBottom: space[3]
  },
  overviewRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: space[3],
  },
  overviewLeft: { gap: space[1], flex: 1 },
  overviewCount: { flexDirection: "row", alignItems: "baseline", gap: space[1] },
  rateBadge: {
    alignItems: "center",
    paddingVertical: space[2],
    paddingHorizontal: space[3],
    borderRadius: 12,
    backgroundColor: surface.sunken,
  },
  progressTrack: {
    marginTop: space[3],
    height: 10,
    borderRadius: 5,
    backgroundColor: surface.sunken,
    overflow: "hidden",
  },
  progressFill: { height: "100%", borderRadius: 5, backgroundColor: palette.coralDeep },
  metricRow: {
    flexDirection: "row",
    alignItems: "center",
    marginTop: space[3],
    paddingTop: space[3],
    borderTopWidth: 1,
    borderTopColor: surface.border,
  },
  miniMetric: { flex: 1, gap: 2, alignItems: "center" },
  miniMetricDivider: { width: 1, height: 28, backgroundColor: surface.border, marginHorizontal: space[1] },
});
