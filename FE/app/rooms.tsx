import { MaterialIcons } from "@expo/vector-icons";
import { router } from "expo-router";
import { useEffect, useState } from "react";
import { Alert, Pressable, ScrollView, StyleSheet, TextInput, View } from "react-native";
import { createRoom, listRooms, type Room } from "../src/api/rooms";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Button } from "../src/components/ui/Button";
import { Card } from "../src/components/ui/Card";
import { EmptyState } from "../src/components/ui/EmptyState";
import { Skeleton } from "../src/components/ui/Skeleton";
import { Text } from "../src/components/ui/Text";
import { space } from "../src/theme/spacing";
import { palette, pickRoomAccent, roomHues, surface } from "../src/theme/tokens";

export default function RoomsScreen() {
  const auth = useRequireAuth();
  const [rooms, setRooms] = useState<Room[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState("");

  useEffect(() => {
    if (!auth.loading && auth.user) {
      void load();
    }
  }, [auth.loading, auth.user]);

  async function load() {
    setLoading(true);
    try {
      setRooms(await listRooms());
    } catch (error) {
      Alert.alert("그룹", error instanceof Error ? error.message : "그룹 목록을 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function submitCreate() {
    const trimmed = name.trim();
    if (!trimmed) return;
    try {
      await createRoom(trimmed);
      setName("");
      setShowCreate(false);
      await load();
    } catch (error) {
      Alert.alert("그룹 만들기 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    }
  }

  return (
    <Screen title="그룹">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View style={styles.header}>
          <View style={{ flex: 1 }}>
            <Text variant="h1">함께하는 그룹</Text>
            <Text variant="bodySmall" color={palette.inkMute}>
              초대 코드로 작은 그룹을 만들어요 (최대 8명)
            </Text>
          </View>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={showCreate ? "그룹 만들기 취소" : "그룹 만들기"}
            onPress={() => setShowCreate((v) => !v)}
            style={({ pressed }) => [styles.iconAction, pressed && { opacity: 0.7 }]}
          >
            <MaterialIcons name={showCreate ? "close" : "add"} size={20} color={palette.ink} />
          </Pressable>
        </View>

        {showCreate ? (
          <Card tone="raised" size="md">
            <Text variant="title">새 그룹 만들기</Text>
            <TextInput
              value={name}
              onChangeText={setName}
              placeholder="그룹 이름 (예: 글쓰기 모임)"
              placeholderTextColor={palette.inkFaint}
              maxLength={80}
              style={styles.input}
              accessibilityLabel="그룹 이름"
            />
            <Button label="만들기" tone="primary" size="md" fullWidth onPress={submitCreate} />
          </Card>
        ) : null}

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="초대 코드로 참여"
          onPress={() => router.push("/join")}
        >
          <Card tone="outline" size="md">
            <View style={styles.joinRow}>
              <MaterialIcons name="key" size={20} color={palette.ink} />
              <View style={{ flex: 1 }}>
                <Text variant="bodyStrong">초대 코드로 참여</Text>
                <Text variant="caption" color={palette.inkMute}>친구가 공유한 8자리 코드를 입력하세요</Text>
              </View>
              <MaterialIcons name="chevron-right" size={20} color={palette.inkMute} />
            </View>
          </Card>
        </Pressable>

        {loading && rooms.length === 0 ? (
          <View style={styles.skeletonList}>
            <Skeleton height={64} />
            <Skeleton height={64} />
          </View>
        ) : rooms.length === 0 ? (
          <EmptyState
            title="아직 그룹이 없어요"
            description="초대 코드로 참여하거나 새 그룹을 만들어보세요."
            action={
              <Button
                label="그룹 만들기"
                tone="primary"
                size="md"
                fullWidth
                onPress={() => setShowCreate(true)}
              />
            }
          />
        ) : (
          <View style={styles.list}>
            {rooms.map((room) => <RoomRow key={room.id} room={room} />)}
          </View>
        )}
      </ScrollView>
    </Screen>
  );
}

function RoomRow({ room }: { room: Room }) {
  const accent = pickRoomAccent(room.id);
  const hue = roomHues[accent];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${room.name} 그룹 열기`}
      onPress={() => router.push(`/rooms/${room.id}`)}
      style={({ pressed }) => [pressed && { opacity: 0.85 }]}
    >
      <Card tone="raised" size="md" style={{ borderColor: hue.base, borderWidth: 1 }}>
        <View style={styles.roomRow}>
          <View style={[styles.dot, { backgroundColor: hue.base }]} />
          <View style={{ flex: 1 }}>
            <Text variant="title" numberOfLines={1}>{room.name}</Text>
            <Text variant="caption" color={palette.inkMute}>최대 {room.maxMembers}명</Text>
          </View>
          <MaterialIcons name="chevron-right" size={20} color={palette.inkMute} />
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
    justifyContent: "center",
  },
  input: {
    minHeight: 44,
    marginVertical: space[2],
    paddingHorizontal: space[3],
    borderRadius: 12,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
    color: palette.ink,
  },
  joinRow: { flexDirection: "row", alignItems: "center", gap: space[3] },
  skeletonList: { gap: space[2] },
  list: { gap: space[2] },
  roomRow: { flexDirection: "row", alignItems: "center", gap: space[3] },
  dot: { width: 12, height: 12, borderRadius: 6 },
});
