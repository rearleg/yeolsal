import { Pressable, StyleSheet, View } from "react-native";
import { GrassDayDto } from "../api/types";
import { colors } from "../theme/tokens";

type Props = {
  days: GrassDayDto[];
  onSelect?: (day: GrassDayDto) => void;
};

const intensityColors = [colors.surfaceHigh, "#B9FFB1", colors.green, colors.greenNeon, colors.pink];

export function GrassGrid({ days, onSelect }: Props) {
  return (
    <View style={styles.grid}>
      {days.map((day, index) => (
        <Pressable
          key={`${day.date}-${index}`}
          accessibilityLabel={`${day.date} 완료 todo ${day.completedTodoCount}개`}
          onPress={() => onSelect?.(day)}
          style={[styles.cell, { backgroundColor: intensityColors[day.intensity] }]}
        />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  grid: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 6
  },
  cell: {
    width: 28,
    height: 28,
    borderColor: colors.black,
    borderWidth: 2,
    borderRadius: 0
  }
});
