// SpectatorRouteProvider (Story 2.1 AC2).
//
// Page-level seat that owns the cross-room spectator decision so per-screen
// components can branch without re-fetching the survival query. The provider
// reads the cached `useMeSurvivalQuery()` result and exposes the resolved
// boolean + per-room id Sets via `useSpectatorRoute()`.
//
// CRITICAL — UX cross-cutting rule #9 (Story 1.5 AC7):
//   This provider does NOT set a sub-mode. The sub-mode wrap belongs to the
//   `(tabs)/_layout.tsx` call site (one seat above), so leaf components never
//   read the spectator boolean to decide their tokens — they consume the
//   resolved theme via `useTheme()`. Keep that boundary intact.

import { createContext, useContext, useMemo, type ReactNode } from "react";
import { useMeSurvivalQuery } from "../lib/query/hooks/survival";
import { isSpectatorAcrossAllRooms } from "../lib/spectator";

export interface SpectatorRouteValue {
  readonly isSpectatorEverywhere: boolean;
  readonly spectatorRoomIds: ReadonlySet<number>;
  readonly activeRoomIds: ReadonlySet<number>;
}

const EMPTY_VALUE: SpectatorRouteValue = {
  isSpectatorEverywhere: false,
  spectatorRoomIds: new Set<number>(),
  activeRoomIds: new Set<number>(),
};

const SpectatorRouteContext = createContext<SpectatorRouteValue>(EMPTY_VALUE);

interface SpectatorRouteProviderProps {
  readonly children: ReactNode;
}

export function SpectatorRouteProvider({ children }: SpectatorRouteProviderProps) {
  const query = useMeSurvivalQuery();
  const value = useMemo<SpectatorRouteValue>(() => {
    const entries = query.data ?? [];
    const spectatorRoomIds = new Set<number>();
    const activeRoomIds = new Set<number>();
    for (const entry of entries) {
      if (entry.status === "SPECTATOR") {
        spectatorRoomIds.add(entry.roomId);
      } else {
        activeRoomIds.add(entry.roomId);
      }
    }
    return {
      isSpectatorEverywhere: isSpectatorAcrossAllRooms(entries),
      spectatorRoomIds,
      activeRoomIds,
    };
  }, [query.data]);

  return (
    <SpectatorRouteContext.Provider value={value}>
      {children}
    </SpectatorRouteContext.Provider>
  );
}

export function useSpectatorRoute(): SpectatorRouteValue {
  return useContext(SpectatorRouteContext);
}
