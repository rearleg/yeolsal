// RitualMoment — Story 1.7 AC3 reduced-motion fallback.
// When OS reduce-motion is on: no ember layer, text appears immediately,
// total elapsed time ≤ 2s before dismiss.

import { act, render, screen, waitFor } from "@testing-library/react-native";
import { AccessibilityInfo } from "react-native";

import { SubModeProvider } from "../../../providers/SubModeProvider";
import {
  getLastFiredKstDate,
  setLastFiredKstDate,
} from "../../../lib/ritualStorage";
import * as motion from "../../../theme/motion";
import { RitualMoment } from "../RitualMoment";

jest.mock("@react-native-async-storage/async-storage", () => ({
  __esModule: true,
  default: { getItem: jest.fn(), setItem: jest.fn(), removeItem: jest.fn() },
}));

jest.mock("../../../lib/ritualStorage", () => ({
  RITUAL_LAST_FIRED_KEY: "ritual.lastFiredKstDate",
  getLastFiredKstDate: jest.fn(),
  setLastFiredKstDate: jest.fn(),
}));

const mockedGet = getLastFiredKstDate as jest.MockedFunction<typeof getLastFiredKstDate>;
const mockedSet = setLastFiredKstDate as jest.MockedFunction<typeof setLastFiredKstDate>;

const KST_IN_WINDOW = new Date(Date.UTC(2026, 4, 13, 21, 3, 0)); // 06:03 KST 2026-05-14

function renderInPostcard(ui: React.ReactElement) {
  return render(<SubModeProvider subMode="postcard">{ui}</SubModeProvider>);
}

describe("RitualMoment — reduced-motion fallback (AC3)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGet.mockResolvedValue(null);
    mockedSet.mockResolvedValue(undefined);
    jest.spyOn(motion, "useReducedMotion").mockReturnValue(true);
  });

  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it("does NOT render the ember radial layer in reduced-motion mode", async () => {
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} />);

    await screen.findByTestId("ritual-root");
    expect(screen.queryByTestId("ritual-ember")).toBeNull();
  });

  it("reduced-motion headline reads the body.lg token instead of hardcoded values (Story 1.7 #4)", async () => {
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} />);

    const headline = await screen.findByTestId("ritual-text");
    const flatStyle = Array.isArray(headline.props.style)
      ? Object.assign({}, ...headline.props.style.filter(Boolean))
      : (headline.props.style ?? {});
    expect(flatStyle.fontSize).toBe(18);
    expect(flatStyle.lineHeight).toBe(30);
    expect(String(flatStyle.fontWeight)).toBe("400");
  });

  it("fires the AccessibilityInfo announcement immediately at mount (no 0.2s delay)", async () => {
    const announceSpy = jest
      .spyOn(AccessibilityInfo, "announceForAccessibility")
      .mockImplementation(() => undefined);

    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} />);

    await waitFor(() => {
      expect(announceSpy).toHaveBeenCalledTimes(1);
    });
  });

  it("dismisses within ≤ 2 seconds (reduced-motion timeline)", async () => {
    jest.useFakeTimers();

    const onComplete = jest.fn();
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} onComplete={onComplete} />);

    await act(async () => {
      jest.advanceTimersByTime(0);
    });
    // Reduced timeline is 1s fade + 1s display = 2000ms.
    await act(async () => {
      jest.advanceTimersByTime(2100);
    });

    await waitFor(() => {
      expect(onComplete).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByTestId("ritual-root")).toBeNull();
  });
});
