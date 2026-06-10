import * as Linking from "expo-linking";
import { router } from "expo-router";
import { useEffect, useRef } from "react";
import * as SecureStore from "expo-secure-store";
import { useAuth } from "../auth/AuthContext";
import {
  getOnboardingState,
  setDeferredDestination,
} from "./onboardingState";

/**
 * SecureStore slot that bridges an unauthenticated KakaoTalk-share tap
 * across the signup flow. {@link useShareLinkDeepLink} writes here when
 * the OS hands us a {@code https://yeolsal.app/join?code=X} URL but no
 * authenticated user is around to consume it; {@link consumePendingInviteCode}
 * reads-then-deletes the slot on the next successful
 * {@code AuthContext.signUp} / {@code signIn} so the invite survives
 * the install gap (Story 6.2 AC3).
 *
 * <p>SecureStore is the deliberate choice over AsyncStorage — TanStack
 * Query owns AsyncStorage exclusively (project-context.md:60), and the
 * pending invite is a single short-lived secret-ish value, well within
 * SecureStore's purpose.
 */
const PENDING_INVITE_KEY = "yeosal.pendingInviteCode";

/**
 * Story 6.2 AC2 — one-time mount inside {@code _layout.tsx} that
 * subscribes to both the cold-launch URL ({@code Linking.getInitialURL})
 * and warm-foreground URL events ({@code Linking.addEventListener}).
 * Branches on auth state: authenticated users push straight into
 * {@code /join?code=X} for the existing auto-submit flow; unauthenticated
 * users persist the code into SecureStore and redirect to the signup
 * screen so the post-install bridging (Story 6.2 AC3) can pick it up.
 *
 * <p>Byte-similar shape to the push-notification deep-link bootstrap
 * ({@code useNotificationResponseDeepLink}) — both run inside
 * QueryProvider, mount inside the same Bootstrap component, and use
 * one-shot effects keyed on auth state.
 */
export function useShareLinkDeepLink(): void {
  const auth = useAuth();
  const initialUrlHandled = useRef(false);
  useEffect(() => {
    if (auth.loading) return;
    const isAuthed = auth.user != null;

    if (!initialUrlHandled.current) {
      initialUrlHandled.current = true;
      Linking.getInitialURL()
        .then((url) => {
          if (url) void routeShareLink(url, isAuthed);
        })
        .catch(() => {
          // The warm-foreground subscription still covers later taps.
        });
    }

    const sub = Linking.addEventListener("url", ({ url }) => {
      void routeShareLink(url, isAuthed);
    });
    return () => sub.remove();
  }, [auth.loading, auth.user]);
}

export async function routeShareLink(
  url: string,
  isAuthed: boolean,
): Promise<void> {
  const parsed = Linking.parse(url);
  if (parsed.path !== "join") return;
  const rawCode = parsed.queryParams?.code;
  const code = typeof rawCode === "string" ? rawCode : null;
  if (!code) return;

  if (isAuthed) {
    const onboardingState = await getOnboardingState();
    if (onboardingState?.completedAt == null) {
      await setDeferredDestination(`/join?code=${encodeURIComponent(code)}`);
      router.replace("/onboarding");
      return;
    }
    router.push(`/join?code=${encodeURIComponent(code)}`);
    return;
  }
  try {
    await SecureStore.setItemAsync(PENDING_INVITE_KEY, code);
  } catch {
    // Signup remains usable even when SecureStore is unavailable.
  }
  router.replace("/signup");
}

/**
 * Reads and deletes the pending invite code in a single call. Returns
 * {@code null} when no slot exists or the read fails (e.g. SecureStore
 * unavailable). Idempotent — a second call returns {@code null}.
 *
 * <p>Invoked from {@code AuthContext.signUp} / {@code signIn} success
 * paths so a KakaoTalk-share tap survives a fresh install → signup
 * flow without the BE auth contract having to carry the
 * {@code inviteCode} (Story 6.2 Trap #2).
 */
export async function consumePendingInviteCode(): Promise<string | null> {
  try {
    const code = await SecureStore.getItemAsync(PENDING_INVITE_KEY);
    if (code) {
      await SecureStore.deleteItemAsync(PENDING_INVITE_KEY);
    }
    return code;
  } catch {
    return null;
  }
}

/** Test-only escape hatch exporting the SecureStore slot key. */
export const __PENDING_INVITE_KEY_FOR_TESTS = PENDING_INVITE_KEY;
