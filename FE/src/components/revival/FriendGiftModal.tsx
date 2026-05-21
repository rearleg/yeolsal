// Story 3.2 AC3 + AC4 — FriendGiftModal (3-CTA equal-weight surface).
//
// Mounted by the room screen when the SecureStore
// `yeosal.pendingFriendGiftPrompt` slot is non-empty OR when a user taps
// the wallet-side gift CTA (Story 3.4 surface). Three CTAs of equal
// visual + a11y weight:
//
//   1. Primary  — "💗 회생권 선물 (5점)" (oxblood filled, disabled if balance < 5)
//   2. Secondary — KudosButton (always enabled — 0 cost)
//   3. Tertiary — "닫기" (ghost, closes without API call)
//
// Comfort footer + error toast branching follow AC4. `closeTimerRef`
// pattern mirrors Story 3.1 review-finding 5 (SelfReviveConfirmModal:58-73).

import { useCallback, useEffect, useRef, useState } from "react";
import { Modal, Pressable, StyleSheet, View } from "react-native";
import { ApiError } from "../../api/client";
import { useSendFriendGift } from "../../lib/query/hooks/friendGift";
import { useCurrentRoomSurvivalState } from "../../lib/query/hooks/survival";
import { toast } from "../../lib/toast";
import { palette } from "../../theme/tokens";
import { Text } from "../ui/Text";
import { KudosButton } from "./KudosButton";
import { M35LifetimeOneOverlay } from "./M35LifetimeOneOverlay";

const COPY = {
  receiverWaitingLabel: "회생 대기",
  myBalancePrefix: "내 잔액: ",
  myBalanceSuffix: "점",
  primary: "💗 회생권 선물 (5점)",
  tertiary: "닫기",
  comfortFooter: "선물해도 안 해도 친구는 모릅니다.",
  disabledTooltip: "잔액 부족 (5점 필요)",
  successToastSuffix: "에게 회생권을 선물했어요",
  alreadyRevivedToast: "이미 회생되었습니다",
  insufficientToast: "포인트가 모자라요 (5점 필요)",
  notFriendsToast: "친구가 된 멤버에게만 선물할 수 있어요",
  notEliminatedToast: "이 친구는 지금 회생 대상이 아니에요",
  spectatorForbiddenToast: "관전자는 회생권을 선물할 수 없어요",
  genericToast: "잠시 후 다시 시도해주세요",
} as const;

const OXBLOOD = "#7E2C2A";
const FRIEND_GIFT_COST = 5;
const AUTO_CLOSE_DELAY_MS = 1500;

interface FriendGiftModalProps {
  open: boolean;
  onClose: () => void;
  roomId: number;
  receiverUserId: number;
  receiverNickname: string;
  /** Story 3.3 AC3 — discriminator the BE stamps on revival_events.
   *  Omit for Story 3.2 push-deep-link callers; pass "WALLET_INITIATED"
   *  from the badge tap path. */
  sourceSubtype?: "PUSH_INITIATED" | "WALLET_INITIATED";
}

