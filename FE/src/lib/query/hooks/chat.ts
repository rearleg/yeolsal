import {
  useInfiniteQuery,
  useMutation,
  useQuery,
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
import { getLastReadId, setLastReadId } from "../../chatRead";

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
 *
 * After the cache write, also invalidates {@link qk.roomLastMessage} so the
 * chat tab list re-reads the freshest preview. The infinite query already
 * carries the full message; the lightweight peek query is a separate cache
 * entry, so we explicitly invalidate it here to avoid a stale preview.
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
      // Refresh the lightweight "last message" peek so the chat tab list
      // updates without waiting for a window-focus refetch.
      qc.invalidateQueries({ queryKey: qk.roomLastMessage(roomId) });
    },
    onError: (error) => {
      toast.error(error.message);
    },
  });
}

/**
 * Lightweight per-room "last message" peek. Fetches just the newest row so
 * the chat tab list can render a preview + unread badge without pulling a
 * full 30-row page per room. Returns {@code null} for empty rooms.
 */
export function useRoomLastMessage(roomId: number) {
  return useQuery<ChatMessageDto | null>({
    queryKey: qk.roomLastMessage(roomId),
    queryFn: async () => {
      const page = await getRoomMessages(roomId, null, 1);
      const msgs = page.messages;
      return msgs.length === 0 ? null : msgs[msgs.length - 1];
    },
    enabled: Number.isFinite(roomId) && roomId > 0,
  });
}

/**
 * Reads the {@code lastReadMessageId} watermark for a room from AsyncStorage.
 * Pairs with {@link useMarkChatRead} which writes the same key and also
 * updates the cached value so the chat tab badge reacts immediately.
 */
export function useLastReadId(roomId: number) {
  return useQuery<number | null>({
    queryKey: qk.chatLastRead(roomId),
    queryFn: () => getLastReadId(roomId),
    enabled: Number.isFinite(roomId) && roomId > 0,
    // Local-only state — keep stale forever until we explicitly invalidate.
    staleTime: Infinity,
  });
}

/**
 * Advances the per-room read watermark. Pushes the value into the React
 * Query cache synchronously so the chat tab list clears the badge on the
 * same render that the user opens the room.
 */
export function useMarkChatRead(roomId: number) {
  const qc = useQueryClient();
  return useMutation<void, Error, number>({
    mutationFn: (messageId) => setLastReadId(roomId, messageId),
    onMutate: (messageId) => {
      qc.setQueryData<number | null>(qk.chatLastRead(roomId), (prev) => {
        if (prev != null && prev >= messageId) return prev;
        return messageId;
      });
    },
  });
}
