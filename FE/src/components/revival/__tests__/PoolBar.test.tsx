// Story 4.1 FE-6 — PoolBar component tests after the AC8 refactor.
//
// The Story 3.4 cases that asserted the inline STOMP subscribe + the
// per-frame qk.meSurvival invalidate have been collapsed — those
// responsibilities now live in the `useRoomPoints(roomId)` domain hook
// (`FE/src/lib/query/hooks/roomPoints.ts`). PoolBar stays purely
// presentational, so the remaining tests cover the animation lifecycle
// and the negative assertion that `getRealtimeClient` is no longer
// reached from this component.

import { render, waitFor } from "@testing-library/react-native";
import { AccessibilityInfo } from "react-native";
import { PoolBar } from "../PoolBar";

const mockGetRealtimeClient = jest.fn();

jest.mock("../../../lib/realtime/client", () => ({
  getRealtimeClient: (...args: unknown[]) => mockGetRealtimeClient(...args),
}));

describe("PoolBar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
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

  it("fill ratio renders the testID for in-range, zero, and over-cap inputs", () => {
    // The internal Animated.Value is not observable through testing-library;
    // probe the three input regimes (>0, 0, ≥max) and assert the fill view
    // renders for each. clampRatio is dead simple — this guards against
    // future regressions that branch the render path on ratio.
    const cases = [
      { total: 50, max: 100 },
      { total: 0, max: 100 },
      { total: 200, max: 100 },
    ];
    for (const { total, max } of cases) {
      const { unmount, getByTestId } = render(
        <PoolBar total={total} max={max} />,
      );
      expect(getByTestId("poolbar-fill")).toBeTruthy();
      unmount();
    }
  });

  it("does NOT open a STOMP subscription (Story 4.1 AC8 — moved to useRoomPoints)", () => {
    render(<PoolBar total={3} max={100} />);
    // The inline `getRealtimeClient().subscribe(...)` block was removed —
    // PoolBar no longer touches the realtime singleton at all. The leaf
    // component is purely presentational; the parent hook owns the
    // subscription.
    expect(mockGetRealtimeClient).not.toHaveBeenCalled();
  });

  it("re-renders cleanly when total prop changes (animate-on-change smoke)", async () => {
    const { rerender, getByTestId } = render(<PoolBar total={3} max={100} />);
    await waitFor(() => expect(getByTestId("poolbar-fill")).toBeTruthy());
    rerender(<PoolBar total={8} max={100} />);
    // No exception thrown; fill view stays mounted.
    expect(getByTestId("poolbar-fill")).toBeTruthy();
  });

  it("reduced-motion path renders without throwing (instant fill, no tween)", async () => {
    jest
      .spyOn(AccessibilityInfo, "isReduceMotionEnabled")
      .mockResolvedValue(true);
    const { getByTestId } = render(<PoolBar total={50} max={100} />);
    await waitFor(() => expect(getByTestId("poolbar-fill")).toBeTruthy());
  });

  it("unmounts cleanly (cancels in-flight animation handle)", async () => {
    const { unmount, getByTestId } = render(<PoolBar total={3} max={100} />);
    await waitFor(() => expect(getByTestId("poolbar-fill")).toBeTruthy());
    // Smoke: unmount completes without throwing (the cleanup effect
    // stops `animationRef.current` if one is in flight).
    expect(() => unmount()).not.toThrow();
  });
});
