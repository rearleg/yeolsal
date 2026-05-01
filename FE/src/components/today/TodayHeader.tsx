import { StyleSheet, View } from "react-native";
import { Text } from "../ui/Text";
import { palette } from "../../theme/tokens";
import { space, layout } from "../../theme/spacing";
import { textStyles } from "../../theme/typography";

const KOREAN_WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"] as const;

interface Props {
  // Override for tests / Storybook. Defaults to the device's current local date,
  // which is what the user expects to see in the header — server-side entry-date
  // boundary handling is intentionally separate.
  date?: Date;
}

export function TodayHeader({ date = new Date() }: Props) {
  const month = date.getMonth() + 1;
  const day = date.getDate();
  const weekday = KOREAN_WEEKDAYS[date.getDay()];

  return (
    <View style={styles.container} accessibilityRole="header">
      <Text variant="h2" style={styles.dateLabel} numberOfLines={1}>
        {`${month}월 ${day}일`}
      </Text>
      <View style={styles.weekdayChip} accessibilityLabel={`${weekday}요일`}>
        <Text variant="bodyStrong" style={styles.weekdayLabel}>
          {weekday}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[2],
    minHeight: layout.headerHeight,
    paddingHorizontal: layout.pageHorizontal,
    paddingTop: space[1],
    paddingBottom: space[2],
  },
  dateLabel: {
    color: palette.ink,
  },
  weekdayChip: {
    paddingHorizontal: space[2],
    paddingVertical: 2,
    borderRadius: 999,
    backgroundColor: palette.coral,
  },
  weekdayLabel: {
    ...textStyles.numericTabular,
    color: palette.paper,
  },
});
