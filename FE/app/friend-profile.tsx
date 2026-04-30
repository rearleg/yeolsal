import { useLocalSearchParams } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, Alert, ScrollView, StyleSheet, View } from "react-native";
import { type ApiEnvelope, apiRequest } from "../src/api/client";
import { listReflections, type ReflectionEntry } from "../src/api/reflections";
import type { GrassDayDto, ProfileDto } from "../src/api/types";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Card } from "../src/components/ui/Card";
import { EmptyState } from "../src/components/ui/EmptyState";
import { Skeleton } from "../src/components/ui/Skeleton";
import { Text } from "../src/components/ui/Text";
import { ContributionGrid } from "../src/components/grid/ContributionGrid";
import { DayDetailCard } from "../src/components/grid/DayDetailCard";
import { bucketFor } from "../src/lib/bucket";
import { entryDateOf, rollingRange } from "../src/lib/calendar";
import { palette, pickRoomAccent, roomHues, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";

const ROLLING_DAYS = 365;

export default function FriendProfileScreen() {
  const auth = useRequireAuth();
  const params = useLocalSearchParams<{ userId?: string }>();
  const userId = Number(params.userId);
  const [profile, setProfile] = useState<ProfileDto | null>(null);
  const [grass, setGrass] = useState<GrassDayDto[]>([]);
  const [selected, setSelected] = useState<GrassDayDto | null>(null);
  const [reflections, setReflections] = useState<ReflectionEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!auth.loading && auth.user && userId) {
      load();
    }
  }, [auth.loading, auth.user, userId]);

  async function load() {
    setLoading(true);
    try {
      const today = entryDateOf();
      const range = rollingRange(today, ROLLING_DAYS);
      const from = range[0];
      const to = range[range.length - 1];
      const [profileResponse, grassResponse, reflectionsResponse] = await Promise.all([
        apiRequest<ApiEnvelope<ProfileDto>>(`/profiles/${userId}`),
        apiRequest<ApiEnvelope<GrassDayDto[]>>(`/profiles/${userId}/grass?from=${from}&to=${to}`),
        listReflections(userId, 20)
      ]);
      setProfile(profileResponse.data);
      setGrass(grassResponse.data);
      setReflections(reflectionsResponse);
      const todays = grassResponse.data.find((d) => d.date === today);
      setSelected(todays ?? grassResponse.data[grassResponse.data.length - 1] ?? null);
    } catch (error) {
      Alert.alert("친구 프로필", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  const stats = useMemo(() => {
    let success = 0;
    let streak = 0;
    let streakRunning = true;
    const today = entryDateOf();
    for (let i = grass.length - 1; i >= 0; i -= 1) {
      const day = grass[i];
      const bucket = bucketFor(day);
      if (bucket > 0) {
        success += 1;
      }
      if (streakRunning) {
        if (bucket > 0) {
          streak += 1;
        } else if (i === grass.length - 1 && day.date === today) {
          // today not recorded yet — keep going
        } else {
          streakRunning = false;
        }
      }
    }
    return { success, streak };
  }, [grass]);

  const accent = useMemo(() => pickRoomAccent(userId || profile?.nickname || "x"), [userId, profile?.nickname]);
  const hue = roomHues[accent];
  const initial = (profile?.nickname ?? "?").slice(0, 1).toUpperCase();

  return (
    <Screen title="친구">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {loading && grass.length === 0 ? <ActivityIndicator color={hue.deep} /> : null}

        <Card tone="hero" size="hero" style={{ borderColor: hue.base, borderWidth: 1 }}>
          <View style={styles.identityRow}>
            <View style={[styles.avatar, { backgroundColor: hue.soft }]}>
              <Text variant="h1" color={hue.deep}>{initial}</Text>
            </View>
            <View style={styles.identityText}>
              <Text variant="h2">{profile?.nickname ?? "친구"}</Text>
              <Text variant="bodySmall" color={palette.inkMute}>{profile?.email ?? ""}</Text>
            </View>
          </View>
          <View style={styles.metricsRow}>
            <Metric label="연속 성공" value={`${stats.streak}일`} />
            <View style={styles.metricDivider} />
            <Metric label="성공한 날" value={`${stats.success}일`} />
          </View>
        </Card>

        <Card tone="raised" size="lg">
          <View style={styles.cardHeader}>
            <Text variant="title">잔디</Text>
            <Text variant="caption" color={palette.inkMute}>최근 365일</Text>
          </View>
          <ContributionGrid days={grass} selectedDate={selected?.date ?? null} onSelect={setSelected} />
        </Card>

        <DayDetailCard day={selected} />

        <View>
          <Text variant="title" style={{ marginBottom: space[2] }}>최근 회고</Text>
          {loading && reflections.length === 0 ? (
            <View style={styles.reflectionList}>
              <Skeleton height={72} />
              <Skeleton height={72} />
            </View>
          ) : reflections.length === 0 ? (
            <EmptyState
              title="아직 회고가 없어요"
              description="이 친구가 회고를 남기면 여기서 볼 수 있어요."
            />
          ) : (
            <View style={styles.reflectionList}>
              {reflections.map((entry) => (
                <Card key={entry.date} tone="default" size="md">
                  <Text variant="caption" color={palette.inkMute}>
                    {new Date(entry.date).toLocaleDateString("ko-KR", { month: "long", day: "numeric", weekday: "short" })}
                  </Text>
                  {entry.bodyVisible && entry.body ? (
                    <Text variant="body" style={{ marginTop: space[1] }}>{entry.body}</Text>
                  ) : (
                    <Text variant="bodySmall" color={palette.inkFaint} style={{ marginTop: space[1] }}>
                      같은 그룹에 있을 때 본문이 표시돼요.
                    </Text>
                  )}
                </Card>
              ))}
            </View>
          )}
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

const styles = StyleSheet.create({
  content: { gap: space[4], paddingBottom: space[8] },
  identityRow: { flexDirection: "row", alignItems: "center", gap: space[3] },
  avatar: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: "center",
    justifyContent: "center"
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
    alignItems: "baseline",
    justifyContent: "space-between",
    marginBottom: space[3]
  },
  reflectionList: { gap: space[2] }
});
