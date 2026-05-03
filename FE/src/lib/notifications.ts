import { useEffect } from "react";
import { type QueryClient, useQueryClient } from "@tanstack/react-query";

/**
 * Hook that registers a foreground listener for incoming Expo push
 * notifications and invalidates the React Query caches the push affects.
 *
 * BE attaches {@code data.kind} to every Expo payload (see
 * NotificationService.dispatch), mirroring the {@code NotificationKind}
 * enum: {@code GOAL_NUDGE}, {@code REFLECTION_NUDGE}, {@code FRIEND_GOAL},
 * {@code FRIEND_REFLECTION}, plus future {@code FRIEND_REQUEST_*} kinds.
 * We branch off that string to invalidate just the right keys —
 * "FRIEND_GOAL → feed", "MILESTONE → roomMessages", etc. — instead of
 * broad-invalidating every cache on every push.
 *
 * Falls back to broad invalidation when {@code data.kind} is absent or
 * unknown so older BE bundles still trigger a refresh.
 *
 * No-ops when expo-notifications native module is missing (dev clients
 * built before the dep was added) — same lazy-require shape as
 * registerForPushAsync to avoid breaking the import chain.
 */
export function useNotificationInvalidation(): void {
  const qc = useQueryClient();
  useEffect(() => {
    type ExpoNotification = {
      request?: { content?: { data?: { kind?: string; key?: string } } };
    };
    type NotificationsModule = {
      addNotificationReceivedListener: (
        listener: (event: ExpoNotification) => void,
      ) => { remove: () => void };
    };
    let Notifications: NotificationsModule;
    try {
      // eslint-disable-next-line @typescript-eslint/no-require-imports -- intentional lazy load; native module may be absent
      Notifications = require("expo-notifications");
    } catch {
      return;
    }
    const subscription = Notifications.addNotificationReceivedListener((event) => {
      const kind = event?.request?.content?.data?.kind;
      routeInvalidation(qc, kind);
    });
    return () => subscription.remove();
  }, [qc]);
}

/**
 * Pure router for kind → invalidation. Exported for unit tests; call
 * sites should use the hook above. Unknown / undefined kinds fall back to
 * broad invalidation so a BE without {@code data.kind} (older deploys)
 * still refreshes the user's data.
 */
export function routeInvalidation(qc: QueryClient, kind: string | undefined): void {
  switch (kind) {
    case "FRIEND_GOAL":
    case "FRIEND_REFLECTION":
      qc.invalidateQueries({ queryKey: ["feed"] });
      return;
    case "FRIEND_REQUEST_RECEIVED":
    case "FRIEND_REQUEST_ACCEPTED":
      qc.invalidateQueries({ queryKey: ["friendRequests"] });
      qc.invalidateQueries({ queryKey: ["feed"] });
      return;
    case "MILESTONE":
      qc.invalidateQueries({
        predicate: (q) => {
          const key = q.queryKey;
          return Array.isArray(key) && key[0] === "rooms" && key[2] === "messages";
        },
      });
      return;
    case "GOAL_NUDGE":
    case "REFLECTION_NUDGE":
      // Self-nudges don't change shared cache state — the user just got
      // a reminder. The Today tab refreshes naturally on focus.
      return;
    default:
      // Unknown / missing kind — broad invalidation as a safety net.
      qc.invalidateQueries({ queryKey: ["feed"] });
      qc.invalidateQueries({ queryKey: ["friendRequests"] });
      qc.invalidateQueries({
        predicate: (q) => {
          const key = q.queryKey;
          return Array.isArray(key) && key[0] === "rooms" && key[2] === "messages";
        },
      });
  }
}
