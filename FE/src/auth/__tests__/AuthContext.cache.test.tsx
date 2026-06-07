import { act, render, waitFor } from "@testing-library/react-native";
import { Text } from "react-native";
import { AuthProvider, useAuth } from "../AuthContext";

jest.mock("../../api/client", () => ({
  apiRequest: jest.fn(),
  saveTokens: jest.fn(),
  clearTokens: jest.fn(),
  getRefreshToken: jest.fn(),
  setOnAuthInvalid: jest.fn(),
  ApiError: class ApiError extends Error {
    readonly status: number;
    readonly code: string;
    constructor(status: number, code: string, message: string) {
      super(message);
      this.status = status;
      this.code = code;
    }
  },
}));

jest.mock("../../lib/query/client", () => ({
  queryClient: { clear: jest.fn() },
}));

jest.mock("../../lib/query/persist", () => ({
  purgePersistedQueries: jest.fn(),
}));

// Story 6.2 — AuthContext now imports `router` from expo-router for the
// post-install bridging redirect. Jest's babel transform doesn't grok the
// transitive expo-router → @react-navigation/native ESM, so short-circuit
// the import with a no-op router mock + matching mocks for the new
// auto-join dependencies.
jest.mock("expo-router", () => ({
  router: { push: jest.fn(), replace: jest.fn() },
}));

jest.mock("../../api/rooms", () => ({
  joinRoom: jest.fn(),
}));

jest.mock("../../lib/deepLinking", () => ({
  consumePendingInviteCode: jest.fn(() => Promise.resolve(null)),
}));

const clientMod = jest.requireMock("../../api/client") as {
  apiRequest: jest.Mock;
  saveTokens: jest.Mock;
  clearTokens: jest.Mock;
  getRefreshToken: jest.Mock;
  setOnAuthInvalid: jest.Mock;
};
const queryClientMod = jest.requireMock("../../lib/query/client") as {
  queryClient: { clear: jest.Mock };
};
const persistMod = jest.requireMock("../../lib/query/persist") as {
  purgePersistedQueries: jest.Mock;
};

type AuthBag = ReturnType<typeof useAuth>;

function TestConsumer({ onRender }: { onRender: (auth: AuthBag) => void }) {
  const auth = useAuth();
  onRender(auth);
  return <Text>{auth.user?.nickname ?? "anon"}</Text>;
}

function renderProvider() {
  let latest: AuthBag | null = null;
  const utils = render(
    <AuthProvider>
      <TestConsumer
        onRender={(a) => {
          latest = a;
        }}
      />
    </AuthProvider>,
  );
  return {
    ...utils,
    getLatest: () => {
      if (!latest) throw new Error("provider did not render");
      return latest;
    },
  };
}

describe("AuthProvider auth-scoped cache purge", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    clientMod.getRefreshToken.mockResolvedValue(null);
  });

  it("registers an auth-invalid handler on mount and clears it on unmount", async () => {
    const { getLatest, unmount } = renderProvider();
    await waitFor(() => expect(getLatest().loading).toBe(false));

    expect(clientMod.setOnAuthInvalid).toHaveBeenCalledWith(expect.any(Function));

    unmount();
    expect(clientMod.setOnAuthInvalid).toHaveBeenLastCalledWith(null);
  });

  it("clears query cache and purges persisted queries on signOut", async () => {
    const { getLatest } = renderProvider();
    await waitFor(() => expect(getLatest().loading).toBe(false));

    clientMod.getRefreshToken.mockResolvedValueOnce(null);
    await act(async () => {
      await getLatest().signOut();
    });

    expect(queryClientMod.queryClient.clear).toHaveBeenCalled();
    expect(persistMod.purgePersistedQueries).toHaveBeenCalled();
    expect(clientMod.clearTokens).toHaveBeenCalled();
  });

  it("clears caches when applying tokens for a different user via signIn", async () => {
    clientMod.apiRequest.mockResolvedValueOnce({
      data: {
        accessToken: "a",
        refreshToken: "r",
        tokenType: "Bearer",
        user: { id: 100, email: "x@y.z", nickname: "alice", timezone: "Asia/Seoul" },
      },
    });

    const { getLatest } = renderProvider();
    await waitFor(() => expect(getLatest().loading).toBe(false));

    const beforeClear = queryClientMod.queryClient.clear.mock.calls.length;
    const beforePurge = persistMod.purgePersistedQueries.mock.calls.length;

    await act(async () => {
      await getLatest().signIn("e@m.com", "pw");
    });

    expect(queryClientMod.queryClient.clear.mock.calls.length).toBeGreaterThan(beforeClear);
    expect(persistMod.purgePersistedQueries.mock.calls.length).toBeGreaterThan(beforePurge);
    expect(clientMod.saveTokens).toHaveBeenCalled();
    expect(getLatest().user?.id).toBe(100);
  });

  it("still clears auth state on signOut when purgePersistedQueries rejects", async () => {
    persistMod.purgePersistedQueries.mockRejectedValue(new Error("storage offline"));

    clientMod.apiRequest.mockResolvedValueOnce({
      data: {
        accessToken: "a",
        refreshToken: "r",
        tokenType: "Bearer",
        user: { id: 7, email: "x@y.z", nickname: "bob", timezone: "Asia/Seoul" },
      },
    });
    const { getLatest } = renderProvider();
    await waitFor(() => expect(getLatest().loading).toBe(false));

    await act(async () => {
      await getLatest().signIn("e@m.com", "pw");
    });
    expect(getLatest().user?.id).toBe(7);

    clientMod.getRefreshToken.mockResolvedValueOnce(null);
    await act(async () => {
      await getLatest().signOut();
    });

    expect(clientMod.clearTokens).toHaveBeenCalled();
    expect(queryClientMod.queryClient.clear).toHaveBeenCalled();
    expect(getLatest().user).toBeNull();
  });
});
