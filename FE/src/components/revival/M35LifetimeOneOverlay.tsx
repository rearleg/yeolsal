// Story 3.2 AC6 — M3.5 lifetime-1 overlay.
//
// Renders once, for exactly 1000ms, the first time a giver successfully
// sends a FRIEND_GIFT (epics 497-499 + UX line 1552-1554). The BE
// decides "is this the first ever send" via the EXCLUDING-self exists
// query (AC1 step 17); this component just trusts the boolean and
// branches.
//
// Reduced-motion users still see the static text for 1 second — there is
// no animation to elide.

import { useEffect, useRef } from "react";
import { Modal, StyleSheet, View } from "react-native";
import { palette } from "../../theme/tokens";
import { Text } from "../ui/Text";

const COPY = "이제 너는 누군가의 어둠을 비춘다";
const HOLD_MS = 1000;

interface M35LifetimeOneOverlayProps {
  open: boolean;
  onComplete: () => void;
}

export function M35LifetimeOneOverlay({ open, onComplete }: M35LifetimeOneOverlayProps) {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!open) return;
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      onComplete();
    }, HOLD_MS);
    return () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [open, onComplete]);

  return (
    <Modal visible={open} transparent animationType="fade">
      <View style={styles.backdrop} accessibilityViewIsModal>
        <Text
          variant="title"
          color={palette.surface}
          align="center"
          accessibilityRole="header"
          accessibilityLabel={COPY}
        >
          {COPY}
        </Text>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: "rgba(15,16,20,0.95)",
    justifyContent: "center",
    paddingHorizontal: 32,
  },
});
