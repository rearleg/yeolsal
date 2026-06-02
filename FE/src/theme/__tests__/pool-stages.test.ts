// Story 4.3 FE-2 — pool-stages.ts threshold table tests (AC2).

import {
  POOL_STAGE_THRESHOLDS,
  stageFor,
  type PoolStageRange,
} from "../pool-stages";

describe("stageFor", () => {
  it.each<[number, PoolStageRange["stage"]]>([
    [0, 1],
    [9, 1],
    [10, 2],
    [24, 2],
    [25, 3],
    [49, 3],
    [50, 4],
    [99, 4],
    [100, 5],
    [9999, 5],
  ])("stageFor(%i) === %i", (total, expected) => {
    expect(stageFor(total)).toBe(expected);
  });

  it.each<number>([-1, -100, -0.5])(
    "negative input %p clamps to stage 1",
    (total) => {
      expect(stageFor(total)).toBe(1);
    },
  );

  it("NaN falls back to stage 1", () => {
    expect(stageFor(Number.NaN)).toBe(1);
  });

  it("Infinity falls back to stage 1 (defensive — !isFinite)", () => {
    expect(stageFor(Number.POSITIVE_INFINITY)).toBe(1);
  });

  it("-Infinity falls back to stage 1 (defensive — !isFinite)", () => {
    expect(stageFor(Number.NEGATIVE_INFINITY)).toBe(1);
  });
});

describe("POOL_STAGE_THRESHOLDS", () => {
  it("contains exactly 5 stages in ascending order 1..5", () => {
    expect(POOL_STAGE_THRESHOLDS.map((r) => r.stage)).toEqual([1, 2, 3, 4, 5]);
  });

  it("min values are non-decreasing", () => {
    for (let i = 1; i < POOL_STAGE_THRESHOLDS.length; i += 1) {
      const prev = POOL_STAGE_THRESHOLDS[i - 1];
      const curr = POOL_STAGE_THRESHOLDS[i];
      expect(curr.min).toBeGreaterThanOrEqual(prev.min);
    }
  });

  it("every max is either >= min (number) or null (stage 5 only)", () => {
    for (const range of POOL_STAGE_THRESHOLDS) {
      if (range.max == null) {
        expect(range.stage).toBe(5);
      } else {
        expect(range.max).toBeGreaterThanOrEqual(range.min);
      }
    }
  });

  it("ranges are contiguous with no overlap (range[i].max + 1 === range[i+1].min)", () => {
    for (let i = 0; i < POOL_STAGE_THRESHOLDS.length - 1; i += 1) {
      const curr = POOL_STAGE_THRESHOLDS[i];
      const next = POOL_STAGE_THRESHOLDS[i + 1];
      expect(curr.max).not.toBeNull();
      expect((curr.max as number) + 1).toBe(next.min);
    }
  });

  it("all labels are non-empty Korean strings", () => {
    for (const range of POOL_STAGE_THRESHOLDS) {
      expect(range.label.length).toBeGreaterThan(0);
      expect(range.label).toMatch(/포인트 풀 \d단계/);
    }
  });

  // Brand-voice gate — none of the AVOID-lexicon words should appear in
  // any threshold label. Mirrors tools/brand-voice-lint.ts Rule 2 list.
  const AVOID_LEXICON = [
    "벌금",
    "잃었다",
    "떨어졌다",
    "실패",
    "자책",
    "부담",
    "패배",
    "죄책감",
  ] as const;

  for (const banned of AVOID_LEXICON) {
    for (const range of POOL_STAGE_THRESHOLDS) {
      it(`brand-voice: stage ${range.stage} label does not contain "${banned}"`, () => {
        expect(range.label).not.toContain(banned);
      });
    }
  }

  it("PRD-locked boundaries: [0..9, 10..24, 25..49, 50..99, >=100] per epics AC2", () => {
    expect(POOL_STAGE_THRESHOLDS[0]).toMatchObject({ stage: 1, min: 0, max: 9 });
    expect(POOL_STAGE_THRESHOLDS[1]).toMatchObject({ stage: 2, min: 10, max: 24 });
    expect(POOL_STAGE_THRESHOLDS[2]).toMatchObject({ stage: 3, min: 25, max: 49 });
    expect(POOL_STAGE_THRESHOLDS[3]).toMatchObject({ stage: 4, min: 50, max: 99 });
    expect(POOL_STAGE_THRESHOLDS[4]).toMatchObject({ stage: 5, min: 100, max: null });
  });
});
