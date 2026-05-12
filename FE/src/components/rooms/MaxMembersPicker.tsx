import { useMemo, useState } from "react";
import { Pressable, StyleSheet, TextInput, View } from "react-native";
import {
  MAX_MEMBERS_DEFAULT,
  MAX_MEMBERS_MAX,
  MAX_MEMBERS_MIN,
} from "../../api/rooms";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Text } from "../ui/Text";

interface MaxMembersPickerProps {
  value: number;
  onChange: (next: number) => void;
}

const RANGE_ERROR_COPY = `정원은 ${MAX_MEMBERS_MIN}~${MAX_MEMBERS_MAX} 사이여야 해요`;

function clamp(n: number): number {
  if (Number.isNaN(n)) return MAX_MEMBERS_DEFAULT;
  if (n < MAX_MEMBERS_MIN) return MAX_MEMBERS_MIN;
  if (n > MAX_MEMBERS_MAX) return MAX_MEMBERS_MAX;
  return Math.trunc(n);
}

/**
 * Capacity picker for `POST /api/v1/rooms`. Validates the same range
 * the BE enforces (FR-8.1.1: 2..30) so a server 400 only fires for direct
 * curls bypassing the picker.
 *
 * The numeric input mirrors the stepper. Both feed `clamp` before
 * propagating, so the parent only ever sees in-range integers. Out-of-range
 * typing surfaces an inline brand-voice error string.
 */
export function MaxMembersPicker({ value, onChange }: MaxMembersPickerProps) {
  // Track the raw text so the user can edit "1" -> "12" without the field
  // snapping to 2 (the min clamp) on every keystroke. Commit on blur.
  const [raw, setRaw] = useState<string>(String(value));
  const [touched, setTouched] = useState(false);

  const parsed = useMemo(() => Number.parseInt(raw, 10), [raw]);
  const outOfRange =
    touched &&
    Number.isFinite(parsed) &&
    (parsed < MAX_MEMBERS_MIN || parsed > MAX_MEMBERS_MAX);

  function commit(next: number) {
    const clamped = clamp(next);
    onChange(clamped);
    setRaw(String(clamped));
  }

  function onText(text: string) {
    // Numeric keyboards usually cover this, but hardware keyboards and
    // paste can leak punctuation — strip aggressively.
    const sanitized = text.replace(/[^0-9]/g, "");
    setRaw(sanitized);
    setTouched(true);
    const next = Number.parseInt(sanitized, 10);
    if (Number.isFinite(next) && next >= MAX_MEMBERS_MIN && next <= MAX_MEMBERS_MAX) {
      onChange(next);
    }
  }

  return (
    <View accessibilityLabel="최대 인원 (2~30명)">
      <View style={styles.row}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="최대 인원 감소"
          onPress={() => commit(value - 1)}
          disabled={value <= MAX_MEMBERS_MIN}
          style={({ pressed }) => [
            styles.stepper,
            value <= MAX_MEMBERS_MIN && styles.stepperDisabled,
            pressed && value > MAX_MEMBERS_MIN && { opacity: 0.7 },
          ]}
        >
          <Text variant="title" color={palette.ink}>−</Text>
        </Pressable>

        <View style={styles.fieldWrap}>
          <TextInput
            value={raw}
            onChangeText={onText}
            onBlur={() => commit(Number.parseInt(raw, 10))}
            keyboardType="number-pad"
            inputMode="numeric"
            maxLength={2}
            style={[styles.field, outOfRange && styles.fieldError]}
            accessibilityLabel="최대 인원 직접 입력"
            accessibilityHint={`${MAX_MEMBERS_MIN}에서 ${MAX_MEMBERS_MAX} 사이의 정수`}
          />
          <Text variant="caption" color={palette.inkMute} style={styles.suffix}>
            명
          </Text>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="최대 인원 증가"
          onPress={() => commit(value + 1)}
          disabled={value >= MAX_MEMBERS_MAX}
          style={({ pressed }) => [
            styles.stepper,
            value >= MAX_MEMBERS_MAX && styles.stepperDisabled,
            pressed && value < MAX_MEMBERS_MAX && { opacity: 0.7 },
          ]}
        >
          <Text variant="title" color={palette.ink}>+</Text>
        </Pressable>
      </View>

      {outOfRange ? (
        <Text variant="caption" color={palette.dangerFg} style={styles.error}>
          {RANGE_ERROR_COPY}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[2],
  },
  stepper: {
    width: 44,
    height: 44,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: surface.sunken,
    borderWidth: 1,
    borderColor: surface.border,
  },
  stepperDisabled: {
    opacity: 0.4,
  },
  fieldWrap: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[1],
    flex: 1,
    justifyContent: "center",
  },
  field: {
    minWidth: 56,
    paddingHorizontal: space[3],
    paddingVertical: space[2],
    borderRadius: 12,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
    color: palette.ink,
    textAlign: "center",
    fontSize: 18,
    fontWeight: "600",
  },
  fieldError: {
    borderColor: palette.dangerFg,
  },
  suffix: {
    marginLeft: -space[1],
  },
  error: {
    marginTop: space[1],
  },
});
