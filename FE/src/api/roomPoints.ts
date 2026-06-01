// Story 4.1 FE-1 / AC5 — REST client for the per-room point-pool snapshot.
//
// Powers the `useRoomPoints(roomId)` domain hook (AC6); never called
// directly by components per the project-context domain-hook rule.

import { apiRequest, type ApiEnvelope } from "./client";

export interface RoomPointPoolDto {
  readonly roomId: number;
  readonly total: number;
  readonly lastEventAt: string | null;
}

/**
 * GET /api/v1/rooms/{roomId}/points
 *
 * <p>BE wraps the response in the standard {@code { data: ... }} envelope
 * ({@link ApiEnvelope}) — this helper unwraps so callers see a clean DTO.
 * Member-guard violations surface as {@code ApiError} with
 * {@code status = 403}, {@code code = "FORBIDDEN"}.
 */
export async function getRoomPoints(roomId: number): Promise<RoomPointPoolDto> {
  const envelope = await apiRequest<ApiEnvelope<RoomPointPoolDto>>(
    `/rooms/${roomId}/points`,
  );
  return envelope.data;
}
