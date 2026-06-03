import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createInvite,
  createRoom,
  getRoomToday,
  joinRoom,
  leaveRoom,
  listMembers,
  listRooms,
  transferLeadership,
  updateMemberCap,
  updateMyMinimum,
  type CreateRoomInput,
  type MemberTodayDto,
  type MinDays,
  type Room,
  type RoomInvite,
  type RoomMember,
  type TransferLeadershipVars,
  type UpdateMemberCapVars,
} from "../../../api/rooms";
import { ApiError } from "../../../api/client";
import { useHaptic } from "../../../hooks/useHaptics";
import { qk } from "../keys";
import { toast } from "../../toast";

export function useRoomsQuery() {
  return useQuery<Room[]>({
    queryKey: qk.rooms,
    queryFn: listRooms,
  });
}

export function useCreateRoom() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<Room, Error, CreateRoomInput>({
    mutationFn: (input) => createRoom(input),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
      haptic("success");
      qc.invalidateQueries({ queryKey: qk.rooms });
    },
  });
}

export function useRoomMembersQuery(roomId: number) {
  return useQuery<RoomMember[]>({
    queryKey: qk.roomMembers(roomId),
    queryFn: () => listMembers(roomId),
    enabled: Number.isFinite(roomId),
  });
}

/**
 * Group-mode Today snapshot. Pass a falsy roomId to keep the query parked
 * (the query is gated by `enabled`, so React Query never fires the request
 * until the user actually picks a group).
 */
export function useGroupTodayQuery(roomId: number | null, date: string) {
  return useQuery<MemberTodayDto[]>({
    queryKey: qk.roomToday(roomId ?? 0, date),
    queryFn: () => getRoomToday(roomId as number, date),
    enabled: roomId != null && Number.isFinite(roomId) && date.length > 0,
  });
}

export function useCreateInvite() {
  const haptic = useHaptic();
  return useMutation<RoomInvite, Error, number>({
    mutationFn: (roomId) => createInvite(roomId),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
      haptic("success");
    },
  });
}

export function useLeaveRoom() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<void, Error, number>({
    mutationFn: (roomId) => leaveRoom(roomId),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
      haptic("light");
      qc.invalidateQueries({ queryKey: qk.rooms });
    },
  });
}

interface UpdateMinVars {
  roomId: number;
  minDailyGoalDays: MinDays;
}

export function useUpdateMyMinimum() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<RoomMember, Error, UpdateMinVars>({
    mutationFn: ({ roomId, minDailyGoalDays }) => updateMyMinimum(roomId, minDailyGoalDays),
    onError: (e) => toast.error(e.message),
    onSuccess: (_member, vars) => {
      haptic("success");
      qc.invalidateQueries({ queryKey: qk.roomMembers(vars.roomId) });
    },
  });
}

export function useJoinRoom() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<RoomMember, Error, string>({
    mutationFn: (code) => joinRoom(code),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
      haptic("success");
      qc.invalidateQueries({ queryKey: qk.rooms });
    },
  });
}

// Story 5.2 — leader-only PATCH /rooms/{id}/members/cap. Invalidates the
// listing so every consumer (Today, Settings, Wallet) sees the new pending
// fields without manual refetch. onError is intentionally not wired to a
// toast here — the screen layer renders the precise 400 / 403 / 404 copy.
export function useUpdateMemberCap() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<Room, ApiError, UpdateMemberCapVars>({
    mutationFn: ({ roomId, maxMembers }) =>
      updateMemberCap(roomId, { maxMembers }),
    onSuccess: () => {
      haptic("success");
      qc.invalidateQueries({ queryKey: qk.rooms });
    },
  });
}

// Story 5.2 — leader-only POST /rooms/{id}/transfer-leadership. Owner change
// ripples through both the listing (room.ownerId) and the per-room membership
// cache (role flip on both members), so invalidate both keys.
export function useTransferLeadership() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<Room, ApiError, TransferLeadershipVars>({
    mutationFn: ({ roomId, targetUserId }) =>
      transferLeadership(roomId, { targetUserId }),
    onSuccess: (_data, { roomId }) => {
      haptic("success");
      qc.invalidateQueries({ queryKey: qk.rooms });
      qc.invalidateQueries({ queryKey: qk.roomMembers(roomId) });
    },
  });
}
