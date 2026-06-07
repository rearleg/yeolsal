const runtime = globalThis as typeof globalThis & {
  process?: { env?: Record<string, string | undefined> };
};

export const API_BASE_URL =
  runtime.process?.env?.EXPO_PUBLIC_API_BASE_URL ?? "https://api.rearleg.com/yeolsal/api/v1";

export const KAKAO_NATIVE_APP_KEY =
  runtime.process?.env?.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY ?? "";
