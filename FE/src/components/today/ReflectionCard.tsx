import { MaterialIcons } from "@expo/vector-icons";
import { useState } from "react";
import { Alert, StyleSheet, TextInput, View } from "react-native";
import type { DailyEntryDto } from "../../api/types";
import { useSubmitReflection } from "../../lib/query/hooks/today";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { Text } from "../ui/Text";
import { palette, roomHues, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";

interface Props {
  entry: DailyEntryDto | null;
}

const MAX = 500;

export function ReflectionCard({ entry }: Props) {
  const [draft, setDraft] = useState("");
  const submit = useSubmitReflection();
  const done = !!entry?.reflection;

  function send() {
    if (!entry || !draft.trim()) {
      Alert.alert("회고", "회고 내용을 입력하세요.");
      return;
    }
    submit.mutate(
      { dailyEntryId: entry.id, body: draft.trim() },
      { onSuccess: () => setDraft("") },
    );
  }

  return (
    <Card tone="raised" size="md">
      <View style={styles.header}>
        <View style={styles.titleRow}>
          <MaterialIcons name="edit-note" size={20} color={roomHues.salmon.deep} />
          <Text variant="title">회고</Text>
        </View>
        <Text variant="caption" color={palette.inkMute}>
          {done ? "완료" : `${draft.length}/${MAX}`}
        </Text>
      </View>
      {done ? (
        <View style={styles.read}>
          <Text variant="body" color={palette.ink}>
            {entry?.reflection?.body}
          </Text>
        </View>
      ) : (
        <View style={styles.wrap}>
          <TextInput
            value={draft}
            onChangeText={(text) => setDraft(text.slice(0, MAX))}
            placeholder="오늘을 짧게 기록해보세요."
            placeholderTextColor={palette.inkFaint}
            multiline
            style={styles.input}
          />
          <View style={styles.actions}>
            <Button
              label="회고 제출"
              tone="primary"
              size="sm"
              onPress={send}
              disabled={submit.isPending || !entry}
            />
          </View>
        </View>
      )}
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
  read: { backgroundColor: surface.sunken, borderRadius: 12, padding: space[3] },
  wrap: { backgroundColor: surface.sunken, borderRadius: 12, overflow: "hidden" },
  input: { minHeight: 96, padding: space[3], color: palette.ink, fontSize: 14, lineHeight: 20 },
  actions: { flexDirection: "row", justifyContent: "flex-end", padding: space[2] },
});
