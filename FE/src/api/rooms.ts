import { apiRequest, type ApiEnvelope } from "./client";

// Whitelisted minimum-days values mirroring the BE CHECK constraint
// (chk_rooms_min_daily_goal_days). 31 means "every day of the calendar
// month"; the server caps it to the actual length of the month at the
// monthly evaluation pass.
export const MIN_DAYS_OPTIONS = [10, 15, 20, 31] as const;
export type MinDays = (typeof MIN_DAYS_OPTIONS)[number];

// Single source of truth for the human-readable label so the home tab,
// join screen, and the segmented control all stay in sync.
export const MIN_DAYS_LABELS: Record<MinDays, string> = {
  10: "10일",
  15: "15일",
  20: "20일",
  31: "매일",
};

export interface Room {
  id: number;
  name: string;
  ownerId: number;
  maxMembers: number;
  minDailyGoalDays: MinDays;
}

export interface RoomMember {
  roomId: number;
  userId: number;
  nickname: string;
  role: "OWNER" | "MEMBER";
  currentMinimum: MinDays;
  warningCount: number;
}

export interface RoomInvite {
  id: number;
  roomId: number;
  code: string;
  expiresAt: string | null;
}

/** Per-member snapshot for the Today screen's group-mode card. Mirrors
 * BE {@code RoomService.MemberTodayDto}. {@code date} is ISO yyyy-MM-dd. */
export interface MemberTodayDto {
  userId: number;
  nickname: string;
  date: string;
  goal: string;
  goalSet: boolean;
  completedTodoCount: number;
  reflectionSubmitted: boolean;
  currentStreak: number;
}

export interface CreateRoomInput {
  name: string;
  minDailyGoalDays: MinDays;
}

export async function listRooms(): Promise<Room[]> {
  const envelope = await apiRequest<ApiEnvelope<Room[]>>("/rooms");
  return envelope.data;
}

export async function createRoom(input: CreateRoomInput): Promise<Room> {
  const envelope = await apiRequest<ApiEnvelope<Room>>("/rooms", {
    method: "POST",
    body: JSON.stringify({
      name: input.name,
      minDailyGoalDays: input.minDailyGoalDays,
    }),
  });
  return envelope.data;
}

export async function listMembers(roomId: number): Promise<RoomMember[]> {
  const envelope = await apiRequest<ApiEnvelope<RoomMember[]>>(`/rooms/${roomId}/members`);
  return envelope.data;
}

/**
 * Fetches the day-of snapshot for every member of {@code roomId}. {@code date}
 * is the entry-date the FE wants to render (typically {@code entryDateOf()}).
 */
export async function getRoomToday(roomId: number, date: string): Promise<MemberTodayDto[]> {
  const envelope = await apiRequest<ApiEnvelope<MemberTodayDto[]>>(
    `/rooms/${roomId}/today?date=${encodeURIComponent(date)}`,
  );
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
