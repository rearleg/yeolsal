import { MaterialIcons } from "@expo/vector-icons";
import { useEffect, useState } from "react";
import { Alert, StyleSheet, TextInput, View } from "react-native";
import type { DailyEntryDto } from "../../api/types";
import { useUpdateGoal } from "../../lib/query/hooks/today";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { Text } from "../ui/Text";
import { palette } from "../../theme/tokens";
import { space } from "../../theme/spacing";

interface Props {
  entry: DailyEntryDto | null;
}

export function GoalCard({ entry }: Props) {
  const [draft, setDraft] = useState(entry?.goal ?? "");
  const update = useUpdateGoal();

  useEffect(() => {
    setDraft(entry?.goal ?? "");
  }, [entry?.goal]);

  function save() {
    const trimmed = draft.trim();
    if (!trimmed) {
      Alert.alert("오늘의 목표", "목표를 입력하세요.");
      return;
    }
    update.mutate(trimmed);
  }

  return (
    <Card tone="raised" size="md">
      <View style={styles.header}>
        <View style={styles.titleRow}>
          <MaterialIcons name="flag" size={18} color={palette.coralDeep} />
          <Text variant="title">오늘의 목표</Text>
        </View>
        <Button
          label="저장"
          tone="primary"
          size="sm"
          onPress={save}
          disabled={update.isPending}
        />
      </View>
      <TextInput
        value={draft}
        onChangeText={setDraft}
        placeholder="오늘 어떤 하루를 보낼까요?"
        placeholderTextColor={palette.inkFaint}
        multiline
        style={styles.input}
      />
    </Card>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: space[3],
  },
  titleRow: { flexDirection: "row", alignItems: "center", gap: space[2] },
  input: {
    minHeight: 56,
    color: palette.ink,
    fontSize: 18,
    fontWeight: "600",
    lineHeight: 26,
    padding: 0,
  },
});
