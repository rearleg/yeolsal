// Story 5.2 — LeaderTransferPicker behavioral test (6+ cases per AC17).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { ApiError } from "../../../api/client";
import * as roomsApi from "../../../api/rooms";
import { setToastBridge } from "../../../lib/toast";
import { LeaderTransferPicker } from "../LeaderTransferPicker";

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
    listMembers: jest.fn(),
    transferLeadership: jest.fn(),
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
const listMembersMock = roomsApi.listMembers as jest.MockedFunction<
  typeof roomsApi.listMembers
>;
const transferLeadershipMock = roomsApi.transferLeadership as jest.MockedFunction<
  typeof roomsApi.transferLeadership
>;

const ROOM_ID = 7;
const LEADER_ID = 1;
const TARGET_ID = 2;
const RED_ID = 3;
const SPECTATOR_ID = 4;

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

function seedLeaderViewer() {
  useAuth.mockReturnValue({
    user: {
      id: LEADER_ID,
      email: "leader@example.com",
      nickname: "Leader",
      timezone: "Asia/Seoul",
    },
    loading: false,
  });
}

function seedNonLeaderViewer() {
  useAuth.mockReturnValue({
    user: {
      id: TARGET_ID,
      email: "target@example.com",
      nickname: "Target",
      timezone: "Asia/Seoul",
    },
    loading: false,
  });
}

