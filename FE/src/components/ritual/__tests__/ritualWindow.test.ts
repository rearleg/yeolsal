import {
  formatKstCaption,
  isInRitualWindow,
  selectRitualText,
  todayKstYmd,
} from "../ritualWindow";

// Helper: construct a Date that, when interpreted in Asia/Seoul (UTC+09:00,
// no DST), reads as the supplied wall-clock (Y-M-D HH:MM:SS). Tests use
// this rather than runtime Date.now() so they remain stable across
// machines + clocks.
function kstWallClock(
  year: number,
  monthOneBased: number,
  day: number,
  hour: number,
  minute: number,
  second: number = 0,
): Date {
  return new Date(Date.UTC(year, monthOneBased - 1, day, hour - 9, minute, second));
}

describe("isInRitualWindow", () => {
  it("returns true at exactly 06:00:00 KST (window start, inclusive)", () => {
    expect(isInRitualWindow(kstWallClock(2026, 5, 14, 6, 0, 0))).toBe(true);
  });

  it("returns true at 06:04:59 KST (last second of the window)", () => {
    expect(isInRitualWindow(kstWallClock(2026, 5, 14, 6, 4, 59))).toBe(true);
  });

  it("returns false at exactly 06:05:00 KST (window end, exclusive)", () => {
    expect(isInRitualWindow(kstWallClock(2026, 5, 14, 6, 5, 0))).toBe(false);
  });

  it("returns false at 05:59:59 KST (one second before window opens)", () => {
    expect(isInRitualWindow(kstWallClock(2026, 5, 14, 5, 59, 59))).toBe(false);
  });

  it("returns false at 07:00:00 KST (clearly past window)", () => {
    expect(isInRitualWindow(kstWallClock(2026, 5, 14, 7, 0, 0))).toBe(false);
  });

  it("returns false at 23:00:00 KST (evening, nowhere near window)", () => {
    expect(isInRitualWindow(kstWallClock(2026, 5, 14, 23, 0, 0))).toBe(false);
  });

  it("uses Asia/Seoul wall-clock for the same UTC instant regardless of device TZ", () => {
    // 21:03 UTC on 2026-05-13 = 06:03 KST on 2026-05-14 — inside the window.
    const utcInstantForKst0603 = new Date(Date.UTC(2026, 4, 13, 21, 3, 0));
    expect(isInRitualWindow(utcInstantForKst0603)).toBe(true);
  });

  it("respects the timeZone parameter (sanity check — UTC evaluation)", () => {
    // Same UTC instant evaluated against UTC reads 21:03 — not in window.
    const utcInstant = new Date(Date.UTC(2026, 4, 13, 21, 3, 0));
    expect(isInRitualWindow(utcInstant, "UTC")).toBe(false);
  });
});

describe("selectRitualText", () => {
  it("returns weekday text on Mon", () => {
    expect(selectRitualText(kstWallClock(2026, 5, 18, 6, 0))).toBe("오늘도 함께");
  });

  it("returns weekday text on Tue", () => {
    expect(selectRitualText(kstWallClock(2026, 5, 19, 6, 0))).toBe("오늘도 함께");
  });

  it("returns weekday text on Wed", () => {
    expect(selectRitualText(kstWallClock(2026, 5, 20, 6, 0))).toBe("오늘도 함께");
  });

  it("returns weekday text on Thu", () => {
    expect(selectRitualText(kstWallClock(2026, 5, 21, 6, 0))).toBe("오늘도 함께");
  });

  it("returns Friday text on Fri", () => {
    expect(selectRitualText(kstWallClock(2026, 5, 22, 6, 0))).toBe("이번 주도 살아남았어요");
  });

  it("returns weekend text on Sat", () => {
    expect(selectRitualText(kstWallClock(2026, 5, 23, 6, 0))).toBe("주말도 함께");
  });

  it("returns weekend text on Sun", () => {
    expect(selectRitualText(kstWallClock(2026, 5, 24, 6, 0))).toBe("주말도 함께");
  });

  it("returns Final-3 text on the 1st of the month (Friday conflict — Final-3 wins)", () => {
    // 2027-01-01 is a Friday.
    expect(selectRitualText(kstWallClock(2027, 1, 1, 6, 0))).toBe(
      "이번 달 Final-3 카드가 도착했어요",
    );
  });

  it("returns Final-3 text on the 1st of the month (Tuesday)", () => {
    // 2026-09-01 is a Tuesday.
    expect(selectRitualText(kstWallClock(2026, 9, 1, 6, 0))).toBe(
      "이번 달 Final-3 카드가 도착했어요",
    );
  });

  it("returns Final-3 text on the 1st of the month (Saturday)", () => {
    // 2026-08-01 is a Saturday.
    expect(selectRitualText(kstWallClock(2026, 8, 1, 6, 0))).toBe(
      "이번 달 Final-3 카드가 도착했어요",
    );
  });

  it("does NOT trigger Final-3 on the 2nd of the month", () => {
    // 2026-05-02 is a Saturday — weekend text, not Final-3.
    expect(selectRitualText(kstWallClock(2026, 5, 2, 6, 0))).toBe("주말도 함께");
  });
});

describe("formatKstCaption", () => {
  it("formats the Asia/Seoul wall-clock date as a Korean long-form caption", () => {
    const out = formatKstCaption(kstWallClock(2026, 5, 14, 6, 0));
    // Intl output is CLDR-driven; assert year/month/day surface tokens.
    expect(out).toMatch(/2026/);
    expect(out).toMatch(/5(월| ?월)/);
    expect(out).toMatch(/14/);
  });

  it("uses Asia/Seoul even when input UTC reads as the prior day", () => {
    // 2026-05-13 21:00 UTC = 2026-05-14 06:00 KST. Caption: May 14, not 13.
    const utcInstant = new Date(Date.UTC(2026, 4, 13, 21, 0, 0));
    const out = formatKstCaption(utcInstant);
    expect(out).toMatch(/14/);
    expect(out).not.toMatch(/13/);
  });
});

describe("todayKstYmd", () => {
  it("formats a KST wall-clock Date as YYYY-MM-DD", () => {
    expect(todayKstYmd(kstWallClock(2026, 5, 14, 6, 3))).toBe("2026-05-14");
  });

  it("zero-pads single-digit month and day", () => {
    expect(todayKstYmd(kstWallClock(2026, 1, 5, 6, 0))).toBe("2026-01-05");
  });

  it("uses the Asia/Seoul date even when input UTC reads as the prior day", () => {
    const utcInstant = new Date(Date.UTC(2026, 4, 13, 21, 0, 0)); // = 2026-05-14 06:00 KST
    expect(todayKstYmd(utcInstant)).toBe("2026-05-14");
  });
});
