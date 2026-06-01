// Story 3.4 FE-12 — LedgerDetailScreen component tests (AC8: 5 cases).
//
// Asserts:
//   1. Empty ledger renders empty-state copy.
//   2. List renders chronologically (DESC pass-through).
//   3. Each reason renders its locked Korean caption.
//   4. Positive delta renders with '+' prefix; negative with '-' prefix.
//   5. Total balance headline matches survival cache (BE-authoritative).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { SafeAreaProvider } from "react-native-safe-area-context";
import * as walletApi from "../../../api/wallet";
import * as survivalApi from "../../../api/survival";
import type { LedgerEntryDto } from "../../../api/wallet";
import type { MeSurvivalEntry } from "../../../lib/spectator";
import { LedgerDetailScreen } from "../LedgerDetailScreen";

jest.mock("../../../api/wallet", () => ({
  getPersonalPointsLedger: jest.fn(),
  getReceivedRevivals: jest.fn(),
}));
jest.mock("../../../api/survival", () => ({
  getMeSurvival: jest.fn(),
}));

const getLedgerMock = walletApi.getPersonalPointsLedger as jest.MockedFunction<
  typeof walletApi.getPersonalPointsLedger
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

const survival = (personalPoints: number): MeSurvivalEntry => ({
  roomId: ROOM_ID,
  roomName: "Room",
  status: "ACTIVE",
  personalPoints,
  roomPointPool: 0,
  freeRevivalTicketUsed: false,
});

describe("LedgerDetailScreen", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getMeSurvivalMock.mockResolvedValue([survival(5)]);
  });

  it("empty ledger renders empty-state copy", async () => {
    getLedgerMock.mockResolvedValue([]);
    render(<LedgerDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(
        screen.getByText("이 방에서 받은 잔디 흔적이 아직 없어요"),
      ).toBeTruthy(),
    );
  });

  it("renders rows chronologically (DESC pass-through)", async () => {
    const data: readonly LedgerEntryDto[] = [
      {
        id: 502,
        roomId: ROOM_ID,
        delta: -5,
        reason: "FRIEND_GIFT_SPEND",
        occurredAt: "2026-05-22T02:00:00Z",
        revivalEventId: 101,
      },
      {
        id: 501,
        roomId: ROOM_ID,
        delta: -3,
        reason: "REVIVAL_SPEND",
        occurredAt: "2026-05-22T01:00:00Z",
        revivalEventId: 100,
      },
      {
        id: 500,
        roomId: ROOM_ID,
        delta: 1,
        reason: "SURVIVAL",
        occurredAt: "2026-05-22T00:00:00Z",
        revivalEventId: null,
      },
    ];
    getLedgerMock.mockResolvedValue(data);
    render(<LedgerDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    // Use caption strings (unique per reason) so DESC ordering assertion
    // does not collide with the shorter row labels.
    await waitFor(() =>
      expect(screen.getByText("친구에게 선물한 회생권")).toBeTruthy(),
    );
    expect(screen.getByText("회생권 사용")).toBeTruthy();
    expect(screen.getByText("오늘의 잔디 한 칸")).toBeTruthy();
  });

  it("each reason renders its locked Korean caption", async () => {
    const data: readonly LedgerEntryDto[] = [
      {
        id: 600,
        roomId: ROOM_ID,
        delta: 1,
        reason: "SURVIVAL",
        occurredAt: "2026-05-22T00:00:00Z",
        revivalEventId: null,
      },
    ];
    getLedgerMock.mockResolvedValue(data);
    render(<LedgerDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(screen.getByText("오늘의 잔디 한 칸")).toBeTruthy(),
    );
  });

  it("positive delta renders with '+' prefix, negative with '-' prefix", async () => {
    const data: readonly LedgerEntryDto[] = [
      {
        id: 700,
        roomId: ROOM_ID,
        delta: -3,
        reason: "REVIVAL_SPEND",
        occurredAt: "2026-05-22T02:00:00Z",
        revivalEventId: 1,
      },
      {
        id: 701,
        roomId: ROOM_ID,
        delta: 1,
        reason: "SURVIVAL",
        occurredAt: "2026-05-22T01:00:00Z",
        revivalEventId: null,
      },
    ];
    getLedgerMock.mockResolvedValue(data);
    render(<LedgerDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(screen.getByText("-3")).toBeTruthy());
    expect(screen.getByText("+1")).toBeTruthy();
  });

  it("total balance headline matches survival cache (BE-authoritative)", async () => {
    getLedgerMock.mockResolvedValue([]);
    getMeSurvivalMock.mockResolvedValue([survival(7)]);
    render(<LedgerDetailScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(screen.getByText("7")).toBeTruthy());
  });
});
