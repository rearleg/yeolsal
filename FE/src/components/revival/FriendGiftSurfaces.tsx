// Story 3.2 FE-12 — single wrapper that lights up the friend-gift
// surfaces (FriendGiftModal, RevivalSequence, SevenDayFootnote) for the
// current room. Mounted by the room screen so the wiring lives in one
// place rather than scattered across the screen body.
//
// SecureStore single-shot slots drive the modal + sequence; the 7-day
// footnote reads its data via React Query directly.

import { useEffect, useState } from "react";
import { View } from "react-native";
import * as SecureStore from "expo-secure-store";
import {
  PENDING_FRIEND_GIFT_PROMPT_KEY,
  PENDING_REVIVAL_SEQUENCE_KEY,
  type PendingFriendGiftPromptSlot,
  type PendingRevivalSequenceSlot,
} from "../../lib/notifications";
import { hasPlayedRevivalEvent } from "../../lib/playedRevivalEvents";
import { FriendGiftModal } from "./FriendGiftModal";
import { RevivalSequence } from "./RevivalSequence";
import { SevenDayFootnote } from "./SevenDayFootnote";

interface FriendGiftSurfacesProps {
  roomId: number;
}

export function FriendGiftSurfaces({ roomId }: FriendGiftSurfacesProps) {
  const [promptSlot, setPromptSlot] = useState<PendingFriendGiftPromptSlot | null>(null);
  const [sequenceSlot, setSequenceSlot] = useState<PendingRevivalSequenceSlot | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const raw = await SecureStore.getItemAsync(PENDING_FRIEND_GIFT_PROMPT_KEY);
        if (raw != null && raw !== "") {
          const parsed = safeParse<PendingFriendGiftPromptSlot>(raw);
          if (!cancelled && parsed != null && parsed.roomId === roomId) {
            setPromptSlot(parsed);
          }
        }
      } catch {
        // Native module unavailable — skip silently.
      }

      try {
        const raw = await SecureStore.getItemAsync(PENDING_REVIVAL_SEQUENCE_KEY);
        if (raw != null && raw !== "") {
          const parsed = safeParse<PendingRevivalSequenceSlot>(raw);
          if (parsed != null && parsed.roomId === roomId) {
            const played = await hasPlayedRevivalEvent(parsed.revivalEventId);
            if (!cancelled && !played) {
              setSequenceSlot(parsed);
            }
          }
        }
      } catch {
        // Native module unavailable — skip silently.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [roomId]);

  const closePrompt = () => {
    setPromptSlot(null);
    void SecureStore.deleteItemAsync(PENDING_FRIEND_GIFT_PROMPT_KEY).catch(() => undefined);
  };

  const closeSequence = () => {
    setSequenceSlot(null);
    void SecureStore.deleteItemAsync(PENDING_REVIVAL_SEQUENCE_KEY).catch(() => undefined);
  };

  return (
    <View>
      <SevenDayFootnote roomId={roomId} />
      {promptSlot != null ? (
        <FriendGiftModal
          open={true}
          onClose={closePrompt}
          roomId={promptSlot.roomId}
          receiverUserId={promptSlot.receiverUserId}
          receiverNickname={promptSlot.receiverNickname}
        />
      ) : null}
      {sequenceSlot != null ? (
        <RevivalSequence
          open={true}
          donorName={sequenceSlot.donorNickname}
          receiverNickname=""
          revivalEventId={sequenceSlot.revivalEventId}
          roomId={sequenceSlot.roomId}
          onComplete={closeSequence}
        />
      ) : null}
    </View>
  );
}

function safeParse<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}
