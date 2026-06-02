// Story 5.1 — RoomRuleEditor behavioral test (5 cases per AC13).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as roomsApi from "../../../api/rooms";
import { setToastBridge } from "../../../lib/toast";
import { RoomRuleEditor } from "../RoomRuleEditor";

jest.mock("../../../api/rooms", () => ({
  __esModule: true,
  listRooms: jest.fn(),
  getRoomRule: jest.fn(),
  updateRoomRule: jest.fn(),
}));

jest.mock("../../../auth/AuthContext", () => ({
  __esModule: true,
  useAuth: jest.fn(),
}));

const { useAuth } = jest.requireMock("../../../auth/AuthContext") as {
  useAuth: jest.Mock;
};

const listRoomsMock = roomsApi.listRooms as jest.MockedFunction<typeof roomsApi.listRooms>;
const getRoomRuleMock = roomsApi.getRoomRule as jest.MockedFunction<typeof roomsApi.getRoomRule>;
const updateRoomRuleMock = roomsApi.updateRoomRule as jest.MockedFunction<
  typeof roomsApi.updateRoomRule
>;

const ROOM_ID = 7;
const LEADER_ID = 1;
const NON_LEADER_ID = 99;

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, gcTime: 60_000 },
      mutations: { retry: false },
    },
  });
}

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function makeToastSpy() {
  const calls: Array<{ variant: string; message: string }> = [];
  setToastBridge({ show: (variant, message) => calls.push({ variant, message }) });
  return calls;
}

function seedLeader() {
  useAuth.mockReturnValue({
    user: { id: LEADER_ID, email: "leader@example.com", nickname: "Leader", timezone: "Asia/Seoul" },
    loading: false,
  });
  listRoomsMock.mockResolvedValue([
    {
      id: ROOM_ID,
      name: "Test Room",
      ownerId: LEADER_ID,
      maxMembers: 12,
      minDailyGoalDays: 10,
      createdAt: "2026-03-01T00:00:00Z",
    },
  ]);
}

function seedNonLeader() {
  useAuth.mockReturnValue({
    user: {
      id: NON_LEADER_ID,
      email: "member@example.com",
      nickname: "Member",
      timezone: "Asia/Seoul",
    },
    loading: false,
  });
  listRoomsMock.mockResolvedValue([
    {
      id: ROOM_ID,
      name: "Test Room",
      ownerId: LEADER_ID,
      maxMembers: 12,
      minDailyGoalDays: 10,
      createdAt: "2026-03-01T00:00:00Z",
    },
  ]);
}

function ruleState(weekendInclude: boolean, pendingWeekendInclude?: boolean) {
  const pending =
    pendingWeekendInclude == null
      ? null
      : {
          id: 700,
          preset: "DAILY_UPDATE" as const,
          weekendInclude: pendingWeekendInclude,
          effectiveFromMonth: "2026-05",
          createdByUserId: LEADER_ID,
          createdAt: "2026-04-15T00:00:00Z",
        };
  return {
    current: {
      id: 500,
      preset: "DAILY_UPDATE" as const,
      weekendInclude,
      effectiveFromMonth: "2026-04",
      createdByUserId: LEADER_ID,
      createdAt: "2026-04-01T00:00:00Z",
    },
    pending,
  };
}

function seedRule(weekendInclude: boolean, pendingWeekendInclude?: boolean) {
  getRoomRuleMock.mockResolvedValue({
    ...ruleState(weekendInclude, pendingWeekendInclude),
  });
}

const AVOID_LEXICON = ["벌금", "실패", "패배", "낙오", "탈락", "꼴찌", "손해"];

