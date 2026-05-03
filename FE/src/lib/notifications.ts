import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";

/**
 * Hook that registers a foreground listener for incoming Expo push
 * notifications and invalidates the query caches that are most likely to
 * have been affected (friend feed, friend requests, every active room
 * chat). The BE doesn't yet attach a structured `data.kind` field to its
 * Expo payload so we err on the side of broad-but-cheap invalidation —
 * each cache hits one HTTP call and only when it has an active observer.
 *
 * When BE adds `data.kind` we can narrow this to per-kind targeted
 * invalidation (FRIEND_GOAL → feed, MILESTONE → roomMessages, …).
 *
 * No-ops when expo-notifications native module is missing (dev clients
 * built before the dep was added) — same lazy-require shape as
 * registerForPushAsync to avoid breaking the import chain.
 */
export function useNotificationInvalidation(): void {
  const qc = useQueryClient();
  useEffect(() => {
    type NotificationsModule = {
      addNotificationReceivedListener: (
        listener: (event: unknown) => void,
      ) => { remove: () => void };
    };
    let Notifications: NotificationsModule;
    try {
      // eslint-disable-next-line @typescript-eslint/no-require-imports -- intentional lazy load; native module may be absent
      Notifications = require("expo-notifications");
    } catch {
      return;
    }
    const subscription = Notifications.addNotificationReceivedListener(() => {
      qc.invalidateQueries({ queryKey: ["feed"] });
      qc.invalidateQueries({ queryKey: ["friendRequests"] });
      qc.invalidateQueries({
        predicate: (q) => {
          const key = q.queryKey;
          return Array.isArray(key) && key[0] === "rooms" && key[2] === "messages";
        },
      });
    });
    return () => subscription.remove();
  }, [qc]);
}
