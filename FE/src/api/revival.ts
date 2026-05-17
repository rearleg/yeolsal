// Self-revival API client (Story 3.1 AC2/AC3 + AC9).
//
// Wraps `POST /api/v1/rooms/{id}/revival` so the query layer never hits
// `fetch` directly — project-context FE rule mandates `apiRequest<T>` for
// all REST calls (401-refresh + ApiError mapping live there).

import { apiRequest, type ApiEnvelope } from "./client";

export type RevivalSource = "FREE_TICKET" | "PERSONAL_POINTS";

export interface RevivalEventDto {
  readonly revivalEventId: number;
  readonly source: RevivalSource;
  readonly pointsSpent: number;
  readonly roomPointPoolAfter: number;
  readonly occurredAt: string;
}

export async function postSelfRevival(
  roomId: number,
  source: RevivalSource,
): Promise<RevivalEventDto> {
  const envelope = await apiRequest<ApiEnvelope<RevivalEventDto>>(
    `/rooms/${roomId}/revival`,
    {
      method: "POST",
      body: JSON.stringify({ source }),
    },
  );
  return envelope.data;
}
