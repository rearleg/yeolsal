import { render, waitFor } from "@testing-library/react-native";
import { joinRoom } from "../../api/rooms";
import JoinRoomScreen from "../../../app/join";

jest.mock("expo-router", () => ({
  router: { replace: jest.fn() },
  useLocalSearchParams: jest.fn(() => ({ code: "abcd1234" })),
}));
jest.mock("../../auth/useRequireAuth", () => ({
  useRequireAuth: jest.fn(),
}));
jest.mock("../../api/rooms", () => ({
  MIN_DAYS_LABELS: {},
  joinRoom: jest.fn(),
}));
jest.mock("../../lib/toast", () => ({
  toast: {
    info: jest.fn(),
    error: jest.fn(),
  },
}));

const mockJoinRoom = joinRoom as jest.MockedFunction<typeof joinRoom>;

describe("JoinRoomScreen deep-link auto-submit", () => {
  it("submits the normalized route code instead of stale component state", async () => {
    mockJoinRoom.mockResolvedValue({
      roomId: 42,
      userId: 7,
      nickname: "alice",
      role: "MEMBER",
      currentMinimum: 10,
      warningCount: 0,
      survivalStatus: "ACTIVE",
    });

    render(<JoinRoomScreen />);

    await waitFor(() => expect(mockJoinRoom).toHaveBeenCalledWith("ABCD1234"));
  });
});
