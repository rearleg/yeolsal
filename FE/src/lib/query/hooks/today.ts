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
import { useHaptic } from "../../../hooks/useHaptics";
import type { DailyEntryDto } from "../../../api/types";

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
    },
    onError: (error) => {
      toast.error(error.message);
    },
  });
}
