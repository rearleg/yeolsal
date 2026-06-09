// Story 8.5 AC4 — SecureStore-backed PIPA opt-in/out durable record.
// Mirrors `playedRevivalEvents.ts` shape: SecureStore for durability,
// module-level cache for hot-path reads. The actual default (opt-in vs
// opt-out) is Story 8.1 onboarding screen 5's lock; this helper only
// makes both code paths available and never decides for the user.
//
// On every call we also notify the PostHog SDK so its runtime capture
// state matches the user's most-recent decision. The notification goes
// through `postHogOptIn` / `postHogOptOut` wrappers in `./analytics` so
// the SDK guard (no-op when API key absent) is centralized there.

import * as SecureStore from "expo-secure-store";
import { postHogOptIn, postHogOptOut } from "./analytics";

const STORAGE_KEY = "yeosal.analyticsConsent";

export type AnalyticsConsent = "opt_in" | "opt_out";

interface ConsentRecord {
  readonly value: AnalyticsConsent;
  readonly at: string;
}

let cache: AnalyticsConsent | null = null;
let cachePrimed = false;
let revision = 0;
let writeQueue: Promise<void> = Promise.resolve();

/**
 * Returns the user's recorded analytics-consent decision, or `null` if
 * no decision has been recorded yet. A `null` return is the "Story 8.1
 * has not surfaced the prompt yet" state — callers must treat it as
 * fail-closed (no capture) until the prompt result lands.
 */
export async function getAnalyticsConsent(): Promise<AnalyticsConsent | null> {
  if (cachePrimed) return cache;
  const readRevision = revision;
  let raw: string | null = null;
  try {
    raw = await SecureStore.getItemAsync(STORAGE_KEY);
  } catch {
    return commitRead(readRevision, null);
  }
  if (raw == null || raw === "") {
    return commitRead(readRevision, null);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return commitRead(readRevision, null);
  }
  const decoded = decodeRecord(parsed);
  return commitRead(readRevision, decoded);
}

/**
 * Persists the user's decision and notifies the PostHog SDK so capture
 * state immediately matches user intent. SecureStore failures are
 * swallowed (no throw) — the SDK side still flips so this-session
 * behavior matches; a re-boot without the durable write reverts to
 * `null` which fail-closes back to opt-out per the bootstrap contract.
 */
export async function setAnalyticsConsent(next: AnalyticsConsent): Promise<void> {
  const record: ConsentRecord = { value: next, at: new Date().toISOString() };
  revision += 1;
  cache = next;
  cachePrimed = true;
  if (next === "opt_in") {
    postHogOptIn();
  } else {
    postHogOptOut();
  }
  const write = writeQueue.then(async () => {
    try {
      await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(record));
    } catch {
      if (__DEV__) {
        console.warn("[analytics] consent SecureStore write failed — runtime state still flipped");
      }
    }
  });
  writeQueue = write.catch(() => undefined);
  await write;
}

/**
 * Clears both the in-memory cache and the durable SecureStore record.
 * Used by tests and by any future "reset to fresh-install" path. NOT
 * called on sign-out: consent is per-device (Story 8.5 AC4) and must
 * survive sign-out / sign-in cycles.
 */
export async function clearAnalyticsConsent(): Promise<void> {
  revision += 1;
  cache = null;
  cachePrimed = false;
  try {
    await SecureStore.deleteItemAsync(STORAGE_KEY);
  } catch {
    // Native module unavailable / quota exceeded — RAM cache cleared
    // is the load-bearing observable for the caller.
  }
}

function commitRead(
  readRevision: number,
  value: AnalyticsConsent | null,
): AnalyticsConsent | null {
  if (readRevision !== revision) return cache;
  cachePrimed = true;
  cache = value;
  return value;
}

function decodeRecord(input: unknown): AnalyticsConsent | null {
  if (input == null || typeof input !== "object") return null;
  const value = (input as { value?: unknown }).value;
  if (value === "opt_in" || value === "opt_out") return value;
  return null;
}
