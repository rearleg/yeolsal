import { createContext, PropsWithChildren, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { Linking } from "react-native";
import { API_BASE_URL } from "../api/config";
import { apiRequest, ApiEnvelope, AuthTokens, AuthUser, clearTokens, getRefreshToken, saveTokens, setOnAuthInvalid } from "../api/client";
import { queryClient } from "../lib/query/client";
import { purgePersistedQueries } from "../lib/query/persist";

async function clearAllCaches(): Promise<void> {
  queryClient.clear();
  try {
    await purgePersistedQueries();
  } catch {
    // best-effort: storage failure must not block auth state cleanup
  }
}

type AuthContextValue = {
  user: AuthUser | null;
  loading: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string, nickname: string) => Promise<void>;
  signInWithKakao: () => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);
  const userRef = useRef<AuthUser | null>(null);

  const handleAuthInvalid = useCallback(async () => {
    await clearAllCaches();
    userRef.current = null;
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
  }

  async function signUp(email: string, password: string, nickname: string) {
    const response = await apiRequest<ApiEnvelope<AuthTokens>>("/auth/signup", {
      method: "POST",
      skipAuth: true,
      body: JSON.stringify({ email, password, nickname })
    });
    await apply(response.data);
  }

  async function signInWithKakao() {
    const tokens = await openKakaoAuthorization();
    await apply(tokens);
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
    setUser(null);
  }

  const value = useMemo(() => ({ user, loading, signIn, signUp, signInWithKakao, signOut }), [user, loading]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

function openKakaoAuthorization() {
  return new Promise<AuthTokens>((resolve, reject) => {
    const timeout = setTimeout(() => {
      subscription.remove();
      reject(new Error("Kakao 로그인 시간이 초과되었습니다."));
    }, 120000);

    const subscription = Linking.addEventListener("url", (event) => {
      const url = new URL(event.url);
      const accessToken = url.searchParams.get("accessToken");
      const refreshToken = url.searchParams.get("refreshToken");
      const tokenType = url.searchParams.get("tokenType") ?? "Bearer";
      const userId = Number(url.searchParams.get("userId"));
      const email = url.searchParams.get("email");
      const nickname = url.searchParams.get("nickname");
      const timezone = url.searchParams.get("timezone") ?? "Asia/Seoul";
      const error = url.searchParams.get("error");
      if ((accessToken && refreshToken && email && nickname && userId) || error) {
        clearTimeout(timeout);
        subscription.remove();
      }
      if (accessToken && refreshToken && email && nickname && userId) {
        resolve({
          accessToken,
          refreshToken,
          tokenType,
          user: { id: userId, email, nickname, timezone }
        });
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
