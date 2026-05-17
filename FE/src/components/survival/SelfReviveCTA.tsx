// SelfReviveCTA (Story 3.1 AC8).
//
// Spectator-only CTA rendered below the WalletPreview balance block. Three
// states map to three rendered variants:
//
//   1. ticket-unused + status ∈ {RED, SPECTATOR}
//        → primary CTA "회생권 사용" (oxblood, filled)
//   2. ticket-used   + balance ≥ 3 + status ∈ {RED, SPECTATOR}
//        → secondary CTA "포인트로 회생 (3점)" (muted outline)
//   3. ticket-used   + balance < 3
//        → muted caption "친구의 회생권 선물을 기다려요" (forward-pointer
//          to Story 3.2; no points-buy CTA in v1)
//
// Brand-voice contract — copy MUST NOT include any of the 8 banned words
// (벌금/잃었다/떨어졌다/실패/자책/부담/패배/죄책감). The strings here are
// vetted; tests assert against them directly.

import { useState } from "react";
import { Pressable, StyleSheet, View } from "react-native";
import { useCurrentRoomSurvivalState } from "../../lib/query/hooks/survival";
import { space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";
import { Text } from "../ui/Text";
import { SelfReviveConfirmModal } from "./SelfReviveConfirmModal";
import type { RevivalSource } from "../../api/revival";

const COPY = {
  primary: "회생권 사용",
  secondary: "포인트로 회생 (3점)",
  waiting: "친구의 회생권 선물을 기다려요",
} as const;

// Oxblood key tone from the v2 design system (Story 1.5 — static hex,
// not a render-tree token, because this component is outside the v2
// theme context's reach for now).
const OXBLOOD = "#7E2C2A";

interface SelfReviveCTAProps {
  roomId: number;
}

export function SelfReviveCTA({ roomId }: SelfReviveCTAProps) {
  const entry = useCurrentRoomSurvivalState(roomId);
  const [pendingSource, setPendingSource] = useState<RevivalSource | null>(null);

  if (entry == null) return null;
  const eliminated =
    entry.status === "RED" || entry.status === "SPECTATOR";
  if (!eliminated) return null;

  const primaryVisible = entry.freeRevivalTicketUsed === false;
  const secondaryEligible =
    entry.freeRevivalTicketUsed === true && entry.personalPoints >= 3;
  const waitingForFriend =
    entry.freeRevivalTicketUsed === true && entry.personalPoints < 3;

  return (
    <View style={styles.container}>
      {primaryVisible ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={COPY.primary}
          style={styles.primary}
          onPress={() => setPendingSource("FREE_TICKET")}
        >
          <Text variant="bodyStrong" color={palette.surface} weight="700">
            {COPY.primary}
          </Text>
        </Pressable>
      ) : null}

      {secondaryEligible ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={COPY.secondary}
          style={styles.secondary}
          onPress={() => setPendingSource("PERSONAL_POINTS")}
        >
          <Text variant="bodyStrong" color={palette.ink}>
            {COPY.secondary}
          </Text>
        </Pressable>
      ) : null}

      {waitingForFriend ? (
        <Text
          variant="caption"
          color={palette.inkMute}
          accessibilityLabel={COPY.waiting}
        >
          {COPY.waiting}
        </Text>
      ) : null}

      <SelfReviveConfirmModal
        open={pendingSource !== null}
        source={pendingSource ?? "FREE_TICKET"}
        roomId={roomId}
        onClose={() => setPendingSource(null)}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: space[2],
    marginTop: space[3],
  },
  primary: {
    paddingVertical: space[3],
    paddingHorizontal: space[4],
    borderRadius: 10,
    backgroundColor: OXBLOOD,
    alignItems: "center",
  },
  secondary: {
    paddingVertical: space[3],
    paddingHorizontal: space[4],
    borderRadius: 10,
    borderWidth: 1,
    borderColor: surface.borderStrong,
    backgroundColor: palette.surface,
    alignItems: "center",
  },
});
