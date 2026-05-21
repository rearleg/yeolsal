// Friend-gift TARGETS query hook (Story 3.3 AC1, AC6).
//
// Two hooks share the targets cache:
//
//   useFriendGiftTargets()              — full per-room aggregation
//   useFriendGiftTargetsForRoom(roomId) — derived per-room view
//
// The per-room helper filters the cache in-memory; no second BE call.
// Components consume the hook (never `useQuery` directly) per project-
// context FE rule.

import { useQuery } from "@tanstack/react-query";
import {
  getFriendGiftTargets,
  type FriendGiftTargetSummaryDto,
} from "../../../api/friendGiftTargets";
import { qk } from "../keys";

const STALE_TIME_MS = 30_000;
const GC_TIME_MS = 5 * 60_000;

export function useFriendGiftTargets() {
  return useQuery<readonly FriendGiftTargetSummaryDto[]>({
    queryKey: qk.friendGiftTargets,
    queryFn: getFriendGiftTargets,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });
}

/**
 * Returns the cached {@link FriendGiftTargetSummaryDto} matching
 * {@code roomId}, or {@code null} when the room has no eligible targets
 * (or the cache is still loading / errored).
 *
 * <p>Pure-function derived view over {@link useFriendGiftTargets} — no
 * second BE roundtrip.
 */
export function useFriendGiftTargetsForRoom(
  roomId: number | null,
): FriendGiftTargetSummaryDto | null {
  const query = useFriendGiftTargets();
  if (roomId == null || !Number.isFinite(roomId)) return null;
  const data = query.data ?? [];
  return data.find((entry) => entry.roomId === roomId) ?? null;
}
