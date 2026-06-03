// Story 5.2 — RoomMemberCapEditor behavioral test (6+ cases per AC17).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { ApiError } from "../../../api/client";
import * as roomsApi from "../../../api/rooms";
import { setToastBridge } from "../../../lib/toast";
import { RoomMemberCapEditor } from "../RoomMemberCapEditor";

const mockRouterBack = jest.fn();

jest.mock("expo-router", () => ({
  router: { back: () => mockRouterBack() },
}));

jest.mock("../../../api/rooms", () => {
  const actual = jest.requireActual("../../../api/rooms") as Record<string, unknown>;
  return {
    __esModule: true,
    ...actual,
    listRooms: jest.fn(),
    updateMemberCap: jest.fn(),
  };
});

jest.mock("../../../auth/AuthContext", () => ({
  __esModule: true,
  useAuth: jest.fn(),
}));

jest.mock("../../../hooks/useHaptics", () => ({
  useHaptic: () => jest.fn(),
}));

const { useAuth } = jest.requireMock("../../../auth/AuthContext") as {
  useAuth: jest.Mock;
};

const listRoomsMock = roomsApi.listRooms as jest.MockedFunction<
  typeof roomsApi.listRooms
>;
const updateMemberCapMock = roomsApi.updateMemberCap as jest.MockedFunction<
  typeof roomsApi.updateMemberCap
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

function seedLeader(
  pendingMaxMembers: number | null = null,
  pendingMonth: string | null = null,
) {
  useAuth.mockReturnValue({
    user: {
      id: LEADER_ID,
      email: "leader@example.com",
      nickname: "Leader",
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
      pendingMaxMembers,
      pendingMaxMembersEffectiveFromMonth: pendingMonth,
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
      pendingMaxMembers: null,
      pendingMaxMembersEffectiveFromMonth: null,
    },
  ]);
}

