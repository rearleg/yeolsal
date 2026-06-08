// Story 7.3 AC8 — calendar-month-boundary helper for the Home tab Final-3 card.
// The card surfaces the IMMEDIATELY prior calendar month's poster in KST; this
// helper computes that "YYYY-MM" string off a Date input so the test cases can
// pin behavior across the year-wrap, leap-year February, and the UTC 20:00
// KST-rollover boundary that distinguishes calendar-month logic from the
// daily-mission 06:00 KST boundary used by entryDateOf.

import { previousYearMonthKst } from "../calendar";

describe("previousYearMonthKst", () => {
  it("returns the prior calendar month for July 1 UTC midnight (KST 09:00 Jul 1)", () => {
    expect(previousYearMonthKst(new Date("2026-07-01T00:00:00Z"))).toBe("2026-06");
  });

  it("crosses the KST month boundary at UTC 15:00 — UTC 20:00 June 30 is KST 05:00 July 1", () => {
    expect(previousYearMonthKst(new Date("2026-06-30T20:00:00Z"))).toBe("2026-06");
  });

  it("returns prior month relative to KST mid-day on the last day", () => {
    // UTC 12:00 June 30 is KST 21:00 June 30 — still June in KST; prev = May.
    expect(previousYearMonthKst(new Date("2026-06-30T12:00:00Z"))).toBe("2026-05");
  });

  it("wraps the year on January 1", () => {
    // UTC 00:00 Jan 1 2026 = KST 09:00 Jan 1 2026; prev = 2025-12.
    expect(previousYearMonthKst(new Date("2026-01-01T00:00:00Z"))).toBe("2025-12");
  });

  it("guards leap-year February (March 1 → February of leap year)", () => {
    // UTC 00:00 Mar 1 2024 = KST 09:00 Mar 1 2024; prev = 2024-02.
    expect(previousYearMonthKst(new Date("2024-03-01T00:00:00Z"))).toBe("2024-02");
  });
});
