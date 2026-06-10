import { act, render, waitFor } from "@testing-library/react-native";
import { Linking, Text } from "react-native";
import { ApiError } from "../../api/client";
import { joinRoom } from "../../api/rooms";
import { captureEvent } from "../../lib/analytics";
import { consumePendingInviteCode } from "../../lib/deepLinking";
import { setDeferredDestination } from "../../lib/onboardingState";
import { AuthProvider, useAuth } from "../AuthContext";

jest.mock("../../api/client", () => {
  class MockApiError extends Error {
    readonly status: number;
    readonly code: string;

    constructor(statusValue: number, codeValue: string, message: string) {
      super(message);
      this.status = statusValue;
      this.code = codeValue;
    }
  }
  return {
    ApiError: MockApiError,
    apiRequest: jest.fn(),
    saveTokens: jest.fn(),
    clearTokens: jest.fn(),
    getRefreshToken: jest.fn(() => Promise.resolve(null)),
    setOnAuthInvalid: jest.fn(),
  };
});

jest.mock("../../api/rooms", () => ({ joinRoom: jest.fn() }));
jest.mock("../../lib/deepLinking", () => ({
  consumePendingInviteCode: jest.fn(),
}));
jest.mock("../../lib/analytics", () => ({ captureEvent: jest.fn() }));
jest.mock("../../lib/onboardingState", () => ({
  setDeferredDestination: jest.fn(),
}));
jest.mock("../../lib/query/client", () => ({
  queryClient: { clear: jest.fn() },
}));
jest.mock("../../lib/query/persist", () => ({
  purgePersistedQueries: jest.fn(),
}));

const mockToastInfo = jest.fn();
jest.mock("../../lib/toast", () => ({
  toast: {
    info: (message: string) => mockToastInfo(message),
    success: jest.fn(),
    warning: jest.fn(),
    error: jest.fn(),
  },
}));

const client = jest.requireMock("../../api/client") as {
  apiRequest: jest.Mock;
  getRefreshToken: jest.Mock;
};
const mockJoinRoom = joinRoom as jest.MockedFunction<typeof joinRoom>;
const mockConsume = consumePendingInviteCode as jest.MockedFunction<
  typeof consumePendingInviteCode
>;
const mockCapture = captureEvent as jest.MockedFunction<typeof captureEvent>;
const mockSetDeferredDestination =
  setDeferredDestination as jest.MockedFunction<typeof setDeferredDestination>;

type AuthBag = ReturnType<typeof useAuth>;

function renderProvider() {
  let latest: AuthBag | null = null;
  render(
    <AuthProvider>
      <AuthConsumer
        onRender={(auth) => {
          latest = auth;
        }}
      />
    </AuthProvider>,
  );
  return () => {
    if (!latest) throw new Error("provider did not render");
    return latest;
  };
}

function AuthConsumer({ onRender }: { onRender: (auth: AuthBag) => void }) {
  const auth = useAuth();
  onRender(auth);
  return <Text>{auth.loading ? "loading" : "ready"}</Text>;
}

