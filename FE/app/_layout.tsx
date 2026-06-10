import { router, Stack, usePathname } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { useEffect } from "react";
import { View } from "react-native";
import { AuthProvider, useAuth } from "../src/auth/AuthContext";
import { getOnboardingState } from "../src/lib/onboardingState";
import { KakaoSdkBootstrap } from "../src/lib/KakaoSdkBootstrap";
import { useShareLinkDeepLink } from "../src/lib/deepLinking";
import { useWantedSans } from "../src/lib/fonts";
import { useIsSpectatorEverywhere } from "../src/lib/query/hooks/survival";
import { registerForPushAsync } from "../src/lib/push";
import {
  useNotificationInvalidation,
  useNotificationResponseDeepLink,
} from "../src/lib/notifications";
import { setupReactQueryFocus } from "../src/lib/query/focus";
import {
  bootstrapAnalytics,
  postHogOptIn,
  postHogOptOut,
  setAnalyticsUser,
} from "../src/lib/analytics";
import { getAnalyticsConsent } from "../src/lib/analyticsConsent";
import { bootstrapSentry, setSentryUser } from "../src/lib/sentry";
import { useRoomsQuery } from "../src/lib/query/hooks/rooms";
import { useMeSurvivalQuery } from "../src/lib/query/hooks/survival";
import { QueryProvider } from "../src/providers/QueryProvider";
import { RealtimeProvider } from "../src/providers/RealtimeProvider";
import { SubModeProvider } from "../src/providers/SubModeProvider";
import { ToastProvider } from "../src/components/feedback/ToastProvider";
import { ErrorBoundary } from "../src/components/feedback/ErrorBoundary";
import { RitualMoment } from "../src/components/ritual";
import { surface } from "../src/theme/tokens";

bootstrapSentry();
bootstrapAnalytics();

/**
 * Story 6.2 AC7 — boot-time Kakao SDK init using the public Native App Key
 * (REST API key remains BE-only per project-context.md:235). Dev / OSS
 * forks without a key skip initialization. EAS injects the production key.
 */
export default function RootLayout() {
  // Wire RN AppState into React Query's focus manager exactly once for the
  // app's lifetime. Without this any "background → active" transition (the
  // user re-opens the app) is invisible to React Query and stale tabs
  // refuse to refetch — the bug surfaced as "친구 요청을 보려면 앱 재시작".
  useEffect(() => {
    return setupReactQueryFocus();
  }, []);
  const fonts = useWantedSans();
  if (!fonts.loaded && !fonts.error) {
    // Paint the page color while fonts load so the first frame isn't the
    // RN window default (dark on Android), which manifested as a black
    // flash before any UI mounted.
    return <View style={{ flex: 1, backgroundColor: surface.page }} />;
  }
  return (
    <SubModeProvider subMode={null}>
      <AuthProvider>
        <QueryProvider>
          <KakaoSdkBootstrap />
          <RealtimeProvider>
            <ToastProvider>
              <ErrorBoundary>
                <PushTokenBootstrap />
                <NotificationInvalidationBootstrap />
                <SentryUserBinding />
                <AnalyticsUserBinding />
                <OnboardingGate />
                <Stack
                  screenOptions={{
                    headerShown: false,
                    // Page-coloured stack background prevents the dark RN
                    // window from bleeding through during route transitions.
                    contentStyle: { backgroundColor: surface.page },
                  }}
                >
                  {/*
                    Story 8.1 — onboarding is non-dismissable: no header,
                    no back gesture. Completion routes away via
                    router.replace inside the screen itself.
                  */}
                  <Stack.Screen
                    name="onboarding"
                    options={{ title: "소개", headerShown: false, gestureEnabled: false }}
                  />
                </Stack>
                {/*
                  Story 1.7 — RitualMoment 06:00–06:05 KST sacred wrapper.
                  Renders as a sibling overlay on top of the Stack so it
                  doesn't block route transitions. The overlay returns null
                  outside the window or after it has fired today (per-KST-date
                  idempotency via AsyncStorage).
                  Wrapped in SubModeProvider subMode="postcard" so the D4
                  cinematic motion + serif typography tokens light up — leaf
                  components do not read the sub-mode string directly.
                  Story 2.1 AC5 — `spectator` prop now reads the real
                  cross-room signal from `useIsSpectatorEverywhere()`.
                  The hook call is factored into RitualMomentBootstrap so it
                  sits inside QueryProvider (Hooks-rules — must be inside the
                  TanStack Query provider seat).
                */}
                <SubModeProvider subMode="postcard">
                  <RitualMomentBootstrap />
                </SubModeProvider>
                <StatusBar style="dark" />
              </ErrorBoundary>
            </ToastProvider>
          </RealtimeProvider>
        </QueryProvider>
      </AuthProvider>
    </SubModeProvider>
  );
}

/**
 * Sits inside <QueryProvider> so the hook's useQueryClient() resolves.
 * Subscribes to incoming Expo push notifications and invalidates the
 * relevant query caches so the user sees fresh feed / friend / chat
 * data the moment a push lands instead of waiting for the next visit.
 */
