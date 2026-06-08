// Story 7.3 FE-4 / AC2 + AC3 — domain hook for the per-(room, yearMonth)
// Final-3 poster.
//
// Composes:
//   - REST primary: useQuery({ qk.finalThreePoster(roomId, ym), getPoster,
//                              staleTime: 5min })
//   - STOMP merge:  useRealtimeSubscription<MonthlyPosterReadyFrame> on
//                   /topic/rooms.{roomId}.posters → invalidate matching key
//   - Reconnect:    disconnected→connected transition refetches (mirrors
//                   useRoomPoints Patch 3).
//
// Returns null for 404s so the consumer can simply branch on `data == null`
// to hide its card. Components never call useQuery directly
// (project-context.md "All data fetching goes through domain hooks").

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { getPoster, type FinalThreePosterDto } from "../../../api/posters";
import { qk } from "../keys";
import {
  useRealtimeStatus,
  useRealtimeSubscription,
  type RealtimeStatus,
} from "../../realtime/client";

export interface MonthlyPosterReadyFrame {
  readonly roomId: number;
  readonly yearMonth: string;
}

export interface UseFinalThreePosterResult {
  readonly data: FinalThreePosterDto | null;
  readonly isLoading: boolean;
  readonly isError: boolean;
}

const STALE_TIME_MS = 5 * 60_000;
const GC_TIME_MS = 30 * 60_000;
const YEAR_MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;

export function useFinalThreePoster(
  roomId: number,
  yearMonth: string,
): UseFinalThreePosterResult {
  const qc = useQueryClient();
  const enabled =
    Number.isFinite(roomId) && roomId > 0 && YEAR_MONTH_RE.test(yearMonth);
  const realtimeStatus = useRealtimeStatus();

  const query = useQuery<FinalThreePosterDto | null>({
    queryKey: qk.finalThreePoster(roomId, yearMonth),
    queryFn: () => getPoster(roomId, yearMonth),
    enabled,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });

  // Reconnect-recovery — mirrors useRoomPoints Patch 3. A user with the
  // Home tab open at 06:25 KST (WS disconnected during overnight idle)
  // re-connects at 06:31 KST after Story 7.2's job fires → invalidate to
  // catch the new poster row even if the STOMP frame slipped past the gap.
  const prevStatusRef = useRef<RealtimeStatus>(realtimeStatus);
  useEffect(() => {
    if (
      enabled
      && prevStatusRef.current === "disconnected"
      && realtimeStatus === "connected"
    ) {
      qc.invalidateQueries({ queryKey: qk.finalThreePoster(roomId, yearMonth) });
    }
    prevStatusRef.current = realtimeStatus;
  }, [realtimeStatus, qc, roomId, yearMonth, enabled]);

  // STOMP — invalidate-on-frame (NOT setQueryData) because Story 7.2 AC4
  // line 199-202 deliberately keeps the frame signal-only ("would push
  // kilobytes of SVG over STOMP for 5K rooms"). Idempotent invalidate is
  // safe under duplicate frames.
  const destination = enabled ? `/topic/rooms.${roomId}.posters` : null;
  useRealtimeSubscription<MonthlyPosterReadyFrame>(destination, (frame) => {
    if (!frame || typeof frame.roomId !== "number" || typeof frame.yearMonth !== "string") {
      return;
    }
    // Defence-in-depth — pin consumer to roomId in case broker fanout
    // misroutes (the topic is room-scoped server-side, but this mirrors
    // useRoomPoints' guard at FE/src/lib/query/hooks/roomPoints.ts).
    if (frame.roomId !== roomId) return;
    if (frame.yearMonth !== yearMonth) return;
    qc.invalidateQueries({ queryKey: qk.finalThreePoster(roomId, yearMonth) });
  });

  return {
    data: query.data ?? null,
    isLoading: enabled && query.isLoading,
    isError: query.isError,
  };
}
