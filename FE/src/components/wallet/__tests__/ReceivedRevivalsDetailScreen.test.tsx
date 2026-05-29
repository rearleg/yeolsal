// Story 3.4 FE-12 — ReceivedRevivalsDetailScreen component tests
// (AC8: 4 cases).
//
// Asserts:
//   1. Empty list renders empty-state copy.
//   2. FRIEND_GIFT row shows donor nickname.
//   3. FREE_TICKET / PERSONAL_POINTS rows do NOT show donor nickname.
//   4. List ordering DESC (pass-through from BE).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { SafeAreaProvider } from "react-native-safe-area-context";
import * as walletApi from "../../../api/wallet";
import type { ReceivedRevivalDto } from "../../../api/wallet";
import { ReceivedRevivalsDetailScreen } from "../ReceivedRevivalsDetailScreen";

jest.mock("../../../api/wallet", () => ({
  getPersonalPointsLedger: jest.fn(),
  getReceivedRevivals: jest.fn(),
}));

const getReceivedMock = walletApi.getReceivedRevivals as jest.MockedFunction<
  typeof walletApi.getReceivedRevivals
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
    return (
      <SafeAreaProvider
        initialMetrics={{
          frame: { x: 0, y: 0, width: 320, height: 640 },
          insets: { top: 0, left: 0, right: 0, bottom: 0 },
        }}
      >
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </SafeAreaProvider>
    );
  };
}

const ROOM_ID = 42;

describe("ReceivedRevivalsDetailScreen", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("empty list renders empty-state copy", async () => {
    getReceivedMock.mockResolvedValue([]);
    render(<ReceivedRevivalsDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(
        screen.getByText("이 방에서 받은 회생권이 아직 없어요"),
      ).toBeTruthy(),
    );
  });

  it("FRIEND_GIFT row shows donor nickname", async () => {
    const data: readonly ReceivedRevivalDto[] = [
      {
        revivalEventId: 1003,
        roomId: ROOM_ID,
        roomName: "Room",
        source: "FRIEND_GIFT",
        donorUserId: 99,
        donorNickname: "정민",
        occurredAt: "2026-05-22T02:00:00Z",
      },
    ];
    getReceivedMock.mockResolvedValue(data);
    render(<ReceivedRevivalsDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(screen.getByText("정민님이 보낸 회생권")).toBeTruthy(),
    );
    expect(screen.getByText("친구의 선물")).toBeTruthy();
  });

  it("FREE_TICKET / PERSONAL_POINTS rows do NOT show donor nickname", async () => {
    const data: readonly ReceivedRevivalDto[] = [
      {
        revivalEventId: 2001,
        roomId: ROOM_ID,
        roomName: "Room",
        source: "FREE_TICKET",
        donorUserId: null,
        donorNickname: null,
        occurredAt: "2026-05-22T01:00:00Z",
      },
      {
        revivalEventId: 2002,
        roomId: ROOM_ID,
        roomName: "Room",
        source: "PERSONAL_POINTS",
        donorUserId: null,
        donorNickname: null,
        occurredAt: "2026-05-22T00:00:00Z",
      },
    ];
    getReceivedMock.mockResolvedValue(data);
    render(<ReceivedRevivalsDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(screen.getByText("스스로 회생")).toBeTruthy());
    expect(screen.getByText("내 포인트 3점 사용")).toBeTruthy();
    expect(screen.queryByText(/님이 보낸 회생권/)).toBeNull();
  });

  it("list ordering DESC pass-through (FRIEND_GIFT first when given first)", async () => {
    const data: readonly ReceivedRevivalDto[] = [
      {
        revivalEventId: 3003,
        roomId: ROOM_ID,
        roomName: "Room",
        source: "FRIEND_GIFT",
        donorUserId: 99,
        donorNickname: "정민",
        occurredAt: "2026-05-22T02:00:00Z",
      },
      {
        revivalEventId: 3002,
        roomId: ROOM_ID,
        roomName: "Room",
        source: "PERSONAL_POINTS",
        donorUserId: null,
        donorNickname: null,
        occurredAt: "2026-05-22T01:00:00Z",
      },
      {
        revivalEventId: 3001,
        roomId: ROOM_ID,
        roomName: "Room",
        source: "FREE_TICKET",
        donorUserId: null,
        donorNickname: null,
        occurredAt: "2026-05-22T00:00:00Z",
      },
    ];
    getReceivedMock.mockResolvedValue(data);
    render(<ReceivedRevivalsDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(screen.getByText("친구의 선물")).toBeTruthy());
    expect(screen.getByText("포인트로 회생")).toBeTruthy();
    expect(screen.getByText("무료 회생권")).toBeTruthy();
  });
});
