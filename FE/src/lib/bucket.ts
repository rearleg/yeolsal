import type { GrassDayDto } from "../api/types";
import { grassRamp } from "../theme/tokens";

/**
 * Locked grass rule:
 *   L0 — goal OR reflection missing → empty cell
 *   L1 — goal + reflection present, 0 todos done
 *   L2 — goal + reflection present, 1-2 todos done
 *   L3 — goal + reflection present, 3-4 todos done
 *   L4 — goal + reflection present, 5+ todos done
 *
 * The current backend doesn't expose a "goalSet" flag explicitly, but a
 * reflection can only exist when a daily entry (and therefore a goal) exists.
 * So `reflectionSubmitted` acts as a sufficient gate for the goal+reflection
 * pair until the backend is updated to send the rule explicitly.
 */
export type Bucket = 0 | 1 | 2 | 3 | 4;

export function bucketFor(day: Pick<GrassDayDto, "reflectionSubmitted" | "completedTodoCount">): Bucket {
  if (!day.reflectionSubmitted) {
    return 0;
  }
  const n = day.completedTodoCount;
  if (n <= 0) {
    return 1;
  }
  if (n <= 2) {
    return 2;
  }
  if (n <= 4) {
    return 3;
  }
  return 4;
}

export const bucketColor: Record<Bucket, string> = {
  0: grassRamp.l0,
  1: grassRamp.l1,
  2: grassRamp.l2,
  3: grassRamp.l3,
  4: grassRamp.l4
};

export const bucketDescription: Record<Bucket, string> = {
  0: "기록 없음",
  1: "목표·회고 완료",
  2: "할 일 1-2개",
  3: "할 일 3-4개",
  4: "할 일 5개 이상"
};
