import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import {
  addTodo,
  deleteTodo,
  fetchToday,
  patchTodo,
  submitReflection,
  updateGoal,
  updateReflection,
} from "../api";
import { qk } from "../keys";
import { toast } from "../../toast";
import { captureQueryError } from "../../sentry";
import { useHaptic } from "../../../hooks/useHaptics";
import { ApiError } from "../../../api/client";
import type { DailyEntryDto, ReflectionDto } from "../../../api/types";

const REFLECTION_ALREADY_SUBMITTED = "이미 회고를 제출했습니다.";
const REFLECTION_GENERIC_ERROR = "회고를 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.";

/**
 * Invalidate every cached room-chat list so a goal/reflection mutation —
 * which fans a system row out via afterCommit on the BE — flips an
 * already-open chat screen into a fresh fetch. The actor wouldn't see
 * their own GOAL/REFLECTION/MILESTONE row otherwise (other members get
 * it on their next visit, but the actor's cache is still stale).
 */
function invalidateChatMessages(qc: QueryClient): void {
  qc.invalidateQueries({
    predicate: (q) =>
      Array.isArray(q.queryKey) &&
      q.queryKey[0] === "rooms" &&
      q.queryKey[2] === "messages",
  });
}

type Snapshot = DailyEntryDto | null | undefined;
interface RollbackContext {
  prev: Snapshot;
}

export function useTodayQuery() {
  return useQuery({
    queryKey: qk.today,
    queryFn: fetchToday,
  });
}

interface ToggleVars {
  id: number;
  completed: boolean;
}

export function useToggleTodo() {
  const qc = useQueryClient();
  const haptic = useHaptic();

  return useMutation<void, Error, ToggleVars, RollbackContext>({
    mutationFn: ({ id, completed }) => patchTodo(id, { completed }),
    onMutate: async ({ id, completed }) => {
      await qc.cancelQueries({ queryKey: qk.today });
      const prev = qc.getQueryData<DailyEntryDto | null>(qk.today);
      qc.setQueryData<DailyEntryDto | null>(qk.today, (old) =>
        old
          ? {
              ...old,
              todos: old.todos.map((t) => (t.id === id ? { ...t, completed } : t)),
            }
          : old,
      );
      haptic("light");
      return { prev };
    },
    onError: (error, _vars, context) => {
      if (context && context.prev !== undefined) {
        qc.setQueryData(qk.today, context.prev);
      }
      toast.error(error.message);
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: qk.today });
    },
  });
}

interface UpdateGoalOptions {
  // Caller-supplied side effects that fire *after* the cache is reconciled.
  // GoalCard uses these to drive its IDLE/EDITING → SAVING → SAVED state
  // machine, surface a toast, and trigger haptics on success.
  onSaved?: (entry: DailyEntryDto) => void;
  onFailed?: (error: Error) => void;
}

export function useUpdateGoal(options: UpdateGoalOptions = {}) {
  const qc = useQueryClient();
  const { onSaved, onFailed } = options;

  return useMutation<DailyEntryDto, Error, string, RollbackContext>({
    mutationFn: (goal) => updateGoal(goal),
    onMutate: async (goal) => {
      await qc.cancelQueries({ queryKey: qk.today });
      const prev = qc.getQueryData<DailyEntryDto | null>(qk.today);
      qc.setQueryData<DailyEntryDto | null>(qk.today, (old) => (old ? { ...old, goal } : old));
      return { prev };
    },
    onError: (error, _vars, context) => {
      if (context && context.prev !== undefined) {
        qc.setQueryData(qk.today, context.prev);
      }
      toast.error(error.message);
      onFailed?.(error);
    },
    onSuccess: (data) => {
      qc.setQueryData(qk.today, data);
      // The first non-blank goal save fans a GOAL system row out to every
      // group the actor belongs to — refresh open chat screens so the
      // actor sees their own row.
      invalidateChatMessages(qc);
      onSaved?.(data);
    },
  });
}

export function useAddTodo() {
  const qc = useQueryClient();

  return useMutation<void, Error, string>({
    mutationFn: (title) => addTodo(title),
    onError: (error) => {
      toast.error(error.message);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.today });
    },
  });
}

