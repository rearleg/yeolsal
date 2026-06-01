// Wallet read API client (Story 3.4 AC2 + AC3).
//
// Wraps the two new viewer-scoped BE endpoints behind `apiRequest<T>` so
// 401-refresh + ApiError mapping stay in the shared client. Direct `fetch`
// is forbidden per project-context FE rule.
//
// Distinct from `api/revival.ts` (self-revival POST) and
// `api/friendGifts.ts` (friend-gift POST + 7-day receipt window): wallet
// is the lifetime per-room read surface that powers the drill-in detail
// screens off the Wallet route.

import { apiRequest, type ApiEnvelope } from "./client";

export type LedgerReason =
  | "SURVIVAL"
  | "REVIVAL_SPEND"
  | "FRIEND_GIFT_SPEND"
  | "ROOM_LEAVE"
  | "ADJUSTMENT";

export interface LedgerEntryDto {
  readonly id: number;
  readonly roomId: number;
  /** Signed: positive = earn, negative = spend. SUM across the list =
   *  current balance (the FE never recomputes — BE is authoritative). */
  readonly delta: number;
  readonly reason: LedgerReason;
  /** ISO-8601 UTC. */
  readonly occurredAt: string;
  /** FK to revival_events.id; null for SURVIVAL / ROOM_LEAVE / ADJUSTMENT
   *  rows (set only on REVIVAL_SPEND + FRIEND_GIFT_SPEND). */
  readonly revivalEventId: number | null;
}

export type RevivalSource = "FREE_TICKET" | "PERSONAL_POINTS" | "FRIEND_GIFT";

export interface ReceivedRevivalDto {
  readonly revivalEventId: number;
  readonly roomId: number;
  readonly roomName: string;
  readonly source: RevivalSource;
  /** Receiver-only donor visibility per FR-8.3.5; null for FREE_TICKET +
   *  PERSONAL_POINTS rows (those are self-revivals — no donor). */
  readonly donorUserId: number | null;
  readonly donorNickname: string | null;
  /** ISO-8601 UTC. */
  readonly occurredAt: string;
}

export async function getPersonalPointsLedger(
  roomId: number,
): Promise<readonly LedgerEntryDto[]> {
  const envelope = await apiRequest<ApiEnvelope<readonly LedgerEntryDto[]>>(
    `/me/personal-points-ledger?roomId=${roomId}`,
  );
  return envelope.data;
}

export async function getReceivedRevivals(
  roomId: number,
): Promise<readonly ReceivedRevivalDto[]> {
  const envelope = await apiRequest<ApiEnvelope<readonly ReceivedRevivalDto[]>>(
    `/me/received-revivals?roomId=${roomId}`,
  );
  return envelope.data;
}
