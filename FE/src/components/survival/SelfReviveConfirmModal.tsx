// SelfReviveConfirmModal (Story 3.1 AC8).
//
// Controlled modal that surfaces a single 1-tap confirm step per the
// Effortless Interactions #2 contract ("1탭 + 확인 모달 1회"). Body copy
// branches on `source` so the user sees what they're spending; both CTAs
// drive {@link useSelfRevival} with the matching source.
//
// Error policy:
//   - ALREADY_REVIVED          → body swaps to "이미 회생됐어요" for 1.5s,
//                                then auto-close (cache invalidation still
//                                fires inside the hook).
//   - INSUFFICIENT_POINTS      → toast "포인트가 모자라요", close after 1.5s.
//   - FREE_TICKET_ALREADY_USED → toast "이미 회생권을 썼어요", close after 1.5s.
// All copy passes the brand-voice lint AVOID lexicon.

import { useCallback, useEffect, useRef, useState } from "react";
import { Modal, Pressable, StyleSheet, View } from "react-native";
import { ApiError } from "../../api/client";
import type { RevivalSource } from "../../api/revival";
import { useSelfRevival } from "../../lib/query/hooks/revival";
import { toast } from "../../lib/toast";
import { space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";
import { Text } from "../ui/Text";

const COPY = {
  title: "방으로 돌아갈까요?",
  bodyFreeTicket: "무료 회생권 1매가 사용돼요.",
  bodyPersonalPoints: "개인 포인트 3점이 사용돼요.",
  primary: "돌아가기",
  ghost: "닫기",
  successToast: "방으로 돌아왔어요",
  alreadyRevivedBody: "이미 회생됐어요",
  insufficientToast: "포인트가 모자라요",
  freeTicketUsedToast: "이미 회생권을 썼어요",
} as const;

const OXBLOOD = "#7E2C2A";
const AUTO_CLOSE_DELAY_MS = 1500;

interface SelfReviveConfirmModalProps {
  open: boolean;
  onClose: () => void;
  roomId: number;
  source: RevivalSource;
}

export function SelfReviveConfirmModal({
  open,
  onClose,
  roomId,
  source,
}: SelfReviveConfirmModalProps) {
  const mutation = useSelfRevival(roomId);
  const [alreadyRevived, setAlreadyRevived] = useState(false);
  // Tracks pending auto-close timers so a re-open or unmount can't fire a
  // stale setTimeout against a later modal instance (review-finding 5).
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
      setAlreadyRevived(false);
      clearCloseTimer();
      mutation.reset();
    }
  }, [open, mutation, clearCloseTimer]);

  useEffect(() => {
    return clearCloseTimer;
  }, [clearCloseTimer]);

  const handleConfirm = () => {
    mutation.mutate(source, {
      onSuccess: () => {
        toast.success(COPY.successToast);
        onClose();
      },
      onError: (error) => {
        if (error instanceof ApiError) {
          if (error.code === "ALREADY_REVIVED") {
            setAlreadyRevived(true);
            scheduleAutoClose();
            return;
          }
          if (error.code === "INSUFFICIENT_POINTS") {
            toast.error(COPY.insufficientToast);
            scheduleAutoClose();
            return;
          }
          if (error.code === "FREE_TICKET_ALREADY_USED") {
            toast.error(COPY.freeTicketUsedToast);
            scheduleAutoClose();
            return;
          }
        }
        toast.error(COPY.insufficientToast);
        scheduleAutoClose();
      },
    });
  };

  const body =
    source === "FREE_TICKET"
      ? COPY.bodyFreeTicket
      : COPY.bodyPersonalPoints;

  return (
    <Modal
      visible={open}
      transparent
      animationType="fade"
      onRequestClose={onClose}
    >
      <View style={styles.backdrop}>
        <View style={styles.card} accessibilityViewIsModal>
          <Text variant="title" color={palette.ink} accessibilityRole="header">
            {alreadyRevived ? COPY.alreadyRevivedBody : COPY.title}
          </Text>
          {!alreadyRevived ? (
            <Text variant="body" color={palette.inkSoft}>
              {body}
            </Text>
          ) : null}

          <View style={styles.actions}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={COPY.primary}
              accessibilityState={{
                disabled: mutation.isPending || alreadyRevived,
              }}
              style={styles.primary}
              disabled={mutation.isPending || alreadyRevived}
              onPress={handleConfirm}
            >
              <Text variant="bodyStrong" color={palette.surface} weight="700">
                {COPY.primary}
              </Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={COPY.ghost}
              style={styles.ghost}
              onPress={onClose}
            >
              <Text variant="bodyStrong" color={palette.inkSoft}>
                {COPY.ghost}
              </Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.45)",
    justifyContent: "center",
    paddingHorizontal: space[4],
  },
  card: {
    backgroundColor: palette.paper,
    borderRadius: 14,
    padding: space[5],
    gap: space[3],
    borderWidth: 1,
    borderColor: surface.border,
  },
  actions: {
    gap: space[2],
    marginTop: space[2],
  },
  primary: {
    paddingVertical: space[3],
    paddingHorizontal: space[4],
    borderRadius: 10,
    backgroundColor: OXBLOOD,
    alignItems: "center",
  },
  ghost: {
    paddingVertical: space[3],
    paddingHorizontal: space[4],
    borderRadius: 10,
    alignItems: "center",
  },
});
