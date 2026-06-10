import { createContext, PropsWithChildren, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { Linking } from "react-native";
import { API_BASE_URL } from "../api/config";
import { ApiError, apiRequest, ApiEnvelope, AuthTokens, AuthUser, clearTokens, getRefreshToken, saveTokens, setOnAuthInvalid } from "../api/client";
import { joinRoom } from "../api/rooms";
import { captureEvent } from "../lib/analytics";
import { consumePendingInviteCode } from "../lib/deepLinking";
import { setDeferredDestination } from "../lib/onboardingState";
import { queryClient } from "../lib/query/client";
import { purgePersistedQueries } from "../lib/query/persist";
import { toast } from "../lib/toast";

async function clearAllCaches(): Promise<void> {
  queryClient.clear();
  try {
    await purgePersistedQueries();
  } catch {
    // best-effort: storage failure must not block auth state cleanup
  }
}

/**
 * Story 8.1 AC5 — transient record of which auth method most recently
 * produced the current `user`. A restored session counts as "signIn"
 * (force-quit + restoreSession resolves to a signIn-like flow, which is
 * exactly the returning-user change-summary case). Never persisted.
 */
export type LastAuthEvent = "signUp" | "signIn" | "signInKakao" | null;

type AuthContextValue = {
  user: AuthUser | null;
  loading: boolean;
  signIn: (email: string, password: string) => Promise<string | null>;
  signUp: (email: string, password: string, nickname: string) => Promise<string | null>;
  signInWithKakao: () => Promise<string | null>;
  signOut: () => Promise<void>;
  getLastAuthEvent: () => LastAuthEvent;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);
  const userRef = useRef<AuthUser | null>(null);
  const lastAuthEventRef = useRef<LastAuthEvent>(null);

  const getLastAuthEvent = useCallback(() => lastAuthEventRef.current, []);

  const handleAuthInvalid = useCallback(async () => {
    await clearAllCaches();
    userRef.current = null;
    lastAuthEventRef.current = null;
    setUser(null);
  }, []);

  useEffect(() => {
    setOnAuthInvalid(handleAuthInvalid);
    return () => {
      setOnAuthInvalid(null);
    };
  }, [handleAuthInvalid]);

  useEffect(() => {
    restoreSession();
  }, []);

  async function apply(tokens: AuthTokens) {
    const prev = userRef.current;
    if (!prev || prev.id !== tokens.user.id) {
      await clearAllCaches();
    }
    await saveTokens(tokens);
    userRef.current = tokens.user;
    setUser(tokens.user);
  }

  async function restoreSession() {
    try {
      const refreshToken = await getRefreshToken();
      if (!refreshToken) {
        return;
      }
      const response = await apiRequest<ApiEnvelope<AuthTokens>>("/auth/refresh", {
        method: "POST",
        skipAuth: true,
        body: JSON.stringify({ refreshToken })
      });
      await apply(response.data);
      lastAuthEventRef.current = "signIn";
    } catch {
      await clearTokens();
      await clearAllCaches();
      userRef.current = null;
      setUser(null);
    } finally {
      setLoading(false);
    }
  }

  async function signIn(email: string, password: string) {
    const response = await apiRequest<ApiEnvelope<AuthTokens>>("/auth/login", {
      method: "POST",
      skipAuth: true,
      body: JSON.stringify({ email, password })
    });
    await apply(response.data);
    lastAuthEventRef.current = "signIn";
    const destination = await tryConsumePendingInvite();
    await stashDeferredDestination(destination);
    return destination;
  }

  async function signUp(email: string, password: string, nickname: string) {
    const response = await apiRequest<ApiEnvelope<AuthTokens>>("/auth/signup", {
      method: "POST",
      skipAuth: true,
      body: JSON.stringify({ email, password, nickname })
    });
    await apply(response.data);
    lastAuthEventRef.current = "signUp";
    // Story 8.1 AC8 — fires BEFORE tryConsumePendingInvite so a
    // deeplink-failed signup still records.
    captureEvent("signup.completed", { authMethod: "EMAIL" });
    const destination = await tryConsumePendingInvite();
    await stashDeferredDestination(destination);
    return destination;
  }

  async function signInWithKakao() {
    const code = await openKakaoAuthorization();
    const response = await apiRequest<ApiEnvelope<AuthTokens>>("/auth/kakao/exchange", {
      method: "POST",
      skipAuth: true,
      body: JSON.stringify({ code })
    });
    await apply(response.data);
    lastAuthEventRef.current = "signInKakao";
    if (response.data.newAccount === true) {
      lastAuthEventRef.current = "signUp";
      captureEvent("signup.completed", { authMethod: "KAKAO" });
    }
    const destination = await tryConsumePendingInvite();
    await stashDeferredDestination(destination);
    return destination;
  }

  async function signOut() {
    const refreshToken = await getRefreshToken();
    if (refreshToken) {
      try {
        await apiRequest("/auth/logout", {
          method: "POST",
          body: JSON.stringify({ refreshToken })
        });
      } catch {
        // Local logout should still succeed if the server is unavailable.
      }
    }
    await clearTokens();
    await clearAllCaches();
    userRef.current = null;
    lastAuthEventRef.current = null;
    setUser(null);
  }

  const value = useMemo(
    () => ({ user, loading, signIn, signUp, signInWithKakao, signOut, getLastAuthEvent }),
    [user, loading, getLastAuthEvent]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/**
 * Story 6.2 AC3 — post-install bridging. Reads-and-deletes the SecureStore
 * slot the deep-link handler wrote (KakaoTalk share-tap landed on a
 * non-authenticated device). On hit, attempts to auto-join the room and
 * redirect to the newcomer settings flow; on miss or failure, falls
 * through silently — the caller's existing post-auth redirect ("/today")
 * remains the default.
 */
async function tryConsumePendingInvite(): Promise<string | null> {
  const pendingCode = await consumePendingInviteCode();
  if (!pendingCode) return null;
  try {
    const member = await joinRoom(pendingCode.toUpperCase());
    return `/rooms/${member.roomId}/settings?onboarding=1`;
  } catch (error) {
    // Best-effort — surface a soft toast so the user knows the gift exists
    // and can retry by typing the code on /join. Brand voice keeps the
    // language calm, no AVOID lexicon.
    if (error instanceof ApiError && error.code === "ROOM_FULL") {
      toast.info("초대받은 방이 가득 찼어요. 직접 코드를 입력해서 다시 시도해보세요.");
    } else {
      toast.info("초대 코드는 그룹 참여 화면에서 다시 사용할 수 있어요.");
    }
    return null;
  }
}

/**
 * Story 8.1 AC9 — stashes the post-auth deeplink destination into the
 * durable onboarding slot so `<OnboardingGate>` / the onboarding screen
 * can route to it after S5 (survives a force-quit between auth and
 * onboarding). A null destination writes nothing — overwriting would
 * wipe a destination stashed by an earlier interrupted auth.
 */
async function stashDeferredDestination(destination: string | null): Promise<void> {
  await setDeferredDestination(destination);
}

function openKakaoAuthorization(): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    const timeout = setTimeout(() => {
      subscription.remove();
      reject(new Error("Kakao 로그인 시간이 초과되었습니다."));
    }, 120000);

    const subscription = Linking.addEventListener("url", (event) => {
      const url = new URL(event.url);
      const code = url.searchParams.get("code");
      const error = url.searchParams.get("error");
      if (code || error) {
        clearTimeout(timeout);
        subscription.remove();
      }
      if (code) {
        resolve(code);
      } else if (error) {
        reject(new Error(`Kakao 로그인 실패: ${error}`));
      }
    });

    Linking.openURL(`${API_BASE_URL}/auth/kakao/authorize`).catch((error: unknown) => {
      clearTimeout(timeout);
      subscription.remove();
      reject(error instanceof Error ? error : new Error("Kakao 로그인 창을 열 수 없습니다."));
    });
  });
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
