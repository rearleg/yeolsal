import { useEffect } from "react";
import { type QueryClient, useQueryClient } from "@tanstack/react-query";
import * as SecureStore from "expo-secure-store";
import { qk } from "./query/keys";

/**
 * Story 3.2 AC8/AC10 — SecureStore single-shot pending buckets the room
 * screen reads on mount.
 *
 * - {@code yeosal.pendingFriendGiftPrompt}: payload from the
 *   FRIEND_GIFT_PROMPT push so the room screen can deep-link straight to
 *   the FriendGiftModal even if the push arrived while the app was
 *   foregrounded.
 * - {@code yeosal.pendingRevivalSequenceId}: payload from the
 *   FRIEND_GIFT_RECEIVED push so the room screen can mount the
 *   RevivalSequence on next foreground / route entry.
 *
 * Both slots clear on `onComplete` of their respective surfaces; the
 * room screen wiring (Story 3.2 FE-12) owns the read + clear.
 */
export const PENDING_FRIEND_GIFT_PROMPT_KEY = "yeosal.pendingFriendGiftPrompt";
export const PENDING_REVIVAL_SEQUENCE_KEY = "yeosal.pendingRevivalSequenceId";

export interface PendingFriendGiftPromptSlot {
  readonly roomId: number;
  readonly receiverUserId: number;
  readonly receiverNickname: string;
  readonly revivalEliminationKey: number;
}

