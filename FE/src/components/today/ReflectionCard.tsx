import { MaterialIcons } from "@expo/vector-icons";
import { useState } from "react";
import { Pressable, StyleSheet, TextInput, View } from "react-native";
import type { DailyEntryDto } from "../../api/types";
import {
  useSubmitReflection,
  useUpdateReflection,
} from "../../lib/query/hooks/today";
import { toast } from "../../lib/toast";
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
  const [editing, setEditing] = useState(false);
  const [editDraft, setEditDraft] = useState("");
  const submit = useSubmitReflection();
  const update = useUpdateReflection();
  const reflection = entry?.reflection ?? null;
  const done = !!reflection;
  // ISO-8601 lexicographic comparison matches chronological order, so
  // updatedAt > submittedAt cleanly distinguishes "first submit" from
  // "user edited after submit".
  const edited =
    !!reflection && reflection.updatedAt > reflection.submittedAt;

  function send() {
    if (!entry || !draft.trim()) {
      toast.warning("회고 내용을 입력하세요.");
      return;
    }
    submit.mutate(
      { dailyEntryId: entry.id, body: draft.trim() },
      { onSuccess: () => setDraft("") },
    );
  }

  function beginEdit() {
    if (!reflection) return;
    setEditDraft(reflection.body);
    setEditing(true);
  }

  function cancelEdit() {
    setEditing(false);
    setEditDraft("");
  }

  function saveEdit() {
    if (!reflection || !editDraft.trim()) {
      toast.warning("회고 내용을 입력하세요.");
      return;
    }
    update.mutate(
      { reflectionId: reflection.id, body: editDraft.trim() },
      {
        onSuccess: () => {
          setEditing(false);
          setEditDraft("");
        },
      },
    );
  }

  const headerStatus = done
    ? edited
      ? "완료 · 수정됨"
      : "완료"
    : `${draft.length}/${MAX}`;

  return (
    <Card tone="raised" size="md">
      <View style={styles.header}>
        <View style={styles.titleRow}>
          <MaterialIcons name="edit-note" size={20} color={roomHues.salmon.deep} />
          <Text variant="title">회고</Text>
        </View>
        <Text variant="caption" color={palette.inkMute}>
          {headerStatus}
        </Text>
      </View>
      {done && !editing ? (
        <View style={styles.read}>
          <Text variant="body" color={palette.ink}>
            {reflection?.body}
          </Text>
          <View style={styles.readActions}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="회고 수정"
              onPress={beginEdit}
              hitSlop={8}
              style={({ pressed }) => [
                styles.editButton,
                pressed && styles.editButtonPressed,
              ]}
            >
              <MaterialIcons name="edit" size={16} color={palette.inkMute} />
              <Text variant="caption" color={palette.inkMute}>
                수정
              </Text>
            </Pressable>
          </View>
        </View>
      ) : done && editing ? (
        <View style={styles.wrap}>
          <TextInput
            value={editDraft}
            onChangeText={(text) => setEditDraft(text.slice(0, MAX))}
            placeholder="오늘을 짧게 기록해보세요."
            placeholderTextColor={palette.inkFaint}
            multiline
            style={styles.input}
          />
          <View style={styles.actions}>
            <Button
              label="취소"
              tone="ghost"
              size="sm"
              onPress={cancelEdit}
              disabled={update.isPending}
            />
            <Button
              label="수정 저장"
              tone="primary"
              size="sm"
              onPress={saveEdit}
              disabled={update.isPending || !editDraft.trim()}
            />
          </View>
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
  readActions: {
    flexDirection: "row",
    justifyContent: "flex-end",
    marginTop: space[2],
  },
  editButton: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[1],
    paddingVertical: space[1],
    paddingHorizontal: space[2],
    borderRadius: 8,
  },
  editButtonPressed: { backgroundColor: surface.contrast },
  wrap: { backgroundColor: surface.sunken, borderRadius: 12, overflow: "hidden" },
  input: { minHeight: 96, padding: space[3], color: palette.ink, fontSize: 14, lineHeight: 20 },
  actions: {
    flexDirection: "row",
    justifyContent: "flex-end",
    gap: space[2],
    padding: space[2],
  },
});
