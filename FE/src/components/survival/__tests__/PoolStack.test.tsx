// Story 4.3 FE-5 — PoolStack component tests (AC3, AC4, AC6, AC9).
//
// Covers stage selection (x5 via labels), cross-fade (both stages
// momentarily mounted), reduced-motion (instant swap, no glow), ratchet
// (display stage never regresses), __DEV__ warn (regression diagnostic),
// production silence (no warn when __DEV__=false), delta glow (+N
// appears + disappears within ~650ms), negative-total defensive path,
// and 5 visual-regression snapshots (one per stage).

import { act, render } from "@testing-library/react-native";
import { AccessibilityInfo } from "react-native";
import { PoolStack } from "../PoolStack";

let reduceMotionListener: ((value: boolean) => void) | null;
let removeMotionListenerMock: jest.Mock;

function mockMotionPreference(initial: boolean) {
  reduceMotionListener = null;
  removeMotionListenerMock = jest.fn();
  jest.spyOn(AccessibilityInfo, "isReduceMotionEnabled").mockResolvedValue(initial);
  jest
    .spyOn(AccessibilityInfo, "addEventListener")
    .mockImplementation((_event, listener) => {
      reduceMotionListener = listener as unknown as (value: boolean) => void;
      return { remove: removeMotionListenerMock } as never;
    });
}

async function settleMotionPreference() {
  await act(async () => {
    await Promise.resolve();
  });
}

describe("PoolStack — stage selection", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockMotionPreference(false);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each<[number, RegExp]>([
    [0, /1단계/],
    [10, /2단계/],
    [25, /3단계/],
    [50, /4단계/],
    [100, /5단계/],
  ])("renders the stage label matching total=%i", async (total, labelRe) => {
    const { getByLabelText } = render(<PoolStack total={total} />);
    await settleMotionPreference();
    expect(getByLabelText(labelRe)).toBeTruthy();
  });

  it("renders stage 1 for negative total (defensive — !isFinite || < 0)", async () => {
    const { getByLabelText } = render(<PoolStack total={-5} />);
    await settleMotionPreference();
    expect(getByLabelText(/1단계/)).toBeTruthy();
  });
});

describe("PoolStack — snapshots (AC9 visual regression)", () => {
  beforeEach(() => {
    mockMotionPreference(false);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("PoolStack renders stage 1 at total=0", async () => {
    const result = render(<PoolStack total={0} />);
    await settleMotionPreference();
    const tree = result.toJSON();
    expect(tree).toMatchSnapshot();
  });

  it("PoolStack renders stage 2 at total=10", async () => {
    const result = render(<PoolStack total={10} />);
    await settleMotionPreference();
    const tree = result.toJSON();
    expect(tree).toMatchSnapshot();
  });

  it("PoolStack renders stage 3 at total=25", async () => {
    const result = render(<PoolStack total={25} />);
    await settleMotionPreference();
    const tree = result.toJSON();
    expect(tree).toMatchSnapshot();
  });

  it("PoolStack renders stage 4 at total=50 (PRD §3.1 KPI success bar)", async () => {
    const result = render(<PoolStack total={50} />);
    await settleMotionPreference();
    const tree = result.toJSON();
    expect(tree).toMatchSnapshot();
  });

  it("PoolStack renders stage 5 at total=100 (keystone)", async () => {
    const result = render(<PoolStack total={100} />);
    await settleMotionPreference();
    const tree = result.toJSON();
    expect(tree).toMatchSnapshot();
  });
});

describe("PoolStack — cross-fade (AC4)", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    mockMotionPreference(false);
  });

  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it("mounts both prev + next stage layers during the transition", async () => {
    const { rerender, queryByTestId, findByTestId } = render(
      <PoolStack total={0} />,
    );
    // Resolve the AccessibilityInfo promise so reduceMotion settles to false.
    await act(async () => {
      await Promise.resolve();
    });
    // Initial state: only the current layer is mounted.
    expect(queryByTestId("pool-stack-prev-layer")).toBeNull();

    rerender(<PoolStack total={10} />);

    // After re-render, the prev layer mounts to host the fade-out.
    await findByTestId("pool-stack-prev-layer");
    expect(queryByTestId("pool-stack-current-layer")).not.toBeNull();
    await act(async () => {
      jest.advanceTimersByTime(250);
      await Promise.resolve();
    });
    expect(queryByTestId("pool-stack-prev-layer")).toBeNull();
  });
});

