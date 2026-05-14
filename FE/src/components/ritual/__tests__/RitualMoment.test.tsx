// RitualMoment — Story 1.7 main behaviour suite (AC1, AC4, AC6, AC7).
// Covers: render gate (window / idempotency), accessibility composite,
// announcement firing, dismiss timing, AsyncStorage write at sequence start.

import { act, render, screen, waitFor } from "@testing-library/react-native";
import { AccessibilityInfo } from "react-native";

import { SubModeProvider } from "../../../providers/SubModeProvider";
import {
  getLastFiredKstDate,
  setLastFiredKstDate,
} from "../../../lib/ritualStorage";
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

// Asia/Seoul UTC+9, no DST. 06:03 KST on 2026-05-14 → 21:03 UTC 2026-05-13.
const KST_IN_WINDOW = new Date(Date.UTC(2026, 4, 13, 21, 3, 0));
const KST_OUTSIDE_WINDOW = new Date(Date.UTC(2026, 4, 14, 5, 0, 0)); // 14:00 KST

function renderInPostcard(ui: React.ReactElement) {
  return render(<SubModeProvider subMode="postcard">{ui}</SubModeProvider>);
}

describe("RitualMoment — render gate (AC1, AC4, AC7)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGet.mockResolvedValue(null);
    mockedSet.mockResolvedValue(undefined);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("renders the overlay when inside the 06:00–06:05 KST window and not yet fired today", async () => {
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} />);

    await waitFor(() => {
      expect(screen.getByTestId("ritual-root")).toBeTruthy();
    });
    expect(screen.getByTestId("ritual-text")).toBeTruthy();
    expect(screen.getByTestId("ritual-caption")).toBeTruthy();
  });

  it("returns null when the current time is outside the ritual window", async () => {
    renderInPostcard(<RitualMoment now={KST_OUTSIDE_WINDOW} />);

    await waitFor(() => {
      expect(screen.queryByTestId("ritual-root")).toBeNull();
    });
    expect(mockedGet).not.toHaveBeenCalled();
  });

  it("returns null when AsyncStorage shows the ritual already fired today", async () => {
    mockedGet.mockResolvedValueOnce("2026-05-14");

    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} />);

    await waitFor(() => {
      expect(mockedGet).toHaveBeenCalled();
    });
    expect(screen.queryByTestId("ritual-root")).toBeNull();
    expect(mockedSet).not.toHaveBeenCalled();
  });

  it("fires the AsyncStorage idempotency write at the start of the visible sequence", async () => {
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} />);

    await waitFor(() => {
      expect(mockedSet).toHaveBeenCalledWith("2026-05-14");
    });
  });
});

describe("RitualMoment — accessibility composite (AC6)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGet.mockResolvedValue(null);
    mockedSet.mockResolvedValue(undefined);
  });

  it("sets accessibilityViewIsModal + alert role on the overlay root", async () => {
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} />);

    const root = await screen.findByTestId("ritual-root");
    expect(root.props.accessibilityViewIsModal).toBe(true);
    expect(root.props.accessibilityRole).toBe("alert");
  });

  it("announces the caption + variant text once via AccessibilityInfo", async () => {
    const announceSpy = jest
      .spyOn(AccessibilityInfo, "announceForAccessibility")
      .mockImplementation(() => undefined);

    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} />);

    await waitFor(() => {
      expect(announceSpy).toHaveBeenCalledTimes(1);
    });
    expect(announceSpy.mock.calls[0]?.[0]).toMatch(/오늘도 함께/);
  });
});

describe("RitualMoment — dismiss timing (AC1)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGet.mockResolvedValue(null);
    mockedSet.mockResolvedValue(undefined);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("calls onComplete and unmounts after the 5-second standard timeline", async () => {
    jest.useFakeTimers();

    const onComplete = jest.fn();
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} onComplete={onComplete} />);

    // Allow the gate-checking microtask + initial mount to settle.
    await act(async () => {
      jest.advanceTimersByTime(0);
    });
    // Total nominal duration: 200 + 500 + 3300 + 500 + 500 = 5000 ms.
    await act(async () => {
      jest.advanceTimersByTime(5500);
    });

    await waitFor(() => {
      expect(onComplete).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByTestId("ritual-root")).toBeNull();
  });
});
