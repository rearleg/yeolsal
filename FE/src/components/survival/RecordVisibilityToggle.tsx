// Story 2.3 — per-room record-visibility opt-in toggle.
//
// Lives on the per-room settings screen. Wires the AC4 brand-voice copy
// (그룹/공유 vocabulary, never 노출/탈락) to the optimistic-update mutation
// in useUpdateRecordVisibilityPref. On success, a toast confirms the new
// state in either direction; on error, the mutation hook rolls the cache
// back and we surface a generic error toast.

import { StyleSheet, Switch, View } from "react-native";
import { Text } from "../ui/Text";
import { toast } from "../../lib/toast";
import {
  useRecordVisibilityPref,
  useUpdateRecordVisibilityPref,
} from "../../lib/query/hooks/visibilityPrefs";
import { palette } from "../../theme/tokens";
import { space } from "../../theme/spacing";

interface RecordVisibilityToggleProps {
  roomId: number;
}

const COPY = {
  label: "이 그룹에서 내 기록 공유",
  onDescription: "공유를 켜면 내 잔디와 회고가 그룹 멤버에게 보여요.",
  offDescription: "꺼져 있어요 — 멤버에게 내 기록은 보이지 않아요.",
  toastOn: "이제 멤버들이 내 기록을 볼 수 있어요.",
  toastOff: "내 기록은 다시 비공개로 돌아갔어요.",
  toastError: "잠시 후 다시 시도해 주세요.",
  a11yLabel: "이 그룹에서 내 기록 공유 토글",
} as const;

export function RecordVisibilityToggle({ roomId }: RecordVisibilityToggleProps) {
  const pref = useRecordVisibilityPref(roomId);
  const mutation = useUpdateRecordVisibilityPref();

  const shareOnElimination = pref?.shareOnElimination ?? false;
  const description = shareOnElimination ? COPY.onDescription : COPY.offDescription;

  function handleToggle(next: boolean) {
    mutation.mutate(
      { roomId, shareOnElimination: next },
      {
        onSuccess: () => {
          toast.success(next ? COPY.toastOn : COPY.toastOff);
        },
        onError: () => {
          toast.error(COPY.toastError);
        },
      }
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.row}>
        <View style={styles.textColumn}>
          <Text variant="bodyStrong">{COPY.label}</Text>
          <Text
            variant="caption"
            color={palette.inkMute}
            accessibilityRole="text"
            style={styles.description}
          >
            {description}
          </Text>
        </View>
        <Switch
          value={shareOnElimination}
          onValueChange={handleToggle}
          disabled={mutation.isPending}
          accessibilityLabel={COPY.a11yLabel}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingVertical: space[3],
    paddingHorizontal: space[3],
    backgroundColor: palette.surfaceRaised,
    borderRadius: 12,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
  },
  textColumn: {
    flex: 1,
    gap: space[1],
  },
  description: {
    lineHeight: 18,
  },
});
