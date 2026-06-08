import { useLocalSearchParams } from "expo-router";
import { useEffect, useMemo } from "react";
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  StyleSheet,
  View,
} from "react-native";
import { SafeAreaView, useSafeAreaInsets } from "react-native-safe-area-context";
import { useAuth } from "../../../src/auth/AuthContext";
import { useRequireAuth } from "../../../src/auth/useRequireAuth";
import { ChatList } from "../../../src/components/chat/ChatList";
import { MessageInput } from "../../../src/components/chat/MessageInput";
import { SpectatorReadOnlyBanner } from "../../../src/components/chat/SpectatorReadOnlyBanner";
import { Text } from "../../../src/components/ui/Text";
import { useRoomMembersQuery } from "../../../src/lib/query/hooks/rooms";
import {
  useChatMessages,
  useChatRealtime,
  useMarkChatRead,
  useSendChatMessage,
} from "../../../src/lib/query/hooks/chat";
import { useCurrentRoomSurvivalState } from "../../../src/lib/query/hooks/survival";
import { space } from "../../../src/theme/spacing";
import { palette, surface } from "../../../src/theme/tokens";

export default function RoomChatScreen() {
  useRequireAuth();
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  const { user } = useAuth();

  const insets = useSafeAreaInsets();
  const messagesQuery = useChatMessages(roomId);
  const membersQuery = useRoomMembersQuery(roomId);
  const sendMut = useSendChatMessage(roomId);
  const markRead = useMarkChatRead(roomId);
  // Story 2.1 AC3 — derive the per-room spectator flag from the cached
  // cross-room survival query. `useCurrentRoomSurvivalState` returns null
  // when the viewer is not a member of this room; that falls through to
  // the regular MessageInput path.
  const currentRoomState = useCurrentRoomSurvivalState(roomId);
  const spectator = currentRoomState?.status === "SPECTATOR";
  // Subscribes to /topic/rooms.{id}.chat for the lifetime of the screen.
  // Incoming frames merge into the InfiniteQuery cache (deduped by id) so
  // peer messages appear without waiting for the polling fallback.
  useChatRealtime(roomId);

  // Flatten the InfiniteQuery pages into a single ascending list. Each page
  // is itself ascending; older pages come *after* newer pages in the cache,
  // so we reverse the page order before flattening.
  const messages = useMemo(() => {
    const pages = messagesQuery.data?.pages ?? [];
    const ordered = [...pages].reverse();
    return ordered.flatMap((page) => page.messages);
  }, [messagesQuery.data]);

  // While the chat screen is open, treat the freshest message as read. The
  // mutation no-ops when the watermark already covers `latestId` (handled
  // inside `useMarkChatRead`), so this fires at most once per new message.
  const latestId = messages.length > 0 ? messages[messages.length - 1].id : null;
  useEffect(() => {
    if (latestId == null) return;
    markRead.mutate(latestId);
    // NOTE: `markRead` (mutation handle) is intentionally excluded from the
    // dependency array — the handle is stable across renders, and including
    // it would re-fire this effect when the mutation state object identity
    // changes. Restore `// eslint-disable-next-line react-hooks/exhaustive-deps`
    // once eslint-plugin-react-hooks is registered (Epic 7 retro A1 follow-up).
  }, [latestId]);

  const nicknameByUserId = useMemo(() => {
    const map: Record<number, string> = {};
    for (const m of membersQuery.data ?? []) {
      map[m.userId] = m.nickname;
    }
    return map;
  }, [membersQuery.data]);

  return (
    <SafeAreaView style={styles.screen} edges={["top", "left", "right", "bottom"]}>
      <View style={styles.header}>
        <Text variant="h2" numberOfLines={1}>채팅</Text>
      </View>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === "ios" ? "padding" : "height"}
        // SafeAreaView reserves `insets.bottom` for the home indicator /
        // gesture bar; without this offset, KAV's padding-bottom would
        // double-count that area on iOS and leave a visible gap between
        // the keyboard and the input. Android (behavior=height) uses
        // adjustResize so no offset is needed.
        keyboardVerticalOffset={Platform.OS === "ios" ? insets.bottom : 0}
      >
        <View style={styles.flex}>
          {messagesQuery.isLoading ? (
            <View style={styles.loader}>
              <ActivityIndicator color={palette.coralDeep} />
            </View>
          ) : (
            <ChatList
              messages={messages}
              selfUserId={user?.id ?? null}
              nicknameByUserId={nicknameByUserId}
              loadingOlder={messagesQuery.isFetchingNextPage}
              hasOlder={!!messagesQuery.hasNextPage}
              onLoadOlder={() => messagesQuery.fetchNextPage()}
            />
          )}
        </View>
        {spectator ? (
          <SpectatorReadOnlyBanner />
        ) : (
          <MessageInput
            pending={sendMut.isPending}
            onSubmit={(body) => sendMut.mutate({ roomId, body })}
          />
        )}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: surface.page },
  flex: { flex: 1 },
  header: {
    minHeight: 56,
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: space[5],
    paddingTop: space[1],
    paddingBottom: space[2],
  },
  loader: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
  },
});
