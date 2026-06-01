// Story 3.4 FE-12 — usePersonalPointsLedger + useReceivedRevivals hook tests
// (AC8: 3 cases each). Mirrors the friendGiftTargets.test.tsx shape: mock
// the api module, wrap in QueryClientProvider, assert the hook's resolved
// data and invalidation flow.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as walletApi from "../../../../api/wallet";
import type { LedgerEntryDto, ReceivedRevivalDto } from "../../../../api/wallet";
import { qk } from "../../keys";
import {
  usePersonalPointsLedger,
  useReceivedRevivals,
} from "../wallet";

jest.mock("../../../../api/wallet", () => ({
  getPersonalPointsLedger: jest.fn(),
  getReceivedRevivals: jest.fn(),
}));

const getLedgerMock = walletApi.getPersonalPointsLedger as jest.MockedFunction<
  typeof walletApi.getPersonalPointsLedger
>;
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
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const ROOM_ID = 42;

describe("usePersonalPointsLedger", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("empty data → returns []", async () => {
    getLedgerMock.mockResolvedValue([]);
    const { result } = renderHook(() => usePersonalPointsLedger(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });

  it("returns BE-sorted ledger rows verbatim (FE asserts pass-through)", async () => {
    const data: readonly LedgerEntryDto[] = [
      {
        id: 501,
        roomId: ROOM_ID,
        delta: -3,
        reason: "REVIVAL_SPEND",
        occurredAt: "2026-05-22T01:00:00Z",
        revivalEventId: 99,
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
    const { result } = renderHook(() => usePersonalPointsLedger(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(data);
  });

  it("queryKey is qk.personalPointsLedger(roomId) — invalidation refetches", async () => {
    getLedgerMock.mockResolvedValue([]);
    const client = makeClient();
    const { result } = renderHook(() => usePersonalPointsLedger(ROOM_ID), {
      wrapper: makeWrapper(client),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const firstCalls = getLedgerMock.mock.calls.length;

    await act(async () => {
      await client.invalidateQueries({
        queryKey: qk.personalPointsLedger(ROOM_ID),
      });
    });
    await waitFor(() =>
      expect(getLedgerMock.mock.calls.length).toBeGreaterThan(firstCalls),
    );
  });
});

describe("useReceivedRevivals", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("empty data → returns []", async () => {
    getReceivedMock.mockResolvedValue([]);
    const { result } = renderHook(() => useReceivedRevivals(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });

  it("returns all 3 source types verbatim", async () => {
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
      {
        revivalEventId: 1002,
        roomId: ROOM_ID,
        roomName: "Room",
        source: "PERSONAL_POINTS",
        donorUserId: null,
        donorNickname: null,
        occurredAt: "2026-05-22T01:00:00Z",
      },
      {
        revivalEventId: 1001,
        roomId: ROOM_ID,
        roomName: "Room",
        source: "FREE_TICKET",
        donorUserId: null,
        donorNickname: null,
        occurredAt: "2026-05-22T00:00:00Z",
      },
    ];
    getReceivedMock.mockResolvedValue(data);
    const { result } = renderHook(() => useReceivedRevivals(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(data);
    expect((result.current.data ?? []).map((r) => r.source)).toEqual([
      "FRIEND_GIFT",
      "PERSONAL_POINTS",
      "FREE_TICKET",
    ]);
  });

  it("predicate-based invalidation on receivedRevivals refetches", async () => {
    getReceivedMock.mockResolvedValue([]);
    const client = makeClient();
    const { result } = renderHook(() => useReceivedRevivals(ROOM_ID), {
      wrapper: makeWrapper(client),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const firstCalls = getReceivedMock.mock.calls.length;

    await act(async () => {
      await client.invalidateQueries({
        predicate: (q) =>
          Array.isArray(q.queryKey) && q.queryKey[0] === "receivedRevivals",
      });
    });
    await waitFor(() =>
      expect(getReceivedMock.mock.calls.length).toBeGreaterThan(firstCalls),
    );
  });
});
