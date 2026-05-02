import { FlashList } from "@shopify/flash-list";
import { useMemo } from "react";
import { ActivityIndicator, StyleSheet, View } from "react-native";
import type { ChatMessageDto } from "../../api/chat";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Text } from "../ui/Text";
import { MessageBubble } from "./MessageBubble";

interface Props {
  /** Ascending list (oldest → newest) — the parent flattens InfiniteData. */
  messages: ChatMessageDto[];
  selfUserId: number | null;
  /** roomId → nickname for sender labels on others' bubbles. */
  nicknameByUserId: Record<number, string>;
  loadingOlder?: boolean;
  hasOlder?: boolean;
  onLoadOlder?: () => void;
}

export function ChatList({
  messages,
  selfUserId,
  nicknameByUserId,
  loadingOlder,
  hasOlder,
  onLoadOlder,
}: Props) {
  // FlashList `inverted` renders the *first* item at the bottom, so reverse
  // the ascending list before handing it to the list. This keeps the cache
  // shape natural (ascending) while letting the screen render newest-down.
  const data = useMemo(() => [...messages].reverse(), [messages]);

  if (messages.length === 0) {
    return (
      <View style={styles.empty}>
        <Text variant="bodyStrong" color={palette.ink}>아직 메시지가 없어요</Text>
        <Text variant="caption" color={palette.inkMute} style={{ marginTop: space[1] }}>
          첫 메시지를 남겨봐요.
        </Text>
      </View>
    );
  }

  // FlashList v2 dropped the `inverted` prop; the documented workaround is a
  // double-flip via scaleY: parent flipped vertically, each item flipped back.
  // Combined with the reversed `data` array this puts the newest message at
  // the bottom while keeping `onEndReached` firing when the user scrolls up
  // toward older history.
  return (
    <FlashList
      data={data}
      style={styles.flipped}
      keyExtractor={(item: ChatMessageDto) => String(item.id)}
      renderItem={({ item }) => (
        <View style={styles.flipped}>
          <MessageBubble
            message={item}
            selfUserId={selfUserId}
            senderName={
              item.senderUserId != null ? nicknameByUserId[item.senderUserId] : undefined
            }
          />
        </View>
      )}
      onEndReached={() => {
        if (hasOlder && !loadingOlder && onLoadOlder) onLoadOlder();
      }}
      onEndReachedThreshold={0.4}
      ListFooterComponent={
        loadingOlder ? (
          <View style={[styles.footerLoader, styles.flipped]}>
            <ActivityIndicator color={palette.inkMute} />
          </View>
        ) : null
      }
    />
  );
}

const styles = StyleSheet.create({
  empty: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: space[6],
    backgroundColor: surface.page,
  },
  footerLoader: {
    paddingVertical: space[3],
    alignItems: "center",
  },
  flipped: { transform: [{ scaleY: -1 }] },
});
