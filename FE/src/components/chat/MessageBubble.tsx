import { StyleSheet, View } from "react-native";
import type { ChatMessageDto } from "../../api/chat";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Text } from "../ui/Text";
import { SystemMessage } from "./SystemMessage";

interface Props {
  message: ChatMessageDto;
  /** Currently-authed user id, so we can flip alignment for "me" vs others. */
  selfUserId: number | null;
  /** Optional sender label (resolved by parent from the room member roster). */
  senderName?: string;
}

export function MessageBubble({ message, selfUserId, senderName }: Props) {
  // Non-USER kinds delegate to <SystemMessage /> for kind-specific icons +
  // tones (GOAL/REFLECTION/MILESTONE/AUTO_LEAVE). The bubble path stays
  // user-only.
  if (message.kind !== "USER") {
    return <SystemMessage message={message} />;
  }

  const mine = selfUserId != null && message.senderUserId === selfUserId;
  const time = formatTime(message.createdAt);
  const a11y = `${mine ? "내가 보낸" : `${senderName ?? "상대"}가 보낸`} 메시지: ${message.body}, ${time}`;

  return (
    <View
      style={[styles.row, mine ? styles.rowMine : styles.rowTheirs]}
      accessibilityRole="text"
      accessibilityLabel={a11y}
    >
      <View style={[styles.bubble, mine ? styles.bubbleMine : styles.bubbleTheirs]}>
        {!mine && senderName ? (
          <Text variant="caption" color={palette.inkMute} style={{ marginBottom: 2 }}>
            {senderName}
          </Text>
        ) : null}
        <Text variant="bodySmall" color={mine ? palette.inkDeep : palette.ink}>
          {message.body}
        </Text>
      </View>
      <Text variant="caption" color={palette.inkFaint} style={mine ? styles.timeMine : styles.timeTheirs}>
        {time}
      </Text>
    </View>
  );
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${hh}:${mm}`;
}

const styles = StyleSheet.create({
  row: {
    flexDirection: "row",
    alignItems: "flex-end",
    gap: space[1],
    marginVertical: space[1],
    paddingHorizontal: space[2],
  },
  rowMine: { justifyContent: "flex-end" },
  rowTheirs: { justifyContent: "flex-start" },
  bubble: {
    maxWidth: "78%",
    paddingVertical: space[2],
    paddingHorizontal: space[3],
    borderRadius: 16,
  },
  bubbleMine: {
    backgroundColor: palette.coralSoft,
    borderBottomRightRadius: 4,
  },
  bubbleTheirs: {
    backgroundColor: surface.sunken,
    borderBottomLeftRadius: 4,
  },
  timeMine: { marginRight: space[1] },
  timeTheirs: { marginLeft: space[1] },
});
