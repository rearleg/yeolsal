// WalletPreview (Story 2.1 AC7).
//
// Spectator-only read-only block on the Today tab. Three lines, each on its
// own <Text> so VoiceOver doesn't trip over inline emoji-number concatenation
// (AC8). The full interactive Wallet UI is Story 3.4 — this block is the v1
// "see your balance, feel the FOMO" surface.
//
// Brand-voice contract (PRD FR-8.8.2, Rule 2): copy MUST NOT contain any of
// the 8 banned words. The emoji prefixes (🎟 / 🌿 / 💚) carry the dignity
// tone; pure RED is banned on spectator surfaces (UX A11 v2 guard).

import { StyleSheet, View } from "react-native";
import { useMeSurvivalQuery } from "../../lib/query/hooks/survival";
import { space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";
import { Text } from "../ui/Text";
import { SelfReviveCTA } from "./SelfReviveCTA";

export function WalletPreview() {
  const query = useMeSurvivalQuery();
  const entries = query.data ?? [];

  // v1 — read from the FIRST entry (multi-room aggregation lands in Story 3.4).
  // Fall through to null when the viewer has no rooms (the parent screen
  // gates rendering on `useIsSpectatorEverywhere()`, which already returns
  // false for zero memberships, so this is a defensive check).
  const first = entries.length > 0 ? entries[0] : null;
  if (first == null) return null;

  const showTicket = first.freeRevivalTicketUsed === false;

  return (
    <View style={styles.container}>
      {showTicket ? (
        <Text
          variant="bodyStrong"
          color={palette.ink}
          accessibilityLabel="무료 회생권 1매"
        >
          {"🎟  무료 회생권 1매"}
        </Text>
      ) : null}
      <Text
        variant="body"
        color={palette.ink}
        accessibilityLabel={`개인 포인트 ${first.personalPoints}점`}
      >
        {`🌿  개인 포인트 ${first.personalPoints}점`}
      </Text>
      <Text
        variant="body"
        color={palette.ink}
        accessibilityLabel={`그룹 포인트 ${first.roomPointPool}`}
      >
        {`💚  그룹 포인트 ${first.roomPointPool}`}
      </Text>
      <SelfReviveCTA roomId={first.roomId} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: space[1],
    padding: space[3],
    borderRadius: 12,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
  },
});
