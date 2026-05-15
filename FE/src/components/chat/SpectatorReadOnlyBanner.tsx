// SpectatorReadOnlyBanner (Story 2.1 AC3).
//
// Drop-in replacement for <MessageInput> on the chat screen when the
// viewer's survival status for the current room is SPECTATOR. Non-
// interactive — no pressable, no text input — and accessibility-tagged
// as plain text so screen readers announce it without prompting the user
// to interact.
//
// Brand-voice contract (PRD FR-8.8.2, Rule 2 — banned lexicon):
//   "관전 중 — 메시지는 회생 후에 다시 보낼 수 있어요"
//   - dignity tone, no shame language
//   - none of: 벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감
//   - matches the D3 Quiet Dark sub-mode the (tabs) layout wraps when
//     `isSpectatorEverywhere === true`

import { StyleSheet, View } from "react-native";
import { space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";
import { Text } from "../ui/Text";

const BANNER_COPY = "관전 중 — 메시지는 회생 후에 다시 보낼 수 있어요";

export function SpectatorReadOnlyBanner() {
  return (
    <View
      style={styles.container}
      accessibilityRole="text"
      accessibilityLabel={BANNER_COPY}
    >
      <Text variant="caption" color={palette.inkMute} align="center">
        {BANNER_COPY}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: space[3],
    paddingVertical: space[3],
    backgroundColor: surface.page,
    borderTopWidth: 1,
    borderTopColor: surface.border,
  },
});
