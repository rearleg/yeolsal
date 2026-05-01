import * as SecureStore from "expo-secure-store";

jest.mock("../config", () => ({ API_BASE_URL: "https://api.test" }));

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

import { apiRequest, setOnAuthInvalid } from "../client";

const getItemAsyncMock = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;
const setItemAsyncMock = SecureStore.setItemAsync as jest.MockedFunction<
  typeof SecureStore.setItemAsync
>;
const deleteItemAsyncMock = SecureStore.deleteItemAsync as jest.MockedFunction<
  typeof SecureStore.deleteItemAsync
>;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function refreshSuccessBody(suffix: string) {
  return {
    data: {
      accessToken: `access-${suffix}`,
      refreshToken: `refresh-${suffix}`,
      tokenType: "Bearer",
      user: { id: 1, email: "a@b.c", nickname: "u", timezone: "Asia/Seoul" },
    },
  };
}

describe("apiRequest single-flight refresh", () => {
  let fetchMock: jest.Mock;
  let storedAccess: string;
  let storedRefresh: string;

  beforeEach(() => {
    jest.clearAllMocks();
    storedAccess = "access-old";
    storedRefresh = "refresh-old";

    getItemAsyncMock.mockImplementation(async (key: string) => {
      if (key === "yeosal.refreshToken") return storedRefresh;
      if (key === "yeosal.accessToken") return storedAccess;
      return null;
    });
    setItemAsyncMock.mockImplementation(async (key: string, value: string) => {
      if (key === "yeosal.refreshToken") storedRefresh = value;
      if (key === "yeosal.accessToken") storedAccess = value;
    });
    deleteItemAsyncMock.mockImplementation(async (key: string) => {
      if (key === "yeosal.refreshToken") storedRefresh = "";
      if (key === "yeosal.accessToken") storedAccess = "";
    });

    fetchMock = jest.fn();
    (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
    setOnAuthInvalid(null);
  });

  it("calls /auth/refresh exactly once for N concurrent 401 responses", async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url.endsWith("/auth/refresh")) {
        refreshCalls += 1;
        return jsonResponse(200, refreshSuccessBody("new"));
      }
      const headers = init.headers as Headers;
      const auth = headers.get("Authorization");
      if (auth === "Bearer access-old") {
        return jsonResponse(401, { message: "unauthorized" });
      }
      return jsonResponse(200, { data: { ok: true } });
    });

    const N = 5;
    const results = await Promise.all(
      Array.from({ length: N }, () =>
        apiRequest<{ data: { ok: boolean } }>("/protected"),
      ),
    );

    expect(refreshCalls).toBe(1);
    expect(results).toHaveLength(N);
    for (const r of results) {
      expect(r.data.ok).toBe(true);
    }
  });

  it("calls onAuthInvalid exactly once when refresh fails for concurrent 401s", async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(401, { message: "expired" });
      }
      return jsonResponse(401, { message: "unauthorized" });
    });

    const onInvalid = jest.fn();
    setOnAuthInvalid(onInvalid);

    const N = 5;
    const settled = await Promise.allSettled(
      Array.from({ length: N }, () => apiRequest("/protected")),
    );

    expect(settled).toHaveLength(N);
    for (const s of settled) {
      expect(s.status).toBe("rejected");
    }
    expect(onInvalid).toHaveBeenCalledTimes(1);
    expect(deleteItemAsyncMock).toHaveBeenCalledWith("yeosal.accessToken");
    expect(deleteItemAsyncMock).toHaveBeenCalledWith("yeosal.refreshToken");
  });

  it("creates a new inflight after the previous refresh resolves", async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url.endsWith("/auth/refresh")) {
        refreshCalls += 1;
        return jsonResponse(200, refreshSuccessBody(`r${refreshCalls}`));
      }
      const headers = init.headers as Headers;
      const auth = headers.get("Authorization");
      if (auth === "Bearer access-old") {
        return jsonResponse(401, { message: "unauthorized" });
      }
      return jsonResponse(200, { data: { ok: true } });
    });

    await apiRequest("/protected");
    expect(refreshCalls).toBe(1);

    storedAccess = "access-old";
    await apiRequest("/protected");
    expect(refreshCalls).toBe(2);
  });
});