function NotificationInvalidationBootstrap() {
  useNotificationInvalidation();
  // Story 3.2 FE-11 — push-tap deep-link handler. Branches on data.kind to
  // push the room route and write the SecureStore pending slot the room
  // screen reads on mount.
  useNotificationResponseDeepLink();
  // Story 6.2 AC2 — KakaoTalk share-link deep-link handler. Subscribes to
  // both Linking.getInitialURL (cold launch) and Linking.addEventListener
  // (warm foreground); routes /join?code=X to the in-app join flow for
  // authenticated users, or persists the code into SecureStore +
  // redirects to /signup for the post-install bridging path.
  useShareLinkDeepLink();
  return null;
}

function PushTokenBootstrap() {
  const auth = useAuth();
  useEffect(() => {
    if (auth.loading || !auth.user) return;
    registerForPushAsync().catch(() => {
      // expo-notifications can fail in simulator / dev clients without native
      // changes; swallow to avoid breaking app boot.
    });
  }, [auth.loading, auth.user]);
  return null;
}

/**
 * Story 8.1 — single centralized onboarding gate (AC0: index.tsx / login /
 * signup must NOT redirect to /onboarding themselves). Any authed user
 * without a completed onboarding record is re-routed to /onboarding, no
 * matter which route they land on — the pathname dependency makes the gate
 * win the race against login/signup's unconditional replace("/today").
 * Fail-open by construction: a SecureStore read failure reads as "no
 * record", so the user re-onboards once rather than getting stuck.
 */
function OnboardingGate() {
  const auth = useAuth();
  const pathname = usePathname();
  useEffect(() => {
    if (auth.loading || !auth.user) return;
    if (pathname === "/onboarding") return;
    let cancelled = false;
    getOnboardingState().then((state) => {
      if (cancelled) return;
      if (state?.completedAt != null) return;
      router.replace("/onboarding");
    });
    return () => {
      cancelled = true;
    };
  }, [auth.loading, auth.user, pathname]);
  return null;
}

function SentryUserBinding() {
  const auth = useAuth();
  useEffect(() => {
    if (auth.loading) return;
    setSentryUser(auth.user ? { id: auth.user.id, email: auth.user.email } : null);
  }, [auth.loading, auth.user]);
  return null;
}

/**
 * Story 8.5 AC3 — mirror of SentryUserBinding for PostHog. Sits inside
 * QueryProvider so the TanStack hooks below resolve. Re-derives the
 * non-PII person properties on auth change and pipes the stored consent
 * decision into the SDK's runtime opt-in/out state.
 *
 * Unlike SentryUserBinding, this sends no email. It also omits account age
 * until the API exposes a truthful signup timestamp; fabricated zero values
 * would corrupt retention cohorts.
 */
function AnalyticsUserBinding() {
  const auth = useAuth();
  useEffect(() => {
    if (!auth.loading && !auth.user) {
      setAnalyticsUser(null);
    }
  }, [auth.loading, auth.user]);
  if (auth.loading || !auth.user) return null;
  return <AuthenticatedAnalyticsUserBinding userId={auth.user.id} />;
}

interface AuthenticatedAnalyticsUserBindingProps {
  userId: number;
}

function AuthenticatedAnalyticsUserBinding({
  userId,
}: AuthenticatedAnalyticsUserBindingProps) {
  const survivalQuery = useMeSurvivalQuery();
  const roomsQuery = useRoomsQuery();
  useEffect(() => {
    if (!survivalQuery.isSuccess || !roomsQuery.isSuccess) return;
    let cancelled = false;
    const survivalEntries = survivalQuery.data ?? [];
    const rooms = roomsQuery.data ?? [];
    const currentSurvivalState = survivalEntries[0]?.status;
    if (!currentSurvivalState) return;
    const isRoomLeader = rooms.some((room) => room.ownerId === userId);
    getAnalyticsConsent()
      .then((decision) => {
        if (cancelled || decision == null) return;
        if (decision === "opt_out") {
          postHogOptOut();
          return;
        }
        postHogOptIn();
        if (cancelled) return;
        setAnalyticsUser({
          id: userId,
          currentSurvivalState,
          isRoomLeader,
        });
      })
      .catch(() => {
        // SecureStore unavailable — keep fail-closed default.
      });
    return () => {
      cancelled = true;
    };
  }, [
    userId,
    survivalQuery.data,
    survivalQuery.isSuccess,
    roomsQuery.data,
    roomsQuery.isSuccess,
  ]);
  return null;
}

/**
 * Story 2.1 AC5 — single seat that owns the `useIsSpectatorEverywhere()`
 * hook call so it sits inside <QueryProvider> (where TanStack Query is
 * mounted) but stays outside <RootLayout>'s top render path. The wrapper
 * forwards the resolved boolean to <RitualMoment>; the underlying
 * component's prop contract is unchanged from Story 1.7.
 */
function RitualMomentBootstrap() {
  const spectator = useIsSpectatorEverywhere();
  return <RitualMoment spectator={spectator} />;
}
