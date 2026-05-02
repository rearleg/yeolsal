import { useState } from "react";
import { Pressable, StyleSheet, TextInput, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";

interface Props {
  onSubmit: (body: string) => void;
  /** Disable the input + button while a send is in flight. */
  pending?: boolean;
}

export function MessageInput({ onSubmit, pending }: Props) {
  const [draft, setDraft] = useState("");
  const trimmed = draft.trim();
  const canSend = trimmed.length > 0 && !pending;

  function handleSend() {
    if (!canSend) return;
    onSubmit(trimmed);
    setDraft("");
  }

  return (
    <View style={styles.container}>
      <TextInput
        value={draft}
        onChangeText={setDraft}
        placeholder="메시지 입력"
        placeholderTextColor={palette.inkFaint}
        multiline
        editable={!pending}
        maxLength={2000}
        accessibilityLabel="메시지 입력"
        style={styles.input}
      />
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="메시지 보내기"
        accessibilityState={{ disabled: !canSend }}
        disabled={!canSend}
        onPress={handleSend}
        style={({ pressed }) => [
          styles.sendButton,
          !canSend && styles.sendButtonDisabled,
          pressed && canSend && { opacity: 0.85 },
        ]}
      >
        <MaterialIcons
          name="send"
          size={20}
          color={canSend ? palette.paper : palette.inkFaint}
        />
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    alignItems: "flex-end",
    gap: space[2],
    paddingHorizontal: space[3],
    paddingVertical: space[2],
    backgroundColor: surface.page,
    borderTopWidth: 1,
    borderTopColor: surface.border,
  },
  input: {
    flex: 1,
    minHeight: 40,
    maxHeight: 120,
    paddingHorizontal: space[3],
    paddingVertical: space[2],
    borderRadius: 18,
    borderWidth: 1,
    borderColor: surface.border,
    backgroundColor: surface.sunken,
    color: palette.ink,
    fontSize: 15,
    lineHeight: 20,
  },
  sendButton: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: palette.coral,
  },
  sendButtonDisabled: {
    backgroundColor: surface.sunken,
  },
});
