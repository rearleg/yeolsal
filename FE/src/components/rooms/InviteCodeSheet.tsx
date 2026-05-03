import { MaterialIcons } from "@expo/vector-icons";
import * as Clipboard from "expo-clipboard";
import * as Haptics from "expo-haptics";
import { Modal, Pressable, StyleSheet, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import type { RoomInvite } from "../../api/rooms";
import { toast } from "../../lib/toast";
import { layout, space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";
import { Button } from "../ui/Button";
import { Text } from "../ui/Text";

interface Props {
  /** Modal visibility — parent owns the boolean. */
  visible: boolean;
  /** Latest invite this session has issued, or null if not yet requested. */
  invite: RoomInvite | null;
  /** Set while the create-invite mutation is pending so the button can disable. */
  isCreating: boolean;
  /** Triggered when the user asks to issue a fresh invite. */
  onCreate: () => void;
  /** Triggered when the user taps "공유하기" — parent opens the share sheet. */
  onShare: () => void;
  /** Triggered for the close affordance and Android back button. */
  onClose: () => void;
}

/**
 * Plan PR J: the room-detail invite UI lives off-screen and only opens
 * when the user explicitly asks for it via the header "person-add"
 * button. Keeping this component pure-presentational means the
 * RoomDetailScreen owns all state — easier to test, and lets follow-up
 * PRs (long-press copy in PR L) attach affordances without re-plumbing
 * a smart wrapper.
 */
export function InviteCodeSheet({
  visible,
  invite,
  isCreating,
  onCreate,
  onShare,
  onClose,
}: Props) {
  return (
    <Modal
      visible={visible}
      animationType="slide"
      presentationStyle="formSheet"
      onRequestClose={onClose}
    >
      <SafeAreaView style={styles.screen}>
        <View style={styles.header}>
          <Text variant="h2">초대 코드</Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="초대 코드 화면 닫기"
            hitSlop={12}
            onPress={onClose}
            style={styles.closeBtn}
          >
            <MaterialIcons name="close" size={22} color={palette.ink} />
          </Pressable>
        </View>

        <View style={styles.body}>
          {invite ? (
            <View style={styles.codeBox}>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="초대 코드 길게 눌러 복사"
                accessibilityHint="길게 누르면 초대 코드만 클립보드에 복사돼요"
                delayLongPress={400}
                onLongPress={async () => {
                  try {
                    await Clipboard.setStringAsync(invite.code);
                  } catch {
                    // Clipboard write can fail on locked-down enterprise
                    // builds; abandon silently rather than fake a copy.
                    return;
                  }
                  // Haptic is a nice-to-have — never block the toast on it.
                  Haptics.notificationAsync(
                    Haptics.NotificationFeedbackType.Success,
                  ).catch(() => undefined);
                  toast.success("초대 코드를 복사했어요");
                }}
                style={styles.codePress}
              >
                <Text variant="display" weight="800">
                  {invite.code}
                </Text>
              </Pressable>
              {invite.expiresAt ? (
                <Text variant="caption" color={palette.inkMute}>
                  유효기간: {new Date(invite.expiresAt).toLocaleDateString("ko-KR")}
                </Text>
              ) : null}
              <Button
                label="공유하기"
                tone="primary"
                size="md"
                fullWidth
                onPress={onShare}
              />
            </View>
          ) : (
            <View style={styles.intro}>
              <Text variant="bodySmall" color={palette.inkMute}>
                새 초대 코드는 7일간 유효합니다. 코드를 친구에게 보내면
                같은 그룹에서 서로의 회고를 함께 볼 수 있어요.
              </Text>
              <Button
                label={isCreating ? "발급 중…" : "초대 코드 만들기"}
                tone="primary"
                size="md"
                fullWidth
                disabled={isCreating}
                onPress={onCreate}
              />
            </View>
          )}
        </View>
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: surface.page,
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: layout.pageHorizontal,
    paddingTop: space[1],
    paddingBottom: space[2],
  },
  closeBtn: {
    padding: space[1],
  },
  body: {
    flex: 1,
    paddingHorizontal: layout.pageHorizontal,
    paddingTop: space[2],
    gap: space[3],
  },
  intro: {
    gap: space[3],
  },
  codeBox: {
    alignItems: "center",
    padding: space[4],
    borderRadius: 16,
    backgroundColor: surface.sunken,
    gap: space[2],
  },
  codePress: {
    paddingHorizontal: space[2],
    paddingVertical: space[1],
  },
});
