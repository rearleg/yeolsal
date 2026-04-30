// Calendar utilities for the contribution grid.
// All "entry dates" use a 06:00 local boundary: anything written before 06:00
// belongs to the previous day. Pure functions, no I/O.

const MS_PER_DAY = 86_400_000;
const DAY_BOUNDARY_HOUR = 6;

/** ISO date `YYYY-MM-DD`. */
export type IsoDate = string;

/** Day index in the rolling window — 0 = oldest (leftmost), `count - 1` = today (rightmost). */
export type DayIndex = number;

/** Monday = 0, Sunday = 6 (Korean convention, week starts Monday at top of grid). */
export type WeekdayIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6;

export function toIso(date: Date): IsoDate {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

export function fromIso(iso: IsoDate): Date {
  const [y, m, d] = iso.split("-").map((s) => Number.parseInt(s, 10));
  return new Date(y, m - 1, d);
}

/**
 * Resolve the "entry date" for a given timestamp under the 06:00 day boundary.
 * Local time of the device is used here; per-user timezone hand-off happens
 * server-side. A timestamp at 03:00 returns the previous calendar date.
 */
export function entryDateOf(now: Date = new Date()): IsoDate {
  const shifted = new Date(now.getTime() - DAY_BOUNDARY_HOUR * 60 * 60 * 1000);
  return toIso(shifted);
}

/** Returns Monday=0..Sunday=6 for the given ISO date. */
export function weekdayIndex(iso: IsoDate): WeekdayIndex {
  const d = fromIso(iso).getDay();
  return ((d + 6) % 7) as WeekdayIndex;
}

/**
 * Build an array of ISO dates spanning the rolling window that ends `today`
 * (inclusive) and contains `count` days. The list is ordered oldest first.
 */
export function rollingRange(today: IsoDate, count: number): IsoDate[] {
  if (count <= 0) {
    return [];
  }
  const end = fromIso(today).getTime();
  const out: IsoDate[] = new Array(count);
  for (let i = 0; i < count; i += 1) {
    const t = end - (count - 1 - i) * MS_PER_DAY;
    out[i] = toIso(new Date(t));
  }
  return out;
}

export interface GridLayout {
  days: IsoDate[];
  columnCount: number;
  cells: Array<Array<IsoDate | null>>;
  index: Map<IsoDate, { col: number; row: WeekdayIndex }>;
}

/**
 * Lay out a list of ISO dates into 7 rows × N week-columns.
 * - Row 0 = Monday … Row 6 = Sunday.
 * - Earlier days are padded so the first column still starts on Monday;
 *   later days are padded so the last column ends with `today` slotted into
 *   its weekday row.
 */
export function buildGridLayout(days: IsoDate[]): GridLayout {
  if (days.length === 0) {
    return { days, columnCount: 0, cells: [], index: new Map() };
  }

  const padBefore = weekdayIndex(days[0]);
  const todayWeekday = weekdayIndex(days[days.length - 1]);
  const padAfter = 6 - todayWeekday;

  const totalCells = padBefore + days.length + padAfter;
  const columnCount = Math.ceil(totalCells / 7);

  const cells: Array<Array<IsoDate | null>> = Array.from(
    { length: columnCount },
    () => Array<IsoDate | null>(7).fill(null)
  );
  const index = new Map<IsoDate, { col: number; row: WeekdayIndex }>();

  for (let i = 0; i < days.length; i += 1) {
    const slot = padBefore + i;
    const col = Math.floor(slot / 7);
    const row = (slot % 7) as WeekdayIndex;
    cells[col][row] = days[i];
    index.set(days[i], { col, row });
  }

  return { days, columnCount, cells, index };
}

/**
 * Compute, for each column, the calendar month label that should appear above
 * it. A column is labeled with the month of its first non-null cell when that
 * month differs from the previous labeled month.
 */
export function monthLabels(layout: GridLayout): Array<{ col: number; label: string }> {
  const labels: Array<{ col: number; label: string }> = [];
  let lastLabel: string | null = null;
  for (let col = 0; col < layout.columnCount; col += 1) {
    const firstDay = layout.cells[col].find((d): d is IsoDate => d !== null);
    if (!firstDay) {
      continue;
    }
    const month = Number.parseInt(firstDay.slice(5, 7), 10);
    const label = `${month}월`;
    if (label !== lastLabel) {
      labels.push({ col, label });
      lastLabel = label;
    }
  }
  return labels;
}
