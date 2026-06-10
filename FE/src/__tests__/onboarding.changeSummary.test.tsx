// Story 8.1 AC13 row 3 — returning-user change-summary branch (4 cases).
// Kept in its own file per Trap #12 (single-purpose test paths).

jest.mock("expo-router", () => ({ router: { replace: jest.fn() } }));
jest.mock("../auth/AuthContext", () => ({ useAuth: jest.fn() }));
jest.mock("../lib/analytics", () => ({ captureEvent: jest.fn() }));
jest.mock("../lib/analyticsConsent", () => ({ setAnalyticsConsent: jest.fn() }));
jest.mock("../lib/onboardingState", () => ({
  getOnboardingState: jest.fn(),
  markOnboardingCompleted: jest.fn(),
}));
jest.mock("../lib/query/hooks/rooms", () => ({
  useRoomsQuery: jest.fn(),
  useRoomMembersQuery: jest.fn(),
}));

import { fireEvent, render, waitFor } from "@testing-library/react-native";
import { router } from "expo-router";
import OnboardingScreen from "../../app/onboarding";
import { useAuth } from "../auth/AuthContext";
import { captureEvent } from "../lib/analytics";
import { setAnalyticsConsent } from "../lib/analyticsConsent";
import {
  getOnboardingState,
  markOnboardingCompleted,
} from "../lib/onboardingState";
import { useRoomMembersQuery, useRoomsQuery } from "../lib/query/hooks/rooms";

const mockReplace = router.replace as jest.MockedFunction<typeof router.replace>;
const mockUseAuth = useAuth as jest.MockedFunction<typeof useAuth>;
const mockCapture = captureEvent as jest.MockedFunction<typeof captureEvent>;
const mockSetConsent = setAnalyticsConsent as jest.MockedFunction<
  typeof setAnalyticsConsent
>;
const mockGetState = getOnboardingState as jest.MockedFunction<
  typeof getOnboardingState
>;
const mockMarkCompleted = markOnboardingCompleted as jest.MockedFunction<
  typeof markOnboardingCompleted
>;
const mockUseRoomsQuery = useRoomsQuery as jest.MockedFunction<typeof useRoomsQuery>;
const mockUseRoomMembersQuery = useRoomMembersQuery as jest.MockedFunction<
  typeof useRoomMembersQuery
>;

const HEADLINE = "yeolsal이 열살방으로 바뀌었어요";
const S1_BODY = "열살방은 친구와 함께 살아남는 방입니다.";

const ROOM = {
  id: 3,
  name: "달리기방",
  ownerId: 1,
  maxMembers: 12,
  minDailyGoalDays: 10,
  createdAt: "2026-01-01T00:00:00Z",
  pendingMaxMembers: null,
  pendingMaxMembersEffectiveFromMonth: null,
};

function roomsResult(data: unknown): ReturnType<typeof useRoomsQuery> {
  return { data, isSuccess: true, isError: false } as unknown as ReturnType<
    typeof useRoomsQuery
  >;
}

function authBag(lastAuthEvent: "signUp" | "signIn" | "signInKakao" | null) {
  return {
    user: { id: 7, email: "a@example.com", nickname: "alice", timezone: "Asia/Seoul" },
    loading: false,
    signIn: jest.fn(),
    signUp: jest.fn(),
    signInWithKakao: jest.fn(),
    signOut: jest.fn(),
    getLastAuthEvent: () => lastAuthEvent,
  } as unknown as ReturnType<typeof useAuth>;
}

async function renderReady() {
  const utils = render(<OnboardingScreen />);
  await waitFor(() => expect(utils.queryByTestId("onboarding-loading")).toBeNull());
  return utils;
}

describe("OnboardingScreen change-summary branch (Story 8.1 AC5)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetState.mockResolvedValue(null);
    mockMarkCompleted.mockResolvedValue(undefined);
    mockSetConsent.mockResolvedValue(undefined);
    mockUseRoomMembersQuery.mockReturnValue(
      { data: undefined } as unknown as ReturnType<typeof useRoomMembersQuery>,
    );
  });

  it("renders the change-summary instead of the carousel for a returning user with rooms", async () => {
    mockUseAuth.mockReturnValue(authBag("signIn"));
    mockUseRoomsQuery.mockReturnValue(roomsResult([ROOM]));

    const { getByText, queryByText } = await renderReady();
    expect(getByText(HEADLINE)).toBeTruthy();
    expect(
      getByText("이름이 바뀌었어요. 그동안의 친구들, 그룹, 잔디는 그대로 함께해요."),
    ).toBeTruthy();
    expect(queryByText(S1_BODY)).toBeNull();
  });

  it("확인했어요 records opt_out, emits onboarding.completed, marks completed and routes home", async () => {
    mockUseAuth.mockReturnValue(authBag("signIn"));
    mockUseRoomsQuery.mockReturnValue(roomsResult([ROOM]));

    const { getByText } = await renderReady();
    fireEvent.press(getByText("확인했어요"));

    await waitFor(() => {
      expect(mockSetConsent).toHaveBeenCalledWith("opt_out");
      expect(mockCapture).toHaveBeenCalledWith("onboarding.completed");
      expect(mockMarkCompleted).toHaveBeenCalledWith(null);
      expect(mockReplace).toHaveBeenCalledWith("/today");
    });
  });

  it("runs the full carousel for a returning user with zero rooms", async () => {
    mockUseAuth.mockReturnValue(authBag("signIn"));
    mockUseRoomsQuery.mockReturnValue(roomsResult([]));

    const { getByText, queryByText } = await renderReady();
    expect(getByText(S1_BODY)).toBeTruthy();
    expect(queryByText(HEADLINE)).toBeNull();
  });

  it("runs the full carousel after a signup even when rooms already exist", async () => {
    mockUseAuth.mockReturnValue(authBag("signUp"));
    mockUseRoomsQuery.mockReturnValue(roomsResult([ROOM]));

    const { getByText, queryByText } = await renderReady();
    expect(getByText(S1_BODY)).toBeTruthy();
    expect(queryByText(HEADLINE)).toBeNull();
  });
});
