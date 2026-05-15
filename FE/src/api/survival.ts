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
