// Story 8.1 AC1 — SecureStore-backed durable onboarding record.
// Mirrors `playedRevivalEvents.ts` (defensive `unknown` narrowing, missing/
// parse-failure → treat as not-yet-completed) and `analyticsConsent.ts`
// (module-level cache + revision guard so an in-flight read can never
// clobber a newer write).
//
// The record is per-device, not per-account: it is NOT cleared on sign-out.
// A signed-out + signed-in user must not re-onboard; a brand-new device
// re-onboards once, which is the accepted v1 cost.
//
// `completedAt` is encoded as an absent field while only the deferred
// destination has been stashed (pre-S1 auth handoff). Decode maps that
// absence to `null` so callers can distinguish "partial — destination
// pending" from "completed".

import * as SecureStore from "expo-secure-store";

const STORAGE_KEY = "yeosal.onboardingState";
const RECORD_VERSION = 1;

export interface OnboardingStateRecord {
  readonly version: number;
  readonly completedAt: string | null;
  readonly deferredDestination: string | null;
}

let cache: OnboardingStateRecord | null = null;
let cachePrimed = false;
let revision = 0;

/**
 * Returns the durable onboarding record, or `null` when no decision has
 * been recorded yet. Callers MUST treat `null` (and a record whose
 * `completedAt` is null) as "needs S1–S5". Read failures return `null`
 * without priming the cache — fail-open: the user re-onboards once
 * rather than getting stuck behind a broken keychain.
 */
export async function getOnboardingState(): Promise<OnboardingStateRecord | null> {
  if (cachePrimed) return cache;
  const readRevision = revision;
  let raw: string | null = null;
  try {
    raw = await SecureStore.getItemAsync(STORAGE_KEY);
  } catch {
    return null;
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
  return commitRead(readRevision, decodeRecord(parsed));
}

/**
 * Persists the completion decision. The deferred destination passed in is
 * preserved on the record so the defensive `/onboarding` re-entry path can
 * still resolve `deferredDestination ?? "/today"` (AC2).
 */
export async function markOnboardingCompleted(
  deferredDestination: string | null,
): Promise<void> {
  await writeRecord({
    version: RECORD_VERSION,
    completedAt: new Date().toISOString(),
    deferredDestination,
  });
}

/**
 * Stashes the post-auth deeplink destination before onboarding runs
 * (pre-S1 auth handoff). `completedAt` stays absent so the next read
 * recognizes the partial record: NOT completed, WITH a pending destination.
 */
export async function setDeferredDestination(
  destination: string | null,
): Promise<void> {
  const prior = await getOnboardingState();
  await writeRecord({
    version: RECORD_VERSION,
    completedAt: prior?.completedAt ?? null,
    deferredDestination: destination,
  });
}

/** Tests only — production code never resets onboarding state. */
export async function clearOnboardingState(): Promise<void> {
  revision += 1;
  cache = null;
  cachePrimed = false;
  try {
    await SecureStore.deleteItemAsync(STORAGE_KEY);
  } catch {
    // RAM cache cleared is the load-bearing observable for callers.
  }
}

async function writeRecord(record: OnboardingStateRecord): Promise<void> {
  revision += 1;
  cache = record;
  cachePrimed = true;
  const encoded: Record<string, unknown> = {
    version: record.version,
    deferredDestination: record.deferredDestination,
  };
  if (record.completedAt != null) {
    encoded.completedAt = record.completedAt;
  }
  try {
    await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(encoded));
  } catch {
    if (__DEV__) {
      console.warn(
        "[onboarding] state SecureStore write failed — RAM cache still updated",
      );
    }
  }
}

function commitRead(
  readRevision: number,
  value: OnboardingStateRecord | null,
): OnboardingStateRecord | null {
  if (readRevision !== revision) return cache;
  cachePrimed = true;
  cache = value;
  return value;
}

function decodeRecord(input: unknown): OnboardingStateRecord | null {
  if (input == null || typeof input !== "object") return null;
  const record = input as {
    version?: unknown;
    completedAt?: unknown;
    deferredDestination?: unknown;
  };
  if (typeof record.version !== "number" || !Number.isFinite(record.version)) {
    return null;
  }
  return {
    version: record.version,
    completedAt:
      typeof record.completedAt === "string" ? record.completedAt : null,
    deferredDestination:
      typeof record.deferredDestination === "string"
        ? record.deferredDestination
        : null,
  };
}