describe("PoolStack — reduced-motion (AC4)", () => {
  beforeEach(() => {
    mockMotionPreference(true);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("instant swap — prev layer is never mounted when reduceMotion=true", async () => {
    const { rerender, queryByTestId } = render(<PoolStack total={0} />);
    // Let AccessibilityInfo.isReduceMotionEnabled promise resolve so the
    // settled `reduceMotion === true` state takes effect.
    await act(async () => {
      await Promise.resolve();
    });
    rerender(<PoolStack total={10} />);
    await act(async () => {
      await Promise.resolve();
    });
    // No prev layer ever appears under reduced-motion.
    expect(queryByTestId("pool-stack-prev-layer")).toBeNull();
  });

  it("delta glow is suppressed entirely under reduced-motion (AC4)", async () => {
    const { rerender, queryByTestId, queryByText } = render(<PoolStack total={10} />);
    await act(async () => {
      await Promise.resolve();
    });
    rerender(<PoolStack total={15} />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(queryByTestId("pool-stack-delta-glow")).toBeNull();
    expect(queryByText("+5")).toBeNull();
  });

  it("enabling reduced motion stops an active cross-fade and glow", async () => {
    jest.useFakeTimers();
    mockMotionPreference(false);
    const { rerender, queryByTestId, queryByText } = render(<PoolStack total={9} />);
    await settleMotionPreference();
    rerender(<PoolStack total={10} />);
    await settleMotionPreference();
    expect(queryByTestId("pool-stack-prev-layer")).not.toBeNull();
    expect(queryByText("+1")).not.toBeNull();
    await act(async () => {
      reduceMotionListener?.(true);
      await Promise.resolve();
    });
    expect(queryByTestId("pool-stack-prev-layer")).toBeNull();
    expect(queryByText("+1")).toBeNull();
    jest.useRealTimers();
  });

  it("does not let the initial lookup overwrite a newer reduce-motion event", async () => {
    let resolveInitial: ((value: boolean) => void) | null = null;
    jest.spyOn(AccessibilityInfo, "isReduceMotionEnabled").mockImplementation(
      () => new Promise<boolean>((resolve) => {
        resolveInitial = resolve;
      }),
    );
    const { rerender, queryByTestId } = render(<PoolStack total={0} />);
    await act(async () => {
      reduceMotionListener?.(true);
      resolveInitial?.(false);
      await Promise.resolve();
    });
    rerender(<PoolStack total={10} />);
    await settleMotionPreference();
    expect(queryByTestId("pool-stack-prev-layer")).toBeNull();
  });
});

describe("PoolStack — ratchet (AC6)", () => {
  beforeEach(() => {
    mockMotionPreference(false);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("mounted at total=47 (stage 3), re-rendered with total=5 → stays on stage 3", async () => {
    const { rerender, getByLabelText } = render(<PoolStack total={47} />);
    await settleMotionPreference();
    expect(getByLabelText(/3단계/)).toBeTruthy();
    rerender(<PoolStack total={5} />);
    // The visible stage NEVER goes backwards — even though stageFor(5)=1.
    expect(getByLabelText(/3단계/)).toBeTruthy();
  });

  it("growing 0→10→25→50→100→9 ends on stage 5 (never regresses)", async () => {
    const { rerender, getByLabelText } = render(<PoolStack total={0} />);
    await settleMotionPreference();
    expect(getByLabelText(/1단계/)).toBeTruthy();
    rerender(<PoolStack total={10} />);
    expect(getByLabelText(/2단계/)).toBeTruthy();
    rerender(<PoolStack total={25} />);
    expect(getByLabelText(/3단계/)).toBeTruthy();
    rerender(<PoolStack total={50} />);
    expect(getByLabelText(/4단계/)).toBeTruthy();
    rerender(<PoolStack total={100} />);
    expect(getByLabelText(/5단계/)).toBeTruthy();
    rerender(<PoolStack total={9} />);
    expect(getByLabelText(/5단계/)).toBeTruthy();
  });

  it("__DEV__ regression — console.warn fires once with the diagnostic shape", async () => {
    // jest-expo defaults __DEV__ to true. Verify and assert.
    expect(__DEV__).toBe(true);
    const warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});

    const { rerender } = render(<PoolStack total={47} />);
    await settleMotionPreference();
    rerender(<PoolStack total={5} />);

    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][0]).toMatch(/\[PoolStack\] regression observed:/);
    expect(warnSpy.mock.calls[0][0]).toMatch(/prev=3/);
    expect(warnSpy.mock.calls[0][0]).toMatch(/next=1/);
    expect(warnSpy.mock.calls[0][0]).toMatch(/total=5/);
    warnSpy.mockRestore();
  });

  it("__DEV__ === false → console.warn is NOT called on regression", async () => {
    // Override __DEV__ for this case. RN injects it as a global; toggle
    // via direct assignment so the restore in afterEach picks it back up.
    const originalDev = (global as { __DEV__?: boolean }).__DEV__;
    (global as { __DEV__?: boolean }).__DEV__ = false;
    const warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
    try {
      const { rerender } = render(<PoolStack total={47} />);
      await settleMotionPreference();
      rerender(<PoolStack total={5} />);
      expect(warnSpy).not.toHaveBeenCalled();
    } finally {
      (global as { __DEV__?: boolean }).__DEV__ = originalDev;
      warnSpy.mockRestore();
    }
  });

  it("warns for consecutive committed regression events in the same stage", async () => {
    const warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
    const { rerender } = render(<PoolStack total={100} />);
    await settleMotionPreference();
    rerender(<PoolStack total={9} />);
    rerender(<PoolStack total={8} />);
    expect(warnSpy).toHaveBeenCalledTimes(2);
    warnSpy.mockRestore();
  });
});

describe("PoolStack — delta glow (AC4)", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    mockMotionPreference(false);
  });

  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it("renders '+5' overlay when total increases by 5", async () => {
    const { rerender, queryByText, findByText } = render(<PoolStack total={10} />);
    // Let AccessibilityInfo settle so the glow effect arms.
    await act(async () => {
      await Promise.resolve();
    });
    expect(queryByText("+5")).toBeNull();
    rerender(<PoolStack total={15} />);
    await act(async () => {
      await Promise.resolve();
    });
    await findByText("+5");
  });

  it("'+N' overlay unmounts after the ~650ms animation window", async () => {
    const { rerender, queryByText, findByText } = render(<PoolStack total={10} />);
    await act(async () => {
      await Promise.resolve();
    });
    rerender(<PoolStack total={15} />);
    await act(async () => {
      await Promise.resolve();
    });
    await findByText("+5");
    // Advance well past the 150 + 250 + 250 = 650ms sequence + slack.
    await act(async () => {
      jest.advanceTimersByTime(1000);
      await Promise.resolve();
    });
    expect(queryByText("+5")).toBeNull();
  });

  it("does not render a false gain after a regressed total recovers", async () => {
    const { rerender, queryByText } = render(<PoolStack total={50} />);
    await settleMotionPreference();
    rerender(<PoolStack total={40} />);
    rerender(<PoolStack total={50} />);
    expect(queryByText("+10")).toBeNull();
  });

  it("does not render a glow when a malformed baseline becomes finite", async () => {
    const { rerender, queryByText } = render(<PoolStack total={Number.NaN} />);
    await settleMotionPreference();
    rerender(<PoolStack total={5} />);
    expect(queryByText("+5")).toBeNull();
  });

  it("keeps the newest glow alive when rapid increases interrupt the previous animation", async () => {
    const { rerender, queryByText } = render(<PoolStack total={10} />);
    await settleMotionPreference();
    rerender(<PoolStack total={15} />);
    expect(queryByText("+5")).not.toBeNull();
    rerender(<PoolStack total={20} />);
    expect(queryByText("+5")).not.toBeNull();
    await act(async () => {
      jest.advanceTimersByTime(1000);
      await Promise.resolve();
    });
    expect(queryByText("+5")).toBeNull();
  });

  it("removes the reduced-motion listener on unmount", async () => {
    const { unmount } = render(<PoolStack total={10} />);
    await settleMotionPreference();
    unmount();
    expect(removeMotionListenerMock).toHaveBeenCalledTimes(1);
  });
});