describe("AuthProvider pending-invite bridging", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockSetDeferredDestination.mockResolvedValue(undefined);
    client.getRefreshToken.mockResolvedValue(null);
    client.apiRequest.mockResolvedValue({
      data: {
        accessToken: "a",
        refreshToken: "r",
        tokenType: "Bearer",
        user: {
          id: 7,
          email: "a@example.com",
          nickname: "alice",
          timezone: "Asia/Seoul",
        },
      },
    });
  });

  it("returns the room onboarding destination after a successful signup join", async () => {
    mockConsume.mockResolvedValue("ABCD1234");
    mockJoinRoom.mockResolvedValue({
      roomId: 42,
      userId: 7,
      nickname: "alice",
      role: "MEMBER",
      currentMinimum: 10,
      warningCount: 0,
      survivalStatus: "ACTIVE",
    });
    const getAuth = renderProvider();
    await waitFor(() => expect(getAuth().loading).toBe(false));

    let destination: string | null = null;
    await act(async () => {
      destination = await getAuth().signUp("a@example.com", "password", "alice");
    });

    expect(mockJoinRoom).toHaveBeenCalledWith("ABCD1234");
    expect(destination).toBe("/rooms/42/settings?onboarding=1");
  });

  it("returns null and shows the ROOM_FULL notice when joining fails", async () => {
    mockConsume.mockResolvedValue("ABCD1234");
    mockJoinRoom.mockRejectedValue(
      new ApiError(409, "ROOM_FULL", "방 정원을 초과했습니다."),
    );
    const getAuth = renderProvider();
    await waitFor(() => expect(getAuth().loading).toBe(false));

    let destination: string | null = "unexpected";
    await act(async () => {
      destination = await getAuth().signIn("a@example.com", "password");
    });

    expect(destination).toBeNull();
    expect(mockToastInfo).toHaveBeenCalledWith(
      "초대받은 방이 가득 찼어요. 직접 코드를 입력해서 다시 시도해보세요.",
    );
  });

  it("emits signup.completed (EMAIL) before consuming the pending invite on signUp", async () => {
    mockConsume.mockResolvedValue(null);
    const getAuth = renderProvider();
    await waitFor(() => expect(getAuth().loading).toBe(false));

    await act(async () => {
      await getAuth().signUp("a@example.com", "password", "alice");
    });

    expect(mockCapture).toHaveBeenCalledWith("signup.completed", {
      authMethod: "EMAIL",
    });
    const captureOrder = mockCapture.mock.invocationCallOrder[0];
    const consumeOrder = mockConsume.mock.invocationCallOrder[0];
    expect(captureOrder).toBeLessThan(consumeOrder);
  });

  it("stashes the deferred destination returned by the pending-invite join on signUp", async () => {
    mockConsume.mockResolvedValue("ABCD1234");
    mockJoinRoom.mockResolvedValue({
      roomId: 42,
      userId: 7,
      nickname: "alice",
      role: "MEMBER",
      currentMinimum: 10,
      warningCount: 0,
      survivalStatus: "ACTIVE",
    });
    const getAuth = renderProvider();
    await waitFor(() => expect(getAuth().loading).toBe(false));

    await act(async () => {
      await getAuth().signUp("a@example.com", "password", "alice");
    });

    expect(mockSetDeferredDestination).toHaveBeenCalledWith(
      "/rooms/42/settings?onboarding=1",
    );
  });

  it("emits signup.completed (KAKAO) only when no onboarding record exists", async () => {
    mockConsume.mockResolvedValue(null);
    const listeners: Array<(event: { url: string }) => void> = [];
    (jest.spyOn(Linking, "addEventListener") as unknown as jest.Mock).mockImplementation(
      (_type: string, handler: (event: { url: string }) => void) => {
        listeners.push(handler);
        return { remove: jest.fn() };
      },
    );
    (jest.spyOn(Linking, "openURL") as unknown as jest.Mock).mockResolvedValue(true);

    const getAuth = renderProvider();
    await waitFor(() => expect(getAuth().loading).toBe(false));

    client.apiRequest.mockResolvedValueOnce({
      data: {
        accessToken: "a",
        refreshToken: "r",
        tokenType: "Bearer",
        newAccount: true,
        user: {
          id: 7,
          email: "a@example.com",
          nickname: "alice",
          timezone: "Asia/Seoul",
        },
      },
    });
    await act(async () => {
      const pending = getAuth().signInWithKakao();
      listeners[listeners.length - 1]({ url: "https://yeolsal.app/oauth?code=abc" });
      await pending;
    });
    expect(mockCapture).toHaveBeenCalledWith("signup.completed", {
      authMethod: "KAKAO",
    });

    mockCapture.mockClear();
    client.apiRequest.mockResolvedValueOnce({
      data: {
        accessToken: "a",
        refreshToken: "r",
        tokenType: "Bearer",
        newAccount: false,
        user: {
          id: 7,
          email: "a@example.com",
          nickname: "alice",
          timezone: "Asia/Seoul",
        },
      },
    });
    await act(async () => {
      const pending = getAuth().signInWithKakao();
      listeners[listeners.length - 1]({ url: "https://yeolsal.app/oauth?code=def" });
      await pending;
    });
    expect(mockCapture).not.toHaveBeenCalled();
    expect(getAuth().getLastAuthEvent()).toBe("signInKakao");
  });

  it("clears an earlier deferred destination when auth returns no invite", async () => {
    mockConsume.mockResolvedValue(null);
    const getAuth = renderProvider();
    await waitFor(() => expect(getAuth().loading).toBe(false));

    await act(async () => {
      await getAuth().signIn("a@example.com", "password");
    });

    expect(mockSetDeferredDestination).toHaveBeenCalledWith(null);
  });
});
