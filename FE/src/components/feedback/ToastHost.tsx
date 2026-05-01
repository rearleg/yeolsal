import { StyleSheet, View } from "react-native";
import { Toast } from "./Toast";
import type { ToastEntry } from "./ToastProvider";
import { space } from "../../theme/spacing";

interface Props {
  toasts: ToastEntry[];
}

export function ToastHost({ toasts }: Props) {
  if (toasts.length === 0) return null;
  return (
    <View pointerEvents="box-none" style={styles.host}>
      {toasts.map((t) => (
        <Toast key={t.id} variant={t.variant} message={t.message} />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  host: {
    position: "absolute",
    left: space[4],
    right: space[4],
    bottom: space[20],
    gap: space[1],
  },
});
