// Story 4.3 AC2 — room point pool 5-stage threshold table.
//
// Boundaries are PRD-locked per Day-30 KPI alignment (PRD §3.1 KPI:
// "평균 active room ≥50 pool points by day 30"). Stage 4 ([50, 99])
// intentionally lines up with the success bar so a room that hits the
// Day-30 metric visually reaches a recognizable artifact. Stage 5 (≥100)
// is the keystone — 2× over-achievement that should feel meaningfully
// different from "success". DO NOT silently retune; raise via
// bmad-correct-course if a PM tuning request lands.

export interface PoolStageRange {
  readonly stage: 1 | 2 | 3 | 4 | 5;
  readonly min: number;
  // Inclusive upper bound. `null` denotes the open-ended cap (stage 5).
  readonly max: number | null;
  readonly label: string;
}

export const POOL_STAGE_THRESHOLDS: readonly PoolStageRange[] = [
  { stage: 1, min: 0,   max: 9,   label: "포인트 풀 1단계 — 토대" },
  { stage: 2, min: 10,  max: 24,  label: "포인트 풀 2단계 — 첫 켜" },
  { stage: 3, min: 25,  max: 49,  label: "포인트 풀 3단계 — 여러 켜" },
  { stage: 4, min: 50,  max: 99,  label: "포인트 풀 4단계 — 형상" },
  { stage: 5, min: 100, max: null, label: "포인트 풀 5단계 — 완성" },
] as const;

export function stageFor(total: number): PoolStageRange["stage"] {
  // Clamp negatives + non-finite inputs to stage 1. Story 4.1's
  // RoomPointPoolService.applyDelta `delta <= 0 → 400 VALIDATION` makes
  // negative `total` impossible from the BE; this is defensive on the FE.
  if (!Number.isFinite(total) || total < 0) return 1;
  for (const range of POOL_STAGE_THRESHOLDS) {
    if (range.max == null) return range.stage;
    if (total <= range.max) return range.stage;
  }
  return 5;
}
