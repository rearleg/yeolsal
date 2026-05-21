// Story 3.3 FE-12 — FriendGiftBadge component tests (7 cases per AC7).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as targetsApi from "../../../api/friendGiftTargets";
import * as survivalApi from "../../../api/survival";
import type { MeSurvivalEntry } from "../../../lib/spectator";
import { FriendGiftBadge } from "../FriendGiftBadge";

jest.mock("../../../api/friendGiftTargets", () => ({
  getFriendGiftTargets: jest.fn(),
}));
jest.mock("../../../api/survival", () => ({
  getMeSurvival: jest.fn(),
}));

const getTargetsMock = targetsApi.getFriendGiftTargets as jest.MockedFunction<
  typeof targetsApi.getFriendGiftTargets
>;
const getMeSurvivalMock = survivalApi.getMeSurvival as jest.MockedFunction<
  typeof survivalApi.getMeSurvival
>;

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

const ROOM_ID = 42;

const survival = (personalPoints: number): MeSurvivalEntry => ({
  roomId: ROOM_ID,
  roomName: "Room",
  status: "ACTIVE",
  personalPoints,
  roomPointPool: 0,
  freeRevivalTicketUsed: false,
});

const targets = (eligibleCount: number) => [
  {
    roomId: ROOM_ID,
    roomName: "Room",
    eligibleCount,
    friends: Array.from({ length: eligibleCount }, (_, i) => ({
      userId: 100 + i,
      nickname: `Friend${i + 1}`,
      status: "RED" as const,
      eliminatedAt: "2026-05-18T03:14:15Z",
    })),
  },
];

describe("FriendGiftBadge", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("renders nothing when no eligible target for the room", async () => {
    getTargetsMock.mockResolvedValue([]);
    getMeSurvivalMock.mockResolvedValue([survival(10)]);
    const { toJSON } = render(
      <FriendGiftBadge roomId={ROOM_ID} onTap={jest.fn()} />,
      { wrapper: makeWrapper(makeClient()) },
    );
    await waitFor(() => expect(getTargetsMock).toHaveBeenCalled());
    expect(toJSON()).toBeNull();
  });

  it('renders "친구 회생 대기 (1)" when N = 1', async () => {
    getTargetsMock.mockResolvedValue(targets(1));
    getMeSurvivalMock.mockResolvedValue([survival(10)]);
    render(<FriendGiftBadge roomId={ROOM_ID} onTap={jest.fn()} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(screen.getByText("친구 회생 대기 (1)")).toBeTruthy(),
    );
  });

  it('renders "친구 회생 대기 (3)" when N = 3', async () => {
    getTargetsMock.mockResolvedValue(targets(3));
    getMeSurvivalMock.mockResolvedValue([survival(10)]);
    render(<FriendGiftBadge roomId={ROOM_ID} onTap={jest.fn()} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(screen.getByText("친구 회생 대기 (3)")).toBeTruthy(),
    );
  });

  it("shows insufficient caption + does not fire onTap when balance < 5", async () => {
    getTargetsMock.mockResolvedValue(targets(1));
    getMeSurvivalMock.mockResolvedValue([survival(4)]);
    const onTap = jest.fn();
    render(<FriendGiftBadge roomId={ROOM_ID} onTap={onTap} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(screen.getByText("잔액 부족 (5점 필요)")).toBeTruthy(),
    );
    const pressable = screen.getByLabelText(
      "친구 회생 대기 1명, 탭하면 회생권 선물 화면이 열려요",
    );
    fireEvent.press(pressable);
    expect(onTap).not.toHaveBeenCalled();
  });

  it("N=1 + balance ≥ 5 — tap dispatches onTap with the receiver tuple", async () => {
    getTargetsMock.mockResolvedValue(targets(1));
    getMeSurvivalMock.mockResolvedValue([survival(10)]);
    const onTap = jest.fn();
    render(<FriendGiftBadge roomId={ROOM_ID} onTap={onTap} />, {
      wrapper: makeWrapper(makeClient()),
    });
    const pressable = await screen.findByLabelText(
      "친구 회생 대기 1명, 탭하면 회생권 선물 화면이 열려요",
    );
    fireEvent.press(pressable);
    expect(onTap).toHaveBeenCalledWith({
      roomId: ROOM_ID,
      receiverUserId: 100,
      receiverNickname: "Friend1",
    });
  });

  it("N=2 + balance ≥ 5 — tap dispatches onTapMulti with the room summary", async () => {
    getTargetsMock.mockResolvedValue(targets(2));
    getMeSurvivalMock.mockResolvedValue([survival(10)]);
    const onTap = jest.fn();
    const onTapMulti = jest.fn();
    render(
      <FriendGiftBadge
        roomId={ROOM_ID}
        onTap={onTap}
        onTapMulti={onTapMulti}
      />,
      { wrapper: makeWrapper(makeClient()) },
    );
    const pressable = await screen.findByLabelText(
      "친구 회생 대기 2명, 탭하면 회생권 선물 화면이 열려요",
    );
    fireEvent.press(pressable);
    expect(onTap).not.toHaveBeenCalled();
    expect(onTapMulti).toHaveBeenCalledWith(
      expect.objectContaining({ roomId: ROOM_ID, eligibleCount: 2 }),
    );
  });

  it("accessibilityLabel includes the count and the tappable hint", async () => {
    getTargetsMock.mockResolvedValue(targets(5));
    getMeSurvivalMock.mockResolvedValue([survival(10)]);
    render(<FriendGiftBadge roomId={ROOM_ID} onTap={jest.fn()} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(
        screen.getByLabelText(
          "친구 회생 대기 5명, 탭하면 회생권 선물 화면이 열려요",
        ),
      ).toBeTruthy(),
    );
  });
});
