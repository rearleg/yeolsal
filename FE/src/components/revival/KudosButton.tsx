// Story 3.2 FE-8 — secondary CTA in the FriendGiftModal that delegates to
// the existing Story 3.5 useSendKudos hook. Always enabled regardless of
// the giver's personal-points balance (kudos is 0-cost — UX line 1572 +
// epics 491). Error/success toast handling lives here since the Story 3.5
// hook does not toast (per its AC10 comment).

import { Pressable, StyleSheet } from "react-native";
import { useSendKudos } from "../../lib/query/hooks/kudos";
import { ApiError } from "../../api/client";
import { toast } from "../../lib/toast";
import { palette } from "../../theme/tokens";
import { Text } from "../ui/Text";

const COPY = {
  label: "💚 응원만 보내기 (0점)",
  successToast: "응원이 도착했어요 🌿",
  alreadySentToast: "오늘은 이미 응원을 보냈어요",
  notFriendsToast: "친구가 된 멤버에게만 보낼 수 있어요",
  targetNotEligibleToast: "응원은 회생을 기다리는 멤버에게만 보낼 수 있어요",
  genericToast: "잠시 후 다시 시도해주세요",
} as const;

interface KudosButtonProps {
  roomId: number;
  targetUserId: number;
  /** Called after the kudos request resolves (success OR error). The
   *  parent modal uses this to schedule its auto-close timer. */
  onSettled?: () => void;
}

export function KudosButton({ roomId, targetUserId, onSettled }: KudosButtonProps) {
  const mutation = useSendKudos(roomId);

  const handlePress = () => {
    mutation.mutate(
      { targetUserId },
      {
        onSuccess: () => {
          toast.success(COPY.successToast);
          onSettled?.();
        },
        onError: (error) => {
          if (error instanceof ApiError) {
            if (error.code === "KUDOS_ALREADY_SENT_TODAY") {
              toast.error(COPY.alreadySentToast);
              onSettled?.();
              return;
            }
            if (error.code === "NOT_FRIENDS") {
              toast.error(COPY.notFriendsToast);
              onSettled?.();
              return;
            }
            if (error.code === "KUDOS_TARGET_NOT_ELIGIBLE") {
              toast.error(COPY.targetNotEligibleToast);
              onSettled?.();
              return;
            }
          }
          toast.error(COPY.genericToast);
          onSettled?.();
        },
      },
    );
  };

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={COPY.label}
      accessibilityState={{ disabled: mutation.isPending }}
      disabled={mutation.isPending}
      style={styles.button}
      onPress={handlePress}
    >
      <Text variant="bodyStrong" color={palette.ink}>
        {COPY.label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: palette.inkMute,
    alignItems: "center",
    minHeight: 48,
    justifyContent: "center",
  },
});
