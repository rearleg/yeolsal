// Story 8.1 AC13 row 2 — onboarding route + carousel behavior (12 cases).
// Test path note: tests for FE/app/-level files live under FE/src/__tests__/
// because Jest testMatch is restricted to <rootDir>/src/** (AC0 / Story 8.5
// PR 102 lesson — the brand-voice hex guard reads "#"+digits as a color).

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
  type OnboardingStateRecord,
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

function roomsResult(data: unknown): ReturnType<typeof useRoomsQuery> {
  return { data, isSuccess: true, isError: false } as unknown as ReturnType<
    typeof useRoomsQuery
  >;
}

function membersResult(data: unknown): ReturnType<typeof useRoomMembersQuery> {
  return { data } as unknown as ReturnType<typeof useRoomMembersQuery>;
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

function partialState(deferredDestination: string | null): OnboardingStateRecord {
  return { version: 1, completedAt: null, deferredDestination };
}

const ROOM_42 = {
  id: 42,
  name: "같이 살기",
  ownerId: 1,
  maxMembers: 12,
  minDailyGoalDays: 15,
  createdAt: "2026-06-01T00:00:00Z",
  pendingMaxMembers: null,
  pendingMaxMembersEffectiveFromMonth: null,
};

async function renderReady() {
  const utils = render(<OnboardingScreen />);
  await waitFor(() => expect(utils.queryByTestId("onboarding-loading")).toBeNull());
  return utils;
}

function pressNextTimes(
  getByText: ReturnType<typeof render>["getByText"],
  times: number,
) {
  for (let i = 0; i < times; i += 1) {
    fireEvent.press(getByText("다음"));
  }
}

describe("OnboardingScreen carousel (Story 8.1)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseAuth.mockReturnValue(authBag("signUp"));
    mockGetState.mockResolvedValue(null);
    mockMarkCompleted.mockResolvedValue(undefined);
    mockSetConsent.mockResolvedValue(undefined);
    mockUseRoomsQuery.mockReturnValue(roomsResult([]));
    mockUseRoomMembersQuery.mockReturnValue(membersResult(undefined));
  });

  it("renders the locked S1 body byte-identically", async () => {
    const { getByText } = await renderReady();
    expect(getByText("열살방은 친구와 함께 살아남는 방입니다.")).toBeTruthy();
  });

  it("renders the locked S2 body byte-identically", async () => {
    const { getByText } = await renderReady();
    expect(
      getByText("매일 약속을 지키면 살아남습니다. 빠지면 친구가 살릴 수 있어요."),
    ).toBeTruthy();
  });

  it("renders the locked S3 body byte-identically", async () => {
    const { getByText } = await renderReady();
    expect(
      getByText("v1에서는 돈을 받지 않습니다 — 살아남는 것 자체가 자산입니다."),
    ).toBeTruthy();
  });

  it("renders the locked S4 body byte-identically", async () => {
    const { getByText } = await renderReady();
    expect(getByText("친구를 살리는 건 옵션이지 의무가 아닙니다.")).toBeTruthy();
  });

  it("renders the S5 composite: grace banner + Wallet + Room + PIPA consent", async () => {
    const { getByText, getByLabelText } = await renderReady();
    expect(getByText("처음 14일은 환영 기간이에요")).toBeTruthy();
    expect(getByText("Wallet")).toBeTruthy();
    expect(
      getByText("처음 합류한 그룹에서 무료 회생권 1장이 자동으로 발급돼요."),
    ).toBeTruthy();
    expect(getByText("🎟️ 무료 회생권 ×1")).toBeTruthy();
    expect(getByText("Room")).toBeTruthy();
    expect(
      getByText("다음 단계에서 그룹을 만들거나 친구의 초대 코드를 입력하세요."),
    ).toBeTruthy();
    expect(getByText("사용 통계 공유 (선택)")).toBeTruthy();
    expect(
      getByText("개인을 직접 식별하지 않는 앱 이용 통계를 수집해 서비스 개선에 사용해요."),
    ).toBeTruthy();
    expect(getByLabelText("사용 통계 공유에 동의합니다")).toBeTruthy();
    expect(getByText("자세히 보기")).toBeTruthy();
  });

  it("emits onboarding.screen.dwell_ms with screen 1 on the S1→S2 forward advance", async () => {
    const { getByText } = await renderReady();
    fireEvent.press(getByText("다음"));
    expect(mockCapture).toHaveBeenCalledWith(
      "onboarding.screen.dwell_ms",
      expect.objectContaining({ screen: 1, dwellMs: expect.any(Number) }),
    );
  });

  it("does NOT emit a dwell event on backward navigation", async () => {
    const { getByText } = await renderReady();
    fireEvent.press(getByText("다음"));
    mockCapture.mockClear();
    fireEvent.press(getByText("이전"));
    expect(mockCapture).not.toHaveBeenCalled();
  });

  it("redirects immediately when onboarding is already completed", async () => {
    mockGetState.mockResolvedValue({
      version: 1,
      completedAt: "2026-06-09T21:00:00.000Z",
      deferredDestination: null,
    });
    render(<OnboardingScreen />);
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith("/today"));
    expect(mockSetConsent).not.toHaveBeenCalled();
    expect(mockMarkCompleted).not.toHaveBeenCalled();
  });

  it("시작하기 with the checkbox unchecked records opt_out then completes", async () => {
    const { getByText } = await renderReady();
    pressNextTimes(getByText, 4);
    fireEvent.press(getByText("시작하기"));

    await waitFor(() => {
      expect(mockSetConsent).toHaveBeenCalledWith("opt_out");
      expect(mockCapture).toHaveBeenCalledWith("onboarding.completed");
      expect(mockMarkCompleted).toHaveBeenCalledWith(null);
      expect(mockReplace).toHaveBeenCalledWith("/today");
    });

    const completedIndex = mockCapture.mock.calls.findIndex(
      ([name]) => name === "onboarding.completed",
    );
    expect(mockCapture.mock.invocationCallOrder[completedIndex]).toBeGreaterThan(
      mockSetConsent.mock.invocationCallOrder[0],
    );
  });

  it("시작하기 with the checkbox checked records opt_in", async () => {
    const { getByText, getByLabelText } = await renderReady();
    fireEvent(getByLabelText("사용 통계 공유에 동의합니다"), "valueChange", true);
    pressNextTimes(getByText, 4);
    fireEvent.press(getByText("시작하기"));

    await waitFor(() => {
      expect(mockSetConsent).toHaveBeenCalledWith("opt_in");
      expect(mockReplace).toHaveBeenCalledWith("/today");
    });
  });

  it("S5 room preview shows the deeplink-joined room when the lookup succeeds", async () => {
    mockGetState.mockResolvedValue(partialState("/rooms/42/settings?onboarding=1"));
    mockUseRoomsQuery.mockReturnValue(roomsResult([ROOM_42]));
    mockUseRoomMembersQuery.mockReturnValue(
      membersResult([{ userId: 1 }, { userId: 2 }, { userId: 3 }]),
    );

    const { getByText, queryByText } = await renderReady();
    expect(getByText("같이 살기")).toBeTruthy();
    expect(getByText("목표 15일")).toBeTruthy();
    expect(getByText("3명 함께 살아남는 중")).toBeTruthy();
    expect(
      queryByText("다음 단계에서 그룹을 만들거나 친구의 초대 코드를 입력하세요."),
    ).toBeNull();
  });

  it("S5 room preview falls through to the default copy when the lookup fails", async () => {
    mockGetState.mockResolvedValue(partialState("/rooms/42/settings?onboarding=1"));
    mockUseRoomsQuery.mockReturnValue(
      roomsResult([{ ...ROOM_42, id: 7, name: "다른 방" }]),
    );

    const { getByText, queryByText } = await renderReady();
    expect(
      getByText("다음 단계에서 그룹을 만들거나 친구의 초대 코드를 입력하세요."),
    ).toBeTruthy();
    expect(queryByText("다른 방")).toBeNull();
  });
});
