import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiRequest, type ApiEnvelope } from "../../../api/client";
import type { DailyFeedItem, FriendRequestDto } from "../../../api/types";
import { qk } from "../keys";
import { toast } from "../../toast";

export function useFeedQuery(date: string) {
  return useQuery({
    queryKey: qk.feed(date),
    queryFn: () =>
      apiRequest<ApiEnvelope<DailyFeedItem[]>>(`/feed/daily?date=${date}`).then((r) => r.data),
  });
}

export function useFriendRequestsQuery() {
  return useQuery({
    queryKey: qk.friendRequests,
    queryFn: () =>
      apiRequest<ApiEnvelope<FriendRequestDto[]>>("/friends/requests").then((r) => r.data),
  });
}

export function useSendFriendRequest() {
  const qc = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: (targetEmail) =>
      apiRequest<void>("/friends/requests", {
        method: "POST",
        body: JSON.stringify({ targetEmail }),
      }),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.friendRequests });
    },
  });
}

export function useRespondFriendRequest() {
  const qc = useQueryClient();
  return useMutation<void, Error, { id: number; accepted: boolean }>({
    mutationFn: ({ id, accepted }) =>
      apiRequest<void>(`/friends/requests/${id}`, {
        method: "PATCH",
        body: JSON.stringify({ accepted }),
      }),
    onError: (e) => toast.error(e.message),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.friendRequests });
      qc.invalidateQueries({ queryKey: ["feed"] });
    },
  });
}
