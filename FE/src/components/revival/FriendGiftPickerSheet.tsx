// FriendGiftPickerSheet (Story 3.3 AC2, AC5).
//
// Slide-up sheet mounted when the wallet badge resolves N>1 eligible
// friends. One row per friend; tap dispatches onSelect with that
// friend's id/nickname; Cancel ("닫기") dispatches onCancel without
// firing onSelect. The parent (WalletPreview / Story 3.4 wallet
// surface) then opens FriendGiftModal with the selected receiver.

import { useEffect, useState } from "react";
import {
  AccessibilityInfo,
  Modal,
  Pressable,
  StyleSheet,
  View,
} from "react-native";
import { palette } from "../../theme/tokens";
import { Text } from "../ui/Text";
import type {
  EligibleFriendDto,
  FriendGiftTargetSummaryDto,
} from "../../api/friendGiftTargets";

const COPY = {
  title: "친구 살리기",
  subhead: "내 친구 중 회생 대기 중인 멤버",
  rowCta: "선택",
  cancel: "닫기",
} as const;

export interface FriendGiftPickerSheetProps {
  readonly open: boolean;
  readonly room: FriendGiftTargetSummaryDto | null;
  readonly onSelect: (friend: EligibleFriendDto) => void;
  readonly onCancel: () => void;
}

export function FriendGiftPickerSheet({
  open,
  room,
  onSelect,
  onCancel,
}: FriendGiftPickerSheetProps) {
  const [reduceMotion, setReduceMotion] = useState(false);
  useEffect(() => {
    let cancelled = false;
    AccessibilityInfo.isReduceMotionEnabled()
      .then((v) => {
        if (!cancelled) setReduceMotion(v);
      })
      .catch(() => {
        // RN web shim may reject; default to motion on.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!open || room == null) return null;

  return (
    <Modal
      visible={open}
      transparent
      animationType={reduceMotion ? "fade" : "slide"}
      onRequestClose={onCancel}
    >
      <View style={styles.backdrop}>
        <View
          style={styles.sheet}
          accessibilityViewIsModal
          accessibilityRole="alert"
        >
          <Text variant="bodyStrong" color={palette.ink}>
            {COPY.title}
          </Text>
          <Text variant="caption" color={palette.inkMute}>
            {COPY.subhead}
          </Text>

          <View style={styles.rows}>
            {room.friends.map((friend) => (
              <Pressable
                key={friend.userId}
                accessibilityRole="button"
                accessibilityLabel={`${friend.nickname} ${COPY.rowCta}`}
                onPress={() => onSelect(friend)}
                style={styles.row}
              >
                <View style={styles.avatar}>
                  <Text variant="body" color={palette.coralDeep}>🌿</Text>
                </View>
                <View style={styles.rowText}>
                  <Text variant="bodyStrong" color={palette.ink}>
                    {friend.nickname}
                  </Text>
                  <Text variant="caption" color={palette.inkMute}>
                    {friend.status}
                  </Text>
                </View>
                <Text variant="bodyStrong" color={palette.coralDeep}>
                  {COPY.rowCta}
                </Text>
              </Pressable>
            ))}
          </View>

          <Pressable
            accessibilityRole="button"
            accessibilityLabel={COPY.cancel}
            style={styles.cancel}
            onPress={onCancel}
          >
            <Text variant="bodyStrong" color={palette.inkSoft}>
              {COPY.cancel}
            </Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.45)",
    justifyContent: "flex-end",
  },
  sheet: {
    backgroundColor: palette.paper,
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    paddingHorizontal: 20,
    paddingVertical: 20,
    gap: 12,
    borderTopWidth: 1,
    borderColor: palette.border,
  },
  rows: {
    gap: 4,
    marginTop: 4,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    minHeight: 48,
    paddingVertical: 8,
    paddingHorizontal: 8,
    gap: 12,
    borderRadius: 10,
  },
  avatar: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: palette.coralSoft,
    alignItems: "center",
    justifyContent: "center",
  },
  rowText: {
    flex: 1,
  },
  cancel: {
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 10,
    alignItems: "center",
    minHeight: 48,
    justifyContent: "center",
    marginTop: 4,
  },
});
