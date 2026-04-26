export type GrassDay = {
  date: string;
  missionCompleted: boolean;
  completedTodoCount: number;
  reflectionSubmitted: boolean;
  intensity: 0 | 1 | 2 | 3 | 4;
};

export const todayTodos = [
  { id: "todo-1", title: "수학 오답 20분", done: true },
  { id: "todo-2", title: "책 10쪽 읽기", done: false },
  { id: "todo-3", title: "운동 인증 남기기", done: true }
];

export const feedItems = [
  {
    id: "friend-1",
    name: "민서",
    goal: "영어 단어 30개와 러닝",
    todosDone: 4,
    reflectionSubmitted: true
  },
  {
    id: "friend-2",
    name: "준",
    goal: "프로젝트 발표 준비",
    todosDone: 2,
    reflectionSubmitted: false
  }
];

export const grassDays: GrassDay[] = Array.from({ length: 35 }, (_, index) => {
  const completed = index % 5 !== 0;
  const count = completed ? (index % 4) + 1 : 0;
  return {
    date: `2026-04-${String((index % 30) + 1).padStart(2, "0")}`,
    missionCompleted: completed,
    completedTodoCount: count,
    reflectionSubmitted: completed,
    intensity: count as 0 | 1 | 2 | 3 | 4
  };
});
