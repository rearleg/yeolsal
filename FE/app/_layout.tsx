import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { useEffect } from "react";
import { View } from "react-native";
import { AuthProvider, useAuth } from "../src/auth/AuthContext";
import { useWantedSans } from "../src/lib/fonts";
import { registerForPushAsync } from "../src/lib/push";
import { useNotificationInvalidation } from "../src/lib/notifications";
import { setupReactQueryFocus } from "../src/lib/query/focus";
import { bootstrapSentry, setSentryUser } from "../src/lib/sentry";
import { QueryProvider } from "../src/providers/QueryProvider";
import { RealtimeProvider } from "../src/providers/RealtimeProvider";
import { ToastProvider } from "../src/components/feedback/ToastProvider";
import { ErrorBoundary } from "../src/components/feedback/ErrorBoundary";
import { surface } from "../src/theme/tokens";

bootstrapSentry();

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
    <AuthProvider>
      <QueryProvider>
        <RealtimeProvider>
          <ToastProvider>
            <ErrorBoundary>
              <PushTokenBootstrap />
              <NotificationInvalidationBootstrap />
              <SentryUserBinding />
              <Stack
                screenOptions={{
                  headerShown: false,
                  // Page-coloured stack background prevents the dark RN
                  // window from bleeding through during route transitions.
                  contentStyle: { backgroundColor: surface.page },
                }}
              />
              <StatusBar style="dark" />
            </ErrorBoundary>
          </ToastProvider>
        </RealtimeProvider>
      </QueryProvider>
    </AuthProvider>
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

function SentryUserBinding() {
  const auth = useAuth();
  useEffect(() => {
    if (auth.loading) return;
    setSentryUser(auth.user ? { id: auth.user.id, email: auth.user.email } : null);
  }, [auth.loading, auth.user]);
  return null;
}
