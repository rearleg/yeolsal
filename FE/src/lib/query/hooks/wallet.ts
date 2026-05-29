// Wallet read hooks (Story 3.4 AC2 + AC3).
//
// Two hooks share the per-room wallet surface:
//
//   usePersonalPointsLedger(roomId) — GET /me/personal-points-ledger?roomId=
//   useReceivedRevivals(roomId)     — GET /me/received-revivals?roomId=
//
// staleTime / gcTime mirror the friendGiftTargets + friendGiftReceipts
// precedent so the wallet drill-in views never re-fetch while the user
// is rapidly navigating between detail and overview screens.
//
// Components consume these hooks (never `useQuery` directly) per
// project-context FE rule.

import { useQuery } from "@tanstack/react-query";
import {
  getPersonalPointsLedger,
  getReceivedRevivals,
  type LedgerEntryDto,
  type ReceivedRevivalDto,
} from "../../../api/wallet";
import { qk } from "../keys";

const STALE_TIME_MS = 30_000;
const GC_TIME_MS = 5 * 60_000;

export function usePersonalPointsLedger(roomId: number | null) {
  return useQuery<readonly LedgerEntryDto[]>({
    queryKey: roomId == null
      ? qk.personalPointsLedger(-1)
      : qk.personalPointsLedger(roomId),
    queryFn: () => getPersonalPointsLedger(roomId ?? -1),
    enabled: roomId != null && Number.isFinite(roomId) && roomId > 0,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });
}

export function useReceivedRevivals(roomId: number | null) {
  return useQuery<readonly ReceivedRevivalDto[]>({
    queryKey: roomId == null
      ? qk.receivedRevivals(-1)
      : qk.receivedRevivals(roomId),
    queryFn: () => getReceivedRevivals(roomId ?? -1),
    enabled: roomId != null && Number.isFinite(roomId) && roomId > 0,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });
}