export interface PendingRevivalSequenceSlot {
  readonly revivalEventId: number;
  readonly roomId: number;
  readonly donorNickname: string;
}

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
      request?: { content?: { data?: Record<string, unknown> } };
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
      const data = event?.request?.content?.data;
      const kind = typeof data?.kind === "string" ? data.kind : undefined;
      routeInvalidation(qc, kind, data);
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
export function routeInvalidation(
  qc: QueryClient,
  kind: string | undefined,
  data?: Record<string, unknown>,
): void {
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
    case "KUDOS_RECEIVED":
      // Story 3.5 — receiver sees the KUDOS chat row appear without
      // waiting for window-focus refetch. Same predicate the MILESTONE
      // branch uses since both kinds materialize as chat_messages rows.
      qc.invalidateQueries({
        predicate: (q) => {
          const key = q.queryKey;
          return Array.isArray(key) && key[0] === "rooms" && key[2] === "messages";
        },
      });
      return;
    case "FRIEND_GIFT_PROMPT": {
      // Story 3.2 AC8 — giver's meSurvival view + receiver's RED state
      // should refresh on the giver's device. The push payload carries
      // the room + receiver tuple so the room screen mount can deep-link
      // to the modal even if the app was already foreground.
      qc.invalidateQueries({ queryKey: qk.meSurvival });
      // Story 3.3 — receiver flipped RED; the badge eligibility list on
      // the giver's device picks up a new entry. Co-invalidate.
      qc.invalidateQueries({ queryKey: qk.friendGiftTargets });
      const slot = readPendingFriendGiftPromptSlot(data);
      if (slot != null) {
        void writeSecureStoreJson(PENDING_FRIEND_GIFT_PROMPT_KEY, slot);
      }
      return;
    }
    case "FRIEND_GIFT_RECEIVED": {
      // Story 3.2 AC8 — receiver's now-ACTIVE state propagates via the
      // meSurvival cache; the 7-day footnote source needs a refresh; the
      // room-messages predicate covers a future system-message extension.
      qc.invalidateQueries({ queryKey: qk.meSurvival });
      qc.invalidateQueries({ queryKey: qk.friendGiftReceipts });
      // Story 3.3 — receiver flipped ACTIVE; any in-cache target entry
      // for that user is now stale (they're no longer RED/SPECTATOR).
      qc.invalidateQueries({ queryKey: qk.friendGiftTargets });
      qc.invalidateQueries({
        predicate: (q) => {
          const key = q.queryKey;
          return Array.isArray(key) && key[0] === "rooms" && key[2] === "messages";
        },
      });
      const slot = readPendingRevivalSequenceSlot(data);
      if (slot != null) {
        void writeSecureStoreJson(PENDING_REVIVAL_SEQUENCE_KEY, slot);
      }
      return;
    }
    case "GOAL_NUDGE":
    case "REFLECTION_NUDGE":
    case "SPECTATOR_DIGEST":
      // Self-nudges and the spectator daily digest don't change shared
      // cache state — the user just got a reminder/summary. The room view
      // refreshes naturally on focus.
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

function readPendingFriendGiftPromptSlot(
  data: Record<string, unknown> | undefined,
): PendingFriendGiftPromptSlot | null {
  if (data == null) return null;
  const roomId = toFiniteNumber(data.roomId);
  const receiverUserId = toFiniteNumber(data.receiverUserId);
  const receiverNickname = typeof data.receiverNickname === "string"
    ? data.receiverNickname
    : null;
  const revivalEliminationKey = toFiniteNumber(data.revivalEliminationKey);
  if (
    roomId == null
    || receiverUserId == null
    || receiverNickname == null
    || revivalEliminationKey == null
  ) {
    return null;
  }
  return { roomId, receiverUserId, receiverNickname, revivalEliminationKey };
}

function readPendingRevivalSequenceSlot(
  data: Record<string, unknown> | undefined,
): PendingRevivalSequenceSlot | null {
  if (data == null) return null;
  const revivalEventId = toFiniteNumber(data.revivalEventId);
  const roomId = toFiniteNumber(data.roomId);
  const donorNickname = typeof data.giverNickname === "string"
    ? data.giverNickname
    : (typeof data.donorNickname === "string" ? data.donorNickname : null);
  if (revivalEventId == null || roomId == null || donorNickname == null) {
    return null;
  }
  return { revivalEventId, roomId, donorNickname };
}

function toFiniteNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

async function writeSecureStoreJson(
  key: string,
  payload: unknown,
): Promise<void> {
  try {
    await SecureStore.setItemAsync(key, JSON.stringify(payload));
  } catch {
    // Native module unavailable / quota — the room screen will skip the
    // deep-link path; the push itself already invalidated caches.
  }
}

/**
 * Story 3.2 AC8 — handles push *tap* (background → foreground) for the
 * two friend-gift kinds. Expo fires `addNotificationResponseReceivedListener`
 * exactly once when the user taps a push notification. We branch on the
 * `data.kind` and:
 *
 * - write the same SecureStore pending-slot the foreground listener
 *   writes (so the room screen mount path is shared), then
 * - {@code router.push(...)} to the room screen so the user lands inside
 *   the right room rather than the default tab.
 *
 * Lives next to {@link useNotificationInvalidation} so the app layout has
 * a single seat to mount both hooks (Story 3.2 FE-11 wiring point).
 */
export function useNotificationResponseDeepLink(): void {
  useEffect(() => {
    type ExpoResponse = {
      notification?: {
        request?: { content?: { data?: Record<string, unknown> } };
      };
    };
    type NotificationsModule = {
      addNotificationResponseReceivedListener: (
        listener: (response: ExpoResponse) => void,
      ) => { remove: () => void };
    };
    type Router = { push: (path: string) => void };
    let Notifications: NotificationsModule;
    let router: Router;
    try {
      // eslint-disable-next-line @typescript-eslint/no-require-imports -- intentional lazy load
      Notifications = require("expo-notifications");
    } catch {
      return;
    }
    try {
      // eslint-disable-next-line @typescript-eslint/no-require-imports -- intentional lazy load
      router = require("expo-router").router;
    } catch {
      return;
    }
    const subscription = Notifications.addNotificationResponseReceivedListener((response) => {
      const data = response?.notification?.request?.content?.data;
      const kind = typeof data?.kind === "string" ? data.kind : undefined;
      if (kind === "FRIEND_GIFT_PROMPT") {
        const slot = readPendingFriendGiftPromptSlot(data);
        if (slot != null) {
          void writeSecureStoreJson(PENDING_FRIEND_GIFT_PROMPT_KEY, slot);
          router.push(`/rooms/${slot.roomId}`);
        }
        return;
      }
      if (kind === "FRIEND_GIFT_RECEIVED") {
        const slot = readPendingRevivalSequenceSlot(data);
        if (slot != null) {
          void writeSecureStoreJson(PENDING_REVIVAL_SEQUENCE_KEY, slot);
          router.push(`/rooms/${slot.roomId}`);
        }
      }
    });
    return () => subscription.remove();
  }, []);
}
