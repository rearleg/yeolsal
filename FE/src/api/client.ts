import { API_BASE_URL } from "./config";
import * as SecureStore from "expo-secure-store";

type RequestOptions = RequestInit & {
  token?: string;
  skipAuth?: boolean;
  retry?: boolean;
};

const ACCESS_TOKEN_KEY = "yeosal.accessToken";
const REFRESH_TOKEN_KEY = "yeosal.refreshToken";

export type ApiEnvelope<T> = { data: T };
export type AuthUser = { id: number; email: string; nickname: string; timezone: string };
export type AuthTokens = { accessToken: string; refreshToken: string; tokenType: string; user: AuthUser };

export async function saveTokens(tokens: AuthTokens) {
  await setStoredValue(ACCESS_TOKEN_KEY, tokens.accessToken);
  await setStoredValue(REFRESH_TOKEN_KEY, tokens.refreshToken);
}

export async function clearTokens() {
  await deleteStoredValue(ACCESS_TOKEN_KEY);
  await deleteStoredValue(REFRESH_TOKEN_KEY);
}

export async function getAccessToken() {
  return getStoredValue(ACCESS_TOKEN_KEY);
}

export async function getRefreshToken() {
  return getStoredValue(REFRESH_TOKEN_KEY);
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");

  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  if (options.token) {
    headers.set("Authorization", `Bearer ${options.token}`);
  } else if (!options.skipAuth) {
    const token = await getAccessToken();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers
  });

  if (response.status === 401 && !options.skipAuth && options.retry !== false) {
    const refreshed = await refreshAccessTokenOnce();
    if (refreshed) {
      return apiRequest<T>(path, { ...options, retry: false });
    }
  }

  if (!response.ok) {
    let message = `API request failed: ${response.status}`;
    try {
      const body = await response.json();
      if (body?.message) {
        message = body.message;
      }
    } catch {
      // Ignore non-JSON error bodies.
    }
    throw new Error(message);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

let refreshInflight: Promise<boolean> | null = null;
let onAuthInvalid: (() => void | Promise<void>) | null = null;

export function setOnAuthInvalid(cb: (() => void | Promise<void>) | null): void {
  onAuthInvalid = cb;
}

async function refreshAccessTokenOnce(): Promise<boolean> {
  if (refreshInflight) {
    return refreshInflight;
  }
  refreshInflight = (async () => {
    const refreshToken = await getRefreshToken();
    if (!refreshToken) {
      return false;
    }
    try {
      const envelope = await apiRequest<ApiEnvelope<AuthTokens>>("/auth/refresh", {
        method: "POST",
        skipAuth: true,
        retry: false,
        body: JSON.stringify({ refreshToken })
      });
      await saveTokens(envelope.data);
      return true;
    } catch {
      await clearTokens();
      if (onAuthInvalid) {
        try {
          await onAuthInvalid();
        } catch {
          // swallow — auth-invalid handler must not break refresh flow
        }
      }
      return false;
    }
  })();
  try {
    return await refreshInflight;
  } finally {
    refreshInflight = null;
  }
}

async function setStoredValue(key: string, value: string) {
  await SecureStore.setItemAsync(key, value);
}

async function getStoredValue(key: string) {
  return SecureStore.getItemAsync(key);
}

async function deleteStoredValue(key: string) {
  await SecureStore.deleteItemAsync(key);
}
