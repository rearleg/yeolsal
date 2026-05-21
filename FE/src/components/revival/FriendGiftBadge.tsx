// FriendGiftBadge (Story 3.3 AC1, AC2, AC5, AC6).
//
// Small Bento-style surface mounted on the wallet (WalletPreview today;
// Story 3.4 full Wallet surface later) that lights up when at least one
// room-mate friend is RED/SPECTATOR AND I (the giver) have ≥ 5 personal
// points. Tapping with N=1 dispatches the modal-open callback directly;
// N>1 dispatches the picker-open callback so the parent mounts
// FriendGiftPickerSheet first.
//
// Visual treatment uses the v1 transitional palette tokens for now;
// Story 3.4 migrates the Wallet surface to D2.bento sub-mode tokens via
// SubModeProvider + useTheme() (Architecture §4.16).

import { Pressable, StyleSheet, View } from "react-native";
import {
  useFriendGiftTargetsForRoom,
} from "../../lib/query/hooks/friendGiftTargets";
import { useCurrentRoomSurvivalState } from "../../lib/query/hooks/survival";
import { palette } from "../../theme/tokens";
import { Text } from "../ui/Text";
import type {
  EligibleFriendDto,
  FriendGiftTargetSummaryDto,
} from "../../api/friendGiftTargets";

const FRIEND_GIFT_COST = 5;

const COPY = {
  badgePrefix: "친구 회생 대기 (",
  badgeSuffix: ")",
  insufficientCaption: "잔액 부족 (5점 필요)",
  a11yEnabledTemplate: (n: number) =>
    `친구 회생 대기 ${n}명, 탭하면 회생권 선물 화면이 열려요`,
  a11yDisabledHint: "포인트가 5점 이상일 때 사용할 수 있어요",
} as const;

export interface FriendGiftBadgeTapTarget {
  readonly roomId: number;
  readonly receiverUserId: number;
  readonly receiverNickname: string;
}

export interface FriendGiftBadgeProps {
  readonly roomId: number;
  /** Called when the badge resolves N=1: open the modal directly. */
  readonly onTap: (target: FriendGiftBadgeTapTarget) => void;
  /** Called when the badge resolves N>1: open the picker. */
  readonly onTapMulti?: (room: FriendGiftTargetSummaryDto) => void;
}

export function FriendGiftBadge({
  roomId,
  onTap,
  onTapMulti,
}: FriendGiftBadgeProps) {
  const room = useFriendGiftTargetsForRoom(roomId);
  const survival = useCurrentRoomSurvivalState(roomId);
  const balance = survival?.personalPoints ?? 0;
  const eligibleCount = room?.eligibleCount ?? 0;

  // AC6 — early return when nothing to show (also covers the spectator-
  // everywhere case: BE returns an empty list for spectator givers).
  if (eligibleCount === 0 || room == null) {
    return null;
  }

  const disabled = balance < FRIEND_GIFT_COST;
  const label = `${COPY.badgePrefix}${eligibleCount}${COPY.badgeSuffix}`;

  const handlePress = () => {
    if (disabled) return;
    if (eligibleCount === 1) {
      const first: EligibleFriendDto = room.friends[0];
      onTap({
        roomId: room.roomId,
        receiverUserId: first.userId,
        receiverNickname: first.nickname,
      });
      return;
    }
    onTapMulti?.(room);
  };

  return (
    <View>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={COPY.a11yEnabledTemplate(eligibleCount)}
        accessibilityState={{ disabled }}
        accessibilityHint={disabled ? COPY.a11yDisabledHint : undefined}
        disabled={disabled}
        onPress={handlePress}
        style={[styles.surface, disabled && styles.surfaceDisabled]}
      >
        <Text variant="bodyStrong" color={disabled ? palette.inkMute : palette.coralDeep}>
          {label}
        </Text>
      </Pressable>
      {disabled ? (
        <Text
          variant="caption"
          color={palette.inkMute}
          accessibilityLabel={COPY.insufficientCaption}
        >
          {COPY.insufficientCaption}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  surface: {
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 12,
    backgroundColor: palette.coralSoft,
    minHeight: 48,
    justifyContent: "center",
  },
  surfaceDisabled: {
    backgroundColor: palette.surfaceContrast,
    opacity: 0.65,
  },
});
