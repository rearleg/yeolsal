// Story 2.3 — per-(user, room) record-visibility opt-in hooks.
//
// One list query keyed at qk.recordVisibilityPrefs feeds every per-room
// Settings screen. useRecordVisibilityPref(roomId) derives the single row
// from that list — the FE never default-constructs a missing pref because
// the server materializes `shareOnElimination = false` server-side (AC6).
//
// useUpdateRecordVisibilityPref() applies an optimistic update on the list
// cache via onMutate's snapshot + setQueryData(map), then rolls back in
// onError. onSettled invalidates so the post-write updated_at is fetched.

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getRecordVisibilityPrefs,
  updateRecordVisibilityPref,
  type VisibilityPrefDto,
} from "../../../api/survival";
import { qk } from "../keys";

const STALE_TIME_MS = 30_000;
const GC_TIME_MS = 5 * 60_000;

export function useRecordVisibilityPrefsQuery() {
  return useQuery<VisibilityPrefDto[]>({
    queryKey: qk.recordVisibilityPrefs,
    queryFn: getRecordVisibilityPrefs,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });
}

/**
 * Returns the cached row for one room, or null when the room is not present
 * (caller is not a member or the list query hasn't resolved yet). Backed by
 * a single shared list query so the Settings page batch-loads all rooms in
 * one round trip.
 */
export function useRecordVisibilityPref(roomId: number): VisibilityPrefDto | null {
  const query = useRecordVisibilityPrefsQuery();
  if (!Number.isFinite(roomId)) return null;
  const list = query.data ?? [];
  return list.find((p) => p.roomId === roomId) ?? null;
}

interface UpdateRecordVisibilityPrefVars {
  roomId: number;
  shareOnElimination: boolean;
}

interface OptimisticContext {
  previous: VisibilityPrefDto[] | undefined;
}

export function useUpdateRecordVisibilityPref() {
  const qc = useQueryClient();
  return useMutation<
    VisibilityPrefDto,
    Error,
    UpdateRecordVisibilityPrefVars,
    OptimisticContext
  >({
    mutationFn: (vars) =>
      updateRecordVisibilityPref(vars.roomId, vars.shareOnElimination),
    onMutate: async (vars) => {
      await qc.cancelQueries({ queryKey: qk.recordVisibilityPrefs });
      const previous = qc.getQueryData<VisibilityPrefDto[]>(qk.recordVisibilityPrefs);
      if (previous != null) {
        const optimistic = previous.map((row) =>
          row.roomId === vars.roomId
            ? { ...row, shareOnElimination: vars.shareOnElimination }
            : row
        );
        qc.setQueryData<VisibilityPrefDto[]>(qk.recordVisibilityPrefs, optimistic);
      }
      return { previous };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous !== undefined) {
        qc.setQueryData<VisibilityPrefDto[]>(qk.recordVisibilityPrefs, context.previous);
      }
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: qk.recordVisibilityPrefs });
    },
  });
}
