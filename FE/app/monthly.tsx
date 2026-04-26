import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, Alert, ScrollView, StyleSheet, Text, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
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
  const monthTotalDays = useMemo(() => new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate(), [month]);
  const completed = stats?.completedDailyCount ?? 0;
  const completionRate = Math.min(100, Math.round((completed / monthTotalDays) * 100));

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
      <ScrollView contentContainerStyle={styles.content}>
        {loading ? <ActivityIndicator color={colors.ink} /> : null}

        <View style={styles.titleBlock}>
          <Text style={styles.title}>Monthly Harvest</Text>
          <View style={styles.underline} />
        </View>

        <View style={styles.progressCard}>
          <View style={styles.progressHeader}>
            <MaterialIcons name="local-florist" size={34} color={colors.greenDark} />
            <Text style={styles.progressTitle}>{month} Missions Completed</Text>
          </View>
          <View style={styles.progressBody}>
            <Text style={styles.goalStamp}>{completed}/{monthTotalDays} GOALS</Text>
            <View style={styles.progressTrack}>
              <View style={[styles.progressFill, { width: `${completionRate}%` }]} />
              <View style={[styles.progressNeedle, { left: `${completionRate}%` }]} />
            </View>
            <Text style={styles.progressCopy}>ALMOST THERE! KEEP PUSHING THE SOIL.</Text>
          </View>
        </View>

        <View style={styles.statGrid}>
          <NeoCard tone="acid" style={styles.statCard}>
            <View style={styles.statHeader}>
              <Text style={styles.statHeaderText}>Reflections</Text>
              <MaterialIcons name="edit-note" size={26} color={colors.paper} />
            </View>
            <Text style={styles.statNumber}>{grass.filter((day) => day.reflectionSubmitted).length}</Text>
            <Text style={styles.statBadge}>ENTRIES WRITTEN</Text>
          </NeoCard>
          <NeoCard tone="pink" style={styles.statCard}>
            <View style={styles.statHeaderDark}>
              <Text style={styles.statHeaderText}>Todo Done</Text>
              <MaterialIcons name="celebration" size={24} color={colors.paper} />
            </View>
            <Text style={styles.statNumber}>{grass.reduce((sum, day) => sum + day.completedTodoCount, 0)}</Text>
            <Text style={styles.statBadge}>SEEDS GROWN</Text>
          </NeoCard>
        </View>

        <View style={styles.calendar}>
          <View style={styles.calendarHeader}>
            <Text style={styles.calendarTitle}>Cultivation Grid</Text>
            <View style={styles.calendarActions}>
              <Text style={styles.calendarActionPink}>PREV</Text>
              <Text style={styles.calendarActionGreen}>NEXT</Text>
            </View>
          </View>
          <View style={styles.daysRow}>
            {["S", "M", "T", "W", "T", "F", "S"].map((day, index) => <Text key={`${day}-${index}`} style={styles.dayLabel}>{day}</Text>)}
          </View>
          <View style={styles.blocks}>
            {grass.map((day) => {
              const dateNumber = Number(day.date.slice(8, 10));
              return (
                <View key={day.date} style={[styles.block, day.missionCompleted ? styles.done : styles.empty]}>
                  <Text style={styles.blockText}>{dateNumber}</Text>
                </View>
              );
            })}
          </View>
          <View style={styles.legend}>
            <View style={styles.legendItem}><View style={[styles.legendBox, styles.done]} /><Text style={styles.legendText}>COMPLETED</Text></View>
            <View style={styles.legendItem}><View style={[styles.legendBox, styles.empty]} /><Text style={styles.legendText}>MISSED</Text></View>
          </View>
        </View>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16, paddingBottom: 32 },
  titleBlock: { alignSelf: "flex-start", marginBottom: 4 },
  title: { color: colors.black, fontSize: 40, lineHeight: 44, fontWeight: "900", textTransform: "uppercase" },
  underline: { height: 14, marginTop: -12, backgroundColor: colors.green, borderWidth: 3, borderColor: colors.black, transform: [{ skewX: "-12deg" }] },
  progressCard: { borderWidth: 4, borderColor: colors.black, backgroundColor: colors.surface, shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 8, height: 8 }, elevation: 9 },
  progressHeader: { flexDirection: "row", alignItems: "center", gap: 10, backgroundColor: colors.white, borderBottomWidth: 4, borderColor: colors.black, padding: 16 },
  progressTitle: { flex: 1, color: colors.black, fontSize: 22, lineHeight: 26, fontWeight: "900", textTransform: "uppercase" },
  progressBody: { padding: 20, backgroundColor: colors.paper, gap: 18 },
  goalStamp: { alignSelf: "flex-end", color: colors.paper, backgroundColor: colors.black, paddingHorizontal: 12, paddingVertical: 6, fontWeight: "900", transform: [{ rotate: "3deg" }] },
  progressTrack: { height: 48, borderWidth: 4, borderColor: colors.black, backgroundColor: colors.white, overflow: "hidden" },
  progressFill: { position: "absolute", top: 0, left: 0, bottom: 0, backgroundColor: colors.green, borderRightWidth: 4, borderRightColor: colors.black },
  progressNeedle: { position: "absolute", top: -4, width: 8, height: 56, backgroundColor: colors.pink, borderWidth: 3, borderColor: colors.black },
  progressCopy: { color: colors.black, textAlign: "center", fontWeight: "900" },
  statGrid: { gap: 14 },
  statCard: { padding: 0, overflow: "hidden", alignItems: "center" },
  statHeader: { alignSelf: "stretch", flexDirection: "row", justifyContent: "space-between", alignItems: "center", backgroundColor: colors.greenDark, borderBottomWidth: 4, borderColor: colors.black, padding: 12 },
  statHeaderDark: { alignSelf: "stretch", flexDirection: "row", justifyContent: "space-between", alignItems: "center", backgroundColor: colors.pinkDark, borderBottomWidth: 4, borderColor: colors.black, padding: 12 },
  statHeaderText: { color: colors.paper, fontSize: 16, fontWeight: "900", textTransform: "uppercase" },
  statNumber: { marginTop: 18, color: colors.black, fontSize: 60, lineHeight: 64, fontWeight: "900", textShadowColor: colors.white, textShadowOffset: { width: 2, height: 2 }, textShadowRadius: 0 },
  statBadge: { marginBottom: 18, color: colors.black, backgroundColor: colors.white, borderWidth: 2, borderColor: colors.black, paddingHorizontal: 10, paddingVertical: 4, fontWeight: "900" },
  calendar: { borderWidth: 4, borderColor: colors.black, backgroundColor: colors.white, shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 8, height: 8 }, elevation: 9 },
  calendarHeader: { backgroundColor: colors.black, padding: 14, flexDirection: "row", justifyContent: "space-between", alignItems: "center", gap: 10 },
  calendarTitle: { flex: 1, color: colors.paper, fontSize: 24, fontWeight: "900", textTransform: "uppercase" },
  calendarActions: { flexDirection: "row", gap: 8 },
  calendarActionPink: { color: colors.paper, backgroundColor: colors.pink, borderWidth: 2, borderColor: colors.paper, paddingHorizontal: 8, paddingVertical: 4, fontSize: 12, fontWeight: "900" },
  calendarActionGreen: { color: colors.paper, backgroundColor: colors.greenDark, borderWidth: 2, borderColor: colors.paper, paddingHorizontal: 8, paddingVertical: 4, fontSize: 12, fontWeight: "900" },
  daysRow: { flexDirection: "row", borderBottomWidth: 4, borderColor: colors.black, padding: 12 },
  dayLabel: { flex: 1, color: colors.black, textAlign: "center", fontWeight: "900" },
  blocks: { flexDirection: "row", flexWrap: "wrap", gap: 10, padding: 16, backgroundColor: colors.surfaceLow },
  block: { width: 38, height: 38, borderRadius: 19, borderColor: colors.black, borderWidth: 3, alignItems: "center", justifyContent: "center" },
  done: { backgroundColor: colors.green },
  empty: { backgroundColor: colors.paper, borderStyle: "dotted" },
  blockText: { color: colors.black, fontSize: 12, fontWeight: "900" },
  legend: { borderTopWidth: 4, borderColor: colors.black, padding: 12, flexDirection: "row", justifyContent: "space-between" },
  legendItem: { flexDirection: "row", alignItems: "center", gap: 8 },
  legendBox: { width: 14, height: 14, borderRadius: 0 },
  legendText: { color: colors.black, fontSize: 11, fontWeight: "900" }
});
