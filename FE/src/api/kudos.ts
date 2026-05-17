// Story 3.5 — kudos send API client (POST /api/v1/rooms/{id}/kudos).
//
// Wraps the BE endpoint via `apiRequest<T>` so 401-refresh + ApiError
// mapping live in the shared client — direct `fetch` is forbidden per
// project-context FE rule.

import { apiRequest, type ApiEnvelope } from "./client";

export interface KudosDto {
  readonly kudosId: number;
  readonly roomId: number;
  readonly senderUserId: number;
  readonly targetUserId: number;
  /** Trimmed donor input, possibly empty (never null per BE contract). */
  readonly message: string;
  /** ISO-8601 UTC. */
  readonly occurredAt: string;
}

export interface SendKudosRequest {
  readonly targetUserId: number;
  /** Optional — when omitted, the BE persists an empty string in payload. */
  readonly message?: string;
}

export async function postKudos(
  roomId: number,
  body: SendKudosRequest,
): Promise<KudosDto> {
  const envelope = await apiRequest<ApiEnvelope<KudosDto>>(
    `/rooms/${roomId}/kudos`,
    {
      method: "POST",
      body: JSON.stringify(body),
    },
  );
  return envelope.data;
}
