import { MaterialIcons } from "@expo/vector-icons";
import { router } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, TextInput, View } from "react-native";
import { type ApiEnvelope, apiRequest } from "../src/api/client";
import type { DailyFeedItem, FriendRequestDto } from "../src/api/types";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Card } from "../src/components/ui/Card";
import { Button } from "../src/components/ui/Button";
import { Text } from "../src/components/ui/Text";
import { palette, pickRoomAccent, roomHues, semantic, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";
import { entryDateOf } from "../src/lib/calendar";

export default function FeedScreen() {
  const auth = useRequireAuth();
  const [items, setItems] = useState<DailyFeedItem[]>([]);
  const [requests, setRequests] = useState<FriendRequestDto[]>([]);
  const [targetEmail, setTargetEmail] = useState("");
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);

  useEffect(() => {
    if (!auth.loading && auth.user) {
      load();
    }
  }, [auth.loading, auth.user]);

  async function load() {
    setLoading(true);
    try {
      const date = entryDateOf();
      const [feedResponse, requestsResponse] = await Promise.all([
        apiRequest<ApiEnvelope<DailyFeedItem[]>>(`/feed/daily?date=${date}`),
        apiRequest<ApiEnvelope<FriendRequestDto[]>>("/friends/requests")
      ]);
      setItems(feedResponse.data);
      setRequests(requestsResponse.data);
    } catch (error) {
      Alert.alert("친구", error instanceof Error ? error.message : "데이터를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function requestFriend() {
    if (!targetEmail.trim()) return;
    try {
      await apiRequest("/friends/requests", {
        method: "POST",
        body: JSON.stringify({ targetEmail: targetEmail.trim() })
      });
      setTargetEmail("");
      setShowRequest(false);
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

  const reflectionDoneCount = useMemo(() => items.filter((i) => i.reflectionSubmitted).length, [items]);

  return (
    <Screen title="친구">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {loading && items.length === 0 ? <ActivityIndicator color={palette.sageDeep} /> : null}

        <View style={styles.header}>
          <View style={{ flex: 1 }}>
            <Text variant="h1">함께하는 친구들</Text>
            <Text variant="bodySmall" color={palette.inkMute}>
              오늘 회고 완료 {reflectionDoneCount} / {items.length}명
            </Text>
          </View>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="친구 추가"
            onPress={() => setShowRequest((v) => !v)}
            style={({ pressed }) => [styles.iconAction, pressed && { opacity: 0.7 }]}
          >
            <MaterialIcons name={showRequest ? "close" : "person-add"} size={20} color={palette.ink} />
          </Pressable>
        </View>

        {showRequest ? (
          <Card tone="raised" size="md">
            <Text variant="title">친구 추가</Text>
            <TextInput
              value={targetEmail}
              onChangeText={setTargetEmail}
              autoCapitalize="none"
              keyboardType="email-address"
              placeholder="친구 이메일"
              placeholderTextColor={palette.inkFaint}
              style={styles.input}
            />
            <Button label="요청 보내기" tone="primary" size="md" fullWidth onPress={requestFriend} />
          </Card>
        ) : null}

        {requests.length > 0 ? (
          <View style={styles.requestList}>
            {requests.map((request) => (
              <Card key={request.id} tone="accent" accent="periwinkle" size="md">
                <View style={styles.requestRow}>
                  <View style={{ flex: 1 }}>
                    <Text variant="title">{request.requesterNickname}</Text>
                    <Text variant="caption" color={palette.inkMute}>{request.requesterEmail}</Text>
                  </View>
                  <View style={styles.requestActions}>
                    <Button label="수락" tone="primary" size="sm" onPress={() => respond(request.id, true)} />
                    <Button label="거절" tone="secondary" size="sm" onPress={() => respond(request.id, false)} />
                  </View>
                </View>
              </Card>
            ))}
          </View>
        ) : null}

        {items.length === 0 && !loading ? (
          <Card tone="sunken" size="md">
            <Text variant="bodySmall" color={palette.inkMute}>
              아직 함께하는 친구가 없어요. 위의 + 버튼으로 친구를 초대하세요.
            </Text>
          </Card>
        ) : (
          <View style={styles.grid}>
            {items.map((item) => <FriendCard key={item.userId} item={item} />)}
          </View>
        )}
      </ScrollView>
    </Screen>
  );
}

function FriendCard({ item }: { item: DailyFeedItem }) {
  const accent = pickRoomAccent(item.userId);
  const hue = roomHues[accent];
  const status = item.reflectionSubmitted
    ? { label: "회고 완료", fg: semantic.success.fg, bg: semantic.success.bg }
    : item.goal
      ? { label: "목표 작성", fg: semantic.warning.fg, bg: semantic.warning.bg }
      : { label: "기록 없음", fg: palette.inkMute, bg: surface.sunken };
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${item.nickname} 프로필 열기`}
      onPress={() => router.push(`/friend-profile?userId=${item.userId}`)}
      style={({ pressed }) => [styles.cardWrap, pressed && { opacity: 0.85 }]}
    >
      <Card tone="raised" size="md" style={{ borderColor: hue.base, borderWidth: 1 }}>
        <View style={styles.cardTop}>
          <View style={[styles.avatar, { backgroundColor: hue.soft }]}>
            <Text variant="bodyStrong" color={hue.deep}>{item.nickname.slice(0, 1).toUpperCase()}</Text>
          </View>
          <View style={[styles.statusBadge, { backgroundColor: status.bg }]}>
            <Text variant="caption" color={status.fg} weight="700">{status.label}</Text>
          </View>
        </View>
        <Text variant="title" numberOfLines={1}>{item.nickname}</Text>
        <Text variant="bodySmall" color={palette.inkMute} numberOfLines={2} style={{ marginTop: space[1] }}>
          {item.goal || "오늘 목표가 아직 없어요."}
        </Text>
        <View style={styles.cardMeta}>
          <MaterialIcons name="checklist" size={14} color={palette.inkMute} />
          <Text variant="caption" color={palette.inkMute}>완료 {item.completedTodoCount}개</Text>
        </View>
      </Card>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  header: { flexDirection: "row", alignItems: "center", gap: space[2] },
  iconAction: {
    width: 40,
    height: 40,
    borderRadius: 12,
    backgroundColor: surface.sunken,
    alignItems: "center",
    justifyContent: "center"
  },
  input: {
    minHeight: 44,
    marginVertical: space[2],
    paddingHorizontal: space[3],
    borderRadius: 12,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
    color: palette.ink
  },
  requestList: { gap: space[2] },
  requestRow: { flexDirection: "row", alignItems: "center", gap: space[3] },
  requestActions: { flexDirection: "row", gap: space[2] },
  grid: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: space[3]
  },
  cardWrap: { flexBasis: "48%", flexGrow: 1 },
  cardTop: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: space[2]
  },
  avatar: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: "center",
    justifyContent: "center"
  },
  statusBadge: {
    paddingHorizontal: space[2],
    paddingVertical: 2,
    borderRadius: 999
  },
  cardMeta: {
    marginTop: space[3],
    flexDirection: "row",
    alignItems: "center",
    gap: space[1]
  }
});
