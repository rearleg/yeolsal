import { StyleSheet, View } from "react-native";
import { MIN_DAYS_LABELS, type MinDays } from "../../api/rooms";
import { space } from "../../theme/spacing";
import { palette, semantic, surface } from "../../theme/tokens";
import { Button } from "../ui/Button";
import { Text } from "../ui/Text";
import { MinDaysSegmented } from "./MinDaysSegmented";

interface Props {
  /** Picker state — the parent owns it so onboarding wizards can reset on mount. */
  value: MinDays;
  onChange: (next: MinDays) => void;
  /** The minimum that's persisted server-side; save is disabled until value diverges. */
  currentMinimum: MinDays;
  /** Lower bound — the room-wide floor any member's override must respect. */
  roomFloor: MinDays;
  /** True while the parent's mutation is pending. */
  saving: boolean;
  /** Mounted right after a fresh join — adds a banner explaining the choice. */
  onboarding: boolean;
  /** Submit gate. The component will not call back unless the value differs and saving=false. */
  onSubmit: (min: MinDays) => void;
}

/**
 * Plan PR K: the minimum-days override used to live as a card on the
 * room detail screen. We push it onto its own settings surface so the
 * detail screen stays focused on members + chat. The exact same panel
 * is mounted with onboarding=true the first time a user lands here
 * after joining a room — same controls, slightly louder copy.
 */
export function RoomMinimumSettings({
  value,
  onChange,
  currentMinimum,
  roomFloor,
  saving,
  onboarding,
  onSubmit,
}: Props) {
  const dirty = value !== currentMinimum;
  const disabled = saving || !dirty;

  return (
    <View style={styles.root}>
      {onboarding ? (
        <View style={styles.banner} accessibilityRole="alert">
          <Text variant="bodyStrong" color={palette.coralDeep}>
            내 최소 목표일수를 처음 설정해주세요
          </Text>
          <Text variant="bodySmall" color={palette.inkMute} style={{ marginTop: space[1] }}>
            그룹 기준({MIN_DAYS_LABELS[roomFloor] ?? `${roomFloor}일`}) 이상으로
            나에게 맞는 강도를 선택할 수 있어요. 나중에 이 화면에서 다시 바꿀 수
            있습니다.
          </Text>
        </View>
      ) : (
        <Text variant="bodySmall" color={palette.inkMute}>
          그룹 기준({MIN_DAYS_LABELS[roomFloor] ?? `${roomFloor}일`}) 이상으로만
          올릴 수 있어요.
        </Text>
      )}

      <View style={styles.picker}>
        <MinDaysSegmented
          value={value}
          onChange={onChange}
          minAllowed={roomFloor}
          disabled={saving}
        />
      </View>

      <Button
        label={saving ? "저장 중…" : "변경 저장"}
        tone="primary"
        size="md"
        fullWidth
        disabled={disabled}
        onPress={() => {
          if (disabled) return;
          onSubmit(value);
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    gap: space[3],
  },
  banner: {
    padding: space[3],
    borderRadius: 12,
    backgroundColor: surface.sunken,
    borderLeftWidth: 4,
    borderLeftColor: semantic.info?.fg ?? palette.coralDeep,
  },
  picker: {
    gap: space[2],
  },
});
