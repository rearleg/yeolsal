import { Pressable, StyleSheet, View } from "react-native";
import { GrassDay } from "../domain/mockData";
import { colors } from "../theme/tokens";

type Props = {
  days: GrassDay[];
  onSelect?: (day: GrassDay) => void;
};

const intensityColors = [colors.paper, "#B9FFB1", colors.green, colors.acid, colors.pink];

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
    borderColor: colors.ink,
    borderWidth: 2
  }
});
