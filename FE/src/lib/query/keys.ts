export const qk = {
  today: ["today"] as const,
  feed: (date: string) => ["feed", date] as const,
  rooms: ["rooms"] as const,
  roomMembers: (id: number) => ["rooms", id, "members"] as const,
  roomToday: (id: number, date: string) => ["rooms", id, "today", date] as const,
  profile: ["profile"] as const,
  grass: (from: string, to: string) => ["grass", from, to] as const,
  monthlyStats: (month: string) => ["monthly", month] as const,
  friendRequests: ["friendRequests"] as const,
  friendProfile: (userId: number) => ["friendProfile", userId] as const,
};
