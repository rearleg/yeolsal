import { MaterialIcons } from "@expo/vector-icons";
import { useState } from "react";
import { Pressable, StyleSheet, TextInput, View } from "react-native";
import { useQueryClient } from "@tanstack/react-query";
import type { DailyEntryDto } from "../../api/types";
import { patchTodo } from "../../lib/query/api";
import { useAddTodo, useDeleteTodo, useToggleTodo } from "../../lib/query/hooks/today";
import { qk } from "../../lib/query/keys";
import { toast } from "../../lib/toast";
import { Card } from "../ui/Card";
import { Text } from "../ui/Text";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";

interface Props {
  entry: DailyEntryDto | null;
}

export function TodoList({ entry }: Props) {
  const [draft, setDraft] = useState("");
  const qc = useQueryClient();
  const addMut = useAddTodo();
  const toggleMut = useToggleTodo();
  const deleteMut = useDeleteTodo();
  const todos = entry?.todos ?? [];
  const completed = todos.filter((t) => t.completed).length;
  const submitting = addMut.isPending || toggleMut.isPending || deleteMut.isPending;

  function add() {
    const title = draft.trim();
    if (!title) return;
    if (!entry) {
      toast.warning("오늘의 목표를 먼저 저장하세요.");
      return;
    }
    addMut.mutate(title);
    setDraft("");
  }

  async function editTitle(id: number, title: string) {
    try {
      await patchTodo(id, { title });
    } catch {
      // best-effort title save; explicit user actions surface toast errors via hooks
    } finally {
      qc.invalidateQueries({ queryKey: qk.today });
    }
  }

  return (
    <Card tone="raised" size="md">
      <View style={styles.header}>
        <View style={styles.titleRow}>
          <MaterialIcons name="checklist" size={18} color={palette.ink} />
          <Text variant="title">할 일</Text>
        </View>
        <Text variant="caption" color={palette.inkMute}>
          {completed}/{todos.length}
        </Text>
      </View>
      <View style={styles.addRow}>
        <TextInput
          value={draft}
          onChangeText={setDraft}
          placeholder="할 일 추가"
          placeholderTextColor={palette.inkFaint}
          returnKeyType="done"
          onSubmitEditing={add}
          style={styles.addInput}
        />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="할 일 추가"
          disabled={submitting}
          onPress={add}
          style={styles.addButton}
        >
          <MaterialIcons name="add" size={18} color={palette.surface} />
        </Pressable>
      </View>
      <View style={styles.list}>
        {todos.map((todo) => (
          <View key={todo.id} style={styles.item}>
            <Pressable
              accessibilityRole="checkbox"
              accessibilityState={{ checked: todo.completed }}
              onPress={() => toggleMut.mutate({ id: todo.id, completed: !todo.completed })}
              style={[styles.checkbox, todo.completed && styles.checkboxDone]}
            >
              {todo.completed ? (
                <MaterialIcons name="check" size={14} color={palette.surface} />
              ) : null}
            </Pressable>
            <TextInput
              defaultValue={todo.title}
              onEndEditing={(event) => {
                const next = event.nativeEvent.text.trim();
                if (next && next !== todo.title) {
                  void editTitle(todo.id, next);
                } else if (!next) {
                  qc.invalidateQueries({ queryKey: qk.today });
                }
              }}
              style={[styles.itemInput, todo.completed && styles.itemDone]}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="할 일 삭제"
              onPress={() => deleteMut.mutate(todo.id)}
              style={styles.deleteButton}
            >
              <MaterialIcons name="close" size={16} color={palette.inkMute} />
            </Pressable>
          </View>
        ))}
      </View>
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
  addRow: { flexDirection: "row", alignItems: "center", gap: space[2] },
  addInput: {
    flex: 1,
    height: 40,
    paddingHorizontal: space[3],
    borderRadius: 12,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
    color: palette.ink,
    fontSize: 14,
  },
  addButton: {
    width: 40,
    height: 40,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: palette.coralDeep,
  },
  list: { marginTop: space[2], borderTopWidth: 1, borderTopColor: surface.border },
  item: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[2],
    height: 44,
    borderBottomWidth: 1,
    borderBottomColor: surface.border,
  },
  checkbox: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 1.5,
    borderColor: palette.borderStrong,
    alignItems: "center",
    justifyContent: "center",
  },
  checkboxDone: { borderColor: palette.coralDeep, backgroundColor: palette.coralDeep },
  itemInput: { flex: 1, color: palette.ink, fontSize: 14, fontWeight: "500", padding: 0 },
  itemDone: { color: palette.inkMute, textDecorationLine: "line-through" },
  deleteButton: { width: 28, height: 40, alignItems: "center", justifyContent: "center" },
});
