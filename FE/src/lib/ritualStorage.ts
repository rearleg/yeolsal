// Per-user "ritual already fired today" persistence — Story 1.7 AC4.
// Backed by AsyncStorage so the once-per-KST-date idempotency invariant
// survives app force-quits and process restarts. Reads return null on
// miss OR on parse failure (defensive — the only callable shape is a
// YYYY-MM-DD ISO date; anything else is treated as a miss). Mirrors the
// chatRead.ts precedent: named exports, prefixed key constant, no class.

import AsyncStorage from "@react-native-async-storage/async-storage";

export const RITUAL_LAST_FIRED_KEY = "ritual.lastFiredKstDate";

// YYYY-MM-DD — anchored, no slop. Year >=1000 so we never accept a
// truncated value like "26-5-14".
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function isIsoDateString(value: unknown): value is string {
  return typeof value === "string" && ISO_DATE_PATTERN.test(value);
}

export async function getLastFiredKstDate(): Promise<string | null> {
  try {
    const raw = await AsyncStorage.getItem(RITUAL_LAST_FIRED_KEY);
    if (!isIsoDateString(raw)) return null;
    return raw;
  } catch {
    // Defensive: AsyncStorage failure must not break the ritual gating.
    // Returning null falls through to "treat as not yet fired today",
    // which is the safe default (worst case: the user sees the overlay
    // a second time, never the inverse — they never miss it).
    return null;
  }
}

export async function setLastFiredKstDate(ymd: string): Promise<void> {
  await AsyncStorage.setItem(RITUAL_LAST_FIRED_KEY, ymd);
}
