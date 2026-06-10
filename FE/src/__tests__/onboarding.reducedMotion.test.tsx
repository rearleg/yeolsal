// Story 8.1 AC11 + AC13 row 4 — reduced-motion fallback (3 cases).
// `useReducedMotion` is replaced with a controllable jest.fn while the rest
// of the motion module (durations, easings, pressScale) stays real so the
// Button press-scale styling keeps working.

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
jest.mock("../theme/motion", () => ({
  ...jest.requireActual("../theme/motion"),
  useReducedMotion: jest.fn(),
}));

import { fireEvent, render, waitFor } from "@testing-library/react-native";
import { Animated, Dimensions, ScrollView } from "react-native";
import OnboardingScreen from "../../app/onboarding";
import { useAuth } from "../auth/AuthContext";
import { getOnboardingState } from "../lib/onboardingState";
import { useRoomMembersQuery, useRoomsQuery } from "../lib/query/hooks/rooms";
import { useReducedMotion } from "../theme/motion";

const mockUseAuth = useAuth as jest.MockedFunction<typeof useAuth>;
const mockGetState = getOnboardingState as jest.MockedFunction<
  typeof getOnboardingState
>;
const mockUseRoomsQuery = useRoomsQuery as jest.MockedFunction<typeof useRoomsQuery>;
const mockUseRoomMembersQuery = useRoomMembersQuery as jest.MockedFunction<
  typeof useRoomMembersQuery
>;
const mockUseReducedMotion = useReducedMotion as jest.MockedFunction<
  typeof useReducedMotion
>;

async function renderReady() {
  const utils = render(<OnboardingScreen />);
  await waitFor(() => expect(utils.queryByTestId("onboarding-loading")).toBeNull());
  return utils;
}

describe("Onboarding reduced-motion fallback (Story 8.1 AC11)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseAuth.mockReturnValue({
      user: { id: 7, email: "a@example.com", nickname: "alice", timezone: "Asia/Seoul" },
      loading: false,
      signIn: jest.fn(),
      signUp: jest.fn(),
      signInWithKakao: jest.fn(),
      signOut: jest.fn(),
      getLastAuthEvent: () => "signUp" as const,
    } as unknown as ReturnType<typeof useAuth>);
    mockGetState.mockResolvedValue(null);
    mockUseRoomsQuery.mockReturnValue(
      { data: [], isSuccess: true, isError: false } as unknown as ReturnType<
        typeof useRoomsQuery
      >,
    );
    mockUseRoomMembersQuery.mockReturnValue(
      { data: undefined } as unknown as ReturnType<typeof useRoomMembersQuery>,
    );
  });

  it("renders the S1 D4 card without an Animated.timing fade under reduced motion", async () => {
    mockUseReducedMotion.mockReturnValue(true);
    const timingSpy = jest.spyOn(Animated, "timing");

    const { getByTestId } = await renderReady();

    expect(getByTestId("onboarding-s1-card")).toBeTruthy();
    expect(timingSpy).not.toHaveBeenCalled();
    timingSpy.mockRestore();
  });

  it("advances with ScrollView.scrollTo({ animated: false }) under reduced motion", async () => {
    mockUseReducedMotion.mockReturnValue(true);
    const scrollTo = ScrollView.prototype.scrollTo as unknown as jest.Mock;
    scrollTo.mockClear();

    const { getByText } = await renderReady();
    fireEvent.press(getByText("다음"));

    const width = Dimensions.get("window").width;
    expect(scrollTo).toHaveBeenCalledWith(
      expect.objectContaining({ x: width, animated: false }),
    );
  });

  it("runs the S1 fade when reduced motion is off", async () => {
    mockUseReducedMotion.mockReturnValue(false);
    const timingSpy = jest.spyOn(Animated, "timing");

    await renderReady();

    expect(timingSpy).toHaveBeenCalled();
    timingSpy.mockRestore();
  });
});
