import { useLocalSearchParams } from "expo-router";
import { useMemo } from "react";
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  StyleSheet,
  View,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { useAuth } from "../../../src/auth/AuthContext";
import { useRequireAuth } from "../../../src/auth/useRequireAuth";
import { ChatList } from "../../../src/components/chat/ChatList";
import { MessageInput } from "../../../src/components/chat/MessageInput";
import { Text } from "../../../src/components/ui/Text";
import { useRoomMembersQuery } from "../../../src/lib/query/hooks/rooms";
import {
  useChatMessages,
  useSendChatMessage,
} from "../../../src/lib/query/hooks/chat";
import { space } from "../../../src/theme/spacing";
import { palette, surface } from "../../../src/theme/tokens";

export default function RoomChatScreen() {
  useRequireAuth();
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  const { user } = useAuth();

  const messagesQuery = useChatMessages(roomId);
  const membersQuery = useRoomMembersQuery(roomId);
  const sendMut = useSendChatMessage(roomId);

  // Flatten the InfiniteQuery pages into a single ascending list. Each page
  // is itself ascending; older pages come *after* newer pages in the cache,
  // so we reverse the page order before flattening.
  const messages = useMemo(() => {
    const pages = messagesQuery.data?.pages ?? [];
    const ordered = [...pages].reverse();
    return ordered.flatMap((page) => page.messages);
  }, [messagesQuery.data]);

  const nicknameByUserId = useMemo(() => {
    const map: Record<number, string> = {};
    for (const m of membersQuery.data ?? []) {
      map[m.userId] = m.nickname;
    }
    return map;
  }, [membersQuery.data]);

  return (
    <SafeAreaView style={styles.screen} edges={["top", "left", "right"]}>
      <View style={styles.header}>
        <Text variant="h2" numberOfLines={1}>채팅</Text>
      </View>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === "ios" ? "padding" : "height"}
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
        <MessageInput
          pending={sendMut.isPending}
          onSubmit={(body) => sendMut.mutate({ roomId, body })}
        />
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
