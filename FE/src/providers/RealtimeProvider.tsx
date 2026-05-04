import { useEffect, type PropsWithChildren } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { getRealtimeClient } from "../lib/realtime/client";
import { routeInvalidation } from "../lib/notifications";

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
    return () => {
      sub.unsubscribe();
    };
  }, [auth.loading, auth.user, qc]);

  return <>{children}</>;
}
