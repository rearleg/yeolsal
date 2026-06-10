// Story 8.1 — /onboarding route. 5-screen carousel for new users, single
// change-summary screen for returning (pre-cutover) users. Non-dismissable:
// the Stack.Screen registration disables the back gesture and the
// <OnboardingGate> in _layout.tsx re-routes any authed user without a
// completion record back here. The consent decision is persisted through
// setAnalyticsConsent ONLY (Story 8.5 AC4 lock — never posthog.optIn/optOut
// directly), and onboarding.completed fires strictly after that decision
// lands so the SDK runtime state matches user intent.

import { useEffect, useRef, useState } from "react";
import { ActivityIndicator, StyleSheet, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { router } from "expo-router";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import {
  OnboardingCarousel,
  type OnboardingCompletionResult,
} from "../src/components/onboarding/OnboardingCarousel";
import { Button } from "../src/components/ui/Button";
import { Text } from "../src/components/ui/Text";
import { captureEvent } from "../src/lib/analytics";
import { setAnalyticsConsent } from "../src/lib/analyticsConsent";
import {
  getOnboardingState,
  markOnboardingCompleted,
} from "../src/lib/onboardingState";
import { useRoomsQuery } from "../src/lib/query/hooks/rooms";
import { palette, surface } from "../src/theme/tokens";
import { space } from "../src/theme/spacing";

const CHANGE_SUMMARY_HEADLINE = "yeolsal이 열살방으로 바뀌었어요";
const CHANGE_SUMMARY_BODY =
  "이름이 바뀌었어요. 그동안의 친구들, 그룹, 잔디는 그대로 함께해요.";
const CHANGE_SUMMARY_CTA = "확인했어요";

export default function OnboardingScreen() {
  const auth = useRequireAuth();
  const roomsQuery = useRoomsQuery();
  const [phase, setPhase] = useState<"checking" | "ready">("checking");
  const [deferredDestination, setDeferredDestinationState] = useState<string | null>(
    null,
  );
  const completingRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    getOnboardingState().then((state) => {
      if (cancelled) return;
      if (state?.completedAt != null) {
        // Defensive — direct navigation to /onboarding after completion.
        router.replace(state.deferredDestination ?? "/today");
        return;
      }
      setDeferredDestinationState(state?.deferredDestination ?? null);
      setPhase("ready");
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const lastAuthEvent = auth.getLastAuthEvent();
  const isReturningAuth = lastAuthEvent === "signIn" || lastAuthEvent === "signInKakao";
  const roomsSettled = roomsQuery.isSuccess || roomsQuery.isError;

  // The change-summary decision needs a definitive rooms answer — branching
  // on a still-loading query would flash the carousel at a returning user.
  if (
    auth.loading ||
    !auth.user ||
    phase === "checking" ||
    (isReturningAuth && !roomsSettled)
  ) {
    return (
      <View style={styles.loading} testID="onboarding-loading">
        <ActivityIndicator color={palette.ink} />
      </View>
    );
  }

  if (isReturningAuth && roomsQuery.isError) {
    return (
      <SafeAreaView style={styles.screen}>
        <View style={styles.retry}>
          <Text variant="body" color={palette.inkMute}>
            그룹 정보를 불러오지 못했어요.
          </Text>
          <Button
            label="다시 시도"
            tone="secondary"
            onPress={() => void roomsQuery.refetch()}
          />
        </View>
      </SafeAreaView>
    );
  }

  function handleCarouselComplete(result: OnboardingCompletionResult) {
    if (completingRef.current) return;
    completingRef.current = true;
    completeCarousel(result).catch(() => {
      completingRef.current = false;
    });
  }

  const hasRooms = (roomsQuery.data?.length ?? 0) > 0;
  if (isReturningAuth && hasRooms) {
    return <ChangeSummary />;
  }

  return (
    <SafeAreaView style={styles.screen}>
      <OnboardingCarousel
        deferredDestination={deferredDestination}
        onComplete={handleCarouselComplete}
      />
    </SafeAreaView>
  );
}

/**
 * AC5 — single change-summary screen for accounts that authenticated
 * against a pre-cutover app version (returning user, has rooms, no
 * onboarding record on this device).
 */
function ChangeSummary() {
  const [submitting, setSubmitting] = useState(false);

  function handleConfirm() {
    if (submitting) return;
    setSubmitting(true);
    completeChangeSummary().catch(() => {
      setSubmitting(false);
    });
  }

  return (
    <SafeAreaView style={styles.screen}>
      <View style={styles.summaryBody}>
        <Text variant="h2">{CHANGE_SUMMARY_HEADLINE}</Text>
        <Text variant="body" color={palette.inkMute}>
          {CHANGE_SUMMARY_BODY}
        </Text>
      </View>
      <View style={styles.summaryFooter}>
        <Button
          label={CHANGE_SUMMARY_CTA}
          tone="primary"
          size="lg"
          fullWidth
          onPress={handleConfirm}
          disabled={submitting}
        />
      </View>
    </SafeAreaView>
  );
}

async function completeCarousel(result: OnboardingCompletionResult): Promise<void> {
  await setAnalyticsConsent(result.consent);
  // Screen-5 dwell + completion are downstream events — they fire only
  // after the consent decision has landed (AC2 step 2).
  captureEvent("onboarding.screen.dwell_ms", {
    screen: 5,
    dwellMs: result.screen5DwellMs,
  });
  captureEvent("onboarding.completed");
  const state = await getOnboardingState();
  const destination = state?.deferredDestination ?? null;
  await markOnboardingCompleted(destination);
  router.replace(destination ?? "/today");
}

async function completeChangeSummary(): Promise<void> {
  const state = await getOnboardingState();
  const destination = state?.deferredDestination ?? null;
  // Returning user defaults to opt-out — no surprise capture-on for an
  // account that pre-dates the consent prompt (AC5).
  await setAnalyticsConsent("opt_out");
  captureEvent("onboarding.completed");
  await markOnboardingCompleted(destination);
  router.replace(destination ?? "/today");
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: surface.page,
  },
  loading: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: surface.page,
  },
  summaryBody: {
    flex: 1,
    justifyContent: "center",
    paddingHorizontal: space[5],
    gap: space[3],
  },
  summaryFooter: {
    padding: space[5],
  },
  retry: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: space[3],
    padding: space[5],
  },
});
