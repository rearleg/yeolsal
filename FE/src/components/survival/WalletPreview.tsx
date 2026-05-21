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

import { useState } from "react";
import { StyleSheet, View } from "react-native";
import { useMeSurvivalQuery } from "../../lib/query/hooks/survival";
import { space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";
import { Text } from "../ui/Text";
import {
  FriendGiftBadge,
  type FriendGiftBadgeTapTarget,
} from "../revival/FriendGiftBadge";
import { FriendGiftModal } from "../revival/FriendGiftModal";
import { FriendGiftPickerSheet } from "../revival/FriendGiftPickerSheet";
import type {
  FriendGiftTargetSummaryDto,
} from "../../api/friendGiftTargets";
import { SelfReviveCTA } from "./SelfReviveCTA";

export function WalletPreview() {
  const query = useMeSurvivalQuery();
  const entries = query.data ?? [];

  // Story 3.3 — local state for the badge tap flow (N=1 → modal direct;
  // N>1 → picker → modal). Lifted to WalletPreview because the badge,
  // picker, and modal need to coordinate around the same room context.
  const [pickerRoom, setPickerRoom] = useState<FriendGiftTargetSummaryDto | null>(null);
  const [modalTarget, setModalTarget] = useState<FriendGiftBadgeTapTarget | null>(null);

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
      <FriendGiftBadge
        roomId={first.roomId}
        onTap={setModalTarget}
        onTapMulti={setPickerRoom}
      />
      <SelfReviveCTA roomId={first.roomId} />

      <FriendGiftPickerSheet
        open={pickerRoom != null}
        room={pickerRoom}
        onSelect={(friend) => {
          if (pickerRoom == null) return;
          setModalTarget({
            roomId: pickerRoom.roomId,
            receiverUserId: friend.userId,
            receiverNickname: friend.nickname,
          });
          setPickerRoom(null);
        }}
        onCancel={() => setPickerRoom(null)}
      />

      {modalTarget != null ? (
        <FriendGiftModal
          open
          onClose={() => setModalTarget(null)}
          roomId={modalTarget.roomId}
          receiverUserId={modalTarget.receiverUserId}
          receiverNickname={modalTarget.receiverNickname}
          sourceSubtype="WALLET_INITIATED"
        />
      ) : null}
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
