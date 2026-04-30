import { StyleSheet, View } from "react-native";
import type { GrassDayDto } from "../../api/types";
import { palette, semantic } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Card } from "../ui/Card";
import { Text } from "../ui/Text";
import { bucketDescription, bucketFor } from "../../lib/bucket";

interface Props {
  day: GrassDayDto | null;
}

function formatDate(iso: string): string {
  const [, m, d] = iso.split("-");
  return `${Number.parseInt(m, 10)}월 ${Number.parseInt(d, 10)}일`;
}

export function DayDetailCard({ day }: Props) {
  if (!day) {
    return (
      <Card tone="sunken" size="md">
        <Text variant="bodySmall" color={palette.inkMute}>
          잔디 칸을 눌러 그날의 기록을 확인하세요.
        </Text>
      </Card>
    );
  }

  const bucket = bucketFor(day);
  const status = day.reflectionSubmitted
    ? day.missionCompleted
      ? { label: "성공", color: semantic.success.fg, bg: semantic.success.bg }
      : { label: "회고 기록", color: semantic.info.fg, bg: semantic.info.bg }
    : { label: "기록 없음", color: palette.inkMute, bg: palette.surfaceContrast };

  return (
    <Card tone="sunken" size="md">
      <View style={styles.row}>
        <Text variant="title" color={palette.ink}>{formatDate(day.date)}</Text>
        <View style={[styles.badge, { backgroundColor: status.bg }]}>
          <Text variant="caption" color={status.color} weight="700">{status.label}</Text>
        </View>
      </View>
      <View style={styles.metaRow}>
        <Text variant="bodySmall" color={palette.inkSoft}>완료한 할 일</Text>
        <Text variant="bodySmall" color={palette.ink} weight="700">{day.completedTodoCount}개</Text>
        <Text variant="bodySmall" color={palette.inkMute}>· {bucketDescription[bucket]}</Text>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: space[3]
  },
  badge: {
    paddingHorizontal: space[2],
    paddingVertical: space[1],
    borderRadius: 999
  },
  metaRow: {
    flexDirection: "row",
    alignItems: "baseline",
    gap: space[1],
    marginTop: space[2]
  }
});