export function useDeleteTodo() {
  const qc = useQueryClient();

  return useMutation<void, Error, number, RollbackContext>({
    mutationFn: (id) => deleteTodo(id),
    onMutate: async (id) => {
      await qc.cancelQueries({ queryKey: qk.today });
      const prev = qc.getQueryData<DailyEntryDto | null>(qk.today);
      qc.setQueryData<DailyEntryDto | null>(qk.today, (old) =>
        old ? { ...old, todos: old.todos.filter((t) => t.id !== id) } : old,
      );
      return { prev };
    },
    onError: (error, _id, context) => {
      if (context && context.prev !== undefined) {
        qc.setQueryData(qk.today, context.prev);
      }
      toast.error(error.message);
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: qk.today });
    },
  });
}

interface ReflectionVars {
  dailyEntryId: number;
  body: string;
}

export function useSubmitReflection() {
  const qc = useQueryClient();
  const haptic = useHaptic();

  return useMutation<void, Error, ReflectionVars>({
    mutationFn: ({ dailyEntryId, body }) => submitReflection(dailyEntryId, body),
    onSuccess: () => {
      haptic("success");
      qc.invalidateQueries({ queryKey: qk.today });
      // The reflection save fans REFLECTION + MILESTONE system rows out
      // to every group the actor belongs to. Invalidate any open chat
      // list so the actor sees their own announcements.
      invalidateChatMessages(qc);
    },
    onError: (error) => {
      // The BE remaps the dup-race on `reflections.daily_entry_id` to a 400
      // BAD_REQUEST. That state means "the server already accepted it" — refresh
      // the cache so the UI flips into the submitted state instead of nagging.
      if (error instanceof ApiError) {
        if (error.code === "BAD_REQUEST" && error.message === REFLECTION_ALREADY_SUBMITTED) {
          // The server already has the reflection — match the success path's
          // tactile feedback so the user perceives the action as completed.
          haptic("success");
          toast.info(REFLECTION_ALREADY_SUBMITTED);
          qc.invalidateQueries({ queryKey: qk.today });
          invalidateChatMessages(qc);
          return;
        }
        if (error.status >= 500 || error.code === "INTERNAL_ERROR") {
          captureQueryError(error, { kind: "mutation", key: qk.today });
          toast.error(REFLECTION_GENERIC_ERROR);
          return;
        }
      }
      toast.error(error.message || REFLECTION_GENERIC_ERROR);
    },
  });
}

interface UpdateReflectionVars {
  reflectionId: number;
  body: string;
}

/**
 * Edit an already-submitted reflection. Mirrors the BE invariant: edits
 * must NOT re-fan-out the REFLECTION/MILESTONE chat rows. Where
 * useSubmitReflection invalidates room chat caches so the actor sees their
 * own announcement, this hook deliberately does not — pinning the
 * counter-contract via the "DOES NOT invalidate room chat caches" test.
 */
export function useUpdateReflection() {
  const qc = useQueryClient();
  const haptic = useHaptic();

  return useMutation<ReflectionDto, Error, UpdateReflectionVars, RollbackContext>({
    mutationFn: ({ reflectionId, body }) => updateReflection(reflectionId, body),
    onMutate: async ({ body }) => {
      await qc.cancelQueries({ queryKey: qk.today });
      const prev = qc.getQueryData<DailyEntryDto | null>(qk.today);
      qc.setQueryData<DailyEntryDto | null>(qk.today, (old) =>
        old && old.reflection
          ? { ...old, reflection: { ...old.reflection, body } }
          : old,
      );
      return { prev };
    },
    onError: (error, _vars, context) => {
      if (context && context.prev !== undefined) {
        qc.setQueryData(qk.today, context.prev);
      }
      toast.error(error.message);
    },
    onSuccess: (data) => {
      qc.setQueryData<DailyEntryDto | null>(qk.today, (old) =>
        old ? { ...old, reflection: data } : old,
      );
      haptic("success");
    },
  });
}
