import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, Image, ScrollView, StyleSheet, Text, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { GrassGrid } from "../src/components/GrassGrid";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { apiRequest, ApiEnvelope } from "../src/api/client";
import { GrassDayDto, ProfileDto } from "../src/api/types";
import { colors } from "../src/theme/tokens";
import logo from "../assets/brand/logo.png";

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
      const now = new Date();
      const from = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10);
      const to = new Date(now.getFullYear(), now.getMonth() + 1, 0).toISOString().slice(0, 10);
      const [profileResponse, grassResponse] = await Promise.all([
        apiRequest<ApiEnvelope<ProfileDto>>("/profiles/me"),
        apiRequest<ApiEnvelope<GrassDayDto[]>>(`/profiles/me/grass?from=${from}&to=${to}`)
      ]);
      setProfile(profileResponse.data);
      setGrass(grassResponse.data);
      setSelected(grassResponse.data[0] ?? null);
    } catch (error) {
      Alert.alert("프로필", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <Screen title="내 잔디">
      <ScrollView contentContainerStyle={styles.content}>
        {loading ? <ActivityIndicator color={colors.ink} /> : null}

        <View style={styles.profileHeader}>
          <View style={styles.avatarWrap}>
            <View style={styles.avatarShadow} />
            <View style={styles.avatar}>
              <Image source={logo} style={styles.avatarImage} />
            </View>
          </View>
          <View style={styles.profileCopy}>
            <Text style={styles.name}>{profile?.nickname ?? "나의 10살방"}</Text>
            <Text style={styles.role}>Digital Gardener</Text>
            <Text style={styles.bio}>Cultivating thoughts, planting ideas, and watching the neon grass grow.</Text>
          </View>
        </View>

        <View style={styles.stats}>
          <NeoCard tone="green" style={styles.statCard}>
            <View style={styles.statTop}>
              <Text style={styles.statLabel}>Current Streak</Text>
              <MaterialIcons name="local-fire-department" size={24} color={colors.black} />
            </View>
            <Text style={styles.statNumber}>{grass.filter((day) => day.missionCompleted).length}</Text>
            <Text style={styles.statUnit}>DAYS</Text>
          </NeoCard>
          <NeoCard tone="pink" style={styles.statCard}>
            <View style={styles.statTopPink}>
              <Text style={styles.statLabel}>Total Impact</Text>
              <MaterialIcons name="eco" size={24} color={colors.black} />
            </View>
            <Text style={[styles.statNumber, styles.statNumberGreen]}>{grass.reduce((sum, day) => sum + day.completedTodoCount, 0)}</Text>
            <Text style={styles.statUnit}>SEEDS</Text>
          </NeoCard>
        </View>

        <NeoCard tone="white" style={styles.garden}>
          <View style={styles.gardenHeader}>
            <Text style={styles.gardenTitle}>Your Garden</Text>
            <MaterialIcons name="grid-view" size={24} color={colors.black} />
          </View>
          <View style={styles.gardenBody}>
            <GrassGrid days={grass} onSelect={setSelected} />
            <View style={styles.legend}>
              <Text style={styles.legendText}>Less</Text>
              {[colors.surfaceHigh, "#B9FFB1", colors.green, colors.greenDark].map((color) => <View key={color} style={[styles.legendBlock, { backgroundColor: color }]} />)}
              <Text style={styles.legendText}>More</Text>
            </View>
          </View>
        </NeoCard>

        {selected ? (
          <NeoCard tone="acid" style={styles.detailCard}>
            <Text style={styles.detail}>{selected.date}</Text>
            <Text style={styles.detail}>완료 todo {selected.completedTodoCount}개</Text>
            <Text style={styles.state}>{selected.missionCompleted ? "MISSION COMPLETE" : "MISSION OPEN"}</Text>
          </NeoCard>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16, paddingBottom: 32 },
  profileHeader: { alignItems: "center", gap: 22, marginTop: 16 },
  avatarWrap: { width: 144, height: 144 },
  avatarShadow: { position: "absolute", inset: 0, borderRadius: 72, borderWidth: 4, borderColor: colors.black, backgroundColor: colors.pink, transform: [{ translateX: 8 }, { translateY: 8 }] },
  avatar: { position: "absolute", inset: 0, borderRadius: 72, borderWidth: 4, borderColor: colors.black, backgroundColor: colors.surface, overflow: "hidden", alignItems: "center", justifyContent: "center" },
  avatarImage: { width: 126, height: 126, resizeMode: "contain" },
  profileCopy: { alignItems: "center", gap: 12 },
  name: { color: colors.black, fontSize: 42, lineHeight: 46, fontWeight: "900", textAlign: "center", textTransform: "uppercase", textShadowColor: colors.green, textShadowOffset: { width: 3, height: 3 }, textShadowRadius: 0 },
  role: { color: colors.paper, backgroundColor: colors.pink, borderWidth: 4, borderColor: colors.black, paddingHorizontal: 14, paddingVertical: 7, fontWeight: "900", textTransform: "uppercase", transform: [{ rotate: "-2deg" }], shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 4, height: 4 }, elevation: 4 },
  bio: { color: colors.black, backgroundColor: colors.white, borderWidth: 2, borderColor: colors.black, padding: 14, fontSize: 15, lineHeight: 22, fontWeight: "700", shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 4, height: 4 }, elevation: 4 },
  stats: { gap: 14 },
  statCard: { padding: 0, overflow: "hidden", alignItems: "center" },
  statTop: { alignSelf: "stretch", flexDirection: "row", justifyContent: "space-between", borderBottomWidth: 4, borderColor: colors.black, backgroundColor: colors.green, padding: 12 },
  statTopPink: { alignSelf: "stretch", flexDirection: "row", justifyContent: "space-between", borderBottomWidth: 4, borderColor: colors.black, backgroundColor: colors.pinkSoft, padding: 12 },
  statLabel: { color: colors.black, fontSize: 13, fontWeight: "900", textTransform: "uppercase" },
  statNumber: { marginTop: 14, color: colors.pink, fontSize: 64, lineHeight: 70, fontWeight: "900", textShadowColor: colors.black, textShadowOffset: { width: 2, height: 2 }, textShadowRadius: 0 },
  statNumberGreen: { color: colors.greenDark },
  statUnit: { marginBottom: 16, color: colors.black, fontSize: 28, fontWeight: "900" },
  garden: { padding: 0, overflow: "hidden" },
  gardenHeader: { backgroundColor: colors.greenNeon, borderBottomWidth: 4, borderColor: colors.black, padding: 14, flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  gardenTitle: { color: colors.black, fontSize: 24, fontWeight: "900", textTransform: "uppercase" },
  gardenBody: { padding: 16, gap: 14 },
  legend: { flexDirection: "row", justifyContent: "flex-end", alignItems: "center", gap: 7 },
  legendText: { color: colors.black, fontSize: 12, fontWeight: "900", textTransform: "uppercase" },
  legendBlock: { width: 16, height: 16, borderWidth: 2, borderColor: colors.black },
  detailCard: { gap: 6 },
  detail: { color: colors.ink, fontSize: 20, fontWeight: "900" },
  state: { color: colors.paper, backgroundColor: colors.black, alignSelf: "flex-start", paddingHorizontal: 10, paddingVertical: 6, fontWeight: "900" }
});
