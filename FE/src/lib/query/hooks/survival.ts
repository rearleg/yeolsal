// Survival query hooks (Story 2.1 AC1, AC6).
//
// Three hooks share a single underlying query against the
// `getMeSurvival()` endpoint:
//
//   useMeSurvivalQuery()            — TanStack Query base hook
//   useCurrentRoomSurvivalState(id) — derived: this-room entry or null
//   useIsSpectatorEverywhere()      — derived: cross-room spectator gate
//
// The cross-room gate delegates to the pure helper
// `isSpectatorAcrossAllRooms` in `lib/spectator.ts` (Epic 1 retro T5) —
// the same helper that has standalone unit tests, so this hook only needs
// to verify the wiring, not the algorithm.

import { useQuery } from "@tanstack/react-query";
import { getMeSurvival } from "../../../api/survival";
import {
  isSpectatorAcrossAllRooms,
  type MeSurvivalEntry,
} from "../../spectator";
import { qk } from "../keys";

const STALE_TIME_MS = 30_000;
const GC_TIME_MS = 5 * 60_000;

export function useMeSurvivalQuery() {
  return useQuery<MeSurvivalEntry[]>({
    queryKey: qk.meSurvival,
    queryFn: getMeSurvival,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });
}

/**
 * Returns the {@link MeSurvivalEntry} matching {@code roomId} from the
 * cached cross-room aggregation, or {@code null} when the viewer is not a
 * member of that room (treat as "not playing in this room" — falls
 * through to the ACTIVE path on the chat screen so a stray deep-link does
 * not strand the user on the spectator banner).
 */
export function useCurrentRoomSurvivalState(
  roomId: number | null,
): MeSurvivalEntry | null {
  const query = useMeSurvivalQuery();
  if (roomId == null || !Number.isFinite(roomId)) return null;
  const entries = query.data ?? [];
  return entries.find((entry) => entry.roomId === roomId) ?? null;
}

/**
 * Cross-room spectator gate. Returns {@code true} only when the viewer has
 * at least one membership AND every membership is SPECTATOR. Brand-new
 * users with zero memberships return {@code false} (they go to the empty-
 * state CTA, not the dim spectator treatment).
 *
 * Delegates to the pure {@code isSpectatorAcrossAllRooms} helper rather
 * than re-implementing the rule — Story 2.1 reuses Epic 1 retro T5's work.
 */
export function useIsSpectatorEverywhere(): boolean {
  const query = useMeSurvivalQuery();
  return isSpectatorAcrossAllRooms(query.data ?? []);
}
