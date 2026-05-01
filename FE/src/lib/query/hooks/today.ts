import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchToday, patchTodo } from "../api";
import { qk } from "../keys";
import { toast } from "../../toast";
import { useHaptic } from "../../../hooks/useHaptics";
import type { DailyEntryDto } from "../../../api/types";

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

interface ToggleContext {
  prev: DailyEntryDto | null | undefined;
}

export function useToggleTodo() {
  const qc = useQueryClient();
  const haptic = useHaptic();

  return useMutation<void, Error, ToggleVars, ToggleContext>({
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
