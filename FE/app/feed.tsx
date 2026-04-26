import { useEffect, useState } from "react";
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { router } from "expo-router";
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
        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>Crew Vibe</Text>
          <Text style={styles.liveBadge}>LIVE</Text>
        </View>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.crewList}>
          {items.map((item) => (
            <Pressable key={item.userId} onPress={() => router.push(`/friend-profile?userId=${item.userId}`)} style={styles.crewItem}>
              <View style={[styles.crewAvatar, item.reflectionSubmitted ? styles.crewAvatarDone : styles.crewAvatarWait]}>
                <Text style={styles.crewInitial}>{item.nickname.slice(0, 1).toUpperCase()}</Text>
                <Text style={[styles.crewPercent, item.reflectionSubmitted ? styles.percentDone : styles.percentWait]}>{item.reflectionSubmitted ? "100%" : "0%"}</Text>
              </View>
              <Text numberOfLines={1} style={styles.crewName}>{item.nickname}</Text>
            </Pressable>
          ))}
          <View style={styles.crewItem}>
            <View style={styles.inviteCircle}>
              <MaterialIcons name="add" size={32} color={colors.black} />
            </View>
            <Text style={styles.crewName}>Invite</Text>
          </View>
        </ScrollView>

        <NeoCard tone="acid" style={styles.form}>
          <Text style={styles.kicker}>SOCIAL BOARD</Text>
          <Text style={styles.heading}>친구 요청</Text>
          <TextInput value={targetEmail} onChangeText={setTargetEmail} autoCapitalize="none" keyboardType="email-address" placeholder="친구 email" style={styles.input} />
          <NeoButton label="요청 보내기" onPress={requestFriend} />
        </NeoCard>
        {requests.map((request) => (
          <NeoCard key={request.id} tone="white" style={styles.form}>
            <Text style={styles.kicker}>INCOMING</Text>
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
            <View style={[styles.feedCard, item.reflectionSubmitted ? styles.feedDone : styles.feedWait]}>
              <View style={styles.feedCardTop}>
                <Text style={styles.name}>{item.nickname}</Text>
                <Text style={[styles.badge, item.reflectionSubmitted ? styles.badgeDone : styles.badgeWait]}>{item.reflectionSubmitted ? "SEALED" : "WAITING"}</Text>
              </View>
              <Text style={styles.goal}>{item.goal || "아직 목표 없음"}</Text>
              <Text style={styles.meta}>완료 todo {item.completedTodoCount}개</Text>
            </View>
          </Pressable>
        ))}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16, paddingBottom: 32 },
  sectionHeader: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", borderBottomWidth: 4, borderColor: colors.black, paddingBottom: 8 },
  sectionTitle: { color: colors.black, fontSize: 32, fontWeight: "900", textTransform: "uppercase", textShadowColor: colors.greenNeon, textShadowOffset: { width: 2, height: 2 }, textShadowRadius: 0 },
  liveBadge: { color: colors.paper, backgroundColor: colors.black, paddingHorizontal: 10, paddingVertical: 5, fontWeight: "900", transform: [{ rotate: "3deg" }] },
  crewList: { gap: 14, paddingBottom: 10 },
  crewItem: { width: 78, alignItems: "center", gap: 8 },
  crewAvatar: { width: 64, height: 64, borderRadius: 32, borderWidth: 4, borderColor: colors.black, alignItems: "center", justifyContent: "center", shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 2, height: 2 }, elevation: 4 },
  crewAvatarDone: { backgroundColor: colors.greenNeon },
  crewAvatarWait: { backgroundColor: colors.surfaceHigh, borderStyle: "dashed" },
  crewInitial: { color: colors.black, fontSize: 24, fontWeight: "900" },
  crewPercent: { position: "absolute", right: -8, bottom: -10, borderWidth: 2, borderColor: colors.black, paddingHorizontal: 4, fontSize: 10, fontWeight: "900", transform: [{ rotate: "-10deg" }] },
  percentDone: { color: colors.greenNeon, backgroundColor: colors.black },
  percentWait: { color: colors.black, backgroundColor: colors.paper },
  inviteCircle: { width: 64, height: 64, borderRadius: 32, borderWidth: 4, borderColor: colors.black, backgroundColor: colors.greenNeon, alignItems: "center", justifyContent: "center", shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 2, height: 2 }, elevation: 4 },
  crewName: { width: "100%", color: colors.black, textAlign: "center", fontSize: 12, fontWeight: "900", textTransform: "uppercase" },
  form: { gap: 10 },
  input: { minHeight: 50, borderWidth: 3, borderColor: colors.black, paddingHorizontal: 12, backgroundColor: colors.white, fontWeight: "800", color: colors.ink },
  actions: { flexDirection: "row", gap: 12 },
  kicker: { color: colors.paper, backgroundColor: colors.black, alignSelf: "flex-start", paddingHorizontal: 9, paddingVertical: 5, fontWeight: "900" },
  heading: { color: colors.ink, fontSize: 28, fontWeight: "900" },
  feedCard: { gap: 8, borderWidth: 4, borderColor: colors.black, padding: 16, shadowColor: colors.black, shadowOpacity: 1, shadowRadius: 0, shadowOffset: { width: 6, height: 6 }, elevation: 7 },
  feedDone: { backgroundColor: colors.green },
  feedWait: { backgroundColor: colors.surface },
  feedCardTop: { flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start", gap: 10 },
  name: { color: colors.ink, fontSize: 24, fontWeight: "900" },
  badge: { color: colors.ink, fontWeight: "900", borderColor: colors.black, borderWidth: 3, paddingHorizontal: 8, paddingVertical: 5 },
  badgeDone: { backgroundColor: colors.greenNeon },
  badgeWait: { backgroundColor: colors.pinkSoft },
  goal: { marginTop: 8, color: colors.ink, fontSize: 18, fontWeight: "800" },
  meta: { marginTop: 8, color: colors.paper, backgroundColor: colors.black, alignSelf: "flex-start", paddingHorizontal: 10, paddingVertical: 6, fontWeight: "900" }
});
