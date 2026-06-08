// Story 7.3 AC2 + AC3 + AC10 — useFinalThreePoster domain hook tests.
//
// Four cases per spec:
//   1. REST returns a poster DTO → `data` is non-null and `isLoading` flips false.
//   2. REST returns null (POSTER_NOT_FOUND 404 → null fall-through) → `data === null`.
//   3. Invalid input (roomId <= 0 or malformed yearMonth) → query disabled,
//      no fetch fired, subscribe destination null.
//   4. STOMP frame for matching (roomId, yearMonth) invalidates the cache key;
//      mismatched roomId / yearMonth frames are dropped.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as postersApi from "../../../../api/posters";
import {
  useFinalThreePoster,
  type MonthlyPosterReadyFrame,
} from "../useFinalThreePoster";

const mockHandlerRegistry: Array<{
  destination: string | null;
  handler: (payload: MonthlyPosterReadyFrame) => void;
}> = [];

const mockUseRealtimeStatus = jest.fn(() => "connected");

jest.mock("../../../realtime/client", () => ({
  useRealtimeStatus: () => mockUseRealtimeStatus(),
  useRealtimeSubscription: jest.fn(
    (
      destination: string | null,
      handler: (p: MonthlyPosterReadyFrame) => void,
    ) => {
      mockHandlerRegistry.push({ destination, handler });
    },
  ),
}));

jest.mock("../../../../api/posters", () => ({
  getPoster: jest.fn(),
}));

const getPosterMock = postersApi.getPoster as jest.MockedFunction<
  typeof postersApi.getPoster
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
const YEAR_MONTH = "2026-05";

function lastHandler(): (p: MonthlyPosterReadyFrame) => void {
  const last = mockHandlerRegistry[mockHandlerRegistry.length - 1];
  if (!last) throw new Error("no useRealtimeSubscription registration captured");
  return last.handler;
}

describe("useFinalThreePoster", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockHandlerRegistry.length = 0;
    mockUseRealtimeStatus.mockReturnValue("connected");
  });

  it("loads the poster via REST when membership-gated 200 returns", async () => {
    getPosterMock.mockResolvedValue({
      roomId: ROOM_ID,
      yearMonth: YEAR_MONTH,
      svgText: "<svg viewBox=\"0 0 800 420\"/>",
      pngUrl: "https://cdn.test/p/42-2026-05.png",
      generatedAt: "2026-06-01T06:30:00Z",
    });
    const { result } = renderHook(
      () => useFinalThreePoster(ROOM_ID, YEAR_MONTH),
      { wrapper: makeWrapper(makeClient()) },
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.data?.yearMonth).toBe(YEAR_MONTH);
    expect(result.current.data?.pngUrl).toBe(
      "https://cdn.test/p/42-2026-05.png",
    );
    expect(result.current.isError).toBe(false);
  });

  it("returns data === null on REST null (404 POSTER_NOT_FOUND)", async () => {
    getPosterMock.mockResolvedValue(null);
    const { result } = renderHook(
      () => useFinalThreePoster(ROOM_ID, YEAR_MONTH),
      { wrapper: makeWrapper(makeClient()) },
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.data).toBeNull();
  });

  it("disables the query AND skips the subscription for invalid roomId / yearMonth", () => {
    const invalidCases: Array<[number, string]> = [
      [0, YEAR_MONTH],
      [-1, YEAR_MONTH],
      [Number.NaN, YEAR_MONTH],
      [ROOM_ID, "2026-13"], // out of range month
      [ROOM_ID, "26-5"], // wrong format
      [ROOM_ID, ""],
    ];
    for (const [roomId, yearMonth] of invalidCases) {
      mockHandlerRegistry.length = 0;
      getPosterMock.mockReset();
      const { result } = renderHook(
        () => useFinalThreePoster(roomId, yearMonth),
        { wrapper: makeWrapper(makeClient()) },
      );
      expect(result.current.isLoading).toBe(false);
      expect(result.current.data).toBeNull();
      expect(getPosterMock).not.toHaveBeenCalled();
      const last = mockHandlerRegistry[mockHandlerRegistry.length - 1];
      expect(last?.destination).toBeNull();
    }
  });

  it("invalidates the matching cache key on STOMP frame; drops foreign roomId / yearMonth", async () => {
    getPosterMock.mockResolvedValue({
      roomId: ROOM_ID,
      yearMonth: YEAR_MONTH,
      svgText: "<svg viewBox=\"0 0 800 420\"/>",
      pngUrl: null,
      generatedAt: "2026-06-01T06:30:00Z",
    });
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(
      () => useFinalThreePoster(ROOM_ID, YEAR_MONTH),
      { wrapper: makeWrapper(client) },
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    invalidateSpy.mockClear();

    // Foreign roomId — must drop.
    act(() => {
      lastHandler()({ roomId: 999, yearMonth: YEAR_MONTH });
    });
    expect(invalidateSpy).not.toHaveBeenCalled();

    // Foreign yearMonth — must drop.
    act(() => {
      lastHandler()({ roomId: ROOM_ID, yearMonth: "2026-06" });
    });
    expect(invalidateSpy).not.toHaveBeenCalled();

    // Matching frame — invalidate exactly once.
    act(() => {
      lastHandler()({ roomId: ROOM_ID, yearMonth: YEAR_MONTH });
    });
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ["finalThreePoster", ROOM_ID, YEAR_MONTH],
    });
  });
});
