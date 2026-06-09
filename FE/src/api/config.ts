const runtime = globalThis as typeof globalThis & {
  process?: { env?: Record<string, string | undefined> };
};

export const API_BASE_URL =
  runtime.process?.env?.EXPO_PUBLIC_API_BASE_URL ?? "https://api.rearleg.com/yeolsal/api/v1";

export const KAKAO_NATIVE_APP_KEY =
  runtime.process?.env?.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY ?? "";

// Story 8.5 AC8 — PostHog Project API Key + self-host base URL. Defaults
// to empty strings so dev / OSS forks boot with analytics disabled (the
// SDK guard in `lib/analytics.ts` no-ops on empty key). The Project API
// Key is client-embeddable (public). The Personal API Key stays BE-only
// and never ships as EXPO_PUBLIC_*.
export const POSTHOG_HOST = runtime.process?.env?.EXPO_PUBLIC_POSTHOG_HOST ?? "";

export const POSTHOG_API_KEY = runtime.process?.env?.EXPO_PUBLIC_POSTHOG_API_KEY ?? "";

// Story 8.5 — internal/staff builds tag is_internal=true so KPIs filter them out.
export const ANALYTICS_INTERNAL_BUILD =
  runtime.process?.env?.EXPO_PUBLIC_INTERNAL_BUILD === "true";
