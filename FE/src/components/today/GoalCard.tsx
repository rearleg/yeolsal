import { MaterialIcons } from "@expo/vector-icons";
import { useEffect, useMemo, useState } from "react";
import { StyleSheet, TextInput, View } from "react-native";
import type { DailyEntryDto } from "../../api/types";
import { useUpdateGoal } from "../../lib/query/hooks/today";
import { useHaptic } from "../../hooks/useHaptics";
import { toast } from "../../lib/toast";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { Text } from "../ui/Text";
import { palette } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { textStyles } from "../../theme/typography";

type GoalState = "IDLE" | "EDITING" | "SAVING" | "SAVED";

interface Props {
  entry: DailyEntryDto | null;
}

function initialState(entry: DailyEntryDto | null): GoalState {
  return entry?.goal && entry.goal.trim().length > 0 ? "SAVED" : "IDLE";
}

export function GoalCard({ entry }: Props) {
  const [draft, setDraft] = useState(entry?.goal ?? "");
  const [state, setState] = useState<GoalState>(() => initialState(entry));
  const haptic = useHaptic();

  const update = useUpdateGoal({
    onSaved: () => {
      haptic("success");
      toast.success("오늘의 목표 저장됨");
      setState("SAVED");
    },
    onFailed: () => {
      // Toast already surfaced inside the hook; just unwind the state machine
      // so the user can keep editing the draft they had typed. Mirror the
      // tactile signal of the success path so the failure is unmistakable.
      haptic("error");
      setState("EDITING");
    },
  });

  // Keep state aligned with whatever the server says — handles refresh /
  // optimistic rollback / first paint. Don't override the in-flight SAVING
  // state because that would briefly drop the spinner mid-mutation. Don't
  // override EDITING either — the user is actively typing.
  // Intentionally re-syncs only when the *server's* goal changes; depending on
  // `state` would re-fire the effect right after we transitioned to SAVED and
  // bounce us back to IDLE on optimistic-update teardown.
  useEffect(() => {
    if (state === "SAVING" || state === "EDITING") return;
    const nextDraft = entry?.goal ?? "";
    setDraft(nextDraft);
    setState(initialState(entry));
  }, [entry?.goal]);

  const isReadOnly = state === "SAVED" || state === "SAVING";
  const buttonLabel = useMemo(() => {
    if (state === "SAVED") return "수정";
    return "저장";
  }, [state]);
  const buttonTone = state === "SAVED" ? "secondary" : "primary";

  function onPressButton() {
    if (state === "SAVED") {
      setState("EDITING");
      return;
    }
    if (state === "SAVING") return;
    const trimmed = draft.trim();
    if (!trimmed) {
      toast.warning("목표를 입력하세요.");
      return;
    }
    setState("SAVING");
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
          label={buttonLabel}
          tone={buttonTone}
          size="sm"
          onPress={onPressButton}
          loading={state === "SAVING"}
        />
      </View>
      <TextInput
        value={draft}
        onChangeText={setDraft}
        editable={!isReadOnly}
        placeholder="오늘 어떤 하루를 보낼까요?"
        placeholderTextColor={palette.inkFaint}
        multiline
        accessibilityLabel="오늘의 목표 입력"
        accessibilityState={{ disabled: isReadOnly }}
        style={[styles.input, isReadOnly && styles.inputReadOnly]}
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
    ...textStyles.h3,
    minHeight: 56,
    // Slightly less weight than full h3 bold so long-form typing feels lighter
    // than the section header above.
    fontWeight: "600",
    padding: 0,
  },
  inputReadOnly: {
    color: palette.inkSoft,
  },
});
