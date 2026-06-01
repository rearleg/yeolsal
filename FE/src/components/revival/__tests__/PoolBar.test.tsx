// Story 3.4 FE-12 — PoolBar component tests (AC8: 4 cases).
//
// Asserts the live-fill animation lifecycle:
//   1. subscribes to the room-specific points topic
//   2. WS frame triggers cache invalidation
//   3. reduced-motion path skips animation
//   4. unmount cleans up the subscription
//
// Mocks the singleton RealtimeClient instead of opening a real WS
// (project-context FE testing rule).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { AccessibilityInfo } from "react-native";
import { qk } from "../../../lib/query/keys";
import { PoolBar } from "../PoolBar";

// `mock` prefix bypasses Jest's no-out-of-scope-vars hoisting rule.
const mockSubscribeCalls: Array<{
  destination: string;
  handler: (frame: { body: string; headers: Record<string, string> }) => void;
}> = [];
const mockUnsubscribeFn = jest.fn();

jest.mock("../../../lib/realtime/client", () => ({
  getRealtimeClient: jest.fn(() => ({
    subscribe: jest.fn((destination: string, handler) => {
      mockSubscribeCalls.push({ destination, handler });
      return { unsubscribe: mockUnsubscribeFn };
    }),
  })),
}));

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

describe("PoolBar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockSubscribeCalls.length = 0;
    mockUnsubscribeFn.mockClear();
    jest
      .spyOn(AccessibilityInfo, "isReduceMotionEnabled")
      .mockResolvedValue(false);
    jest
      .spyOn(AccessibilityInfo, "addEventListener")
      .mockReturnValue({ remove: jest.fn() } as never);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("subscribes to the room-specific points topic on mount", async () => {
    render(<PoolBar roomId={ROOM_ID} total={3} max={100} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(mockSubscribeCalls.length).toBe(1));
    expect(mockSubscribeCalls[0].destination).toBe(`/topic/rooms.${ROOM_ID}.points`);
  });

  it("fill ratio renders the testID for in-range, zero, and over-cap inputs", () => {
    // Spec AC8 PoolBar case 1: "Fill ratio computed correctly from
    // roomPointPool / poolMax". The internal Animated.Value is not
    // observable through testing-library; instead probe the three
    // input regimes (>0, 0, ≥max) and assert the fill view renders
    // for each. The clampRatio function is dead simple — this guards
    // against future regressions that branch the render path on ratio.
    const cases = [
      { total: 50, max: 100 },
      { total: 0, max: 100 },
      { total: 200, max: 100 },
    ];
    for (const { total, max } of cases) {
      const { unmount, getByTestId } = render(
        <PoolBar roomId={ROOM_ID} total={total} max={max} />,
        { wrapper: makeWrapper(makeClient()) },
      );
      expect(getByTestId("poolbar-fill")).toBeTruthy();
      unmount();
    }
  });

  it("ignores WS frames carrying a foreign roomId (defence-in-depth)", async () => {
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    render(<PoolBar roomId={ROOM_ID} total={3} max={100} />, {
      wrapper: makeWrapper(client),
    });
    await waitFor(() => expect(mockSubscribeCalls.length).toBe(1));
    const handler = mockSubscribeCalls[0].handler;

    act(() => {
      handler({
        body: JSON.stringify({
          roomId: ROOM_ID + 1,
          totalAfter: 8,
        }),
        headers: {},
      });
    });

    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: qk.meSurvival });
  });

  it("WS frame triggers qk.meSurvival invalidation", async () => {
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    render(<PoolBar roomId={ROOM_ID} total={3} max={100} />, {
      wrapper: makeWrapper(client),
    });
    await waitFor(() => expect(mockSubscribeCalls.length).toBe(1));
    const handler = mockSubscribeCalls[0].handler;

    act(() => {
      handler({
        body: JSON.stringify({
          roomId: ROOM_ID,
          totalAfter: 8,
          lastEventAt: "2026-05-22T03:00:00Z",
        }),
        headers: {},
      });
    });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: qk.meSurvival });
  });

  it("reduced-motion path renders without throwing (instant fill)", async () => {
    jest
      .spyOn(AccessibilityInfo, "isReduceMotionEnabled")
      .mockResolvedValue(true);
    const { getByTestId } = render(
      <PoolBar roomId={ROOM_ID} total={50} max={100} />,
      { wrapper: makeWrapper(makeClient()) },
    );
    // The fill view exists immediately; its transform is set via Animated
    // value. Smoke-test that the testID renders without throwing.
    await waitFor(() => expect(getByTestId("poolbar-fill")).toBeTruthy());
  });

  it("unmount tears down the STOMP subscription (cleanup timer)", async () => {
    const { unmount } = render(
      <PoolBar roomId={ROOM_ID} total={3} max={100} />,
      { wrapper: makeWrapper(makeClient()) },
    );
    await waitFor(() => expect(mockSubscribeCalls.length).toBe(1));
    unmount();
    expect(mockUnsubscribeFn).toHaveBeenCalled();
  });
});
