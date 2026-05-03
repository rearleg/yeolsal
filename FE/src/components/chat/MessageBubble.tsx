import { StyleSheet, View } from "react-native";
import type { ChatMessageDto } from "../../api/chat";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Text } from "../ui/Text";

interface Props {
  message: ChatMessageDto;
  /** Currently-authed user id, so we can flip alignment for "me" vs others. */
  selfUserId: number | null;
  /** Optional sender label (resolved by parent from the room member roster). */
  senderName?: string;
}

export function MessageBubble({ message, selfUserId, senderName }: Props) {
  const isSystem = message.kind !== "USER";
  // System-speech messages (PR G) get a centered, bubble-less treatment so a
  // GOAL/REFLECTION/MILESTONE/AUTO_LEAVE event isn't visually mistaken for a
  // user line. PR G will swap this stub for a richer <SystemMessage /> with
  // icons; this default renders the body text plainly so PR F+G handoff
  // doesn't leave system rows looking like "상대" speech.
  if (isSystem) {
    return (
      <View style={styles.systemRow} accessibilityLabel={`시스템 알림: ${message.body}`}>
        <Text variant="caption" color={palette.inkMute} style={styles.systemText}>
          {message.body}
        </Text>
      </View>
    );
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
  systemRow: {
    alignItems: "center",
    justifyContent: "center",
    marginVertical: space[2],
    paddingHorizontal: space[6],
  },
  systemText: {
    textAlign: "center",
    lineHeight: 16,
  },
});