function seedListRooms() {
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

function seedMembers() {
  listMembersMock.mockResolvedValue([
    {
      roomId: ROOM_ID,
      userId: LEADER_ID,
      nickname: "Leader",
      role: "OWNER",
      currentMinimum: 10,
      warningCount: 0,
      survivalStatus: "ACTIVE",
    },
    {
      roomId: ROOM_ID,
      userId: TARGET_ID,
      nickname: "Target",
      role: "MEMBER",
      currentMinimum: 10,
      warningCount: 0,
      survivalStatus: "ACTIVE",
    },
    {
      roomId: ROOM_ID,
      userId: RED_ID,
      nickname: "Red",
      role: "MEMBER",
      currentMinimum: 10,
      warningCount: 0,
      survivalStatus: "RED",
    },
    {
      roomId: ROOM_ID,
      userId: SPECTATOR_ID,
      nickname: "Spectator",
      role: "MEMBER",
      currentMinimum: 10,
      warningCount: 0,
      survivalStatus: "SPECTATOR",
    },
  ]);
}

describe("LeaderTransferPicker", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    seedListRooms();
    seedMembers();
  });

  afterEach(() => {
    setToastBridge(null);
  });

  it("leader sees the current leader + eligible members only (excludes self + RED + SPECTATOR)", async () => {
    seedLeaderViewer();
    const client = makeClient();
    render(<LeaderTransferPicker roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    await screen.findByText("양도할 멤버 선택");
    expect(screen.getByText("Target")).toBeTruthy();
    expect(screen.queryByText("Red")).toBeNull();
    expect(screen.queryByText("Spectator")).toBeNull();
    expect(screen.getAllByText("Leader").length).toBe(1);
  });

  it("non-leader sees the same list as read-only (caption shown)", async () => {
    seedNonLeaderViewer();
    const client = makeClient();
    render(<LeaderTransferPicker roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    await screen.findByText("양도할 멤버 선택");
    expect(screen.getByText("방장 양도는 방장만 할 수 있어요.")).toBeTruthy();
    expect(screen.queryByLabelText("Target에게 방장 양도")).toBeNull();
  });

  it("tapping a member opens the confirm modal with nickname interpolation", async () => {
    seedLeaderViewer();
    const client = makeClient();
    render(<LeaderTransferPicker roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    fireEvent.press(await screen.findByText("Target"));
    await waitFor(() => {
      expect(
        screen.getByText(
          "Target님에게 방장을 양도할까요? 양도 후에는 본인이 되돌릴 수 없어요.",
        ),
      ).toBeTruthy();
    });
  });

  it("confirm CTA invokes transferLeadership, shows success toast, and navigates back", async () => {
    seedLeaderViewer();
    transferLeadershipMock.mockResolvedValueOnce({
      id: ROOM_ID,
      name: "Test Room",
      ownerId: TARGET_ID,
      maxMembers: 12,
      minDailyGoalDays: 10,
      createdAt: "2026-03-01T00:00:00Z",
      pendingMaxMembers: null,
      pendingMaxMembersEffectiveFromMonth: null,
    });
    const toastCalls = makeToastSpy();
    const client = makeClient();
    render(<LeaderTransferPicker roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    fireEvent.press(await screen.findByText("Target"));
    await screen.findByText(
      "Target님에게 방장을 양도할까요? 양도 후에는 본인이 되돌릴 수 없어요.",
    );
    fireEvent.press(screen.getByText("양도하기"));

    await waitFor(() => {
      expect(transferLeadershipMock).toHaveBeenCalledWith(ROOM_ID, {
        targetUserId: TARGET_ID,
      });
    });
    await waitFor(() =>
      expect(toastCalls).toContainEqual({
        variant: "success",
        message: "Target님에게 방장을 양도했어요.",
      }),
    );
    expect(mockRouterBack).toHaveBeenCalledTimes(1);
  });

  it("shows default ApiError messages for unmatched server errors", async () => {
    seedLeaderViewer();
    transferLeadershipMock.mockRejectedValueOnce(
      new ApiError(500, "INTERNAL_ERROR", "내부 오류가 발생했습니다."),
    );
    const toastCalls = makeToastSpy();
    const client = makeClient();
    render(<LeaderTransferPicker roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    fireEvent.press(await screen.findByText("Target"));
    await screen.findByText("양도하기");
    fireEvent.press(screen.getByText("양도하기"));

    await waitFor(() =>
      expect(toastCalls).toContainEqual({
        variant: "danger",
        message: "내부 오류가 발생했습니다.",
      }),
    );
    expect(mockRouterBack).not.toHaveBeenCalled();
  });

  it("closes any pending confirmation when roomId changes", async () => {
    seedLeaderViewer();
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
        maxMembers: 12,
        minDailyGoalDays: 10,
        createdAt: "2026-03-01T00:00:00Z",
        pendingMaxMembers: null,
        pendingMaxMembersEffectiveFromMonth: null,
      },
    ]);
    const client = makeClient();
    const rendered = render(<LeaderTransferPicker roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    fireEvent.press(await screen.findByText("Target"));
    await screen.findByText(
      "Target님에게 방장을 양도할까요? 양도 후에는 본인이 되돌릴 수 없어요.",
    );

    rendered.rerender(<LeaderTransferPicker roomId={8} />);

    await waitFor(() =>
      expect(
        screen.queryByText(
          "Target님에게 방장을 양도할까요? 양도 후에는 본인이 되돌릴 수 없어요.",
        ),
      ).toBeNull(),
    );
  });

  it("cancel CTA closes the modal without invoking the mutation", async () => {
    seedLeaderViewer();
    const client = makeClient();
    render(<LeaderTransferPicker roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });

    fireEvent.press(await screen.findByText("Target"));
    await screen.findByText("양도하기");
    fireEvent.press(screen.getByText("취소"));

    await waitFor(() => {
      expect(transferLeadershipMock).not.toHaveBeenCalled();
    });
  });

  it("avoids the brand-voice AVOID lexicon (no `탈락` / `실패` etc. in rendered output)", async () => {
    seedLeaderViewer();
    const client = makeClient();
    const { toJSON } = render(<LeaderTransferPicker roomId={ROOM_ID} />, {
      wrapper: makeWrapper(client),
    });
    await screen.findByText("양도할 멤버 선택");
    const rendered = JSON.stringify(toJSON());
    expect(rendered).not.toMatch(/탈락|실패|패배|벌금|꼴찌|손해|낙오/);
  });
});
