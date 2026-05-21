// Story 3.2 AC5 — receiver-side mythic RevivalSequence.
//
// Five-phase Animated overlay rendered once per revival event (CRITICAL
// note 6 — gated by the SecureStore `playedRevivalEventIds` array). On
// reduced-motion devices the long backdrop fade collapses to a single
// 1-second card render — reduced motion is "less motion", not "no event".
//
// Animations stay on compositor-friendly properties only (opacity +
// translateY) per the project-context FE rule.

import { useEffect, useRef, useState } from "react";
import {
  AccessibilityInfo,
  Animated,
  Modal,
  Pressable,
  StyleSheet,
  View,
} from "react-native";
import { addPlayedRevivalEventId } from "../../lib/playedRevivalEvents";
import { palette } from "../../theme/tokens";
import { Text } from "../ui/Text";

const COPY = {
  handwritten: "너를 위해 자기 것을 썼다",
  ctaReturn: "방으로 돌아가기",
} as const;

const OXBLOOD = "#7E2C2A";
const BACKDROP_OPACITY = 0.92;

interface RevivalSequenceProps {
  open: boolean;
  donorName: string;
  receiverNickname: string;
  revivalEventId: number;
  roomId: number;
  onComplete: () => void;
}

export function RevivalSequence({
  open,
  donorName,
  receiverNickname,
  revivalEventId,
  onComplete,
}: RevivalSequenceProps) {
  const backdropOpacity = useRef(new Animated.Value(0)).current;
  const donorOpacity = useRef(new Animated.Value(0)).current;
  const handwrittenOpacity = useRef(new Animated.Value(0)).current;
  const cardOpacity = useRef(new Animated.Value(0)).current;
  const cardTranslate = useRef(new Animated.Value(40)).current;
  const [reducedMotion, setReducedMotion] = useState(false);

  useEffect(() => {
    let cancelled = false;
    AccessibilityInfo.isReduceMotionEnabled()
      .then((value) => {
        if (!cancelled) setReducedMotion(value);
      })
      .catch(() => {
        // Native module unavailable (older clients) — default to full motion.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!open) return;

    if (reducedMotion) {
      // Single 1-second card fade-in; donor name visible immediately.
      Animated.timing(cardOpacity, {
        toValue: 1,
        duration: 1000,
        useNativeDriver: true,
      }).start();
      Animated.timing(cardTranslate, {
        toValue: 0,
        duration: 1000,
        useNativeDriver: true,
      }).start();
      return;
    }

    Animated.timing(backdropOpacity, {
      toValue: BACKDROP_OPACITY,
      duration: 3000,
      useNativeDriver: true,
    }).start();
    Animated.timing(donorOpacity, {
      toValue: 1,
      duration: 1500,
      delay: 1500,
      useNativeDriver: true,
    }).start();
    Animated.timing(handwrittenOpacity, {
      toValue: 1,
      duration: 1500,
      delay: 3000,
      useNativeDriver: true,
    }).start();
    Animated.timing(backdropOpacity, {
      toValue: 0,
      duration: 500,
      delay: 4500,
      useNativeDriver: true,
    }).start();
    Animated.timing(cardOpacity, {
      toValue: 1,
      duration: 500,
      delay: 4500,
      useNativeDriver: true,
    }).start();
    Animated.timing(cardTranslate, {
      toValue: 0,
      duration: 500,
      delay: 4500,
      useNativeDriver: true,
    }).start();
  }, [
    open,
    reducedMotion,
    backdropOpacity,
    donorOpacity,
    handwrittenOpacity,
    cardOpacity,
    cardTranslate,
  ]);

  const handleComplete = () => {
    void addPlayedRevivalEventId(revivalEventId);
    onComplete();
  };

  return (
    <Modal visible={open} transparent animationType="none">
      <View style={styles.container} accessibilityViewIsModal>
        {!reducedMotion ? (
          <>
            <Animated.View
              style={[styles.backdrop, { opacity: backdropOpacity }]}
              pointerEvents="none"
            />
            <Animated.Text
              style={[styles.donorBig, { opacity: donorOpacity }]}
              accessibilityRole="text"
              accessibilityLabel={donorName}
            >
              {donorName}
            </Animated.Text>
            <Animated.Text
              style={[styles.handwritten, { opacity: handwrittenOpacity }]}
              accessibilityRole="text"
              accessibilityLabel={COPY.handwritten}
            >
              {COPY.handwritten}
            </Animated.Text>
          </>
        ) : null}

        <Animated.View
          style={[
            styles.card,
            {
              opacity: cardOpacity,
              transform: [{ translateY: cardTranslate }],
            },
          ]}
        >
          <Text
            variant="title"
            color={OXBLOOD}
            align="center"
            accessibilityRole="header"
          >
            {donorName}
          </Text>
          <Text variant="body" color={palette.ink} align="center">
            {receiverNickname}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={COPY.ctaReturn}
            style={styles.cta}
            onPress={handleComplete}
          >
            <Text variant="bodyStrong" color={palette.surface} weight="700">
              {COPY.ctaReturn}
            </Text>
          </Pressable>
        </Animated.View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    paddingHorizontal: 24,
  },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: palette.ink,
  },
  donorBig: {
    position: "absolute",
    top: "30%",
    fontSize: 32,
    color: palette.surface,
    textAlign: "center",
  },
  handwritten: {
    position: "absolute",
    top: "45%",
    fontSize: 18,
    color: palette.surface,
    textAlign: "center",
    paddingHorizontal: 32,
  },
  card: {
    backgroundColor: palette.paper,
    borderRadius: 14,
    padding: 20,
    gap: 12,
    width: "100%",
    maxWidth: 360,
  },
  cta: {
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 10,
    backgroundColor: OXBLOOD,
    alignItems: "center",
    minHeight: 48,
    justifyContent: "center",
  },
});
