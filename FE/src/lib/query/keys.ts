export const qk = {
  today: ["today"] as const,
  feed: (date: string) => ["feed", date] as const,
  rooms: ["rooms"] as const,
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
};
