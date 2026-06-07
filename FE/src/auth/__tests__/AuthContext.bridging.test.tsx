import { act, render, waitFor } from "@testing-library/react-native";
import { Text } from "react-native";
import { ApiError } from "../../api/client";
import { joinRoom } from "../../api/rooms";
import { consumePendingInviteCode } from "../../lib/deepLinking";
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
});
