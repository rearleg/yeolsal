// Friend-gift mutation + query hooks (Story 3.2 AC1 + AC7 + AC10).
//
// Three hooks share the friend-gift surface:
//
//   useSendFriendGift(roomId) — POST /rooms/{id}/revivals/gifts
//   useFriendGiftReceipts()    — GET /me/friend-gift-receipts (7-day window)
//   useHasGivenFriendGift()    — GET /me/has-given-friend-gift (lifetime-1)
//
// Cache-invalidation policy mirrors `revival.ts` patterns + AC4's error
// branching: invalidate `qk.meSurvival` on every settled state so the
// giver's balance + receiver's status stay current, plus
// `qk.friendGiftReceipts` on success so the receiver-side footnote
// re-renders without waiting for window-focus refetch.

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../../api/client";
import {
  getFriendGiftReceipts,
  getHasGivenFriendGift,
  postFriendGift,
  type FriendGiftReceiptDto,
  type FriendGiftRevivalDto,
  type HasGivenFriendGiftDto,
} from "../../../api/friendGifts";
import { qk } from "../keys";

const STALE_TIME_MS = 30_000;
const GC_TIME_MS = 5 * 60_000;

export interface SendFriendGiftVars {
  readonly targetUserId: number;
  /** Story 3.3 AC3 — friend-gift discriminator. Omit on the wire for
   *  Story 3.2 push-deep-link callers (BE defaults to PUSH_INITIATED). */
  readonly sourceSubtype?: "PUSH_INITIATED" | "WALLET_INITIATED";
}

export function useSendFriendGift(roomId: number) {
  const queryClient = useQueryClient();
  return useMutation<FriendGiftRevivalDto, ApiError, SendFriendGiftVars>({
    mutationFn: ({ targetUserId, sourceSubtype }) =>
      postFriendGift(roomId, targetUserId, sourceSubtype),
    onSuccess: () => {
      // Receiver's status flips ACTIVE + giver's balance drops 5 + room
      // pool gains 5 — all three propagate via the meSurvival cache.
      queryClient.invalidateQueries({ queryKey: qk.meSurvival });
      queryClient.invalidateQueries({ queryKey: qk.roomMessages(roomId) });
      queryClient.invalidateQueries({ queryKey: qk.friendGiftReceipts });
      // M3.5 lifetime-1 fallback may flip — invalidate so the next read
      // is fresh if the response DTO field doesn't reach the component.
      queryClient.invalidateQueries({ queryKey: qk.hasGivenFriendGift });
      // Story 3.3 — receiver is no longer RED/SPECTATOR, so the badge
      // eligibility list shrinks. Co-invalidate.
      queryClient.invalidateQueries({ queryKey: qk.friendGiftTargets });
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        // Defensive cache refresh for every 4xx — the FE's optimistic
        // disabled-state may be stale relative to BE truth (AC3 final
        // paragraph). Skip 5xx — those are transient and the user retries.
        if (error.status >= 400 && error.status < 500) {
          queryClient.invalidateQueries({ queryKey: qk.meSurvival });
          queryClient.invalidateQueries({ queryKey: qk.friendGiftTargets });
        }
      }
    },
  });
}

export function useFriendGiftReceipts() {
  return useQuery<FriendGiftReceiptDto[]>({
    queryKey: qk.friendGiftReceipts,
    queryFn: getFriendGiftReceipts,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });
}

export function useHasGivenFriendGift() {
  return useQuery<HasGivenFriendGiftDto>({
    queryKey: qk.hasGivenFriendGift,
    queryFn: getHasGivenFriendGift,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });
}
