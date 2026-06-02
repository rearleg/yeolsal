// useRoomRule(roomId) fetches the rule state via GET /api/v1/rooms/{id}/rule.
// useUpdateRoomRule() wraps the leader-only PATCH and invalidates the cache
// on success — never setQueryData(mutation.data), because the mutation only
// returns the pending row and would clobber `current`.

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../../api/client";
import {
  getRoomRule,
  updateRoomRule,
  type RoomRuleStateDto,
  type RoomRuleVersionDto,
  type UpdateRoomRuleVars,
} from "../../../api/rooms";
import { qk } from "../keys";

const STALE_TIME_MS = 30_000;

export function useRoomRule(roomId: number) {
  return useQuery<RoomRuleStateDto>({
    queryKey: qk.roomRule(roomId),
    queryFn: () => getRoomRule(roomId),
    staleTime: STALE_TIME_MS,
    enabled: Number.isFinite(roomId) && roomId > 0,
  });
}

export function useUpdateRoomRule() {
  const queryClient = useQueryClient();
  return useMutation<RoomRuleVersionDto, ApiError, UpdateRoomRuleVars>({
    mutationFn: ({ roomId, preset, weekendInclude }) =>
      updateRoomRule(roomId, { preset, weekendInclude }),
    onSuccess: (_data, { roomId }) => {
      queryClient.invalidateQueries({ queryKey: qk.roomRule(roomId) });
    },
  });
}
