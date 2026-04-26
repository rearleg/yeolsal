import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { Link, router } from "expo-router";
import { NeoButton } from "../src/components/NeoButton";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { apiRequest, ApiEnvelope } from "../src/api/client";
import { DailyFeedItem, FriendRequestDto } from "../src/api/types";
import { colors } from "../src/theme/tokens";

export default function FeedScreen() {
  const auth = useRequireAuth();
  const [items, setItems] = useState<DailyFeedItem[]>([]);
  const [requests, setRequests] = useState<FriendRequestDto[]>([]);
  const [targetEmail, setTargetEmail] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!auth.loading && auth.user) {
      load();
    }
  }, [auth.loading, auth.user]);

  async function load() {
    setLoading(true);
    try {
      const date = new Date().toISOString().slice(0, 10);
      const [feedResponse, requestsResponse] = await Promise.all([
        apiRequest<ApiEnvelope<DailyFeedItem[]>>(`/feed/daily?date=${date}`),
        apiRequest<ApiEnvelope<FriendRequestDto[]>>("/friends/requests")
      ]);
      setItems(feedResponse.data);
      setRequests(requestsResponse.data);
    } catch (error) {
      Alert.alert("피드", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function requestFriend() {
    if (!targetEmail.trim()) {
      return;
    }
    try {
      await apiRequest("/friends/requests", {
        method: "POST",
        body: JSON.stringify({ targetEmail: targetEmail.trim() })
      });
      setTargetEmail("");
      Alert.alert("친구", "친구 요청을 보냈습니다.");
      await load();
    } catch (error) {
      Alert.alert("친구 요청 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    }
  }

  async function respond(id: number, accepted: boolean) {
    try {
      await apiRequest(`/friends/requests/${id}`, {
        method: "PATCH",
        body: JSON.stringify({ accepted })
      });
      await load();
    } catch (error) {
      Alert.alert("친구 요청", error instanceof Error ? error.message : "응답하지 못했습니다.");
    }
  }

  return (
    <Screen title="친구 피드">
      <ScrollView contentContainerStyle={styles.content}>
        <Link href="/today" style={styles.link}>오늘로 돌아가기</Link>
        <NeoCard tone="acid" style={styles.form}>
          <Text style={styles.heading}>친구 요청</Text>
          <TextInput value={targetEmail} onChangeText={setTargetEmail} autoCapitalize="none" keyboardType="email-address" placeholder="친구 email" style={styles.input} />
          <NeoButton label="요청 보내기" onPress={requestFriend} />
        </NeoCard>
        {requests.map((request) => (
          <NeoCard key={request.id} tone="paper" style={styles.form}>
            <Text style={styles.goal}>{request.requesterNickname} ({request.requesterEmail})</Text>
            <View style={styles.actions}>
              <NeoButton label="수락" onPress={() => respond(request.id, true)} />
              <NeoButton label="거절" tone="pink" onPress={() => respond(request.id, false)} />
            </View>
          </NeoCard>
        ))}
        {loading ? <ActivityIndicator color={colors.ink} /> : null}
        {items.map((item) => (
          <Pressable key={item.userId} onPress={() => router.push(`/friend-profile?userId=${item.userId}`)}>
            <NeoCard tone={item.reflectionSubmitted ? "green" : "paper"}>
              <View style={styles.row}>
                <Text style={styles.name}>{item.nickname}</Text>
                <Text style={styles.badge}>{item.reflectionSubmitted ? "회고 완료" : "회고 대기"}</Text>
              </View>
              <Text style={styles.goal}>{item.goal || "아직 목표 없음"}</Text>
              <Text style={styles.meta}>완료 todo {item.completedTodoCount}개</Text>
            </NeoCard>
          </Pressable>
        ))}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 14, paddingBottom: 28 },
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  form: { gap: 10 },
  input: { minHeight: 48, borderWidth: 3, borderColor: colors.ink, paddingHorizontal: 12, backgroundColor: colors.white, fontWeight: "800" },
  actions: { flexDirection: "row", gap: 12 },
  heading: { color: colors.ink, fontSize: 20, fontWeight: "900" },
  row: { flexDirection: "row", justifyContent: "space-between", gap: 10 },
  name: { color: colors.ink, fontSize: 24, fontWeight: "900" },
  badge: { color: colors.ink, fontWeight: "900", backgroundColor: colors.pink, padding: 6 },
  goal: { marginTop: 8, color: colors.ink, fontSize: 18, fontWeight: "800" },
  meta: { marginTop: 8, color: colors.ink, fontWeight: "900" }
});
