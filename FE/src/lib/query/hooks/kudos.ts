// Story 3.5 — useSendKudos mutation hook (AC9, AC10).
//
// Wraps `postKudos` in a TanStack mutation that invalidates the room
// messages + last-message peek so the KUDOS row appears in the chat list
// without waiting for a window-focus refetch. The WS frame from
// /topic/rooms.{id}.kudos may arrive in parallel; the receiver-side
// invalidation covers the "donor was already in the room when the push
// fired" race.
//
// The hook does NOT toast — Story 3.2 Friend Gift Modal (downstream
// consumer) owns the donor-side toast "응원이 도착했어요 🌿" so the
// visual response isn't coupled to the data layer.
//
// Project-context rule: never call queryClient.clear() — that nukes the
// AsyncStorage-persisted cache. Use invalidateQueries instead.

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../../api/client";
import {
  postKudos,
  type KudosDto,
  type SendKudosRequest,
} from "../../../api/kudos";
import { captureEvent } from "../../analytics";
import { qk } from "../keys";

export function useSendKudos(roomId: number) {
  const queryClient = useQueryClient();
  return useMutation<KudosDto, ApiError, SendKudosRequest>({
    mutationFn: (body) => postKudos(roomId, body),
    onSuccess: () => {
      // Analytics — donor-side terminal event of the revival/kudos funnel.
      captureEvent("kudos.sent", { roomId });
      // The KUDOS row appears via cache refresh; the realtime frame on
      // /topic/rooms.{id}.kudos is non-load-bearing for this surface.
      queryClient.invalidateQueries({ queryKey: qk.roomMessages(roomId) });
      queryClient.invalidateQueries({ queryKey: qk.roomLastMessage(roomId) });
    },
    // No onError cache mutation — the Story 3.2 Friend Gift Modal
    // surfaces the error via its own toast (KUDOS_ALREADY_SENT_TODAY,
    // KUDOS_TARGET_NOT_ELIGIBLE, NOT_FRIENDS). Story 3.5 ships the
    // contract; toasting is a consumer concern (AC10).
  });
}
