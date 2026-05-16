// Cross-room survival API client (Story 2.1 AC1).
//
// Wraps the existing `GET /api/v1/me/survival` controller (Epic 1 retro T4,
// shipped on main via PR #64) so the FE query layer never hits `fetch`
// directly — the project-context FE rule mandates `apiRequest<T>` for all
// REST calls (401-refresh + ApiError mapping live there).
//
// Re-exports `MeSurvivalEntry` from `lib/spectator.ts` so callers don't need
// to know about the helper file's existence to read the wire type.

import { apiRequest, type ApiEnvelope } from "./client";
import type { MeSurvivalEntry } from "../lib/spectator";

export type { MeSurvivalEntry };

export async function getMeSurvival(): Promise<MeSurvivalEntry[]> {
  const envelope = await apiRequest<ApiEnvelope<MeSurvivalEntry[]>>("/me/survival");
  return envelope.data;
}

// Story 2.3 — per-(user, room) record visibility opt-in. Server returns one
// entry per room the user is a member of; rooms without an explicit pref row
// are materialized with shareOnElimination=false (default-private). updatedAt
// is null until the user has touched the toggle at least once.
export interface VisibilityPrefDto {
  roomId: number;
  roomName: string;
  shareOnElimination: boolean;
  updatedAt: string | null;
}

export async function getRecordVisibilityPrefs(): Promise<VisibilityPrefDto[]> {
  const envelope = await apiRequest<ApiEnvelope<VisibilityPrefDto[]>>(
    "/me/visibility-prefs"
  );
  return envelope.data;
}

export async function updateRecordVisibilityPref(
  roomId: number,
  shareOnElimination: boolean
): Promise<VisibilityPrefDto> {
  const envelope = await apiRequest<ApiEnvelope<VisibilityPrefDto>>(
    "/me/visibility-prefs",
    {
      method: "POST",
      body: JSON.stringify({ roomId, shareOnElimination }),
    }
  );
  return envelope.data;
}
