import { useQuery } from "@tanstack/react-query";
import { apiRequest, type ApiEnvelope } from "../../../api/client";
import type { GrassDayDto, MonthlyStatsDto, ProfileDto } from "../../../api/types";
import { qk } from "../keys";

export function useProfileQuery() {
  return useQuery<ProfileDto>({
    queryKey: qk.profile,
    queryFn: () => apiRequest<ApiEnvelope<ProfileDto>>("/profiles/me").then((r) => r.data),
  });
}

export function useGrassQuery(from: string, to: string) {
  return useQuery<GrassDayDto[]>({
    queryKey: qk.grass(from, to),
    queryFn: () =>
      apiRequest<ApiEnvelope<GrassDayDto[]>>(`/profiles/me/grass?from=${from}&to=${to}`).then(
        (r) => r.data,
      ),
    enabled: Boolean(from && to),
  });
}

export function useMonthlyStatsQuery(month: string) {
  return useQuery<MonthlyStatsDto>({
    queryKey: qk.monthlyStats(month),
    queryFn: () =>
      apiRequest<ApiEnvelope<MonthlyStatsDto>>(`/stats/monthly?month=${month}`).then((r) => r.data),
    enabled: Boolean(month),
  });
}

export function useFriendProfileQuery(userId: number) {
  return useQuery<ProfileDto>({
    queryKey: qk.friendProfile(userId),
    queryFn: () =>
      apiRequest<ApiEnvelope<ProfileDto>>(`/profiles/${userId}`).then((r) => r.data),
    enabled: Number.isFinite(userId) && userId > 0,
  });
}

export function useFriendGrassQuery(userId: number, from: string, to: string) {
  return useQuery<GrassDayDto[]>({
    queryKey: ["friendGrass", userId, from, to] as const,
    queryFn: () =>
      apiRequest<ApiEnvelope<GrassDayDto[]>>(
        `/profiles/${userId}/grass?from=${from}&to=${to}`,
      ).then((r) => r.data),
    enabled: Number.isFinite(userId) && userId > 0 && Boolean(from && to),
  });
}
