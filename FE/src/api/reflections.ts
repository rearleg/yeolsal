import { apiRequest, type ApiEnvelope } from "./client";

export interface ReflectionEntry {
  date: string;
  body: string | null;
  submittedAt: string;
  bodyVisible: boolean;
}

export async function listReflections(userId: number, limit = 20): Promise<ReflectionEntry[]> {
  const envelope = await apiRequest<ApiEnvelope<ReflectionEntry[]>>(
    `/profiles/${userId}/reflections?limit=${limit}`
  );
  return envelope.data;
}
