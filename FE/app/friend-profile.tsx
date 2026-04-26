import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, StyleSheet, Text } from "react-native";
import { Link, useLocalSearchParams } from "expo-router";
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
    } catch (error) {
      Alert.alert("친구 프로필", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <Screen title={profile ? `${profile.nickname}의 잔디` : "친구 잔디"}>
      <Link href="/feed" style={styles.link}>친구 피드</Link>
      {loading ? <ActivityIndicator color={colors.ink} /> : null}
      <NeoCard tone="pink">
        <Text style={styles.name}>{profile ? profile.email : "친구를 선택하세요"}</Text>
        <GrassGrid days={grass} />
      </NeoCard>
    </Screen>
  );
}

const styles = StyleSheet.create({
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  name: { color: colors.ink, fontSize: 22, fontWeight: "900", marginBottom: 14 }
});
