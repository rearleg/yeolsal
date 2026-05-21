import { useEffect, type PropsWithChildren } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { getRealtimeClient } from "../lib/realtime/client";
import { routeInvalidation } from "../lib/notifications";
import { qk } from "../lib/query/keys";

/**
 * Mounts the singleton {@link RealtimeClient}. Connects when an
 * authenticated user appears, disconnects on logout. Subscribes to
 * the per-user notification queue and routes each frame through
 * {@link routeInvalidation} — the same dispatcher the push-notification
 * path uses, so realtime delivery and push delivery share one
 * cache-invalidation source of truth.
 *
 * <p>The chat subscription lives at the {@code rooms/[id]/chat} screen
 * (see {@code useChatRealtime}) so we don't keep a chat fan-out open
 * while the user is browsing other tabs.
 */
export function RealtimeProvider({ children }: PropsWithChildren) {
  const auth = useAuth();
  const qc = useQueryClient();

  useEffect(() => {
    if (auth.loading) return;
    const client = getRealtimeClient();
    if (!auth.user) {
      client.disconnect();
      return;
    }
    client.connect().catch(() => undefined);
    const sub = client.subscribe("/user/queue/notifications", (frame) => {
      try {
        const event = JSON.parse(frame.body) as { kind?: string };
        routeInvalidation(qc, event.kind);
      } catch {
        // Malformed payloads are ignored — broker drops them on the
        // next disconnect cycle.
      }
    });
    // Story 2.1 AC6 — SurvivalStateChange (revival, RED → SPECTATOR, etc.)
    // invalidates the cross-room aggregation so the spectator branch flips
    // back to ACTIVE in < 1s without an app restart.
    // Story 3.3 — the same transitions flip badge eligibility (a friend
    // entering RED becomes a candidate target; their revival removes them).
    const survivalSub = client.subscribe("/user/queue/private-survival", () => {
      qc.invalidateQueries({ queryKey: qk.meSurvival });
      qc.invalidateQueries({ queryKey: qk.friendGiftTargets });
    });
    return () => {
      sub.unsubscribe();
      survivalSub.unsubscribe();
    };
  }, [auth.loading, auth.user, qc]);

  return <>{children}</>;
}
