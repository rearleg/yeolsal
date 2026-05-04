export type TodoItemDto = {
  id: number;
  title: string;
  completed: boolean;
};

export type ReflectionDto = {
  id: number;
  dailyEntryId: number;
  body: string;
  submitted: boolean;
  // ISO-8601 instant. Day-complete marker — never advances on edit.
  submittedAt: string;
  // ISO-8601 instant. Equal to submittedAt for un-edited reflections; the
  // "수정됨" caption fires when this is later than submittedAt.
  updatedAt: string;
};

export type DailyEntryDto = {
  id: number;
  date: string;
  goal: string;
  todos: TodoItemDto[];
  reflection: ReflectionDto | null;
};

export type FriendDto = {
  userId: number;
  nickname: string;
  status: string;
};

export type FriendRequestDto = {
  id: number;
  requesterEmail: string;
  requesterNickname: string;
  status: string;
};

export type DailyFeedItem = {
  userId: number;
  nickname: string;
  date: string;
  goal: string;
  completedTodoCount: number;
  reflectionSubmitted: boolean;
};

export type ProfileDto = {
  userId: number;
  email: string;
  nickname: string;
  timezone: string;
};

// /api/v1/profiles/{userId} omits email (other users' email is no longer
// exposed). /api/v1/profiles/me still returns the full ProfileDto above.
export type PublicProfileDto = {
  userId: number;
  nickname: string;
  timezone: string;
};

export type GrassDayDto = {
  date: string;
  missionCompleted: boolean;
  completedTodoCount: number;
  reflectionSubmitted: boolean;
  intensity: 0 | 1 | 2 | 3 | 4;
};

export type MonthlyStatsDto = {
  month: string;
  completedDailyCount: number;
};
