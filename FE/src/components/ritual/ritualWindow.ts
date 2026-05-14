// Pure helpers for the RitualMoment overlay — Story 1.7.
//
// All time/date logic is concentrated here so the React component can stay
// thin and so the time-zone-sensitive behavior is unit-testable without
// rendering a tree. Every helper takes a `Date` (defaulting to `new Date()`)
// so callers can inject a fixed instant in tests.
//
// Asia/Seoul is UTC+09:00 with no DST. We still go through Intl.DateTimeFormat
// rather than hard-coding the +9h offset so the helpers degrade gracefully
// if the OS or Hermes ICU data ever changes its representation, and so
// `timeZone` remains overridable for tests against UTC.

export const RITUAL_TIME_ZONE = "Asia/Seoul";

// Variant copy — Story 1.7 AC2. Exported so the component (and brand-voice
// lint downstream) can reference them by identifier instead of string-literal
// duplication.
export const RITUAL_TEXT_WEEKDAY = "오늘도 함께";
export const RITUAL_TEXT_FRIDAY = "이번 주도 살아남았어요";
export const RITUAL_TEXT_WEEKEND = "주말도 함께";
export const RITUAL_TEXT_FIRST_OF_MONTH = "이번 달 Final-3 카드가 도착했어요";

interface KstParts {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly weekdayShort: string;
}

// Pull the wall-clock parts in the target timezone via Intl. Going through
// `formatToParts` keeps us out of the trap of reading `Date.prototype.getHours`,
// which silently returns the device timezone. The `en-US` locale + `weekday: 'short'`
// gives us a stable English token (Mon/Tue/Wed/Thu/Fri/Sat/Sun) regardless of
// the device's UI locale.
function extractParts(now: Date, timeZone: string): KstParts {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    weekday: "short",
  });

  const parts = formatter.formatToParts(now);
  let year = 0;
  let month = 0;
  let day = 0;
  let hour = 0;
  let minute = 0;
  let weekdayShort = "";
  for (const part of parts) {
    switch (part.type) {
      case "year":
        year = Number.parseInt(part.value, 10);
        break;
      case "month":
        month = Number.parseInt(part.value, 10);
        break;
      case "day":
        day = Number.parseInt(part.value, 10);
        break;
      case "hour":
        hour = Number.parseInt(part.value, 10) % 24;
        break;
      case "minute":
        minute = Number.parseInt(part.value, 10);
        break;
      case "weekday":
        weekdayShort = part.value;
        break;
      default:
        break;
    }
  }
  return { year, month, day, hour, minute, weekdayShort };
}

/**
 * Returns `true` iff `now`, interpreted in `timeZone`, falls inside the
 * 06:00:00 (inclusive) → 06:05:00 (exclusive) ritual window. Default zone
 * is Asia/Seoul.
 */
export function isInRitualWindow(
  now: Date = new Date(),
  timeZone: string = RITUAL_TIME_ZONE,
): boolean {
  const { hour, minute } = extractParts(now, timeZone);
  return hour === 6 && minute >= 0 && minute < 5;
}

/**
 * Pick the variant text for the current KST date per Story 1.7 AC2.
 *   • 1st of month wins over all weekday rules → Final-3 copy.
 *   • Fri          → "이번 주도 살아남았어요"
 *   • Sat / Sun    → "주말도 함께"
 *   • Mon–Thu      → "오늘도 함께"
 */
export function selectRitualText(now: Date = new Date()): string {
  const { day, weekdayShort } = extractParts(now, RITUAL_TIME_ZONE);
  if (day === 1) return RITUAL_TEXT_FIRST_OF_MONTH;
  if (weekdayShort === "Fri") return RITUAL_TEXT_FRIDAY;
  if (weekdayShort === "Sat" || weekdayShort === "Sun") return RITUAL_TEXT_WEEKEND;
  return RITUAL_TEXT_WEEKDAY;
}

/**
 * Render a Korean-locale long-form caption like "2026년 5월 14일 (목)" for the
 * given KST date. Used as the small caption under the variant headline.
 */
export function formatKstCaption(now: Date = new Date()): string {
  const formatter = new Intl.DateTimeFormat("ko-KR", {
    timeZone: RITUAL_TIME_ZONE,
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "short",
  });
  return formatter.format(now);
}

/**
 * Yield the Asia/Seoul wall-clock date as `YYYY-MM-DD`. Used as the
 * AsyncStorage idempotency key value (`ritual.lastFiredKstDate`).
 */
export function todayKstYmd(now: Date = new Date()): string {
  const { year, month, day } = extractParts(now, RITUAL_TIME_ZONE);
  const mm = month < 10 ? `0${month}` : `${month}`;
  const dd = day < 10 ? `0${day}` : `${day}`;
  return `${year}-${mm}-${dd}`;
}
