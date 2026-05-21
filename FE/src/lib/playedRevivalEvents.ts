// Story 3.2 AC5 + CRITICAL note 6 — SecureStore-backed durable record of
// revival_event ids whose RevivalSequence the receiver has already played
// through. Surviving a force-quit + re-open is the load-bearing property
// (the receiver may open the app hours after the push lands); TanStack
// Query cache is not durable enough, so SecureStore is the right seat.
//
// The shape is a JSON-encoded `number[]` with LRU eviction at length > 50
// (a user with 50+ lifetime friend-gift receives is the edge case, not
// the common case). Reads are defensive — `unknown` narrowing on every
// element, fallback to `[]` on any parse failure (project-context FE
// rule: type external input as `unknown` and narrow).

import * as SecureStore from "expo-secure-store";

const STORAGE_KEY = "yeosal.playedRevivalEventIds";
const MAX_RETAINED = 50;

/**
 * Returns true iff the given revival_event id has been recorded as
 * played-through. Read failures (missing key / parse error / native
 * module unavailable) return false so the FE falls back to playing the
 * sequence — a single replay is preferable to a stuck "never plays"
 * state.
 */
export async function hasPlayedRevivalEvent(id: number): Promise<boolean> {
  const ids = await readPlayedIds();
  return ids.includes(id);
}

/**
 * Appends the given id to the played list. No-op when already present.
 * Idempotent so a sequence onComplete handler that fires twice (defensive
 * re-mount, etc.) doesn't grow the list with duplicates.
 */
export async function addPlayedRevivalEventId(id: number): Promise<void> {
  const ids = await readPlayedIds();
  if (ids.includes(id)) return;
  const next = [...ids, id];
  const trimmed = next.length > MAX_RETAINED
    ? next.slice(next.length - MAX_RETAINED)
    : next;
  try {
    await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // Native module unavailable / quota exceeded — swallowed because the
    // worst-case outcome is replaying the sequence, not data loss.
  }
}

async function readPlayedIds(): Promise<readonly number[]> {
  let raw: string | null = null;
  try {
    raw = await SecureStore.getItemAsync(STORAGE_KEY);
  } catch {
    return [];
  }
  if (raw == null || raw === "") return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const out: number[] = [];
  for (const entry of parsed) {
    if (typeof entry === "number" && Number.isFinite(entry)) {
      out.push(entry);
    }
  }
  return out;
}
