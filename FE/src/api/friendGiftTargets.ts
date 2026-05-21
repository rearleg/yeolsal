// Friend-gift TARGETS API client (Story 3.3 AC1).
//
// Wraps GET /me/friend-gift-targets behind `apiRequest<T>` so 401-refresh
// + ApiError handling stay in the shared client. Direct `fetch` is
// forbidden per project-context FE rule.
//
// Distinct from `api/friendGifts.ts` (Story 3.2 — gift-spending surface):
// targets is read-only and powers the wallet badge surface.

import { apiRequest, type ApiEnvelope } from "./client";

export interface EligibleFriendDto {
  readonly userId: number;
  readonly nickname: string;
  /** Raw `survival_state.status` value — picker uses it as a UX hint. */
  readonly status: "RED" | "SPECTATOR";
  /** ISO-8601 UTC. The FE never parses; informational only. */
  readonly eliminatedAt: string;
}

export interface FriendGiftTargetSummaryDto {
  readonly roomId: number;
  readonly roomName: string;
  /** Redundant with friends.length but BE writes both so the FE can
   *  early-return without iterating. */
  readonly eligibleCount: number;
  readonly friends: readonly EligibleFriendDto[];
}

export async function getFriendGiftTargets(): Promise<
  readonly FriendGiftTargetSummaryDto[]
> {
  const envelope = await apiRequest<
    ApiEnvelope<readonly FriendGiftTargetSummaryDto[]>
  >("/me/friend-gift-targets");
  return envelope.data;
}
