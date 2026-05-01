import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createInvite,
  createRoom,
  joinRoom,
  leaveRoom,
  listMembers,
  listRooms,
  type Room,
  type RoomInvite,
  type RoomMember,
} from "../../../api/rooms";
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
  return useMutation<Room, Error, string>({
    mutationFn: (name) => createRoom(name),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
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

export function useCreateInvite() {
  return useMutation<RoomInvite, Error, number>({
    mutationFn: (roomId) => createInvite(roomId),
    onError: (e) => toast.error(e.message),
  });
}

export function useLeaveRoom() {
  const qc = useQueryClient();
  return useMutation<void, Error, number>({
    mutationFn: (roomId) => leaveRoom(roomId),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.rooms });
    },
  });
}

export function useJoinRoom() {
  const qc = useQueryClient();
  return useMutation<RoomMember, Error, string>({
    mutationFn: (code) => joinRoom(code),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.rooms });
    },
  });
}