describe("RoomMemberCapEditor", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    setToastBridge(null);
  });

  it("leader sees current cap, stepper, and Save CTA", async () => {
    seedLeader();
    const client = makeClient();
    render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    expect(await screen.findByText("이번 달 정원")).toBeTruthy();
    expect(screen.getByText("12명")).toBeTruthy();
    expect(screen.getByLabelText("정원 증가")).toBeTruthy();
    expect(screen.getByLabelText("정원 감소")).toBeTruthy();
    expect(screen.getByText("다음 달부터 적용하기")).toBeTruthy();
  });

  it("non-leader sees read-only view (no stepper / no Save CTA)", async () => {
    seedNonLeader();
    const client = makeClient();
    render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    expect(await screen.findByText("이번 달 정원")).toBeTruthy();
    expect(screen.queryByLabelText("정원 증가")).toBeNull();
    expect(screen.queryByLabelText("정원 감소")).toBeNull();
    expect(screen.queryByText("다음 달부터 적용하기")).toBeNull();
    expect(screen.getByText("정원 변경은 방장만 할 수 있어요.")).toBeTruthy();
  });

  it("renders the AC18-locked preview literal verbatim (byte-identical)", async () => {
    seedLeader();
    const client = makeClient();
    render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    expect(
      await screen.findByText("변경된 정원은 다음 달 1일부터 적용됩니다."),
    ).toBeTruthy();
  });

  it("Stepper updates the displayed cap after pressing increase", async () => {
    seedLeader();
    const client = makeClient();
    render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    await screen.findByText("다음 달부터 적용하기");
    fireEvent.press(screen.getByLabelText("정원 증가"));
    await waitFor(() => {
      expect(screen.getByText("13")).toBeTruthy();
    });
  });

  it("renders the pending row when room.pendingMaxMembers is set", async () => {
    seedLeader(20, "2026-05");
    const client = makeClient();
    render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    expect(await screen.findByText("다음 달 적용 예정")).toBeTruthy();
    expect(screen.getByText("20명 (2026년 5월부터)")).toBeTruthy();
  });

  it("Save calls updateMemberCap, shows success toast, and navigates back", async () => {
    seedLeader();
    updateMemberCapMock.mockResolvedValueOnce({
      id: ROOM_ID,
      name: "Test Room",
      ownerId: LEADER_ID,
      maxMembers: 12,
      minDailyGoalDays: 10,
      createdAt: "2026-03-01T00:00:00Z",
      pendingMaxMembers: 13,
      pendingMaxMembersEffectiveFromMonth: "2026-05",
    });
    const toastCalls = makeToastSpy();
    const client = makeClient();
    render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    await screen.findByText("다음 달부터 적용하기");
    fireEvent.press(screen.getByLabelText("정원 증가"));
    fireEvent.press(screen.getByText("다음 달부터 적용하기"));

    await waitFor(() => {
      expect(updateMemberCapMock).toHaveBeenCalledWith(ROOM_ID, {
        maxMembers: 13,
      });
    });
    await waitFor(() =>
      expect(toastCalls).toContainEqual({
        variant: "success",
        message: "다음 달부터 새 정원으로 시작해요.",
      }),
    );
    expect(mockRouterBack).toHaveBeenCalledTimes(1);
  });

  it("compares Save against the staged pending cap so the leader can revert it", async () => {
    seedLeader(13, "2026-05");
    updateMemberCapMock.mockResolvedValueOnce({
      id: ROOM_ID,
      name: "Test Room",
      ownerId: LEADER_ID,
      maxMembers: 12,
      minDailyGoalDays: 10,
      createdAt: "2026-03-01T00:00:00Z",
      pendingMaxMembers: 12,
      pendingMaxMembersEffectiveFromMonth: "2026-05",
    });
    const client = makeClient();
    render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    await waitFor(() =>
      expect(screen.getByLabelText("다음 달부터 적용하기").props.accessibilityState.disabled).toBe(
        true,
      ),
    );
    fireEvent.press(screen.getByLabelText("정원 감소"));
    fireEvent.press(screen.getByText("다음 달부터 적용하기"));

    await waitFor(() => {
      expect(updateMemberCapMock).toHaveBeenCalledWith(ROOM_ID, {
        maxMembers: 12,
      });
    });
  });

  it("uses default ApiError messages for unmatched server errors", async () => {
    seedLeader();
    updateMemberCapMock.mockRejectedValueOnce(
      new ApiError(500, "INTERNAL_ERROR", "내부 오류가 발생했습니다."),
    );
    const toastCalls = makeToastSpy();
    const client = makeClient();
    render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    await screen.findByText("다음 달부터 적용하기");
    fireEvent.press(screen.getByLabelText("정원 증가"));
    fireEvent.press(screen.getByText("다음 달부터 적용하기"));

    await waitFor(() =>
      expect(toastCalls).toContainEqual({
        variant: "danger",
        message: "내부 오류가 발생했습니다.",
      }),
    );
    expect(mockRouterBack).not.toHaveBeenCalled();
  });

  it("resets draft cap when roomId changes", async () => {
    useAuth.mockReturnValue({
      user: {
        id: LEADER_ID,
        email: "leader@example.com",
        nickname: "Leader",
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
        pendingMaxMembers: null,
        pendingMaxMembersEffectiveFromMonth: null,
      },
      {
        id: 8,
        name: "Other Room",
        ownerId: LEADER_ID,
        maxMembers: 20,
        minDailyGoalDays: 10,
        createdAt: "2026-03-01T00:00:00Z",
        pendingMaxMembers: null,
        pendingMaxMembersEffectiveFromMonth: null,
      },
    ]);
    const client = makeClient();
    const rendered = render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    await screen.findByText("다음 달부터 적용하기");
    fireEvent.press(screen.getByLabelText("정원 증가"));
    await waitFor(() => expect(screen.getByText("13")).toBeTruthy());

    rendered.rerender(<RoomMemberCapEditor roomId={8} />);

    await waitFor(() => expect(screen.getByText("20")).toBeTruthy());
  });

  it("avoids brand-voice AVOID lexicon in rendered output (no 탈락 / 실패 / 패배)", async () => {
    seedLeader();
    const client = makeClient();
    const { toJSON } = render(<RoomMemberCapEditor roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });
    await screen.findByText("이번 달 정원");
    const rendered = JSON.stringify(toJSON());
    expect(rendered).not.toMatch(/탈락|실패|패배|벌금|꼴찌|손해|낙오/);
  });
});
