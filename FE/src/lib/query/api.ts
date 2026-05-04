import { apiRequest, type ApiEnvelope } from "../../api/client";
import type { DailyEntryDto, ReflectionDto } from "../../api/types";

export const fetchToday = (): Promise<DailyEntryDto | null> =>
  apiRequest<ApiEnvelope<DailyEntryDto | null>>("/daily-entries/today").then((r) => r.data);

export const updateGoal = (goal: string): Promise<DailyEntryDto> =>
  apiRequest<ApiEnvelope<DailyEntryDto>>("/daily-entries/today", {
    method: "PATCH",
    body: JSON.stringify({ goal }),
  }).then((r) => r.data);

export const addTodo = (title: string): Promise<void> =>
  apiRequest<void>("/daily-entries/today/todo-items", {
    method: "POST",
    body: JSON.stringify({ title }),
  });

export const patchTodo = (
  id: number,
  body: { title?: string; completed?: boolean },
): Promise<void> =>
  apiRequest<void>(`/todo-items/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });

export const deleteTodo = (id: number): Promise<void> =>
  apiRequest<void>(`/todo-items/${id}`, { method: "DELETE" });

export const submitReflection = (dailyEntryId: number, body: string): Promise<void> =>
  apiRequest<void>("/reflections", {
    method: "POST",
    body: JSON.stringify({ dailyEntryId, body }),
  });

export const updateReflection = (
  reflectionId: number,
  body: string,
): Promise<ReflectionDto> =>
  apiRequest<ApiEnvelope<ReflectionDto>>(`/reflections/${reflectionId}`, {
    method: "PATCH",
    body: JSON.stringify({ body }),
  }).then((r) => r.data);
