import { apiRequest, type ApiEnvelope } from "../../api/client";
import type { DailyEntryDto } from "../../api/types";

export const fetchToday = (): Promise<DailyEntryDto | null> =>
  apiRequest<ApiEnvelope<DailyEntryDto | null>>("/daily-entries/today").then((r) => r.data);

export const patchTodo = (
  id: number,
  body: { title?: string; completed?: boolean },
): Promise<void> =>
  apiRequest<void>(`/todo-items/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
