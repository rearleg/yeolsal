import { MaterialIcons } from "@expo/vector-icons";
import { router } from "expo-router";
import { Pressable, ScrollView, StyleSheet, View } from "react-native";
import type { ChatMessageDto, ChatMessageKind } from "../../src/api/chat";
import type { Room } from "../../src/api/rooms";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { Button } from "../../src/components/ui/Button";
import { Card } from "../../src/components/ui/Card";
import { EmptyState } from "../../src/components/ui/EmptyState";
import { Skeleton } from "../../src/components/ui/Skeleton";
import { Text } from "../../src/components/ui/Text";
import {
  useLastReadId,
  useRoomLastMessage,
} from "../../src/lib/query/hooks/chat";
import { useRoomsQuery } from "../../src/lib/query/hooks/rooms";
import { entryDateOf } from "../../src/lib/calendar";
import { space } from "../../src/theme/spacing";
import { palette, pickRoomAccent, roomHues, surface } from "../../src/theme/tokens";

export default function ChatTabScreen() {
  useRequireAuth();
  const roomsQuery = useRoomsQuery();
  const rooms = roomsQuery.data ?? [];
  const loading = roomsQuery.isLoading;

  return (
    <Screen title="채팅">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View>
          <Text variant="h1">채팅</Text>
          <Text variant="bodySmall" color={palette.inkMute}>
            그룹별 대화를 한 곳에서 확인해요.
          </Text>
        </View>

        {loading && rooms.length === 0 ? (
          <View style={styles.skeletonList}>
            <Skeleton height={72} />
            <Skeleton height={72} />
          </View>
        ) : rooms.length === 0 ? (
          <EmptyState
            title="아직 그룹이 없어요"
            description="그룹에 가입하거나 만들면 채팅방이 여기에 나타나요."
            action={
              <Button
                label="그룹 탭으로 가기"
                tone="primary"
                size="md"
                fullWidth
                onPress={() => router.push("/(tabs)/rooms")}
              />
            }
          />
        ) : (
          <View style={styles.list}>
            {rooms.map((room) => (
              <ChatRoomRow key={room.id} room={room} />
            ))}
          </View>
        )}
      </ScrollView>
    </Screen>
  );
}

interface ChatRoomRowProps {
  room: Room;
}

function ChatRoomRow({ room }: ChatRoomRowProps) {
  const accent = pickRoomAccent(room.id);
  const hue = roomHues[accent];
  const lastMessageQuery = useRoomLastMessage(room.id);
  const lastReadQuery = useLastReadId(room.id);
  const lastMessage = lastMessageQuery.data ?? null;
  const lastReadId = lastReadQuery.data ?? null;
  // The "(empty room) → message arrives" boundary: if the user has no
  // watermark yet, treat any message as unread so the badge surfaces it.
  const unread = lastMessage != null && (lastReadId == null || lastMessage.id > lastReadId);
  const initial = room.name.slice(0, 1).toUpperCase();

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={
        unread
          ? `${room.name} 채팅방 — 읽지 않은 새 메시지`
          : `${room.name} 채팅방 열기`
      }
      onPress={() => router.push(`/rooms/${room.id}/chat`)}
      style={({ pressed }) => [pressed && { opacity: 0.85 }]}
    >
      <Card tone="raised" size="md" style={{ borderColor: hue.base, borderWidth: 1 }}>
        <View style={styles.row}>
          <View style={[styles.avatar, { backgroundColor: hue.soft }]}>
            <Text variant="bodyStrong" color={hue.deep}>
              {initial}
            </Text>
            {unread ? <View style={styles.unreadDot} /> : null}
          </View>

          <View style={{ flex: 1, gap: 2 }}>
            <View style={styles.headerLine}>
              <Text variant="bodyStrong" numberOfLines={1} style={{ flex: 1 }}>
                {room.name}
              </Text>
              {lastMessage ? (
                <Text variant="caption" color={palette.inkFaint}>
                  {formatChatTime(lastMessage.createdAt)}
                </Text>
              ) : null}
            </View>
            <Text
              variant="caption"
              color={unread ? palette.ink : palette.inkMute}
              numberOfLines={1}
            >
              {previewFor(lastMessage)}
            </Text>
          </View>

          {unread ? (
            <View style={styles.unreadBadge} accessibilityLabel="읽지 않은 메시지">
              <Text variant="caption" color={palette.paper}>
                NEW
              </Text>
            </View>
          ) : (
            <MaterialIcons name="chevron-right" size={20} color={palette.inkMute} />
          )}
        </View>
      </Card>
    </Pressable>
  );
}

const KIND_PREFIX: Record<ChatMessageKind, string> = {
  USER: "",
  SYSTEM: "[알림] ",
  GOAL: "🎯 ",
  REFLECTION: "📖 ",
  MILESTONE: "🏆 ",
  AUTO_LEAVE: "🚪 ",
};

function previewFor(message: ChatMessageDto | null): string {
  if (!message) return "아직 메시지가 없어요. 첫 인사를 남겨봐요.";
  const prefix = KIND_PREFIX[message.kind] ?? "";
  return `${prefix}${message.body}`.trim();
}

/**
 * Same-day messages render as `HH:mm`; older rows fall back to `M/D` so the
 * row stays compact. "Same day" is the FE's entry-date (06:00 local cutoff)
 * so a 3 a.m. retrospective written for yesterday shows yesterday's date
 * rather than today's clock.
 */
function formatChatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const today = entryDateOf();
  const messageDate = entryDateOf(d);
  if (messageDate === today) {
    const hh = String(d.getHours()).padStart(2, "0");
    const mm = String(d.getMinutes()).padStart(2, "0");
    return `${hh}:${mm}`;
  }
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  skeletonList: { gap: space[2] },
  list: { gap: space[2] },
  row: { flexDirection: "row", alignItems: "center", gap: space[3] },
  avatar: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: "center",
    justifyContent: "center",
  },
  unreadDot: {
    position: "absolute",
    top: -2,
    right: -2,
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: palette.coralDeep,
    borderWidth: 2,
    borderColor: surface.card,
  },
  headerLine: { flexDirection: "row", alignItems: "baseline", gap: space[2] },
  unreadBadge: {
    paddingHorizontal: space[2],
    paddingVertical: 2,
    borderRadius: 999,
    backgroundColor: palette.coralDeep,
    minWidth: 36,
    alignItems: "center",
  },
});
