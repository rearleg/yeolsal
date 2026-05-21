// Friend-gift revival API client (Story 3.2 AC1 + AC7 + AC10).
//
// Wraps the three BE endpoints behind `apiRequest<T>` so 401-refresh +
// ApiError mapping stay in the shared client. Direct `fetch` is
// forbidden per project-context FE rule.

import { apiRequest, type ApiEnvelope } from "./client";

export interface FriendGiftRevivalDto {
  readonly revivalEventId: number;
  /** Always the literal "FRIEND_GIFT" — kept as a typed string for forward
   *  compatibility with future RevivalSource additions. */
  readonly source: "FRIEND_GIFT";
  readonly pointsSpent: number;
  readonly roomPointPoolAfter: number;
  /** ISO-8601 UTC. */
  readonly occurredAt: string;
  /** True iff this is the giver's first-ever successful FRIEND_GIFT send
   *  (lifetime, EXCLUDING-self query on the BE). Drives the M3.5 overlay. */
  readonly isFirstEverFriendGiftSend: boolean;
  /** Receiver display nickname — donor-side toast surfaces this verbatim. */
  readonly receiverNickname: string;
}

export interface FriendGiftReceiptDto {
  readonly revivalEventId: number;
  readonly roomId: number;
  readonly roomName: string;
  readonly donorUserId: number;
  readonly donorNickname: string;
  /** ISO-8601 UTC. */
  readonly occurredAt: string;
  /** KST day count 0..6 inclusive — 7-day-old rows are filtered out by the
   *  BE. The FE may render this directly in the footnote. */
  readonly daysSinceRevival: number;
}

export interface HasGivenFriendGiftDto {
  readonly has: boolean;
}

export async function postFriendGift(
  roomId: number,
  targetUserId: number,
  sourceSubtype?: "PUSH_INITIATED" | "WALLET_INITIATED",
): Promise<FriendGiftRevivalDto> {
  // Omit-when-undefined: Story 3.2 callers continue to send the bare
  // {targetUserId} body; Story 3.3 wallet callers carry "WALLET_INITIATED".
  // Sending `sourceSubtype: undefined` would serialize as `null` via
  // JSON.stringify and break the BE enum binding.
  const body: { targetUserId: number; sourceSubtype?: string } = { targetUserId };
  if (sourceSubtype !== undefined) {
    body.sourceSubtype = sourceSubtype;
  }
  const envelope = await apiRequest<ApiEnvelope<FriendGiftRevivalDto>>(
    `/rooms/${roomId}/revivals/gifts`,
    {
      method: "POST",
      body: JSON.stringify(body),
    },
  );
  return envelope.data;
}

export async function getFriendGiftReceipts(): Promise<FriendGiftReceiptDto[]> {
  const envelope = await apiRequest<ApiEnvelope<FriendGiftReceiptDto[]>>(
    "/me/friend-gift-receipts",
  );
  return envelope.data;
}

export async function getHasGivenFriendGift(): Promise<HasGivenFriendGiftDto> {
  const envelope = await apiRequest<ApiEnvelope<HasGivenFriendGiftDto>>(
    "/me/has-given-friend-gift",
  );
  return envelope.data;
}
