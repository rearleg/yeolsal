// Story 4.1 FE-3 / AC6 + AC7 — domain hook for the per-room point pool.
//
// Composes:
//   - REST primary: useQuery({ qk.roomPoints, getRoomPoints, staleTime: 30s })
//   - STOMP merge:  useRealtimeSubscription<PointPoolFrame> on
//                   /topic/rooms.{roomId}.points
//   - Dedupe:       sourceRevivalEventId via a per-hook-instance useRef Set
//                   (mirrors the useChatRealtime canonical pattern)
//
// The hook returns `{ total, lastEventAt, isLoading, isError }` so consumers
// can render the number directly without unwrapping the underlying query
// result shape. Components never call useQuery directly (project-context
// "All data fetching goes through domain hooks in src/lib/query/hooks/*").

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import {
  getRoomPoints,
  type RoomPointPoolDto,
} from "../../../api/roomPoints";
import { qk } from "../keys";
import { useRealtimeSubscription } from "../../realtime/client";

export interface PointPoolFrame {
  readonly roomId: number;
  readonly delta: number;
  readonly newTotal: number;
  readonly sourceRevivalEventId: number;
  readonly occurredAt: string;
}

export interface UseRoomPointsResult {
  readonly total: number;
  readonly lastEventAt: string | null;
  readonly isLoading: boolean;
  readonly isError: boolean;
}

export function useRoomPoints(roomId: number): UseRoomPointsResult {
  const qc = useQueryClient();
  const enabled = Number.isFinite(roomId) && roomId > 0;

  const query = useQuery<RoomPointPoolDto>({
    queryKey: qk.roomPoints(roomId),
    queryFn: () => getRoomPoints(roomId),
    enabled,
    // 30s matches qk.meSurvival so the per-room and cross-room caches
    // refresh on the same cadence. Don't rely on TanStack Query defaults
    // here (project-context "set staleTime per domain").
    staleTime: 30_000,
  });

  // Per-hook-instance dedupe set — lives across rerenders, GC'd on
  // unmount. Module-level singletons would cross-contaminate two
  // useRoomPoints calls for different rooms; useRef on a Set is the
  // canonical shape (mirrors useChatRealtime in lib/query/hooks/chat.ts).
  // The set may grow unbounded across a long session; v1 magnitudes
  // (~10–50 pool events per active room per day) tolerate this without
  // an LRU — YAGNI per the story Dev Notes Trap #4.
  const seenEventIds = useRef<Set<number>>(new Set());

  const destination = enabled ? `/topic/rooms.${roomId}.points` : null;

  useRealtimeSubscription<PointPoolFrame>(destination, (frame) => {
    if (!frame || typeof frame.sourceRevivalEventId !== "number") return;
    if (typeof frame.newTotal !== "number") return;
    // Defence-in-depth: the topic is room-scoped server-side, but pin the
    // consumer to roomId so a broker fanout misconfig or a race during
    // roomId change never spends a setQueryData on a foreign room.
    if (frame.roomId !== roomId) return;
    if (seenEventIds.current.has(frame.sourceRevivalEventId)) return;
    seenEventIds.current.add(frame.sourceRevivalEventId);

    qc.setQueryData<RoomPointPoolDto | undefined>(
      qk.roomPoints(roomId),
      (prev) => {
        // The WS payload is authoritative — BE computed newTotal inside
        // the row-level FOR UPDATE lock. Never do `total + delta`
        // arithmetic on the FE; it drifts on out-of-order delivery,
        // missed frames, or stale starting points (story Trap #5).
        if (!prev) {
          return {
            roomId,
            total: frame.newTotal,
            lastEventAt: frame.occurredAt,
          };
        }
        return {
          ...prev,
          total: frame.newTotal,
          lastEventAt: frame.occurredAt,
        };
      },
    );
  });

  const total = query.data?.total ?? 0;
  const lastEventAt = query.data?.lastEventAt ?? null;
  return {
    total,
    lastEventAt,
    isLoading: enabled && query.isLoading,
    isError: query.isError,
  };
}
