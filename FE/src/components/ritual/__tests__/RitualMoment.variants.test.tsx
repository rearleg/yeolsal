// RitualMoment — Story 1.7 AC2 variant matrix.
// Verifies the rendered headline matches the weekday/month-day rules with
// the 1st-of-month override winning over weekday selection.

import { render, screen, waitFor } from "@testing-library/react-native";

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

// Helper: 06:03 KST on the named (KST) wall-clock date — inside the window.
function kstSixOhThree(year: number, month1: number, day: number): Date {
  return new Date(Date.UTC(year, month1 - 1, day, -3, 3, 0)); // 06:03 KST
}

function renderInPostcard(ui: React.ReactElement) {
  return render(<SubModeProvider subMode="postcard">{ui}</SubModeProvider>);
}

describe("RitualMoment — variant matrix (AC2)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGet.mockResolvedValue(null);
    mockedSet.mockResolvedValue(undefined);
  });

  const cases: ReadonlyArray<{
    label: string;
    now: Date;
    expectText: string;
  }> = [
    { label: "Tuesday (Mon–Thu)", now: kstSixOhThree(2026, 5, 19), expectText: "오늘도 함께" },
    { label: "Friday", now: kstSixOhThree(2026, 5, 22), expectText: "이번 주도 살아남았어요" },
    { label: "Saturday", now: kstSixOhThree(2026, 5, 23), expectText: "주말도 함께" },
    { label: "Sunday", now: kstSixOhThree(2026, 5, 24), expectText: "주말도 함께" },
    {
      label: "1st of month on a Friday (Final-3 wins over weekday)",
      now: kstSixOhThree(2027, 1, 1),
      expectText: "이번 달 Final-3 카드가 도착했어요",
    },
  ];

  cases.forEach(({ label, now, expectText }) => {
    it(`renders the right variant text on ${label}`, async () => {
      renderInPostcard(<RitualMoment now={now} />);

      const text = await screen.findByTestId("ritual-text");
      await waitFor(() => {
        expect(text).toHaveTextContent(expectText);
      });
    });
  });
});