describe("RoomRuleEditor", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    setToastBridge(null);
  });

  it("leader sees the weekend toggle and the Save CTA", async () => {
    seedLeader();
    seedRule(true);
    render(<RoomRuleEditor roomId={ROOM_ID} />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() => expect(screen.getByLabelText("주말 포함 여부")).toBeTruthy());
    expect(screen.getByText("다음 달부터 적용하기")).toBeTruthy();
  });

  it("non-leader sees the read-only note (no editor toggle, no Save CTA)", async () => {
    seedNonLeader();
    seedRule(true);
    render(<RoomRuleEditor roomId={ROOM_ID} />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByText("규칙 변경은 방장만 할 수 있어요.")).toBeTruthy(),
    );
    expect(screen.queryByLabelText("주말 포함 여부")).toBeNull();
    expect(screen.queryByText("다음 달부터 적용하기")).toBeNull();
  });

  it("renders the preview literal byte-identical to the PRD lock", async () => {
    seedLeader();
    seedRule(true);
    render(<RoomRuleEditor roomId={ROOM_ID} />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByText("변경된 규칙은 다음 달 1일부터 적용됩니다.")).toBeTruthy(),
    );
  });

  it("Save flow calls updateRoomRule with the toggled value and shows the success toast", async () => {
    seedLeader();
    seedRule(true);
    updateRoomRuleMock.mockResolvedValue({
      id: 1001,
      preset: "DAILY_UPDATE",
      weekendInclude: false,
      effectiveFromMonth: "2026-05",
      createdByUserId: LEADER_ID,
      createdAt: "2026-04-15T03:14:00Z",
    });
    const toastCalls = makeToastSpy();
    render(<RoomRuleEditor roomId={ROOM_ID} />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() => expect(screen.getByLabelText("주말 포함 여부")).toBeTruthy());
    fireEvent(screen.getByLabelText("주말 포함 여부"), "valueChange", false);
    fireEvent.press(screen.getByText("다음 달부터 적용하기"));

    await waitFor(() =>
      expect(updateRoomRuleMock).toHaveBeenCalledWith(ROOM_ID, {
        preset: "DAILY_UPDATE",
        weekendInclude: false,
      }),
    );
    await waitFor(() =>
      expect(toastCalls).toContainEqual({
        variant: "success",
        message: "다음 달부터 새 규칙으로 시작해요.",
      }),
    );
  });

  it("compares Save against the staged pending value so the leader can revert it", async () => {
    seedLeader();
    seedRule(true, false);
    updateRoomRuleMock.mockResolvedValue({
      ...ruleState(true).current,
      id: 1001,
      effectiveFromMonth: "2026-05",
    });
    render(<RoomRuleEditor roomId={ROOM_ID} />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByLabelText("다음 달부터 적용하기").props.accessibilityState.disabled).toBe(
        true,
      ),
    );
    fireEvent(screen.getByLabelText("주말 포함 여부"), "valueChange", true);
    fireEvent.press(screen.getByLabelText("다음 달부터 적용하기"));

    await waitFor(() =>
      expect(updateRoomRuleMock).toHaveBeenCalledWith(ROOM_ID, {
        preset: "DAILY_UPDATE",
        weekendInclude: true,
      }),
    );
  });

  it("preserves an unsaved toggle edit across a background rule refetch", async () => {
    seedLeader();
    seedRule(true);
    const client = makeClient();
    render(<RoomRuleEditor roomId={ROOM_ID} />, { wrapper: makeWrapper(client) });

    await waitFor(() => expect(screen.getByLabelText("주말 포함 여부")).toBeTruthy());
    fireEvent(screen.getByLabelText("주말 포함 여부"), "valueChange", false);
    getRoomRuleMock.mockResolvedValue({
      ...ruleState(true),
      current: { ...ruleState(true).current, createdAt: "2026-04-01T00:01:00Z" },
    });
    await client.invalidateQueries({ queryKey: ["roomRule", ROOM_ID] });

    await waitFor(() =>
      expect(screen.getByLabelText("주말 포함 여부").props.value).toBe(false),
    );
  });

  it("shows an error state when room ownership cannot be loaded", async () => {
    useAuth.mockReturnValue({
      user: { id: LEADER_ID, email: "leader@example.com", nickname: "Leader", timezone: "Asia/Seoul" },
      loading: false,
    });
    listRoomsMock.mockRejectedValue(new Error("network down"));
    seedRule(true);
    render(<RoomRuleEditor roomId={ROOM_ID} />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByText("규칙 정보를 불러올 수 없어요.")).toBeTruthy(),
    );
    expect(screen.queryByText("규칙 변경은 방장만 할 수 있어요.")).toBeNull();
  });

  it("brand-voice — none of the AVOID lexicon strings appear in the editor output", async () => {
    seedLeader();
    seedRule(true);
    const { toJSON } = render(<RoomRuleEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(screen.getByLabelText("주말 포함 여부")).toBeTruthy());

    const dump = JSON.stringify(toJSON());
    for (const word of AVOID_LEXICON) {
      expect(dump).not.toContain(word);
    }
  });
});
