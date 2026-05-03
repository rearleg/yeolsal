import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
} from "@tanstack/react-query";
import {
  getRoomMessages,
  sendRoomMessage,
  type ChatMessageDto,
  type ChatMessagePage,
} from "../../../api/chat";
import { qk } from "../keys";
import { toast } from "../../toast";
import { useHaptic } from "../../../hooks/useHaptics";

/**
 * Cursor-paged chat history. The first page (no cursor) returns the most
 * recent messages in *ascending* order from the BE; older pages chain off
 * `nextCursor`. The hook keeps pages flat in memory; the UI flattens them
 * into a single ascending list.
 */
export function useChatMessages(roomId: number) {
  return useInfiniteQuery<ChatMessagePage, Error>({
    queryKey: qk.roomMessages(roomId),
    queryFn: ({ pageParam }) => getRoomMessages(roomId, pageParam as number | null),
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    initialPageParam: null as number | null,
    enabled: Number.isFinite(roomId),
  });
}

interface SendVars {
  roomId: number;
  body: string;
}

/**
 * Optimistic send that drops the server-confirmed message into the first
 * cached page. The first page is the newest, so the new row goes to the
 * end of its `messages` array (kept ascending) — this matches BE order.
 */
export function useSendChatMessage(roomId: number) {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<ChatMessageDto, Error, SendVars>({
    mutationFn: ({ body }) => sendRoomMessage(roomId, body),
    onSuccess: (saved) => {
      haptic("light");
      qc.setQueryData<{ pages: ChatMessagePage[]; pageParams: unknown[] } | undefined>(
        qk.roomMessages(roomId),
        (prev) => {
          if (!prev || prev.pages.length === 0) {
            return {
              pages: [{ messages: [saved], nextCursor: null }],
              pageParams: [null],
            };
          }
          const [first, ...rest] = prev.pages;
          const updatedFirst: ChatMessagePage = {
            ...first,
            messages: [...first.messages, saved],
          };
          return { ...prev, pages: [updatedFirst, ...rest] };
        },
      );
    },
    onError: (error) => {
      toast.error(error.message);
    },
  });
}
