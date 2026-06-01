// Story 4.1 FE-8 / AC6 + AC7 — useRoomPoints domain hook tests.
//
// Five cases per spec:
//   1. REST primary loads initial pool snapshot into the hook return.
//   2. STOMP frame merges authoritative `newTotal` into the cache (never
//      `total + delta` arithmetic — Trap #5).
//   3. Duplicate `sourceRevivalEventId` is deduped (setQueryData called
//      exactly once across two identical frames).
//   4. Foreign `roomId` on a frame is ignored (defence-in-depth).
//   5. Invalid roomId (0, negative, NaN) → query disabled, no subscribe,
//      isLoading=false, total=0.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as roomPointsApi from "../../../../api/roomPoints";
import {
  useRoomPoints,
  type PointPoolFrame,
} from "../roomPoints";

// Capture every useRealtimeSubscription invocation so cases can drive the
// handler synchronously without a real WebSocket. Each entry is one hook
// instance's subscription registration.
const mockHandlerRegistry: Array<{
  destination: string | null;
  handler: (payload: PointPoolFrame) => void;
}> = [];

jest.mock("../../../realtime/client", () => ({
  useRealtimeSubscription: jest.fn(
    (destination: string | null, handler: (p: PointPoolFrame) => void) => {
      mockHandlerRegistry.push({ destination, handler });
    },
  ),
}));

jest.mock("../../../../api/roomPoints", () => ({
  getRoomPoints: jest.fn(),
}));

const getRoomPointsMock = roomPointsApi.getRoomPoints as jest.MockedFunction<
  typeof roomPointsApi.getRoomPoints
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

const ROOM_ID = 123;
const OCCURRED_AT = "2026-06-01T03:00:00Z";

function lastHandler(): (payload: PointPoolFrame) => void {
  const last = mockHandlerRegistry[mockHandlerRegistry.length - 1];
  if (!last) throw new Error("no useRealtimeSubscription registration captured");
  return last.handler;
}

describe("useRoomPoints", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockHandlerRegistry.length = 0;
  });

  it("loads initial pool snapshot via REST", async () => {
    getRoomPointsMock.mockResolvedValue({
      roomId: ROOM_ID,
      total: 8,
      lastEventAt: "2026-05-29T03:00:00Z",
    });
    const { result } = renderHook(() => useRoomPoints(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.total).toBe(8);
    expect(result.current.lastEventAt).toBe("2026-05-29T03:00:00Z");
    expect(result.current.isError).toBe(false);
  });

  it("merges STOMP frame using authoritative newTotal (never total+delta)", async () => {
    getRoomPointsMock.mockResolvedValue({
      roomId: ROOM_ID,
      total: 8,
      lastEventAt: null,
    });
    const { result } = renderHook(() => useRoomPoints(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.total).toBe(8));

    act(() => {
      lastHandler()({
        roomId: ROOM_ID,
        delta: 5,
        // BE computed newTotal=13 inside the row-level FOR UPDATE.
        // The hook MUST use this value verbatim — not 8+5 arithmetic.
        newTotal: 13,
        sourceRevivalEventId: 99,
        occurredAt: OCCURRED_AT,
      });
    });

    await waitFor(() => expect(result.current.total).toBe(13));
    expect(result.current.lastEventAt).toBe(OCCURRED_AT);
  });

  it("dedupes duplicate sourceRevivalEventId — second frame is a no-op", async () => {
    getRoomPointsMock.mockResolvedValue({
      roomId: ROOM_ID,
      total: 0,
      lastEventAt: null,
    });
    const client = makeClient();
    const setQueryDataSpy = jest.spyOn(client, "setQueryData");
    const { result } = renderHook(() => useRoomPoints(ROOM_ID), {
      wrapper: makeWrapper(client),
    });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    setQueryDataSpy.mockClear();

    const frame: PointPoolFrame = {
      roomId: ROOM_ID,
      delta: 5,
      newTotal: 5,
      sourceRevivalEventId: 42,
      occurredAt: OCCURRED_AT,
    };

    act(() => {
      lastHandler()(frame);
      lastHandler()(frame);
    });

    // First frame writes; second is collapsed by the seenEventIds Set.
    expect(setQueryDataSpy).toHaveBeenCalledTimes(1);
  });

  it("ignores frame whose roomId does not match the subscribed room (defence)", async () => {
    getRoomPointsMock.mockResolvedValue({
      roomId: ROOM_ID,
      total: 4,
      lastEventAt: null,
    });
    const { result } = renderHook(() => useRoomPoints(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.total).toBe(4));

    act(() => {
      lastHandler()({
        roomId: 999, // foreign — should be filtered by the in-handler guard
        delta: 5,
        newTotal: 99,
        sourceRevivalEventId: 7,
        occurredAt: OCCURRED_AT,
      });
    });

    // The pool value stays at the REST snapshot's value.
    expect(result.current.total).toBe(4);
  });

  it("disables the query AND skips the subscription for invalid roomId", () => {
    const cases = [0, -1, Number.NaN];
    for (const invalid of cases) {
      mockHandlerRegistry.length = 0;
      getRoomPointsMock.mockReset();
      const { result } = renderHook(() => useRoomPoints(invalid), {
        wrapper: makeWrapper(makeClient()),
      });
      // The hook treats unstarted/disabled queries as not-loading; total
      // falls back to 0 so render paths can safely consume the number.
      expect(result.current.isLoading).toBe(false);
      expect(result.current.total).toBe(0);
      expect(result.current.lastEventAt).toBeNull();
      // No fetch was triggered.
      expect(getRoomPointsMock).not.toHaveBeenCalled();
      // The subscription destination is null so the hook is a no-op
      // realtime-wise. The registry still captures the call, but with a
      // null destination; the real useRealtimeSubscription would skip it.
      const last = mockHandlerRegistry[mockHandlerRegistry.length - 1];
      expect(last?.destination).toBeNull();
    }
  });
});
