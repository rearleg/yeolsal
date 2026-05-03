import { MaterialIcons } from "@expo/vector-icons";
import { StyleSheet, View } from "react-native";
import type { ChatMessageDto, ChatMessageKind } from "../../api/chat";
import { palette, semantic, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Text } from "../ui/Text";

interface Props {
  message: ChatMessageDto;
}

interface Visual {
  icon: keyof typeof MaterialIcons.glyphMap;
  iconColor: string;
  pillBg: string;
  textColor: string;
  emphasized?: boolean;
}

function visualFor(kind: ChatMessageKind): Visual {
  switch (kind) {
    case "GOAL":
      return {
        icon: "edit",
        iconColor: palette.coralDeep,
        pillBg: surface.sunken,
        textColor: palette.inkSoft,
      };
    case "REFLECTION":
      return {
        icon: "auto-stories",
        iconColor: palette.periwinkleDeep,
        pillBg: surface.sunken,
        textColor: palette.inkSoft,
      };
    case "MILESTONE":
      return {
        icon: "emoji-events",
        iconColor: palette.coralDeep,
        pillBg: palette.coralSoft,
        textColor: palette.inkDeep,
        emphasized: true,
      };
    case "AUTO_LEAVE":
      return {
        icon: "person-remove",
        iconColor: semantic.warning.fg,
        pillBg: surface.sunken,
        textColor: palette.inkMute,
      };
    case "SYSTEM":
    default:
      return {
        icon: "info-outline",
        iconColor: palette.inkMute,
        pillBg: surface.sunken,
        textColor: palette.inkMute,
      };
  }
}

const KIND_LABEL: Record<ChatMessageKind, string> = {
  USER: "메시지",
  SYSTEM: "시스템 알림",
  GOAL: "목표 작성 알림",
  REFLECTION: "회고 작성 알림",
  MILESTONE: "월간 달성 알림",
  AUTO_LEAVE: "자동 탈퇴 알림",
};

/**
 * Renders a non-USER chat row. Each kind gets its own icon + tone so the
 * stream of {@code GOAL → REFLECTION → MILESTONE} events stays visually
 * scannable instead of merging into a single muted column. Falls back to
 * the SYSTEM look for unknown kinds.
 */
export function SystemMessage({ message }: Props) {
  const v = visualFor(message.kind);
  return (
    <View
      style={styles.row}
      accessibilityRole="text"
      accessibilityLabel={`${KIND_LABEL[message.kind]}: ${message.body}`}
    >
      <View style={[styles.pill, { backgroundColor: v.pillBg }]}>
        <MaterialIcons name={v.icon} size={14} color={v.iconColor} />
        <Text
          variant={v.emphasized ? "bodyStrong" : "caption"}
          color={v.textColor}
          style={styles.body}
        >
          {message.body}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    alignItems: "center",
    justifyContent: "center",
    marginVertical: space[2],
    paddingHorizontal: space[3],
  },
  pill: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[1],
    paddingHorizontal: space[3],
    paddingVertical: space[1],
    borderRadius: 999,
    maxWidth: "92%",
  },
  body: {
    flexShrink: 1,
    textAlign: "center",
  },
});
