import { apiRequest, type ApiEnvelope } from "./client";

export interface Room {
  id: number;
  name: string;
  ownerId: number;
  maxMembers: number;
}

export interface RoomMember {
  roomId: number;
  userId: number;
  nickname: string;
  role: "OWNER" | "MEMBER";
}

export interface RoomInvite {
  id: number;
  roomId: number;
  code: string;
  expiresAt: string | null;
}

export async function listRooms(): Promise<Room[]> {
  const envelope = await apiRequest<ApiEnvelope<Room[]>>("/rooms");
  return envelope.data;
}

export async function createRoom(name: string): Promise<Room> {
  const envelope = await apiRequest<ApiEnvelope<Room>>("/rooms", {
    method: "POST",
    body: JSON.stringify({ name }),
  });
  return envelope.data;
}

export async function listMembers(roomId: number): Promise<RoomMember[]> {
  const envelope = await apiRequest<ApiEnvelope<RoomMember[]>>(`/rooms/${roomId}/members`);
  return envelope.data;
}

export async function createInvite(roomId: number): Promise<RoomInvite> {
  const envelope = await apiRequest<ApiEnvelope<RoomInvite>>(`/rooms/${roomId}/invites`, {
    method: "POST",
  });
  return envelope.data;
}

export async function joinRoom(code: string): Promise<RoomMember> {
  const envelope = await apiRequest<ApiEnvelope<RoomMember>>("/rooms/join", {
    method: "POST",
    body: JSON.stringify({ code: code.trim() }),
  });
  return envelope.data;
}

export async function leaveRoom(roomId: number): Promise<void> {
  await apiRequest<void>(`/rooms/${roomId}/members/me`, { method: "DELETE" });
}
