// Story 8.5 AC2 + AC5 — PostHog analytics bootstrap + thin capture helpers.
//
// Behaviour matrix (gate is purely PostHog API key presence):
//   no key   → all helpers silently no-op. Useful for local dev / OSS forks.
//   key set  → SDK initialized once with capture-disabled-by-default
//              (fail-closed), captureEvent / identify / reset routed through
//              the singleton client.
//
// Shape mirrors `FE/src/lib/sentry.ts` deliberately — same module-level
// guard, same one-shot init flag, same swallow-all-failures contract.
// Analytics must NEVER crash the host app.
//
// PIPA gold (Story 8.5 Trap #8): no PII goes into PostHog person
// properties. Sentry's setSentryUser intentionally sets email; this file
// intentionally does NOT. The two systems diverge by design.

import { PostHog } from "posthog-react-native";

import {
  ANALYTICS_INTERNAL_BUILD,
  POSTHOG_API_KEY,
  POSTHOG_HOST,
} from "../api/config";

export const ANALYTICS_EVENTS = [
  "signup.completed",
  "onboarding.screen.dwell_ms",
  "onboarding.completed",
  "first_daily_entry",
  "activation.24h_complete",
  "revival.attempted",
  "revival.succeeded",
  "revival.failed",
  "kudos.sent",
  "kudos.received",
  "friend_gift.push_sent",
  "friend_gift.push_opened",
  "friend_gift.modal_opened",
  "friend_gift.modal_closed",
  "spectator.entered",
  "spectator.app_opened",
  "spectator.wallet_viewed",
  "spectator.revival_succeeded.day_n",
  "final_three.poster_viewed",
  "final_three.share_tapped",
  "final_three.share_completed",
] as const;

export type AnalyticsEventName = (typeof ANALYTICS_EVENTS)[number];

export type CurrentSurvivalState = "ACTIVE" | "YELLOW" | "RED" | "SPECTATOR";

export interface AnalyticsUserProperties {
  id: number;
  currentSurvivalState: CurrentSurvivalState;
  isRoomLeader: boolean;
}

let initialized = false;
let client: PostHog | null = null;

/**
 * One-shot bootstrap. Called at module top-level from `app/_layout.tsx`
 * (mirrors `bootstrapSentry()` at line 25). Sync by design — module top
 * cannot await — so the SDK starts opt-out fail-closed and the user's
 * stored consent decision flips it later via `AnalyticsUserBinding`.
 */
export function bootstrapAnalytics(): void {
  if (initialized) return;
  if (!POSTHOG_API_KEY) {
    if (__DEV__) {
      console.log("[analytics] EXPO_PUBLIC_POSTHOG_API_KEY not set — analytics disabled");
    }
    return;
  }
  if (!isValidPostHogHost(POSTHOG_HOST)) {
    if (__DEV__) {
      console.warn(
        "[analytics] EXPO_PUBLIC_POSTHOG_HOST must be an http(s) self-host URL; analytics disabled",
      );
    }
    return;
  }
  try {
    client = new PostHog(POSTHOG_API_KEY, {
      host: POSTHOG_HOST,
      captureAppLifecycleEvents: false,
      // Story 8.5 Trap #6 — session replay locked OFF for v1. PostHog
      // RN v4 names this `enableSessionReplay` (the posthog-js naming
      // `disable_session_recording` does not exist here); default is
      // false but we set it explicitly so a v1 reader sees the intent.
      enableSessionReplay: false,
      // Story 8.5 AC4 — fail-closed default. PostHog RN's option name
      // for "opt_out_capturing_by_default" (posthog-js naming) is
      // `defaultOptIn: false`. Onboarding S5 (Story 8.1) and Settings
      // toggle (AC9) flip the runtime state via posthog.optIn/optOut.
      defaultOptIn: false,
    });
    initialized = true;
  } catch (error) {
    if (__DEV__) {
      console.warn("[analytics] PostHog init failed; analytics disabled", error);
    }
  }
}

export function isAnalyticsEnabled(): boolean {
  return initialized && client != null;
}

export function captureEvent(
  name: AnalyticsEventName,
  properties?: Record<string, unknown>,
): void {
  if (!initialized || client == null) return;
  try {
    client.capture(name, properties as Parameters<typeof client.capture>[1]);
  } catch (error) {
    if (__DEV__) {
      console.warn("[analytics] capture failed", { name, error });
    }
  }
}

export function identifyUser(props: AnalyticsUserProperties | null): void {
  if (!initialized || client == null) return;
  if (props == null) {
    safeReset();
    return;
  }
  try {
    client.identify(
      String(props.id),
      buildPersonProperties(props) as Parameters<typeof client.identify>[1],
    );
  } catch (error) {
    if (__DEV__) {
      console.warn("[analytics] identify failed", error);
    }
  }
}

/** Wrapper over identifyUser+reset so callers have a single seat to call. */
export function setAnalyticsUser(props: AnalyticsUserProperties | null): void {
  identifyUser(props);
}

export function addAnalyticsBreadcrumb(input: {
  category: string;
  level: "info" | "warning" | "error";
  message: string;
}): void {
  void input;
  // Kept for API symmetry with sentry.ts. PostHog has no breadcrumb
  // primitive, and emitting an ad-hoc event would bypass the locked taxonomy.
}

/**
 * Story 8.5 AC4 helpers — `analyticsConsent.ts` calls these when the
 * user flips the toggle. No-op when the SDK is disabled so a fresh
 * dev/OSS fork doesn't crash on consent writes.
 */
export function postHogOptIn(): void {
  if (!initialized || client == null) return;
  try {
    void client.optIn().catch((error: unknown) => {
      if (__DEV__) {
        console.warn("[analytics] optIn failed", error);
      }
    });
  } catch (error) {
    if (__DEV__) {
      console.warn("[analytics] optIn failed", error);
    }
  }
}

export function postHogOptOut(): void {
  if (!initialized || client == null) return;
  try {
    void client.optOut().catch((error: unknown) => {
      if (__DEV__) {
        console.warn("[analytics] optOut failed", error);
      }
    });
  } catch (error) {
    if (__DEV__) {
      console.warn("[analytics] optOut failed", error);
    }
  }
}

function safeReset(): void {
  if (!initialized || client == null) return;
  try {
    client.reset();
  } catch (error) {
    if (__DEV__) {
      console.warn("[analytics] reset failed", error);
    }
  }
}

function buildPersonProperties(props: AnalyticsUserProperties): Record<string, unknown> {
  return {
    // v1: mandatory single room → hardcoded. v1.5 revisit when multi-room
    // ships (see docs/analytics.md §4).
    room_count: 1,
    current_survival_state: props.currentSurvivalState,
    is_room_leader: props.isRoomLeader,
    is_internal: ANALYTICS_INTERNAL_BUILD,
  };
}

function isValidPostHogHost(value: string): boolean {
  if (!value) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:";
  } catch {
    return false;
  }
}
