import { apiRequest, type ApiEnvelope } from "./client";

/**
 * Mirrors BE {@code ChatMessageKind}. PR G writes the non-USER system
 * variants; Story 3.5 adds {@code "KUDOS"} — the only non-USER kind with
 * a non-null {@code senderUserId} (the donor of the kudos).
 */
export type ChatMessageKind =
  | "USER"
  | "SYSTEM"
  | "GOAL"
  | "REFLECTION"
  | "MILESTONE"
  | "AUTO_LEAVE"
  | "KUDOS";

export interface ChatMessageDto {
  id: number;
  roomId: number;
  /** null for system-speech messages (kind != "USER"). */
  senderUserId: number | null;
  kind: ChatMessageKind;
  body: string;
  /** PR G writes structured metadata; FE treats it as opaque object. */
  payload: Record<string, unknown>;
  /** ISO 8601 timestamptz. */
  createdAt: string;
}

export interface ChatMessagePage {
  messages: ChatMessageDto[];
  /** Pass back as `cursor` to fetch the page older than this id. null = no more. */
  nextCursor: number | null;
}

export async function getRoomMessages(
  roomId: number,
  cursor: number | null,
  limit = 30,
): Promise<ChatMessagePage> {
  const qs = new URLSearchParams();
  if (cursor != null) qs.set("cursor", String(cursor));
  qs.set("limit", String(limit));
  const envelope = await apiRequest<ApiEnvelope<ChatMessagePage>>(
    `/rooms/${roomId}/messages?${qs.toString()}`,
  );
  return envelope.data;
}

export async function sendRoomMessage(roomId: number, body: string): Promise<ChatMessageDto> {
  const envelope = await apiRequest<ApiEnvelope<ChatMessageDto>>(
    `/rooms/${roomId}/messages`,
    {
      method: "POST",
      body: JSON.stringify({ body }),
    },
  );
  return envelope.data;
}
