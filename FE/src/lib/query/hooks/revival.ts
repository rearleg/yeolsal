// useSelfRevival mutation hook (Story 3.1 AC9).
//
// Wraps `postSelfRevival` in a TanStack mutation that drives the
// confirm-modal flow. The cache-invalidation policy varies by error code
// so the FE never holds stale state:
//
//   ALREADY_REVIVED          → invalidate qk.meSurvival (state must re-read)
//   FREE_TICKET_ALREADY_USED → invalidate qk.meSurvival (flag must re-read)
//   INSUFFICIENT_POINTS      → no cache change (toast covers UX)
//   any other error          → no cache change (defensive default)
//
// Project-context rule: never call queryClient.clear() — that nukes the
// AsyncStorage-persisted cache. Use invalidateQueries instead.

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../../api/client";
import {
  postSelfRevival,
  type RevivalEventDto,
  type RevivalSource,
} from "../../../api/revival";
import { qk } from "../keys";

export function useSelfRevival(roomId: number) {
  const queryClient = useQueryClient();
  return useMutation<RevivalEventDto, ApiError, RevivalSource>({
    mutationFn: (source) => postSelfRevival(roomId, source),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.meSurvival });
      // Story 3.3 — self-revival flips caller ACTIVE → defensively
      // invalidate the targets cache (which is keyed on the caller as
      // GIVER, so this scrubs any stale per-room snapshot).
      queryClient.invalidateQueries({ queryKey: qk.friendGiftTargets });
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        if (
          error.code === "ALREADY_REVIVED" ||
          error.code === "FREE_TICKET_ALREADY_USED"
        ) {
          queryClient.invalidateQueries({ queryKey: qk.meSurvival });
          queryClient.invalidateQueries({ queryKey: qk.friendGiftTargets });
        }
      }
    },
  });
}
