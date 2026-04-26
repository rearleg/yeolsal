import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, StyleSheet, Text } from "react-native";
import { Link } from "expo-router";
import { GrassGrid } from "../src/components/GrassGrid";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { apiRequest, ApiEnvelope } from "../src/api/client";
import { GrassDayDto, ProfileDto } from "../src/api/types";
import { colors } from "../src/theme/tokens";

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
      <Link href="/feed" style={styles.link}>친구 피드</Link>
      {loading ? <ActivityIndicator color={colors.ink} /> : null}
      <NeoCard tone="dark">
        <Text style={styles.name}>{profile?.nickname ?? "나의 10살방"}</Text>
        <GrassGrid days={grass} onSelect={setSelected} />
      </NeoCard>
      {selected ? (
        <NeoCard tone="acid">
          <Text style={styles.detail}>{selected.date}</Text>
          <Text style={styles.detail}>완료 todo {selected.completedTodoCount}개</Text>
        </NeoCard>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  name: { color: colors.paper, fontSize: 22, fontWeight: "900", marginBottom: 14 },
  detail: { color: colors.ink, fontSize: 20, fontWeight: "900" }
});
