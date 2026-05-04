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
import {
  useRealtimeStatus,
  useRealtimeSubscription,
} from "../../realtime/client";

/**
 * Cursor-paged chat history. The first page (no cursor) returns the most
 * recent messages in *ascending* order from the BE; older pages chain off
 * `nextCursor`. The hook keeps pages flat in memory; the UI flattens them
 * into a single ascending list.
 */
export function useChatMessages(roomId: number) {
  const realtimeStatus = useRealtimeStatus();
  // When realtime is delivering frames in milliseconds, the 8s poll is just
  // wasted requests; slow it to 30s as a safety net for missed frames /
  // broker hiccups. When WS is down, fall back to the original 8s cadence.
  const refetchInterval = realtimeStatus === "connected" ? 30_000 : 8_000;
  return useInfiniteQuery<ChatMessagePage, Error>({
    queryKey: qk.roomMessages(roomId),
    queryFn: ({ pageParam }) => getRoomMessages(roomId, pageParam as number | null),
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    initialPageParam: null as number | null,
    enabled: Number.isFinite(roomId),
    // Background polling stays off so we don't wake the device just to
    // fetch chat. React Query stops polling automatically when the
    // query has no observers, so closed rooms cost zero requests.
    refetchInterval,
    refetchIntervalInBackground: false,
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

/**
 * Subscribes to /topic/rooms.{id}.chat for the lifetime of the chat
 * screen. Each incoming frame is appended to the first (newest) page of
 * the cached InfiniteQuery, deduplicated by message id so a REST
 * optimistic write followed by the WS echo never doubles up.
 *
 * The dedupe is the load-bearing invariant — without it, every message
 * the actor sends would render twice (once from sendRoomMessage's
 * onSuccess, once from the WS frame).
 */
export function useChatRealtime(roomId: number): void {
  const qc = useQueryClient();
  const destination = Number.isFinite(roomId) && roomId > 0
    ? `/topic/rooms.${roomId}.chat`
    : null;
  useRealtimeSubscription<ChatMessageDto>(destination, (incoming) => {
    if (!incoming || typeof incoming.id !== "number") return;
    qc.setQueryData<{ pages: ChatMessagePage[]; pageParams: unknown[] } | undefined>(
      qk.roomMessages(roomId),
      (prev) => {
        if (!prev || prev.pages.length === 0) {
          return {
            pages: [{ messages: [incoming], nextCursor: null }],
            pageParams: [null],
          };
        }
        // Walk every page — a duplicate could live in any of them since
        // the user may have already scrolled past the optimistic insert
        // by the time the WS echo lands.
        for (const page of prev.pages) {
          if (page.messages.some((m) => m.id === incoming.id)) {
            return prev;
          }
        }
        const [first, ...rest] = prev.pages;
        const updatedFirst: ChatMessagePage = {
          ...first,
          messages: [...first.messages, incoming],
        };
        return { ...prev, pages: [updatedFirst, ...rest] };
      },
    );
    // Refresh the lightweight "last message" peek so the chat tab list
    // updates without waiting for a window-focus refetch.
    qc.invalidateQueries({ queryKey: qk.roomLastMessage(roomId) });
  });
}
