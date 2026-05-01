import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  addTodo,
  deleteTodo,
  fetchToday,
  patchTodo,
  submitReflection,
  updateGoal,
} from "../api";
import { qk } from "../keys";
import { toast } from "../../toast";
import { captureQueryError } from "../../sentry";
import { useHaptic } from "../../../hooks/useHaptics";
import { ApiError } from "../../../api/client";
import type { DailyEntryDto } from "../../../api/types";

const REFLECTION_ALREADY_SUBMITTED = "이미 회고를 제출했습니다.";
const REFLECTION_GENERIC_ERROR = "회고 저장에 실패했어요. 잠시 뒤 다시 시도해 주세요.";

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

export function useUpdateGoal() {
  const qc = useQueryClient();

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
    },
    onSuccess: (data) => {
      qc.setQueryData(qk.today, data);
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
