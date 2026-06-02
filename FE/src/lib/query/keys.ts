export const qk = {
  today: ["today"] as const,
  feed: (date: string) => ["feed", date] as const,
  rooms: ["rooms"] as const,
  // Story 2.1 AC1 — GET /api/v1/me/survival cross-room aggregation.
  // 30s staleTime; invalidated on STOMP SurvivalStateChange frames so
  // a revival flips spectator → ACTIVE in < 1s without an app restart.
  meSurvival: ["meSurvival"] as const,
  roomMembers: (id: number) => ["rooms", id, "members"] as const,
  roomToday: (id: number, date: string) => ["rooms", id, "today", date] as const,
  roomMessages: (id: number) => ["rooms", id, "messages"] as const,
  roomLastMessage: (id: number) => ["rooms", id, "lastMessage"] as const,
  chatLastRead: (id: number) => ["chat", "lastRead", id] as const,
  profile: ["profile"] as const,
  grass: (from: string, to: string) => ["grass", from, to] as const,
  monthlyStats: (month: string) => ["monthly", month] as const,
  friendRequests: ["friendRequests"] as const,
  friendProfile: (userId: number) => ["friendProfile", userId] as const,
  // Transient flag set by WelcomeWindow CTA-B so the Today tab can render
  // the warm-tone "첫 잔디 — 곧 함께 채워질 거예요" tagline (Story 1.6 AC6).
  // Lives in the in-memory query cache (no persistence) and is cleared once
  // the Today screen has rendered the tagline at least once.
  soloLeaderTagline: (roomId: number) => ["soloLeaderTagline", roomId] as const,
  // Story 2.3 — list cache for GET /api/v1/me/visibility-prefs. Single key
  // shared across per-room Settings screens; each toggle derives its row by
  // filtering the list locally so the FE never has to default-construct.
  recordVisibilityPrefs: ["recordVisibilityPrefs"] as const,
  // Story 3.2 — receiver-scoped 7-day FRIEND_GIFT receipts cache. User-
  // scoped (no parameter) because the BE response is filtered by
  // `me.user_id`. Powers the `<SevenDayFootnote>` daily-entry caption.
  friendGiftReceipts: ["friendGiftReceipts"] as const,
  // Story 3.2 — lifetime-1 fallback. Pure boolean cache; staleTime matches
  // meSurvival so the M3.5 fallback path can read without a fresh request
  // every render.
  hasGivenFriendGift: ["hasGivenFriendGift"] as const,
  // Story 3.3 — eligible friend-gift TARGETS (caller is the giver). User-
  // scoped; BE filters by `me.user_id`. Invalidated alongside qk.meSurvival
  // so a friend's RED/SPECTATOR transition flips badge state.
  friendGiftTargets: ["friendGiftTargets"] as const,
  // Story 4.1 AC6 — per-room live point-pool snapshot (room-member scoped;
  // BE filters by membership). Seeded from GET /api/v1/rooms/{id}/points
  // and merged from `/topic/rooms.{id}.points` STOMP frames (dedupe-by-
  // sourceRevivalEventId via the useRoomPoints hook). staleTime is set
  // to 30s in the hook so it stays in lock-step with qk.meSurvival.
  roomPoints: (roomId: number) => ["roomPoints", roomId] as const,
  // Story 3.4 — per-room personal-points ledger (caller-scoped). Powers the
  // Wallet ledger drill-in. Invalidated on every event that appends a
  // ledger row: useSelfRevival (REVIVAL_SPEND), useSendFriendGift
  // (FRIEND_GIFT_SPEND). The daily SURVIVAL evaluator rows propagate via
  // the next window-focus refetch — too noisy for live invalidation.
  personalPointsLedger: (roomId: number) =>
    ["personalPointsLedger", roomId] as const,
  // Story 3.4 — per-room lifetime received-revival history (all 3 sources;
  // caller-scoped). Powers the Wallet received-revivals drill-in.
  // Invalidated on FRIEND_GIFT_RECEIVED notifications (Story 3.2 receiver
  // path) and on useSelfRevival success (caller's own self-revival).
  receivedRevivals: (roomId: number) =>
    ["receivedRevivals", roomId] as const,
  // Per-room rule cache (current + pending). Member-scoped and invalidated
  // after each successful update; realtime invalidation is intentionally absent.
  roomRule: (roomId: number) => ["roomRule", roomId] as const,
};
