import { router } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, Alert, ScrollView, StyleSheet, View } from "react-native";
import { type ApiEnvelope, apiRequest } from "../src/api/client";
import type { GrassDayDto, ProfileDto } from "../src/api/types";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Card } from "../src/components/ui/Card";
import { Text } from "../src/components/ui/Text";
import { Button } from "../src/components/ui/Button";
import { ContributionGrid } from "../src/components/grid/ContributionGrid";
import { DayDetailCard } from "../src/components/grid/DayDetailCard";
import { bucketFor } from "../src/lib/bucket";
import { entryDateOf, rollingRange } from "../src/lib/calendar";
import { palette, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";

const ROLLING_DAYS = 365;

export default function ProfileScreen() {
  const auth = useRequireAuth();
  const [profile, setProfile] = useState<ProfileDto | null>(null);
  const [grass, setGrass] = useState<GrassDayDto[]>([]);
  const [selected, setSelected] = useState<GrassDayDto | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!auth.loading && auth.user) {
      load();
    }
  }, [auth.loading, auth.user]);

  async function load() {
    setLoading(true);
    try {
      const today = entryDateOf();
      const range = rollingRange(today, ROLLING_DAYS);
      const from = range[0];
      const to = range[range.length - 1];
      const [profileResponse, grassResponse] = await Promise.all([
        apiRequest<ApiEnvelope<ProfileDto>>("/profiles/me"),
        apiRequest<ApiEnvelope<GrassDayDto[]>>(`/profiles/me/grass?from=${from}&to=${to}`)
      ]);
      setProfile(profileResponse.data);
      setGrass(grassResponse.data);
      const todays = grassResponse.data.find((d) => d.date === today);
      setSelected(todays ?? grassResponse.data[grassResponse.data.length - 1] ?? null);
    } catch (error) {
      Alert.alert("프로필", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

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

  const initial = (profile?.nickname ?? "나").slice(0, 1);

  return (
    <Screen title="프로필">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {loading && grass.length === 0 ? <ActivityIndicator color={palette.sageDeep} /> : null}

        <Card tone="hero" size="hero">
          <View style={styles.identityRow}>
            <View style={styles.avatar}>
              <Text variant="h1" color={palette.sageDeep}>{initial}</Text>
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
            label="방 관리"
            tone="secondary"
            size="md"
            fullWidth
            onPress={() => router.push("/rooms")}
          />
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
    backgroundColor: palette.sageSoft
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
  }
});
