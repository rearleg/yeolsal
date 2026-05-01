// Sentry bootstrap + thin capture helpers.
//
// Behaviour matrix (gate is purely DSN presence):
//   no DSN     → all functions silently no-op. Useful for local dev / OSS forks.
//   DSN set    → SDK initialized once, native crash handler installed,
//                renders/queries/mutations report unhandled errors.
//
// DSN is exposed via EXPO_PUBLIC_SENTRY_DSN so it ships into the client bundle.
// That is intended — Sentry DSNs are not secrets. Keep SENTRY_AUTH_TOKEN out of
// the JS bundle (build-time only, lives in EAS secrets / .env).

import * as Sentry from "@sentry/react-native";

const DSN = process.env.EXPO_PUBLIC_SENTRY_DSN;
const ENV =
  process.env.EXPO_PUBLIC_SENTRY_ENVIRONMENT ?? (__DEV__ ? "development" : "production");
const RELEASE = process.env.EXPO_PUBLIC_APP_VERSION;

let initialized = false;

export function bootstrapSentry(): void {
  if (initialized) return;
  if (!DSN) {
    if (__DEV__) {
      // eslint-disable-next-line no-console
      console.log("[sentry] EXPO_PUBLIC_SENTRY_DSN not set — Sentry disabled");
    }
    return;
  }
  Sentry.init({
    dsn: DSN,
    environment: ENV,
    release: RELEASE,
    enableAutoSessionTracking: true,
    tracesSampleRate: ENV === "production" ? 0.1 : 1.0,
    maxBreadcrumbs: 100,
    enableNative: true,
    enableNativeCrashHandling: true,
  });
  initialized = true;
}

export function isSentryEnabled(): boolean {
  return initialized;
}

export function captureRenderError(
  error: Error,
  info: { componentStack?: string | null },
): void {
  if (!initialized) return;
  Sentry.withScope((scope) => {
    scope.setTag("source", "render");
    if (info.componentStack) scope.setExtra("componentStack", info.componentStack);
    Sentry.captureException(error);
  });
}

export function captureQueryError(
  error: unknown,
  context: { kind: "query" | "mutation"; key?: readonly unknown[] },
): void {
  if (!initialized) return;
  Sentry.withScope((scope) => {
    scope.setTag("source", context.kind);
    if (context.key) scope.setExtra("queryKey", JSON.stringify(context.key));
    Sentry.captureException(error);
  });
}

export function setSentryUser(user: { id: number; email: string } | null): void {
  if (!initialized) return;
  if (!user) {
    Sentry.setUser(null);
    return;
  }
  Sentry.setUser({ id: String(user.id), email: user.email });
}
