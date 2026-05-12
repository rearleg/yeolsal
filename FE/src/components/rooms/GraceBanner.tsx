import { StyleSheet, View } from "react-native";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Text } from "../ui/Text";

/**
 * Reassures the room creator that the first 14 days are a no-stake trial.
 *
 * Brand-voice §6.1: avoid "탈락"/"실패"/"꼴찌" — this copy frames the grace
 * as an offering, not a warning. Reviewed manually pending the lint helper
 * that ships with Story 8.2.
 */
export function GraceBanner() {
  return (
    <View
      accessibilityRole="text"
      accessibilityLabel="14일 무탈락 적응 기간 안내"
      style={styles.container}
    >
      <Text variant="bodyStrong" color={palette.ink}>
        첫 14일은 RED 없이 적응해볼 수 있어요
      </Text>
      <Text variant="caption" color={palette.inkMute}>
        새 그룹에 합류하면 매일 페이스를 익히는 동안 강제 종료 걱정이 없어요.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: space[1],
    padding: space[3],
    borderRadius: 12,
    backgroundColor: surface.sunken,
    borderWidth: 1,
    borderColor: surface.border,
  },
});
