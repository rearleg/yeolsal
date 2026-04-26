import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, ScrollView, StyleSheet, Text, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { useLocalSearchParams } from "expo-router";
import { GrassGrid } from "../src/components/GrassGrid";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { apiRequest, ApiEnvelope } from "../src/api/client";
import { GrassDayDto, ProfileDto } from "../src/api/types";
import { colors } from "../src/theme/tokens";

export default function FriendProfileScreen() {
  const auth = useRequireAuth();
  const params = useLocalSearchParams<{ userId?: string }>();
  const userId = Number(params.userId);
  const [profile, setProfile] = useState<ProfileDto | null>(null);
  const [grass, setGrass] = useState<GrassDayDto[]>([]);
  const [selected, setSelected] = useState<GrassDayDto | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!auth.loading && auth.user) {
      load();
    }
  }, [auth.loading, auth.user, userId]);

  async function load() {
    if (!userId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const now = new Date();
      const from = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10);
      const to = new Date(now.getFullYear(), now.getMonth() + 1, 0).toISOString().slice(0, 10);
      const [profileResponse, grassResponse] = await Promise.all([
        apiRequest<ApiEnvelope<ProfileDto>>(`/profiles/${userId}`),
        apiRequest<ApiEnvelope<GrassDayDto[]>>(`/profiles/${userId}/grass?from=${from}&to=${to}`)
      ]);
      setProfile(profileResponse.data);
      setGrass(grassResponse.data);
      setSelected(grassResponse.data[0] ?? null);
    } catch (error) {
      Alert.alert("친구 프로필", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <Screen title={profile ? `${profile.nickname}의 잔디` : "친구 잔디"}>
      <ScrollView contentContainerStyle={styles.content}>
        {loading ? <ActivityIndicator color={colors.ink} /> : null}
        <View style={styles.profileHeader}>
          <View style={styles.avatarWrap}>
            <View style={styles.avatarShadow} />
            <View style={styles.avatar}><Text style={styles.avatarInitial}>{profile?.nickname?.slice(0, 1).toUpperCase() ?? "?"}</Text></View>
          </View>
          <Text style={styles.name}>{profile?.nickname ?? "친구를 선택하세요"}</Text>
          <Text style={styles.role}>{profile?.email ?? "Friend Garden"}</Text>
        </View>

        <NeoCard tone="white" style={styles.garden}>
          <View style={styles.gardenHeader}>
            <Text style={styles.gardenTitle}>Friend Garden</Text>
            <MaterialIcons name="grid-view" size={24} color={colors.black} />
          </View>
          <View style={styles.gardenBody}>
            <GrassGrid days={grass} onSelect={setSelected} />
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
  profileHeader: { alignItems: "center", gap: 12, marginTop: 16 },
  avatarWrap: { width: 130, height: 130 },
  avatarShadow: { position: "absolute", inset: 0, borderRadius: 65, borderWidth: 4, borderColor: colors.black, backgroundColor: colors.green, transform: [{ translateX: 8 }, { translateY: 8 }] },
  avatar: { position: "absolute", inset: 0, borderRadius: 65, borderWidth: 4, borderColor: colors.black, backgroundColor: colors.pinkSoft, alignItems: "center", justifyContent: "center" },
  avatarInitial: { color: colors.black, fontSize: 56, fontWeight: "900" },
  name: { color: colors.black, fontSize: 38, lineHeight: 42, textAlign: "center", fontWeight: "900", textTransform: "uppercase", textShadowColor: colors.green, textShadowOffset: { width: 3, height: 3 }, textShadowRadius: 0 },
  role: { color: colors.paper, backgroundColor: colors.pink, borderWidth: 4, borderColor: colors.black, paddingHorizontal: 12, paddingVertical: 6, fontWeight: "900", transform: [{ rotate: "-2deg" }] },
  garden: { padding: 0, overflow: "hidden" },
  gardenHeader: { backgroundColor: colors.greenNeon, borderBottomWidth: 4, borderColor: colors.black, padding: 14, flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  gardenTitle: { color: colors.black, fontSize: 24, fontWeight: "900", textTransform: "uppercase" },
  gardenBody: { padding: 16 },
  detailCard: { gap: 6 },
  detail: { color: colors.ink, fontSize: 20, fontWeight: "900" },
  state: { color: colors.paper, backgroundColor: colors.black, alignSelf: "flex-start", paddingHorizontal: 10, paddingVertical: 6, fontWeight: "900" }
});