export function FriendGiftModal({
  open,
  onClose,
  roomId,
  receiverUserId,
  receiverNickname,
  sourceSubtype,
}: FriendGiftModalProps) {
  const mutation = useSendFriendGift(roomId);
  const entry = useCurrentRoomSurvivalState(roomId);
  const myBalance = entry?.personalPoints ?? 0;
  const balanceDisabled = myBalance < FRIEND_GIFT_COST;
  const [m35Open, setM35Open] = useState(false);
  const closeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearCloseTimer = useCallback(() => {
    if (closeTimerRef.current !== null) {
      clearTimeout(closeTimerRef.current);
      closeTimerRef.current = null;
    }
  }, []);

  const scheduleAutoClose = useCallback(() => {
    clearCloseTimer();
    closeTimerRef.current = setTimeout(() => {
      closeTimerRef.current = null;
      onClose();
    }, AUTO_CLOSE_DELAY_MS);
  }, [clearCloseTimer, onClose]);

  useEffect(() => {
    if (!open) {
      setM35Open(false);
      clearCloseTimer();
      mutation.reset();
    }
  }, [open, mutation, clearCloseTimer]);

  useEffect(() => clearCloseTimer, [clearCloseTimer]);

  const handlePrimary = () => {
    mutation.mutate(
      { targetUserId: receiverUserId, sourceSubtype },
      {
        onSuccess: (dto) => {
          toast.success(receiverNickname + COPY.successToastSuffix);
          if (dto.isFirstEverFriendGiftSend) {
            setM35Open(true);
          } else {
            onClose();
          }
        },
        onError: (error) => {
          if (error instanceof ApiError) {
            const code = error.code;
            if (code === "ALREADY_REVIVED") toast.error(COPY.alreadyRevivedToast);
            else if (code === "INSUFFICIENT_GIFT_POINTS") toast.error(COPY.insufficientToast);
            else if (code === "NOT_FRIENDS_FOR_GIFT") toast.error(COPY.notFriendsToast);
            else if (code === "NOT_ELIMINATED") toast.error(COPY.notEliminatedToast);
            else if (code === "SPECTATOR_WRITE_FORBIDDEN") toast.error(COPY.spectatorForbiddenToast);
            else toast.error(COPY.genericToast);
            scheduleAutoClose();
            return;
          }
          toast.error(COPY.genericToast);
        },
      },
    );
  };

  return (
    <>
      <Modal
        visible={open && !m35Open}
        transparent
        animationType="fade"
        onRequestClose={onClose}
      >
        <View style={styles.backdrop}>
          <View style={styles.card} accessibilityViewIsModal>
            <View style={styles.receiverRow}>
              <View style={styles.avatar} accessibilityLabel={receiverNickname}>
                <Text variant="body" color={palette.coralDeep}>🌿</Text>
              </View>
              <Text variant="bodyStrong" color={palette.ink}>
                {receiverNickname}
              </Text>
              <Text variant="caption" color={palette.inkMute}>
                {COPY.receiverWaitingLabel}
              </Text>
            </View>

            <Text variant="body" color={palette.ink}>
              {COPY.myBalancePrefix}{myBalance}{COPY.myBalanceSuffix}
            </Text>

            <View style={styles.actions}>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={COPY.primary}
                accessibilityState={{ disabled: balanceDisabled || mutation.isPending }}
                disabled={balanceDisabled || mutation.isPending}
                style={[
                  styles.primary,
                  (balanceDisabled || mutation.isPending) && styles.primaryDisabled,
                ]}
                onPress={handlePrimary}
              >
                <Text variant="bodyStrong" color={palette.surface} weight="700">
                  {COPY.primary}
                </Text>
              </Pressable>
              {balanceDisabled ? (
                <Text
                  variant="caption"
                  color={palette.inkMute}
                  accessibilityLabel={COPY.disabledTooltip}
                >
                  {COPY.disabledTooltip}
                </Text>
              ) : null}

              <KudosButton
                roomId={roomId}
                targetUserId={receiverUserId}
                onSettled={scheduleAutoClose}
              />

              <Pressable
                accessibilityRole="button"
                accessibilityLabel={COPY.tertiary}
                style={styles.tertiary}
                onPress={onClose}
              >
                <Text variant="bodyStrong" color={palette.inkSoft}>
                  {COPY.tertiary}
                </Text>
              </Pressable>
            </View>

            <Text
              variant="caption"
              color={palette.inkMute}
              accessibilityLabel={COPY.comfortFooter}
            >
              {COPY.comfortFooter}
            </Text>
          </View>
        </View>
      </Modal>

      <M35LifetimeOneOverlay open={m35Open} onComplete={onClose} />
    </>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.45)",
    justifyContent: "center",
    paddingHorizontal: 16,
  },
  card: {
    backgroundColor: palette.paper,
    borderRadius: 14,
    padding: 20,
    gap: 12,
    borderWidth: 1,
    borderColor: palette.border,
  },
  receiverRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  avatar: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: palette.coralSoft,
    alignItems: "center",
    justifyContent: "center",
  },
  actions: {
    gap: 8,
    marginTop: 4,
  },
  primary: {
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 10,
    backgroundColor: OXBLOOD,
    alignItems: "center",
    minHeight: 48,
    justifyContent: "center",
  },
  primaryDisabled: {
    backgroundColor: palette.inkMute,
    opacity: 0.5,
  },
  tertiary: {
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 10,
    alignItems: "center",
    minHeight: 48,
    justifyContent: "center",
  },
});
