# Story 4.3: `<PoolStack>` 5-stage SVG asset pipeline + threshold table

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a room member,
I want the room's accumulating point pool to be visualized as a 5-stage growing artifact (a stone-tower / weaving / pottery-firing metaphor) that visibly progresses as my room and friends survive together,
So that the pool number is *felt* — not just read — as a shared, growing thing, and the v1 "no-redemption" stance reads as deferred reward rather than a forgotten feature.

## Acceptance Criteria

> 이 스토리는 **PoolStack 컴포넌트의 5-stage 시각 자산 + threshold table + Wallet 통합**을 다룬다. Story 4.1이 깐 `room_point_pool` counter cache + `useRoomPoints` hook + `RoomPointPoolService.applyDelta`의 negative-delta guard 위에 시각 자산만 얹는 FE-only 스토리. BE 변경 없음 (AC6 SCOPE FENCE).

### AC1 — Design-source assets exist on disk (DESIGN DELIVERABLE)

**Given** the design owner has commissioned the 5-stage pool artifact (one of the candidate metaphors: 돌탑 (stone tower) / 천 짜기 (weaving) / 도자기 굽기 (pottery firing); final selection at W3 design lock — D2 Bento Density sub-mode token consumption per UX `ux-design-specification.md:171-175`)
**When** the SVG design-source assets land in `FE/src/assets/pool/stage-{1..5}.svg`
**Then** each .svg file MUST use only hex values that already exist in `FE/src/theme/tokens.json` — verified by `tools/brand-voice-lint.ts` Rule 3 (design-token literal guard) which is **extended in this story** to scan `FE/src/assets/**/*.svg` in addition to `FE/src/**/*.ts(x)`.

**Hex allowlist source:** the lint extracts every `hex` value from `tokens.json` (`color.*`, `semantic.survival.*.color.hex`, `subMode.*.color.*.hex`) and treats them as the allowed set. Any hex literal in an .svg file that is NOT in that set is a Rule 3 WARN (not HARD — matches the current Rule 3 severity for `.ts/.tsx` files per `tools/brand-voice-lint.ts:14-19`).

**Optional PNG raster export (RECOMMENDED FOR DESIGN PIPELINE, NOT RUNTIME-REQUIRED):** Design may also commit `FE/src/assets/pool/stage-{1..5}.png` as a rasterized fallback for marketing / Kakao share-card preview (Story 6.x). These PNGs are NOT consumed at runtime in v1 (the runtime uses declarative `react-native-svg` — see Trap #1). If the design team wants them committed for visual-regression handoff, that's fine; if not, defer to Story 6.x. Either way, the PNGs do NOT block this story's merge.

PRD: FR-8.4.1, FR-8.4.2, FR-8.4.3. Architecture: §4.6 (room point pool counter cache), §4.15 (design-token literal guard), §4.16 (FE↔BE token codegen — FE is canonical token source). UX: O2 Pool 메타포 (line 171-175 — *"구현은 5단계 정적 SVG/PNG swap 한정"*), Surface Assignment Matrix L3 `<PoolStack>` (line 1445).

### AC2 — Threshold table is checked in at `FE/src/theme/pool-stages.ts` (REQUIRED FILE)

**Given** the pool grows from 0 to N
**When** any consumer (initially `<PoolStack>`, future stories may add more) needs to map `total` → `stage ∈ 1..5`
**Then** the table MUST live at `FE/src/theme/pool-stages.ts` and export the following exactly:

```ts
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
  // Clamp negatives to stage 1 — `total < 0` is never expected
  // (Story 4.1's RoomPointPoolService.applyDelta `delta <= 0 → 400 VALIDATION`
  // makes negative `total` impossible) but defensive on the FE side.
  if (!Number.isFinite(total) || total < 0) return 1;
  for (const range of POOL_STAGE_THRESHOLDS) {
    if (range.max == null) return range.stage;
    if (total <= range.max) return range.stage;
  }
  return 5;
}
```

**And** the thresholds MUST be exactly `[0..9 → 1, 10..24 → 2, 25..49 → 3, 50..99 → 4, ≥100 → 5]` per epics.md Story 4.3 AC2 (line 681-685). Final tuning is reviewed with PM at the Day-30 telemetry checkpoint — DO NOT pre-tune.

**Rationale for these boundaries:** PRD §3.1 KPI is *"평균 active room ≥50 pool points by day 30"*. Stage 4 ([50, 99]) corresponds to the success bar, so a room that hits the success metric visually reaches "형상" (recognizable artifact) on Day 30. Stage 5 (≥100) is the keystone — a 2× over-achievement that should feel meaningfully different from "success".

**And** a unit test at `FE/src/theme/__tests__/pool-stages.test.ts` MUST exhaustively assert `stageFor(N)` for every transition point: `stageFor(0)`, `stageFor(9)`, `stageFor(10)`, `stageFor(24)`, `stageFor(25)`, `stageFor(49)`, `stageFor(50)`, `stageFor(99)`, `stageFor(100)`, `stageFor(9999)` plus the negative/NaN defensive paths `stageFor(-1)`, `stageFor(NaN)`, `stageFor(Infinity)`.

PRD: FR-8.4.1, FR-8.4.2, FR-8.4.3, §3.1 KPI. Architecture: §4.6.

### AC3 — `<PoolStack>` component is checked in at `FE/src/components/survival/PoolStack.tsx` (REQUIRED FILE)

**Given** the pool's current total (from Story 4.1's `useRoomPoints(roomId)` hook, threaded as a prop)
**When** `<PoolStack total={N} />` renders
**Then** the component MUST:

1. Render the 5-stage visual artifact for `stage = stageFor(total)`. The 5 stages are implemented as **5 sibling pure-functional components** at `FE/src/components/survival/poolStages/Stage{1..5}.tsx`. Each stage component returns a declarative `<Svg>` tree composed from `react-native-svg` primitives (`Svg`, `G`, `Rect`, `Circle`, `Path`, `Line`) — same library and pattern as `FE/src/components/grid/ContributionGrid.tsx:3` (the project's canonical SVG precedent). NO new dependency.
2. Source colors via `tokensV2.color.*` (re-exported from `FE/src/theme/tokens.ts:25` — `import { tokensV2 } from "../../theme/tokens"`) OR via `useTheme()` for sub-mode-resolved values. Direct hex literals inside `Stage{1..5}.tsx` are FORBIDDEN — enforced by `tools/brand-voice-lint.ts` Rule 3 already scanning `FE/src/**/*.tsx`.
3. Use `tokensV2.color.key.default.hex` (`#7E2C2A` oxblood) as the primary stroke/fill across stages, with `tokensV2.color.ember.subtle.hex` (`#A48064`) reserved for the keystone (stage 5) accent. `ember.default` fails the required 3:1 contrast against `surface.sunken`; the subtler ember-ramp tone is the approved accessible keystone color. Other strokes use `tokensV2.color.stroke.default.hex` / `tokensV2.color.text.tertiary.hex` for muted/early-stage detail. NO survival colors (`semantic.survival.*` is reserved for survival state; the pool is not a survival-state surface).
4. Set `accessibilityLabel={POOL_STAGE_THRESHOLDS[stage-1].label}` on the wrapping `<View>`. Set `accessibilityRole="image"` (the artifact reads as an image, not a button — there is no interaction).
5. Accept exactly this props interface — DO NOT widen:
   ```ts
   export interface PoolStackProps {
     readonly total: number;
     /** Optional override for the wrapping View's testID — useful for
      *  WalletScreen mounting multiple stack candidates in tests. */
     readonly testID?: string;
   }
   ```
6. NOT accept a `max` prop. The 5 thresholds in `pool-stages.ts` ARE the max — no per-room override (epics.md AC2 thresholds are global per PM tuning, not per-room).
7. NOT call `useRoomPoints` itself. The `total` prop is threaded from the consumer (WalletScreen) so the component stays purely presentational (mirrors the Story 4.1 AC8 PoolBar refactor — leaf components don't own the realtime subscription).

PRD: FR-8.4.1, FR-8.4.2, FR-8.4.3. Architecture: §4.6. UX: L3 `<PoolStack>` (line 1445), O2 Pool 메타포 (line 171-175).

### AC4 — Cross-fade animation between stages + delta ember glow (MOTION CONTRACT)

**Given** the pool transitions between stages (e.g., total goes from 9 → 10 — stage 1 → 2, or from 49 → 50 — stage 3 → 4)
**When** `<PoolStack>` receives the new total via the `total` prop
**Then** the stage-N → stage-(N+1) swap MUST:

1. Cross-fade (NOT pop) between the two stages over `tokensV2.motion.duration.normal` (= 250ms; resolved via `useTheme()` so sub-mode overrides apply) with `Easing.bezier(...tokensV2.motion.easing.entry)` (= `cubic-bezier(0, 0, 0.2, 1)`).
2. Run as `Animated.parallel([fadeOut(prevStage, 250ms), fadeIn(nextStage, 250ms)])` — both stages mount during the transition; the outgoing unmounts on animation complete.
3. Animate `opacity` only — NOT `width` / `height` / `transform.scale` / layout properties. Compositor-friendly (project-context web/performance rule).
4. When `AccessibilityInfo.isReduceMotionEnabled() === true` (or `prefers-reduced-motion: reduce`), the swap MUST be **instant** (no animation, opacity sets directly to `0` / `1`). Mirror the PoolBar reduced-motion pattern at `FE/src/components/revival/PoolBar.tsx:84-93`.

**Given** the pool total INCREASES by `delta > 0` (e.g., a revival lands and `total` goes 47 → 52, regardless of whether the stage changes)
**When** `<PoolStack>` observes a `total` increase between renders
**Then** a brief ember-tinted "+N" feedback element MUST animate at the top-right of the artifact:
- Opacity sawtooth `0 → 1` over 150ms (`tokensV2.motion.duration.fast`), hold for `tokensV2.motion.duration.normal` (250ms), then `1 → 0` over 250ms. Total visible time ≈ 650ms.
- Color: `tokensV2.color.ember.default.hex` (`#D89F62`) — the v2 micro-confirmation warmth color (UX line 856-857 — *"Kudos send glow, daily-completion micro-burst"*).
- Text: `"+{delta}"` (e.g., `"+5"`, `"+3"`). Use `Text variant="caption"` from `../ui/Text`.
- Reduced-motion fallback: skip the glow entirely (instant suppress; do NOT show a static `"+N"` because that would clutter the artifact permanently).

**And** the cross-fade and the ember glow animations are INDEPENDENT — a delta that does not cross a stage boundary triggers ONLY the ember glow; a delta that crosses (e.g., 9 → 10) triggers BOTH in parallel (the glow and the cross-fade share `t=0`).

UX: feedback pattern *"Success — 1초 toast, `bg.elevated` + `ember.default` dot stroke"* (line 763-768); reduced-motion policy (line 985-1001 — *"prefers-reduced-motion: reduce 시 motion.instant 0ms + 모션 제거"*). PRD: NFR-9.6.* (a11y, reduced-motion compliance).

### AC5 — WCAG 2.2 AA contrast verified per stage (BLOCKING accessibility gate)

**Given** the WCAG 2.2 contrast test runs against each stage's primary stroke/fill against the wallet card surface
**When** the contrast verifier executes
**Then** every stage SVG's primary stroke color MUST achieve ≥ 3:1 contrast against `surface.sunken` (`#F0EBE3` — the D2 Bento card background per `FE/src/components/wallet/WalletScreen.tsx:258`). Stage 5's keystone accent (ember) also passes ≥3:1 against the same surface.

**And** `tools/contrast-check.ts` MUST be **extended in this story** to add a new pair-set `POOL_STACK_PAIRS` that validates:
- `tokensV2.color.key.default.hex` × `surface.sunken` (≥ 3:1 required — non-text/graphics threshold per WCAG 2.x §1.4.11)
- `tokensV2.color.ember.subtle.hex` × `surface.sunken` (≥ 3:1 required)
- `tokensV2.color.stroke.default.hex` × `surface.sunken` (≥ 3:1 required — early-stage muted strokes)

If any pair fails, the contrast-check exits non-zero (matches existing `tools/contrast-check.ts` failure mode).

**And** the wrapping `<View accessibilityLabel="…" accessibilityRole="image">` ensures VoiceOver / TalkBack reads the stage label without relying on visual contrast at all.

**Test reuse:** `tools/__tests__/contrast-check.test.ts` already validates the existing pair set; this story adds 3 new cases (one per pair above) using the same shape.

PRD: NFR-9.6.* (a11y). Architecture: §4.15 (brand-voice + a11y gate — contrast-check is a hard CI gate). UX: line 985-1001 (reduced-motion + a11y policy).

### AC6 — Positive-only invariant: lastSeenStage ratchet + dev-time warning (DEFENSIVE)

**Given** Story 4.1's `RoomPointPoolService.applyDelta` guarantees `total` is monotonically non-decreasing (BE-side negative-delta guard)
**When** `<PoolStack total={N}>` is rendered with `N < lastSeenStage`'s lower bound (i.e., the FE somehow observes a regression — STOMP frame ordering bug, cache snapshot drift, etc.)
**Then** the component MUST:
1. Use a `useRef<number>` initialized to `stageFor(initialTotal)` and updated only when the *current* stage exceeds the ref — `lastSeenStage = max(lastSeenStage, currentStage)`. The displayed stage is `max(currentStage, lastSeenStage)`.
2. In `__DEV__` mode only (i.e., when `__DEV__ === true`, the Metro/React Native dev-build constant), emit a `console.warn("[PoolStack] regression observed: prev=" + prevStage + " next=" + nextStage + " total=" + total)` exactly once per regression event. NEVER `console.error` (project-context FE rule — no `console.log` in production code; `console.warn` in dev-only branches is allowed).
3. In production builds (`__DEV__ === false`), the ratchet silently renders the higher stage. NO user-facing message.

**Rationale:** The BE write-path forbids negative deltas (Story 4.1 AC3 — `IllegalArgumentException → 400 VALIDATION`). The FE ratchet is defence-in-depth against transport-layer bugs (out-of-order STOMP frames are handled by Story 4.1 Patch 1, but multi-room cache cross-contamination or a future bug could still produce a regression at the View layer). The visible stage NEVER goes backwards — the metaphor "growing artifact" demands monotonic progress.

**Edge case — initial mount with non-zero total:** When the component first mounts with `total = 47` (e.g., a room with prior pool history), `lastSeenStage` initializes to `stageFor(47) = 3`. A subsequent prop change to `total = 5` would still render stage 3. This is intentional — the FE never displays a regression even if the BE somehow ships one.

**Test cases (in PoolStack.test.tsx):**
- "given mounted with total=47, when re-rendered with total=5, then renders stage 3 (ratcheted)"
- "given mounted with total=0, when total grows 0→10→25→50→100→9, then renders stage 5 (never regresses)"
- "given __DEV__ === true and a regression occurs, then console.warn is called once with the diagnostic shape"
- "given __DEV__ === false and a regression occurs, then console.warn is NOT called"

PRD: FR-8.4.5. Architecture: §4.6 (*"Negative deltas are forbidden by the write path"*). Story 4.1 BE-7 `RoomPointPoolServiceTest.applyDelta_negativeDelta_throwsIllegalArgument`.

### AC7 — Wallet integration: PoolStack REPLACES PoolBar in WalletScreen (BREAKING UI MIGRATION)

**Given** Story 3.4 / Story 4.1 shipped `<PoolBar>` as the **v1 linear-fill placeholder** with the explicit `TODO(Story 4.3)` comment at `FE/src/components/wallet/WalletScreen.tsx:38` (*"replace with the BE-shipped poolMax per room"*) and `FE/src/components/revival/PoolBar.tsx:32-34` (*"v1 placeholder threshold per Story 3.4 critical note 3. Story 4.3 wires the real per-room threshold table"*)
**When** Story 4.3 lands
**Then** `<PoolStack>` REPLACES `<PoolBar>` in WalletScreen — the linear-bar metaphor is the v1 placeholder; the 5-stage stone-tower metaphor is the v2 final form per UX line 1445 (*"`<PoolStack>` 그룹 점수 5단계 SVG/PNG swap (돌탑 메타포) | D2 | 확장 (v1 `<PoolMeter>`)"*).

**Required edits in `FE/src/components/wallet/WalletScreen.tsx`:**
1. Replace the import `import { PoolBar } from "../revival/PoolBar";` (line 30) with `import { PoolStack } from "../survival/PoolStack";`.
2. Replace `<PoolBar total={pool} max={POOL_MAX_V1} />` (line 210, inside `styles.poolBarSpacer`) with `<PoolStack total={pool} />`.
3. Delete the `POOL_MAX_V1 = 100` constant (lines 35-39 including the comment + TODO).
4. Update the file's leading comment (line 6) — change `"3. Room point pool (<PoolBar> + Story 3.3's <FriendGiftBadge>)"` → `"3. Room point pool (<PoolStack> + Story 3.3's <FriendGiftBadge>)"`.
5. Rename `styles.poolBarSpacer` → `styles.poolStackSpacer` (kebab-grep then rename). Adjust `marginTop` / `marginBottom` from `space[2]` to `space[3]` if needed — let visual smoke testing decide (PoolStack is taller than PoolBar; the existing 8px spacer rhythm may need 12px).

**Required deletions:**
- `FE/src/components/revival/PoolBar.tsx` — DELETE. Story 4.3 is the only consumer's migration story; grep confirms no other surface uses PoolBar (`grep -rn "PoolBar" FE/src FE/app` returns only `WalletScreen.tsx` + `PoolBar.test.tsx` + the component's own self-references). DO NOT keep PoolBar.tsx as dead code.
- `FE/src/components/revival/__tests__/PoolBar.test.tsx` — DELETE. The tests covered the v1 placeholder behavior; PoolStack has its own dedicated test file (FE-7).

**WalletScreen.test.tsx required edits:**
1. Existing test case at line 181 (`"room pool section mounts PoolBar + FriendGiftBadge children"`) — RENAME to `"room pool section mounts PoolStack + FriendGiftBadge children"` and replace the PoolBar testID/text assertion with the equivalent PoolStack assertion (`getByTestId("pool-stack")` or `getByLabelText(/포인트 풀 \d단계/)`).
2. Existing leading comment at line 8 (`"5. Room pool section: includes FriendGiftBadge mount + PoolBar children."`) — update PoolBar → PoolStack.
3. Add a NEW case covering PoolStack stage transition (FE-7).

**WalletPreview NOT touched:** WalletPreview (spectator-only Today-tab readout) renders the pool as a plain `<Text>` line (`💚 그룹 포인트 N` at line 70-76) — NO PoolBar mount. The compact spectator surface intentionally does NOT show the 5-stage artifact (it's an at-a-glance balance readout, not the canonical Pool surface). The canonical Pool visualization is the dedicated per-room Wallet route (`/wallet/{roomId}` → WalletScreen) — accessible from WalletPreview's existing *"Wallet 자세히 보기"* link (line 86-95). DO NOT add PoolStack to WalletPreview.

PRD: FR-8.4.1, FR-8.4.2 (pool visible on every room screen — WalletScreen is the per-room surface). UX: Surface Assignment Matrix line 1162 (*"Wallet (4-track) — D2 Bento Density"*), L3 `<PoolStack>` (line 1445).

### AC8 — D2 Bento sub-mode token consumption (UX contract)

**Given** WalletScreen is wrapped in `<SubModeProvider subMode="bento">` at `FE/app/wallet/[roomId].tsx:25`
**When** `<PoolStack>` renders inside that subtree
**Then** the component MUST consume sub-mode-resolved tokens via `useTheme()` so D2.bento overrides apply automatically (per Story 1.5 design system). Specifically:
- `theme.radius.default` (D2 override: `12px`, base: `10px`) — used on the wrapping container if any rounded corners are present.
- `theme.space.layout.padding` (D2 override: `16px`, base: `20px`) — used for substantial inner-surface padding if PoolStack adds an inner surface. The top-right `+N` glow's compact `4px` / `2px` inset is an approved fixed micro-spacing exception; scaling it to layout padding would distort the overlay.
- The `bento` sub-mode introduces `color.bg.elevated` override (`#27221F`) — if PoolStack uses an elevated inner surface (e.g., the stage-5 keystone glow background), it consumes this token.

**And** the component MUST NOT add a `<SubModeProvider>` wrap of its own — it inherits the route's wrap (DO NOT double-wrap; that breaks D2 override resolution).

**And** the component MUST be sub-mode-agnostic in source code — no `subMode === "bento"` branching. If/when a future route mounts `<PoolStack>` under `<SubModeProvider subMode="postcard">` (e.g., Final-3 ceremony), the token resolution adapts automatically with zero PoolStack code change.

PRD: NFR-9.6.1 (semantic packed-type — survival colors are NOT used here; pool is its own surface). Architecture: §4.16 (FE↔BE codegen — FE owns canonical token source). UX: D2 Bento (line 1073-1091).

### AC9 — Snapshot tests + visual regression (TEST COVERAGE)

**Given** all 5 stages need a stable visual baseline to prevent regression as colors / strokes / paths shift across PRs
**When** the Jest test suite runs
**Then** `FE/src/components/survival/__tests__/PoolStack.test.tsx` MUST emit Jest snapshot files at `FE/src/components/survival/__tests__/__snapshots__/PoolStack.test.tsx.snap` containing one snapshot per stage (5 snapshots minimum). Use `@testing-library/react-native`'s `toJSON()` shape per the existing precedent at `FE/src/components/survival/__tests__/__snapshots__/SurvivalChip.dynamic-type.test.tsx.snap`.

**Snapshot cases (one per stage):**
- `"PoolStack renders stage 1 at total=0"` — initial empty state
- `"PoolStack renders stage 2 at total=10"` — first transition
- `"PoolStack renders stage 3 at total=25"` — mid
- `"PoolStack renders stage 4 at total=50"` — success bar (PRD §3.1 KPI)
- `"PoolStack renders stage 5 at total=100"` — complete

**And** the visual-regression test for the dev-time warning + ratchet (AC6) MUST NOT be a snapshot — it asserts behavior, not visual structure. Keep snapshots scoped to the 5 stage renders.

**Storybook deviation:** The epics text says *"a Storybook / visual-regression snapshot per stage"*. The project does NOT have Storybook configured (no `.storybook/` dir, no `storybook` in `package.json`). Jest snapshot tests are the **canonical visual-regression mechanism** in this codebase (see `SurvivalChip.dynamic-type.test.tsx.snap` precedent). This is the substitution; the epics intent (per-stage visual baseline that catches regression) is preserved.

PRD: FR-8.4.1, FR-8.4.2. Architecture: §4.15. Project-context: *"`@testing-library/react-native`. Test files at `FE/src/**/__tests__/**/*.test.{ts,tsx}`."*

### AC10 — Scope fence: no BE changes, no new endpoints, no new realtime topics, no new schema

**Given** the implementation is complete
**When** a diff is taken against `main`
**Then** the diff MUST contain ZERO lines under:
- `BE/src/main/java/com/yeosal/api/**` — no Java changes
- `BE/src/main/resources/db/migration/V*.sql` — no new Flyway migration
- `FE/src/api/**` — no new API client (existing `roomPoints.ts` is untouched)
- `FE/src/lib/query/keys.ts` — no new query key
- `FE/src/lib/query/hooks/**` — no new domain hook (existing `roomPoints.ts` is untouched)
- `FE/src/lib/realtime/**` — no new STOMP subscription (Story 4.1's `/topic/rooms.{id}.points` topic is the existing realtime channel)
- `FE/src/theme/tokens.json` — no new token (PoolStack consumes existing `color.key.*`, `color.ember.*`, `color.stroke.*`, `space.*`, `radius.*`, `motion.*`)

**And** the diff MAY contain lines ONLY under:
- `FE/src/components/survival/PoolStack.tsx` — NEW
- `FE/src/components/survival/poolStages/Stage1.tsx` — NEW
- `FE/src/components/survival/poolStages/Stage2.tsx` — NEW
- `FE/src/components/survival/poolStages/Stage3.tsx` — NEW
- `FE/src/components/survival/poolStages/Stage4.tsx` — NEW
- `FE/src/components/survival/poolStages/Stage5.tsx` — NEW
- `FE/src/components/survival/__tests__/PoolStack.test.tsx` — NEW
- `FE/src/components/survival/__tests__/__snapshots__/PoolStack.test.tsx.snap` — NEW (auto-generated by Jest)
- `FE/src/theme/pool-stages.ts` — NEW
- `FE/src/theme/__tests__/pool-stages.test.ts` — NEW
- `FE/src/components/wallet/WalletScreen.tsx` — MODIFIED (AC7 PoolBar → PoolStack migration)
- `FE/src/components/wallet/__tests__/WalletScreen.test.tsx` — MODIFIED (AC7 + new stage transition case)
- `FE/src/assets/pool/stage-{1..5}.svg` — NEW (design deliverables, AC1)
- `FE/src/assets/pool/stage-{1..5}.png` — NEW IF design ships them (optional per AC1)
- `FE/src/components/revival/PoolBar.tsx` — DELETED (AC7)
- `FE/src/components/revival/__tests__/PoolBar.test.tsx` — DELETED (AC7)
- `tools/brand-voice-lint.ts` — MODIFIED (extend Rule 3 to scan `FE/src/assets/**/*.svg`)
- `tools/__tests__/brand-voice-lint.test.ts` — MODIFIED (add svg-scan case)
- `tools/contrast-check.ts` — MODIFIED (add `POOL_STACK_PAIRS` set)
- `tools/__tests__/contrast-check.test.ts` — MODIFIED (add 3 PoolStack pair cases)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — flip `4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table: ready-for-dev → in-progress → review`
- `_bmad-output/implementation-artifacts/4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table.md` — Status, Tasks/Subtasks checkboxes, Dev Agent Record, File List, Change Log.

**Anything else is out of scope.** If a dev-time discovery suggests additional file changes are needed, raise it as a deferred-work entry instead of bundling.

### AC11 — Phase-2 readiness invariant preserved (regression guard)

**Given** Story 4.1's `RoomPointPoolService.applyDelta` enforces `delta <= 0 → IllegalArgumentException → 400 VALIDATION` (the BE-side proof that v1 forbids pool decrement)
**When** Story 4.3's changes land
**Then** that BE invariant MUST stay byte-identical — Story 4.3 makes NO Java changes (per AC10), so the existing `RoomPointPoolServiceTest.applyDelta_negativeDelta_throwsIllegalArgument` test from Story 4.1 BE-7 continues to enforce epics.md Story 4.2 AC3 (*"no v1 code path accommodates phase-2 decrement pre-emptively"*).

**And** PoolStack's AC6 client-side ratchet is a separate, additive defence-in-depth — it does NOT replace the BE guard. The BE guard remains the source of truth.

PRD: FR-8.4.5. Architecture: §4.6. Story 4.1 BE-7 + Story 4.2 AC7.

### AC12 — Existing tests continue to pass (regression gate)

**Given** all existing test suites
**When** Story 4.3's changes land
**Then** the following test files MUST stay green:
- `WalletScreen.test.tsx` — existing 10 cases (8 from Story 3.4 + 2 from Story 4.2) stay green after the PoolBar→PoolStack rename; 1 NEW case added per AC7 covering the stage transition. Net delta: same count, one assertion shape change inside the existing line-181 case + 1 new case = effective +1.
- `WalletPreview.test.tsx` — all existing cases stay green; ZERO changes (AC7 explicitly does NOT touch WalletPreview).
- `roomPoints.test.tsx` (Story 4.1) — all 8 existing cases stay green (the hook is unchanged).
- `useSelfRevival.test.tsx`, `useSendFriendGift.test.tsx` — green (their pool-invalidation logic targets the existing `qk.roomPoints` key; nothing changes).
- All Story 3.4 wallet tests + Story 4.1 BE tests (`RoomPointPoolServiceTest` 6 cases, `RoomPointsControllerTest` 5 cases) — green by virtue of NO BE files touched.
- `tools/__tests__/brand-voice-lint.test.ts` — stays green after the Rule 3 svg-scan extension (new case added; existing assertions stable).
- `tools/__tests__/contrast-check.test.ts` — stays green after the `POOL_STACK_PAIRS` addition.

**And** `cd FE && npm run lint` MUST stay at the Story 4.2 pre-existing baseline (4 errors + 2 warnings in untouched files). The new files MUST be lint-clean (`npx eslint FE/src/components/survival/PoolStack.tsx FE/src/components/survival/poolStages/Stage{1..5}.tsx FE/src/components/survival/__tests__/PoolStack.test.tsx FE/src/theme/pool-stages.ts FE/src/theme/__tests__/pool-stages.test.ts FE/src/components/wallet/WalletScreen.tsx FE/src/components/wallet/__tests__/WalletScreen.test.tsx` → 0 problems).

**And** `cd FE && npm run typecheck` MUST NOT add any NEW typecheck errors (pre-existing `FriendsTodayPager.tsx` 2 errors stay; Story 4.3 adds 0).

**And** `cd FE && npm test` MUST be green. Suite-count delta: Story 4.2 baseline is 53 suites / 339 tests; Story 4.3 adds:
- `+1 suite` (`PoolStack.test.tsx`) — 8+ cases (stage selection × 5 + cross-fade + ratchet + delta glow)
- `+1 suite` (`pool-stages.test.ts`) — ~12 cases (every transition boundary + defensive paths)
- `-1 suite` (`PoolBar.test.tsx` deleted) — was 4-5 cases

Expected: 54 suites / ~355–360 tests (rough estimate; AC8 in dev-agent-record reports the actual count).

## Tasks / Subtasks

### Frontend (FE/) — Theme + threshold table

- [x] **FE-1** Create `FE/src/theme/pool-stages.ts` (AC2).
  - [x] Export `PoolStageRange` interface with `stage`, `min`, `max` (number | null), `label` fields.
  - [x] Export `POOL_STAGE_THRESHOLDS: readonly PoolStageRange[]` with exactly the 5 ranges from epics AC2 lines 681-685. Labels: `"포인트 풀 1단계 — 토대"` / `"...2단계 — 첫 켜"` / `"...3단계 — 여러 켜"` / `"...4단계 — 형상"` / `"...5단계 — 완성"`. NO emojis. NO trailing space. Each label is brand-voice-safe (verified against AVOID-lexicon — none of `벌금|잃었다|떨어졌다|실패|자책|부담|패배|죄책감` appear).
  - [x] Export `stageFor(total: number): 1 | 2 | 3 | 4 | 5` with defensive handling: `!Number.isFinite(total) || total < 0` → returns `1`. Iterate `POOL_STAGE_THRESHOLDS` for inclusive-max bucket matching.
  - [x] Do NOT add a `PoolStageLabels` enum or duplicate the labels elsewhere — the const array is the single source of truth.

- [x] **FE-2** Create `FE/src/theme/__tests__/pool-stages.test.ts` (AC2).
  - [x] `describe("stageFor")` block with parametric cases covering: 0, 9, 10, 24, 25, 49, 50, 99, 100, 9999, -1, NaN, Infinity, -0.5. Use `it.each([...])` for compactness.
  - [x] One `describe("POOL_STAGE_THRESHOLDS")` block asserting the table's structural invariants:
    - All 5 stages exist in ascending order (`stages.map(r => r.stage)` deep-equals `[1, 2, 3, 4, 5]`).
    - All min values are non-decreasing.
    - All `max` values are either numbers ≥ matching `min`, or `null` (only stage 5).
    - No range overlaps (range[i].max + 1 === range[i+1].min, for i < 4).
    - All labels are non-empty Korean strings (length > 0, no AVOID-lexicon substring).
  - [x] Brand-voice loop case: iterate the 8 AVOID-lexicon words × every label, asserting `expect(label).not.toContain(banned)`.

### Frontend (FE/) — Stage components + PoolStack

- [x] **FE-3** Create `FE/src/components/survival/poolStages/Stage{1..5}.tsx` (AC3, AC5).
  - [x] Each file is a pure-functional component returning `<Svg>...</Svg>` from `react-native-svg`. Width/height: 96×96 (fits the D2 Bento card padding rhythm without overflow — adjust if visual smoke requires).
  - [x] Stage progression — propose a stone-tower (돌탑) metaphor as the **default candidate**, subject to W3 design lock:
    - Stage 1: foundation outline only (`<Path ... stroke="...stroke.default" />`). Empty seedling.
    - Stage 2: foundation + 1 small stone layer (low rectangle).
    - Stage 3: foundation + 2 stone layers (multi-layer presence).
    - Stage 4: foundation + 3 stone layers (recognizable artifact, `color.key.default` strokes).
    - Stage 5: foundation + 3 stone layers + keystone circle on top (`color.ember.subtle` accent — the approved accessible ember-ramp tone).
  - [x] Colors MUST come from `tokensV2.color.*` (static read) or `useTheme().color.*` (sub-mode-aware read). Stage 5's keystone uses `tokensV2.color.ember.subtle.hex`; other strokes use `tokensV2.color.key.default.hex` / `tokensV2.color.stroke.default.hex` / `tokensV2.color.text.tertiary.hex`. NO inline hex literals.
  - [x] Each stage component accepts NO props (pure visual). The stage selection happens in PoolStack, not inside the stage components.
  - [x] If the design team commits a different metaphor (천 짜기 / 도자기) at W3 design lock, update the SVG primitives accordingly — the threshold table, PoolStack motion, and accessibility labels stay unchanged (metaphor-agnostic).
  - [x] DO NOT add filters / gradients / blurs. v1 metaphor is *"5단계 정적 SVG/PNG swap"* (UX line 173-175) — keep it static and compositor-friendly.

- [x] **FE-4** Create `FE/src/components/survival/PoolStack.tsx` (AC3, AC4, AC6, AC8).
  - [x] Props interface exactly: `interface PoolStackProps { readonly total: number; readonly testID?: string }`.
  - [x] Import `stageFor` and `POOL_STAGE_THRESHOLDS` from `@/theme/pool-stages`.
  - [x] Compute `currentStage = stageFor(total)`. Maintain `lastSeenStageRef = useRef<number>(currentStage)` initialized lazily. Update via effect: `if (currentStage > lastSeenStageRef.current) lastSeenStageRef.current = currentStage;`.
  - [x] Compute `displayStage = Math.max(currentStage, lastSeenStageRef.current)`.
  - [x] In `__DEV__ && currentStage < lastSeenStageRef.current`, call `console.warn(...)` exactly once per regression event (use a separate `useRef<boolean>` flag if needed to dedupe across renders for the same regression).
  - [x] Mount the appropriate `<Stage{N} />` based on `displayStage`. Cross-fade implementation: maintain `prevStageRef`; when `displayStage` changes between renders, mount BOTH `<Stage{prev}>` and `<Stage{next}>` with `Animated.Value` opacity. Run `Animated.parallel([fadeOut, fadeIn])` over `theme.motion.duration.normal` (250ms) with `Easing.bezier(0, 0, 0.2, 1)`. On complete: unmount prev. Use `useNativeDriver: true` since opacity is on the native driver allow-list.
  - [x] Reduced-motion branch: detect via `AccessibilityInfo.isReduceMotionEnabled()` (mirror PoolBar's `useState<boolean | null>(null)` settle pattern at `PoolBar.tsx:48-79`). When reduced, set opacity directly (no animation).
  - [x] Delta ember glow: maintain `prevTotalRef`; when `total > prevTotalRef.current`, render a temporary `"+{delta}"` `<Animated.Text>` overlay at top-right with the AC4-specified opacity sawtooth (0 → 1 over 150ms, hold 250ms, 1 → 0 over 250ms). Total visible ≈ 650ms. Reduced-motion: skip entirely.
  - [x] Wrapping `<View>` carries `accessibilityRole="image"` and `accessibilityLabel={POOL_STAGE_THRESHOLDS[displayStage - 1].label}`. Cancel in-flight animations on unmount (mirror PoolBar's cleanup at `PoolBar.tsx:110-118`).
  - [x] DO NOT call `useRoomPoints` from inside PoolStack — the `total` prop is the only input (purely presentational, mirrors Story 4.1 AC8 PoolBar refactor).
  - [x] DO NOT add a `<SubModeProvider>` wrap — inherit the route's wrap (AC8).

- [x] **FE-5** Create `FE/src/components/survival/__tests__/PoolStack.test.tsx` (AC3, AC4, AC6, AC9).
  - [x] **Stage selection cases (5 cases via `it.each`)**: each case mounts `<PoolStack total={N} />`, asserts `getByLabelText` matches the expected stage label. N values: 0, 10, 25, 50, 100.
  - [x] **Snapshot cases (5 cases)**: one `toJSON()` snapshot per stage. Use `total = 0 / 10 / 25 / 50 / 100`. These produce `__snapshots__/PoolStack.test.tsx.snap`.
  - [x] **Cross-fade case**: mount with `total=0`, advance the timer / rerender with `total=10`, assert that both stage-1 and stage-2 are momentarily mounted (use `getAllByRole("image")` length 2 during the transition window). Use `jest.useFakeTimers()` + `act()` to drive the animation. After 250ms, only stage-2 remains.
  - [x] **Reduced-motion case**: mock `AccessibilityInfo.isReduceMotionEnabled` to resolve `true`; mount with `total=0`, rerender with `total=10`; assert that ONLY one stage is mounted at any point (instant swap). Use the existing PoolBar test precedent at `FE/src/components/revival/__tests__/PoolBar.test.tsx` (whatever it uses for the AccessibilityInfo mock).
  - [x] **Ratchet case (AC6)**: mount with `total=47` (stage 3), rerender with `total=5`, assert that stage 3 (not stage 1) is still rendered.
  - [x] **`__DEV__` warning case (AC6)**: stub `console.warn`; force `__DEV__ = true`; mount with `total=47`, rerender with `total=5`, assert `console.warn` called once with the diagnostic shape. Restore `__DEV__` in `afterEach`.
  - [x] **Production silence case (AC6)**: stub `console.warn`; force `__DEV__ = false`; trigger a regression; assert `console.warn` NOT called.
  - [x] **Delta glow case (AC4)**: mount with `total=10`, rerender with `total=15`; within the 650ms window, assert `getByText("+5")` exists. After 650ms, assert `queryByText("+5")` is null.
  - [x] **Negative-total defensive case (AC6)**: mount with `total=-5` (defensive — BE guarantees this can't happen); assert stage 1 renders without crashing.

### Frontend (FE/) — Asset files

- [x] **FE-6** Commit the 5 design-source SVG files at `FE/src/assets/pool/stage-{1..5}.svg` (AC1).
  - [x] Each .svg uses ONLY hex values present in `FE/src/theme/tokens.json` (`color.key.*.hex`, `color.ember.*.hex`, `color.stroke.*.hex`, `color.text.*.hex`).
  - [x] No `<style>` inline CSS (RN doesn't render those anyway in declarative SVG); each color is on a `fill=""` / `stroke=""` attribute.
  - [x] These files are **design-source artifacts** — they document the visual intent and seed the TSX port at FE-3. They are NOT imported at runtime (no `react-native-svg-transformer` is configured in metro; runtime uses declarative `<Svg>` components).
  - [x] PNG raster exports (`stage-{1..5}.png`) are OPTIONAL per AC1 — commit if the design team ships them; otherwise skip and let Story 6.x own raster export when needed for marketing / Kakao share.

### Tools — Lint extension

- [x] **TOOLS-1** Extend `tools/brand-voice-lint.ts` Rule 3 to scan `FE/src/assets/**/*.svg` (AC1).
  - [x] The existing Rule 3 walks `.ts/.tsx` files (`tools/brand-voice-lint.ts:14-19`). Extend the walker to also include `.svg` files under `FE/src/assets/`.
  - [x] For .svg files, the rule matches `fill="#XXXXXX"` and `stroke="#XXXXXX"` (and rgba/oklch — match existing Rule 3 regex). Allowlist: union of all `hex` values found in `FE/src/theme/tokens.json`.
  - [x] Severity stays WARN (matches existing Rule 3 severity). A hex literal NOT in the allowlist prints the file/line/column.
  - [x] Add a regression test in `tools/__tests__/brand-voice-lint.test.ts`: synthesize a temp `.svg` with an unknown hex and assert the Rule 3 violation is reported. Synthesize one with an allowlisted hex and assert no violation. Mirror the existing test fixture pattern.

- [x] **TOOLS-2** Extend `tools/contrast-check.ts` to validate PoolStack pairs (AC5).
  - [x] Add a new pair set `POOL_STACK_PAIRS` after the existing pair definitions in `tools/contrast-check.ts`. Three pairs minimum:
    - `{ fg: tokens.color.key.default.hex, bg: surfaceSunkenHex, threshold: 3.0, label: "PoolStack primary stroke" }`
    - `{ fg: tokens.color.ember.subtle.hex, bg: surfaceSunkenHex, threshold: 3.0, label: "PoolStack keystone accent" }`
    - `{ fg: tokens.color.stroke.default.hex, bg: surfaceSunkenHex, threshold: 3.0, label: "PoolStack muted stroke" }`
  - [x] `surfaceSunkenHex` is the v1 surface tone Wallet card uses (`palette.surfaceSunken = "#F0EBE3"` from `FE/src/theme/tokens.ts:36`). The contrast verifier currently reads tokens.json directly — extend it to also load the v1 palette via a static import OR (simpler) inline the literal `"#F0EBE3"` with a code comment citing `tokens.ts:36`.
  - [x] Add 3 test cases in `tools/__tests__/contrast-check.test.ts` mirroring the existing pair-set test shape.
  - [x] Verify locally that all 3 pairs pass ≥3:1. `ember.default × sunken` fails at 1.95:1, so Story 4.3 review approved `ember.subtle × sunken` as the accessible keystone pair.

### Frontend (FE/) — Wallet integration

- [x] **FE-7** Modify `FE/src/components/wallet/WalletScreen.tsx` to mount `<PoolStack>` instead of `<PoolBar>` (AC7).
  - [x] Replace `import { PoolBar } from "../revival/PoolBar";` with `import { PoolStack } from "../survival/PoolStack";` (alphabetize / preserve existing import order).
  - [x] Replace `<PoolBar total={pool} max={POOL_MAX_V1} />` with `<PoolStack total={pool} testID="wallet-pool-stack" />`.
  - [x] Delete the `POOL_MAX_V1 = 100` constant (lines 35-39) including the `TODO(Story 4.3)` comment.
  - [x] Update the leading file comment line 6: `"3. Room point pool (<PoolBar> + Story 3.3's <FriendGiftBadge>)"` → `"3. Room point pool (<PoolStack> + Story 3.3's <FriendGiftBadge>)"`.
  - [x] Rename `styles.poolBarSpacer` → `styles.poolStackSpacer`. Adjust margin if visual smoke shows PoolStack needs more breathing room (consider `space[3]` = 12px instead of `space[2]` = 8px). The bento card's `gap: space[1]` already provides 4px between siblings; the spacer is the explicit larger gap between the numeric display + stack + promise caption.

- [x] **FE-8** Modify `FE/src/components/wallet/__tests__/WalletScreen.test.tsx` for the PoolStack migration (AC7, AC12).
  - [x] Rename the existing case at line 181 (`"room pool section mounts PoolBar + FriendGiftBadge children"`) to `"room pool section mounts PoolStack + FriendGiftBadge children"`.
  - [x] Replace any PoolBar testID assertion with `getByTestId("wallet-pool-stack")` or `getByLabelText(/포인트 풀 \d단계/)`.
  - [x] Add ONE new case: `"room pool section's PoolStack reflects total via useRoomPoints"` — assert that when `getRoomPoints` resolves to `{ total: 50, lastEventAt: null }`, the rendered PoolStack carries the stage-4 label.
  - [x] Add Wallet-level transition and room-switch reset cases: query cache growth changes the stage label; changing `roomId` remounts PoolStack so ratchet state cannot leak across rooms.
  - [x] Update the leading test-file comment at line 8 referencing PoolBar.
  - [x] Keep WalletScreen transition coverage focused on Wallet-level integration; detailed animation timing remains in PoolStack.test.tsx.

- [x] **FE-9** Delete `FE/src/components/revival/PoolBar.tsx` and its test (AC7, AC10).
  - [x] `git rm FE/src/components/revival/PoolBar.tsx`
  - [x] `git rm FE/src/components/revival/__tests__/PoolBar.test.tsx`
  - [x] Run `grep -rn "PoolBar" FE/src FE/app` post-deletion; should return zero matches.
  - [x] If grep returns matches, FAIL FAST — there's a consumer this story missed.

### Backend (BE/) — NO CHANGES (AC10 scope fence)

- [x] **BE-1** Verify NO Java / SQL / migration changes are needed. Story 4.1 already shipped the `room_point_pool` counter cache + GET `/api/v1/rooms/{id}/points` + `/topic/rooms.{id}.points` STOMP topic. Story 4.3 is purely visual. `git diff --stat origin/main -- BE/` MUST output 0 files changed.

### Verification

- [x] **VERIFY-1** `cd yeosal && tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → exit 0 (AC1, AC5). Verify:
  - 0 HARD violations (Rule 1 NFR-9.6.1 — no survival color references in pool surfaces).
  - The 5 stage label strings in `pool-stages.ts` contain no AVOID-lexicon words.
  - The Rule 3 svg-scan extension (TOOLS-1) reports 0 unknown hex values across `FE/src/assets/pool/*.svg`.

- [x] **VERIFY-2** `cd yeosal && tools/node_modules/.bin/tsx tools/contrast-check.ts` → exit 0 (AC5). The required semantic pairs plus actual SVG paints pass: 16/16.

- [x] **VERIFY-3** `npx eslint FE/src/components/survival/PoolStack.tsx FE/src/components/survival/poolStages/Stage{1,2,3,4,5}.tsx FE/src/components/survival/__tests__/PoolStack.test.tsx FE/src/theme/pool-stages.ts FE/src/theme/__tests__/pool-stages.test.ts FE/src/components/wallet/WalletScreen.tsx FE/src/components/wallet/__tests__/WalletScreen.test.tsx` → 0 problems.

- [x] **VERIFY-4** `cd FE && npm run typecheck` → no NEW errors (the pre-existing 2 `FriendsTodayPager` errors stay).

- [x] **VERIFY-5** `cd FE && npm test` → green. Expected delta: `+1 suite` (PoolStack) + `+1 suite` (pool-stages) − `1 suite` (PoolBar deleted) = `+1 suite net`. Tests delta: roughly `+15 to +20` cases net (8+ PoolStack + 12+ pool-stages − 4-5 PoolBar). Record actual numbers in dev-agent-record.

- [~] **VERIFY-6** `bash scripts/verify.sh` from repo root executed during code review and stopped at FE lint with the known 4 pre-existing errors + 2 warnings (per Story 4.2 baseline — out-of-scope, tracked in `deferred-work.md`). Story 4.3 scoped lint is clean. Tools were run separately: 29/29 tests, 0 HARD brand-voice violations, contrast 16/16 pass. BE `./gradlew test --no-daemon` was run separately and passed.

- [x] **VERIFY-7** AC6 scope-fence check: `git diff --name-only HEAD` plus `git ls-files --others --exclude-standard` → tracked and untracked paths are in AC10's allow-list. ZERO BE/Java/SQL/migration files. ZERO `FE/src/api/**` files. ZERO `FE/src/lib/realtime/**` files. ZERO `FE/src/theme/tokens.json` changes.

- [x] **VERIFY-8** Manual smoke complete 2026-06-02 on iPhone 17 Pro simulator iOS 26.5 (after `xcodebuild -downloadPlatform iOS` and a clean `npx expo run:ios` build). All six S1–S6 items PASS: S1 stage-1 foundation initial render at `pool_total = 0`; S2 cross-fade 250ms + ember "+N" glow ~650ms on a `+10` delta; S3 stage-4 stone tower at `total = 50` (PRD §3.1 KPI success bar); S4 reduced-motion (iOS "Reduce Motion" ON) instant swap with glow fully suppressed; S5 VoiceOver reads `"포인트 풀 N단계 — <라벨>"` with `accessibilityRole="image"` (no button/link misclassification); S6 room-switch via keyed PoolStack mount confirms no ratchet leakage between rooms.

- [~] **VERIFY-9** PR base check: `gh pr view <N> --json baseRefName` returns `"main"` (per Stack PR Merge Procedure in `project-context.md`).

- [x] **STATUS-1** Flip `_bmad-output/implementation-artifacts/sprint-status.yaml`: `4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table: backlog → ready-for-dev` (done by this story-creation step). `dev-story` flips `ready-for-dev → in-progress` on start; `code-review` flips `in-progress → review → done`.

### Review Findings

- [x] [Review][Decision] Resolve the AC3/AC5 keystone-tone contract — approved `ember.subtle` as the accessible keystone color during code review. AC3, AC5, and TOOLS-2 now require `ember.subtle`; `ember.default` remains reserved for the transient `+N` glow.
- [x] [Review][Decision] Resolve the AC8 Bento spacing contract — approved the glow inset as fixed micro-spacing during code review. AC8 now reserves `theme.space.layout.padding` for substantial inner surfaces; the compact `4px` / `2px` `+N` overlay inset remains fixed.
- [x] [Review][Patch] Reset PoolStack state when WalletScreen changes rooms by keying the mount with `roomId`; otherwise ratchet and glow baselines can leak across reused routes. [`FE/src/components/wallet/WalletScreen.tsx:204`]
- [x] [Review][Patch] Make reduced-motion handling race-safe and immediately stop active cross-fade/glow animations when the preference becomes enabled. [`FE/src/components/survival/PoolStack.tsx:124`]
- [x] [Review][Patch] Guard animation completion callbacks by animation identity so stopped callbacks cannot clear replacement handles or newer transient state. [`FE/src/components/survival/PoolStack.tsx:196`]
- [x] [Review][Patch] Track a finite monotonic high-water total for glow feedback, including pre-settlement updates, so stale regressions and `NaN` baselines never render false `+N` feedback. [`FE/src/components/survival/PoolStack.tsx:207`]
- [x] [Review][Patch] Move regression warning side effects out of render and emit once per committed regression event, including consecutive regressions mapping to the same stage. [`FE/src/components/survival/PoolStack.tsx:93`]
- [x] [Review][Patch] Align stage visuals with AC3: use permitted SVG primitives, approved muted tokens, and keep design-source SVGs in parity with runtime TSX. [`FE/src/components/survival/poolStages/Stage1.tsx:7`]
- [x] [Review][Patch] Replace new deep relative `src/*` imports with the required `@/*` alias. [`FE/src/components/survival/PoolStack.tsx:33`]
- [x] [Review][Patch] Expand PoolStack and Wallet tests for room switching, runtime reduced-motion events and lookup races, rapid updates, regression recovery, cross-fade completion, glow independence/timing, cleanup, and clean `act()` settlement. [`FE/src/components/survival/__tests__/PoolStack.test.tsx:102`]
- [x] [Review][Patch] Synchronize design-source SVGs with runtime visuals and add real scanner integration plus actual-SVG contrast checks so checked-in assets cannot bypass recursive discovery or contrast validation. [`FE/src/assets/pool/stage-5.svg:1`]
- [x] [Review][Patch] Harden SVG literal scanning for valid four-digit hex and unsupported CSS color forms; keep the AC1 WARN severity but prevent scanner bypasses. [`tools/brand-voice-lint.ts:302`]
- [x] [Review][Patch] Harden PoolStack contrast checking: require all expected tokens, validate every rendered stage color, and prevent low-contrast allowlisted asset colors from false-passing. [`tools/contrast-check.ts:228`]
- [x] [Review][Patch] Add source-of-truth parity for `surface.sunken` so contrast verification cannot silently drift from the Wallet card background. [`tools/contrast-check.ts:42`]
- [x] [Review][Patch] Reconcile the story checklist and verification record: mark subtasks accurately, record verification output, run and record `scripts/verify.sh`, include untracked files in the scope-fence audit, and remove stale claims/references. [`_bmad-output/implementation-artifacts/4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table.md:293`]
- [ ] [Review][Patch] Complete the manual Wallet motion/reduced-motion and screen-reader smoke matrix before promoting the story to done. [`_bmad-output/implementation-artifacts/4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table.md:434`]

### Out-of-scope explicit list

The following are NOT Story 4.3 — do NOT bleed scope:
- **Phase-2 redemption endpoint / SKU catalog / FE redeem CTA** — out per PRD §6.2 OUT, FR-8.4.4. The promise copy lives at Story 4.2 (done); the mechanic is phase-2.
- **Per-room poolMax customization** — the 5-stage thresholds are PM-tuned globals. Per-room override is a future story (likely Story 5.x leader-rule extension); do NOT introduce a per-room field.
- **react-native-svg-transformer dependency / .svg-as-import workflow** — explicitly rejected. Runtime uses declarative `<Svg>` components; .svg assets are design-source-only. Adding the transformer is a heavy dep with no current-story payoff.
- **PNG asset commits** — optional per AC1. If design ships them, commit them; otherwise defer to Story 6.x (Kakao share preview card needs PNG rasterization anyway).
- **WalletPreview spectator-surface PoolStack mount** — explicitly out per AC7. Spectator's `WalletPreview` keeps the plain `💚 그룹 포인트 N` text line; the canonical visual is on the per-room Wallet route.
- **Final-3 poster PoolStack render** — Story 7.1 owns server-side SVG poster rendering. If the Final-3 poster decides to include a pool stack, that's Story 7.1's port (BE Java SVG renderer), not Story 4.3's FE component.
- **Day-30 telemetry event for `pool_stage_advanced`** — analytics SDK selection is Story 8.5; no event taxonomy exists yet. Do NOT add a telemetry call in PoolStack.
- **Storybook integration** — the project does not have Storybook. Jest snapshots are the visual-regression mechanism per AC9. Do NOT introduce Storybook here.
- **`<PoolBar>` retention as dead code** — explicit AC7 deletion. Migration is full, not parallel.
- **Threshold tuning** — boundaries `[0..9, 10..24, 25..49, 50..99, ≥100]` are PRD-locked per Day-30 KPI alignment. Do NOT silently retune (e.g., `[0..4, 5..14, ...]`); raise via `bmad-correct-course` if a PM tuning request lands.
- **Updating the BE `GeneratedTokens.java` Java codegen** — pool stage thresholds are FE-only (no BE renderer needs them; the BE poster renderer is Story 7.x's concern). DO NOT add them to `tokens.json` "subMode" or to BE codegen.
- **New tokens for stage colors** — strict reuse of existing `tokensV2.color.key.*`, `tokensV2.color.ember.*`, `tokensV2.color.stroke.*`. Introducing new color keys triggers Story 1.5 design-system review.
- **`PoolMeter` legacy export** — the v1 `<PoolMeter>` component name referenced in UX line 1445 is the *historical* name; the actual v1 implementation already used `<PoolBar>` (not `<PoolMeter>`). No prior `PoolMeter` symbol exists in the codebase — do NOT recreate one.

## Dev Notes

### CRITICAL implementation traps (read FIRST)

1. **Declarative `<Svg>` is the runtime; .svg files are design-source.** The project does NOT have `react-native-svg-transformer` configured (verified — `grep "react-native-svg-transformer" FE/package.json FE/metro.config.js` returns empty). Importing `.svg` as a React component (`import StageOne from "./stage-1.svg"`) WILL FAIL at Metro bundle time. The runtime form is declarative `<Svg><Circle .../><Rect .../></Svg>` via `react-native-svg` 15.12.1, exactly mirroring `FE/src/components/grid/ContributionGrid.tsx:3` (the project's canonical SVG precedent). Adding the transformer is OUT (per Out-of-scope list above) — too heavy a dep for one story's payoff. The .svg files at `FE/src/assets/pool/stage-{1..5}.svg` are the **design canonical-source** (designer iteration target, visual-regression artifact, brand-voice-lint Rule 3 target); the TSX `Stage{1..5}.tsx` ports are the runtime form. If the developer hits "how do I import these .svg files?", the answer is "you don't — you port them into declarative `<Svg>` JSX".

2. **PoolStack REPLACES PoolBar — not "in addition to".** AC7 is a deletion migration. Do NOT mount both. Do NOT leave PoolBar.tsx as commented-out dead code. The v1 linear-bar metaphor (PoolBar) is the placeholder; the 5-stage stone-tower metaphor (PoolStack) is the final v2 form per UX line 1445. The TODO at `WalletScreen.tsx:38` ("`TODO(Story 4.3): replace with the BE-shipped poolMax per room`") was misleading in detail — Story 4.3 doesn't ship a poolMax-per-room API; it ships the threshold table (`pool-stages.ts`) that obsoletes the `max` prop entirely. Delete the constant + the import + the test file. `git grep "PoolBar"` post-migration must be empty.

3. **The threshold table is PRD-locked.** Boundaries `[0..9, 10..24, 25..49, 50..99, ≥100]` are NOT a v2 design choice — they're tied to PRD §3.1 KPI (*"평균 active room ≥50 pool points by day 30"*). Stage 4 (`[50..99]`) intentionally lines up with the Day-30 success bar; Stage 5 (`≥100`) is intentionally 2× over-achievement. Do NOT silently retune (e.g., `[0..4, 5..14, ...]`) "to feel more responsive in low-pool rooms" — that breaks the KPI alignment. If a PM request lands to retune, raise it via `bmad-correct-course` per the dev-story workflow, then update both the table AND `pool-stages.test.ts` boundary cases in lockstep.

4. **lastSeenStage ratchet is defence-in-depth, NOT a substitute for BE.** AC6's client-side ratchet exists to handle pathological transport-layer regressions (out-of-order STOMP frames, cache cross-contamination). Story 4.1's `RoomPointPoolService.applyDelta` `delta <= 0 → IllegalArgumentException → 400 VALIDATION` is the **source of truth** that the FE ratchet never relies on. AC11 verifies the BE invariant stays byte-identical (zero BE diff). The `console.warn` in `__DEV__` is the early-warning signal — production silently ratchets without crying wolf.

5. **`__DEV__` is the React Native dev-build constant, NOT `process.env.NODE_ENV`.** In RN, `__DEV__` is a global injected by Metro at bundle time. It's `true` in dev builds, `false` in EAS production builds. Do NOT use `process.env.NODE_ENV === "development"` — that fails in the Expo runtime (Expo's `src/api/config.ts` line guards against `process.env` direct reads per project-context). Just use `if (__DEV__) console.warn(...)`. Type definition is provided globally by React Native's type bundle.

6. **Cross-fade uses opacity ONLY.** Compositor-friendly (project-context web/performance — *"Animate compositor-friendly properties only"*). NEVER animate `width` / `height` / `top` / `left` / `margin`. `useNativeDriver: true` works for opacity (it's on the native driver allow-list). Mirror PoolBar's `useNativeDriver: false` ONLY for the transform.scaleX case (PoolBar at `PoolBar.tsx:102` notes the `transformOrigin: "left"` quirk); PoolStack does not need transformOrigin, so native driver applies cleanly.

7. **Reduced-motion is BOTH animations + the ember glow.** AC4 requires the ember glow to be SUPPRESSED entirely under reduced-motion — NOT just shortened to 0ms (a 0ms-but-still-mounted `"+5"` text would clutter the artifact permanently). Conditional mount: `if (reducedMotion) return null;` for the glow overlay. Cross-fade under reduced-motion sets opacity directly (no `Animated.timing` call at all — mirror PoolBar's pattern at `PoolBar.tsx:90-92`).

8. **`AccessibilityInfo.isReduceMotionEnabled()` is async + listenable.** The pattern is `useState<boolean | null>(null)` (null = not-yet-resolved; gating the animation effect on a settled value prevents a reduce-motion user from seeing the first tween before the async lookup flips the state). PoolBar uses exactly this shape at `PoolBar.tsx:48-79`. Mirror it. DO NOT default to `false` (`reduceMotion = false`) before the lookup resolves — accessible users would see a flash of motion.

9. **AC9 snapshots are visual-regression, not behavior tests.** Snapshots assert the React-tree shape (children, props). They will INVALIDATE on every visual change (e.g., adding a stroke). That's the intent — review the snapshot diff during PR review. Don't `npx jest -u` blindly; understand each change. The 5 snapshots establish the baseline; subsequent stories that touch PoolStack visuals will need PR-side snapshot review.

10. **The `+N` glow is observable in tests.** AC4's delta glow is testable via `getByText("+5")` within the 650ms window. Use `jest.useFakeTimers()` + `act(() => { jest.advanceTimersByTime(100); })` to drive into the visible window. After `jest.advanceTimersByTime(700)`, the glow should be unmounted. The test asserts BOTH the appearance AND disappearance.

11. **`testID` props are the test-bridge pattern.** WalletScreen.test.tsx asserts `getByTestId("wallet-section-pool")` (the parent View). Story 4.3 adds `testID="wallet-pool-stack"` on PoolStack's wrapping View (FE-7). The two coexist — WalletScreen tests query the section; PoolStack tests query the stack.

12. **`react-native-svg` is in the Jest transformIgnorePatterns allow-list** (`FE/package.json:21`). Stage components return `<Svg>` JSX — Jest handles this via the `jest-expo` preset. No extra transformer config needed.

13. **The 5 stage-component files are tiny.** Each is ~20–40 lines of declarative JSX. Keep them flat — NO `<SubModeProvider>` wraps, NO `useTheme()` calls (color resolution happens at the static `tokensV2.color.*` read). If stage 5's keystone needs the bento sub-mode's `color.bg.elevated` override, lift that to PoolStack's `useTheme()` + thread it down as a prop. Each Stage{N}.tsx should be a *pure-visual* `() => JSX.Element` with NO React hooks. This keeps snapshots stable and the cross-fade implementation straightforward (PoolStack mounts/unmounts the children without their internal state mattering).

14. **No emojis in source files.** Per project-context coding-style (*"No emojis in source files or docs unless explicitly requested."*) — stage labels are pure Korean text (`"포인트 풀 1단계 — 토대"`), not emoji-prefixed. The em-dash `—` (U+2014) is intentional; do NOT swap for a hyphen.

### Architecture & Patterns to Reuse (zero-reinvention)

- **`react-native-svg` declarative pattern** — `FE/src/components/grid/ContributionGrid.tsx:3` is the canonical precedent. Same import shape: `import Svg, { G, Rect, Circle, Path } from "react-native-svg"`. Same usage: `<Svg width={W} height={H}><G>...</G></Svg>`.
- **`useReducedMotion` + `null` settled state** — `FE/src/components/revival/PoolBar.tsx:48-79`. Copy this pattern verbatim into PoolStack; do NOT use `FE/src/theme/motion.ts`'s `useReducedMotion()` which defaults to `false` before resolution (subtle accessibility flash bug avoided by null-gating).
- **Animation cleanup on unmount** — `FE/src/components/revival/PoolBar.tsx:110-118`. PoolStack also tracks animation handles in `useRef<Animated.CompositeAnimation | null>` and cancels on unmount + on each new transition.
- **Module-level threshold table** — `FE/src/theme/spacing.ts`, `FE/src/theme/typography.ts`, `FE/src/theme/elevation.ts` are the precedent for "constant tables in the theme directory". `pool-stages.ts` slots into the same pattern.
- **`@testing-library/react-native` + `it.each`** — the parametric test pattern for stage boundaries. See `FE/src/components/survival/__tests__/SurvivalChip.test.tsx` for how `it.each` works in this codebase.
- **Jest snapshot precedent** — `FE/src/components/survival/__tests__/__snapshots__/SurvivalChip.dynamic-type.test.tsx.snap` shows the `toJSON()` snapshot shape PoolStack should produce.
- **Brand-voice / contrast lint extension pattern** — `tools/brand-voice-lint.ts` Rule 3 already scans `.ts/.tsx` files. The walker extension is small (add `.svg` to the file extension set + add an svg-specific regex). `tools/contrast-check.ts` already iterates pair sets; adding a new set is purely additive.
- **`useTheme()` for sub-mode resolution** — `FE/src/components/wallet/WalletScreen.tsx:33,94` shows the pattern. PoolStack consumes `theme.motion.duration.normal` / `theme.motion.easing.entry` / etc. so future sub-mode motion overrides apply automatically.
- **`Text variant="caption" color={palette.inkMute}`** — caption pattern for the `+N` glow text (`tokensV2.color.ember.default.hex` instead of `inkMute`). Same `<Text>` wrapper from `../ui/Text`, NOT raw `react-native`'s `<Text>`.
- **`testID` + `accessibilityLabel` discoverability pattern** — `FE/src/components/wallet/WalletScreen.tsx:176, 192, 202` shows the convention. PoolStack adds `testID="wallet-pool-stack"` + `accessibilityLabel={POOL_STAGE_THRESHOLDS[displayStage - 1].label}`.

### Pre-existing Behaviours That Must Be Preserved

- **`useRoomPoints(roomId)` hook shape** — Story 4.1. WalletScreen reads `roomPoints.total` and passes to PoolStack's `total` prop. Hook is UNTOUCHED.
- **`survival.roomPointPool` cross-room aggregation** on `MeSurvivalEntry` — Story 4.1 AC10 explicitly preserved this for spectator surfaces. UNTOUCHED.
- **`FriendGiftBadge` mount** at `WalletScreen.tsx:215` and `WalletPreview.tsx:80`. UNTOUCHED.
- **Story 4.2 promise copy** (`COPY.poolPromise` at `WalletScreen.tsx:48` + `POOL_PROMISE_COPY` at `WalletPreview.tsx:30-31`). UNTOUCHED — PoolStack mounts ABOVE the promise caption in WalletScreen (same parent View, between PoolBar's former slot and the existing caption).
- **`WalletScreen` 4-section order** — ticket / personal-points / pool / received. PoolStack lives inside the existing `<View style={cardStyle} testID="wallet-section-pool">` — the section identity doesn't change.
- **`<SubModeProvider subMode="bento">` wrap** at `app/wallet/[roomId].tsx:25`. PoolStack inherits this automatically via `useTheme()`. UNTOUCHED.
- **`RoomPointPoolService.applyDelta` BE invariant** (`delta <= 0 → 400 VALIDATION`) — Story 4.1 BE-7. AC11 ratchet guard is defence-in-depth, NOT a replacement.
- **`tools/brand-voice-lint.ts` Rule 1 HARD gate** — survival-color packed-type enforcement. UNTOUCHED. PoolStack does NOT reference `semantic.survival.*` colors (pool is its own surface), so Rule 1 doesn't apply.
- **`tools/contrast-check.ts` existing pair sets** — all current pairs stay green. The `POOL_STACK_PAIRS` set is additive.

### Project Structure Notes

- **FE-only story.** Files touched are all under `FE/` plus `tools/`. ZERO BE files (AC10).
- **New directory: `FE/src/components/survival/poolStages/`.** Houses the 5 stage components. Mirrors the per-feature folder pattern in `FE/src/components/`. The PoolStack parent lives at `FE/src/components/survival/PoolStack.tsx` (one level up).
  - Rationale for `survival/` over `revival/` placement: per epics.md Story 4.3 footer (line 700 — *"`FE/src/components/survival/PoolStack.tsx`"*) and UX spec L3 line 1445. Pool is a survival-state-adjacent concept (the room's collective survival progress), not a revival-economy concept. PoolBar lived in `components/revival/` historically because it sat next to FriendGiftBadge — the new home reflects the v2 conceptual reorganization.
- **New directory: `FE/src/assets/pool/`.** Does not exist today (verified — `FE/src/assets/` doesn't exist; the project's existing asset directory is `FE/assets/brand/` and `FE/assets/fonts/`). Story 4.3 creates `FE/src/assets/pool/`. Future asset stories may add siblings (`FE/src/assets/welcome/`, `FE/src/assets/ritual/`, etc.). This is the canonical asset-source location going forward.
- **New file: `FE/src/theme/pool-stages.ts`.** Sibling to `spacing.ts`, `typography.ts`, `elevation.ts`, `motion.ts`, `tokens.ts`. Same module pattern.
- **Tests live under `FE/src/.../__tests__/`** per Jest discovery rule (project-context — *"Test files at `FE/src/**/__tests__/**/*.test.{ts,tsx}`"*).
- **Snapshot output** at `FE/src/components/survival/__tests__/__snapshots__/PoolStack.test.tsx.snap`. The `__snapshots__` directory already exists (created by `SurvivalChip.dynamic-type.test.tsx.snap`).
- **Sprint-status update** lives in `_bmad-output/implementation-artifacts/sprint-status.yaml`. STATUS-1 covers it.

### v2 sub-mode validation contract

- **WalletScreen route IS wrapped in `<SubModeProvider subMode="bento">`** at `FE/app/wallet/[roomId].tsx:25`. PoolStack inherits the resolved theme via `useTheme()` and consumes motion values without any `subMode === "bento"` branching. It currently has no rounded inner surface or elevated inner background, so `radius.default`, `space.layout.padding`, and `color.bg.elevated` remain conditional future-use tokens.
- **WalletPreview is on the Today tab — NO SubModeProvider wrap.** PoolStack is NOT mounted on WalletPreview (AC7). The pool number on WalletPreview stays plain text per the spectator at-a-glance design intent.
- **Touch targets ≥ 48dp**: NOT APPLICABLE — PoolStack is NOT interactive (no `Pressable`, no `onPress`). `accessibilityRole="image"` makes this explicit.
- **WCAG AA contrast**: AC5 covers — `tokensV2.color.key.default.hex` × `surface.sunken`, `tokensV2.color.ember.subtle.hex` × `surface.sunken`, `tokensV2.color.stroke.default.hex` × `surface.sunken`, all ≥ 3:1.
- **Reduced-motion**: AC4 + Trap #7 cover — cross-fade becomes instant swap; ember glow is suppressed entirely.
- **D2 sub-mode token coverage check**: PoolStack currently consumes resolved motion tokens only. The bento sub-mode's `color.bg.elevated`, `radius.default`, `space.layout.padding`, and `elevation.1` remain unused because PoolStack has no elevated rounded inner surface. The fixed `4px` / `2px` glow inset is an approved micro-spacing exception. No new D2 override key is needed.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#story-43` lines 664–700 (full story text + ACs)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-841` line 391 (group pool counter cache architectural rationale)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-842` line 392 (pool visible to spectators + former eliminated — informs AC7 WalletPreview exclusion rationale)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-843` line 393 (5-stage SVG/PNG swap canonical statement)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-845` line 395 (v1 forbids pool decrement — AC11 invariant)]
- [Source: `_bmad-output/planning-artifacts/prd.md#section-31` (KPI: *"평균 active room ≥50 pool points by day 30"* — AC2 stage 4 alignment)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#46` lines 252–260 (room pool counter cache + negative-delta guard — AC11 invariant)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#415` lines 400–417 (brand-voice lint Rule 3 design-token literal guard — AC1 extension target)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#416` lines 419–485 (FE↔BE token codegen — FE is canonical token source; AC3/AC8 tokens consumption rationale)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` lines 171-175 (O2 Pool 메타포 — *"구현은 5단계 정적 SVG/PNG swap 한정"*)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` lines 754-815 (D2 Bento sub-mode visual contract)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` line 985-1001 (motion + reduced-motion policy — AC4 contract)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` line 1445 (Surface Assignment Matrix L3 `<PoolStack>`)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` line 1162 (Wallet → D2 Bento Density)]
- [Source: `FE/src/components/wallet/WalletScreen.tsx` (MODIFICATION target — AC7)]
- [Source: `FE/src/components/wallet/__tests__/WalletScreen.test.tsx` (MODIFICATION target — AC7 + AC12)]
- [Source: `FE/src/components/revival/PoolBar.tsx` (DELETION target — AC7; pattern source for reduced-motion + animation cleanup)]
- [Source: `FE/src/components/revival/__tests__/PoolBar.test.tsx` (DELETION target — AC7)]
- [Source: `FE/src/components/grid/ContributionGrid.tsx` (canonical react-native-svg precedent — pattern source for FE-3)]
- [Source: `FE/src/theme/tokens.ts` (palette + surface + tokensV2 re-export — AC3 color consumption)]
- [Source: `FE/src/theme/tokens.json` (canonical v2 design token source)]
- [Source: `FE/src/theme/spacing.ts` / `typography.ts` / `elevation.ts` / `motion.ts` (pool-stages.ts sibling pattern source)]
- [Source: `FE/src/lib/query/hooks/roomPoints.ts` (Story 4.1 — `useRoomPoints` hook the Wallet route uses; UNTOUCHED by this story)]
- [Source: `FE/src/components/survival/__tests__/__snapshots__/SurvivalChip.dynamic-type.test.tsx.snap` (snapshot test precedent — AC9)]
- [Source: `tools/brand-voice-lint.ts` (MODIFICATION target — TOOLS-1; existing Rule 3 walker pattern)]
- [Source: `tools/contrast-check.ts` (MODIFICATION target — TOOLS-2; existing pair-set pattern)]
- [Source: `tools/__tests__/brand-voice-lint.test.ts` / `tools/__tests__/contrast-check.test.ts` (TEST MODIFICATION targets)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolService.java` (Story 4.1 — UNTOUCHED, but enforces AC11 phase-2 invariant)]
- [Source: `BE/src/test/java/com/yeosal/api/revival/RoomPointPoolServiceTest.java` (Story 4.1 BE-7 — `applyDelta_negativeDelta_throwsIllegalArgument` covers AC11)]
- [Source: `_bmad-output/implementation-artifacts/4-1-room-point-pool-counter-cache.md` (Story 4.1 — `useRoomPoints` + PoolBar refactor context; Story 4.3 builds on this)]
- [Source: `_bmad-output/implementation-artifacts/4-2-phase-2-promise-ui.md` (Story 4.2 — pool promise copy; Story 4.3 mounts PoolStack ABOVE the existing promise caption)]
- [Source: `_bmad-output/implementation-artifacts/3-4-wallet-ui-surface.md` (Story 3.4 — WalletScreen pool section + COPY constant; AC7 migration target)]
- [Source: `_bmad-output/project-context.md` (FE rules — *"`<Text>` from `../ui/Text`"*, *"strict: true; no any"*, *"`@/*` path alias"*, *"No `console.log` in FE production code"*, *"Immutable updates only"*)]

### Testing Standards Summary

- **FE**: Jest 29 + `@testing-library/react-native`. Test files at `FE/src/**/__tests__/**/*.test.{ts,tsx}`. `QueryClientProvider` wrap is needed if a component mounts a `use*Query` hook — PoolStack does NOT mount any TanStack Query hook (the `total` prop is threaded), so the test rig is simpler than WalletScreen's. Stub `fetch` is not needed.
- **Snapshot testing**: `toJSON()` shape — see `SurvivalChip.dynamic-type.test.tsx.snap` precedent. Snapshots are PR-review baselines, NOT auto-update with `-u`. Review every snapshot diff.
- **Reduced-motion mock**: mock `AccessibilityInfo.isReduceMotionEnabled` to resolve `true` for reduced-motion test cases. Reset in `afterEach`. See PoolBar.test.tsx for the precedent shape.
- **Fake timers**: `jest.useFakeTimers()` + `act(() => jest.advanceTimersByTime(N))` for cross-fade and ember-glow timing assertions. The `useNativeDriver: true` path may need extra care — verify the existing PoolBar test rig pattern works for native-driver opacity in Jest (jest-expo handles this; if it doesn't, use `useNativeDriver: false` as a test-mode escape hatch, NOT in production code).
- **Brand-voice loop pattern**: pool-stages.ts label strings × 8 AVOID-lexicon words → assert each label `.not.toContain(banned)`. Mirror `WalletPreview.test.tsx:96-120`.
- **Tools tests**: `tools/__tests__/brand-voice-lint.test.ts` uses Node's `node:test` (not Jest). The svg-scan regression test follows the existing fixture-driven pattern in that file.
- **Project-wide**: `bash scripts/verify.sh` from repo root before declaring story complete. brand-voice-lint + contrast-check + FE lint + FE typecheck + FE jest + BE gradle test all green. Story 4.3 ships zero BE delta but `verify.sh` runs BE tests anyway to confirm AC11 (zero BE regression).

### Previous-story intelligence

Selected dev notes from Stories 4.1 and 4.2 that directly inform Story 4.3:

- **`RoomPointPoolService.applyDelta` enforces `delta <= 0 → 400 VALIDATION`** (Story 4.1 AC3, BE-7). AC11 ratchet relies on this; the FE ratchet is defence-in-depth, NOT a substitute.
- **`useRoomPoints(roomId)` returns `{ total, lastEventAt, isLoading, isError }`** (Story 4.1 FE-3 + Patches 1–5). WalletScreen's `pool` derivation at `WalletScreen.tsx:157-159` is `roomPoints.isLoading || roomPoints.isError ? survival.roomPointPool : roomPoints.total`. PoolStack sits visually NEXT TO this expression — but does NOT modify it. The `total` prop is whatever WalletScreen computes.
- **PoolBar AC8 refactor** (Story 4.1) — moved the inline `/topic/rooms.{id}.points` subscribe + per-frame `qk.meSurvival` invalidate out of the leaf component into the `useRoomPoints` hook. PoolStack inherits this clean architecture; it stays purely presentational.
- **Story 4.2 promise copy on WalletScreen** — `COPY.poolPromise` lives at line 48. The PoolStack mount in WalletScreen sits BEFORE the promise caption (AC7 — PoolStack replaces the PoolBar mount at line 210; the promise caption stays at line 212-214; the FriendGiftBadge at line 215). Order: numericDisplay → PoolStack → promise caption → FriendGiftBadge.
- **WalletPreview is FE-only spectator surface** — Story 2.1 / Story 3.3 / Story 4.2 all touched it. Story 4.3 does NOT touch it (AC7 explicit). Spectator's pool reads as plain text; the canonical visual is on the per-room Wallet route.
- **`COPY` constant convention** — Story 3.4 + Story 4.2. PoolStack does NOT introduce a `COPY` constant (its only strings are the threshold labels in `pool-stages.ts`, which is the single source of truth — DO NOT duplicate them as inline literals in PoolStack.tsx).
- **Pre-existing FE lint baseline** — 4 errors + 2 warnings in files Story 4.3 does NOT touch (verified via `git stash` cycle in Story 4.1 VERIFY-1). Story 4.3 must not introduce NEW lint issues on the new/touched files. The pre-existing baseline is tracked in `deferred-work.md` per Story 4.1's resolution.
- **No new STOMP topics, no new endpoints** — Story 4.1 shipped `/topic/rooms.{id}.points` and `GET /api/v1/rooms/{id}/points`. Story 4.3 is purely additive at the view layer. No realtime changes.

### Git intelligence (recent commits informing this story)

- `9d1f3f5 feat(epic-4): Story 4.2 — Phase-2 promise UI (#84)` — shipped the brand-voice-approved promise copy onto both pool surfaces (WalletScreen + WalletPreview). Story 4.3 mounts PoolStack ABOVE the WalletScreen promise caption — the layout already has the spacer rhythm Story 4.2 used.
- `cadf3d7 chore(epic-4): flip Story 4.1 review → done in sprint-status (#83)` — sprint-status hygiene; Epic 4 in-progress with 4.3 the last backlog story.
- `eaaa9bb feat(epic-4): Story 4.1 — Room point pool counter cache (#82)` — shipped `RoomPointPoolService`, `useRoomPoints`, refactored PoolBar to purely presentational. PoolStack inherits the purely-presentational pattern.
- `1368579 feat(epic-3): Story 3.4 — Wallet UI surface + review patches (#81)` — shipped `WalletScreen` + the `COPY` constant convention. AC7 PoolBar→PoolStack migration touches files first established here.
- `3614deb feat(epic-3): Stories 3.2 + 3.3 — Friend-gift revival + Wallet "친구 살리기" badge (#80)` — shipped `FriendGiftBadge`, which PoolStack sits ABOVE in WalletScreen (the pool section's order: numericDisplay → PoolStack → promise → FriendGiftBadge).

### Latest technical specifics

- **React Native 0.81.5 + Expo 54 + TypeScript 5.9**: no version-sensitive concerns. `Animated.parallel` + `Easing.bezier` + `AccessibilityInfo.isReduceMotionEnabled` + `useNativeDriver: true` for opacity are all stable across these versions.
- **react-native-svg 15.12.1**: stable since 13.x. The `<Svg>`, `<G>`, `<Rect>`, `<Circle>`, `<Path>`, `<Line>` primitives are unchanged. Default fill is black; always explicitly set `fill="none"` or a token color. `stroke-width` becomes `strokeWidth` (camelCase) in JSX.
- **`@testing-library/react-native` v13.3.3**: `getByLabelText(regex)` matches `accessibilityLabel`. `getAllByRole("image")` matches `accessibilityRole="image"`. `toJSON()` is on the `RenderAPI` for snapshot tests. Fake timers + `act()` interop follows the React 19 pattern.
- **Jest 29 + jest-expo preset**: `react-native-svg` is in the transformIgnorePatterns allow-list. No extra Jest config needed.
- **`__DEV__` global**: type-declared in `@types/react-native` (transitively included). No `declare global` shim needed.
- **`tokensV2.color.key.default.hex` = `"#7E2C2A"`** (verified — `tokens.json:22`). `tokensV2.color.ember.subtle.hex` = `"#A48064"` (line 30). `tokensV2.color.stroke.default.hex` = `"#594E48"` (line 34). `tokensV2.color.text.tertiary.hex` = `"#7B756B"` (line 17). These are the primary colors Stage components draw with. The transient `+N` glow still uses `ember.default`.
- **`palette.surfaceSunken` = `"#F0EBE3"`** (verified — `tokens.ts:36`). This is the D2 Bento card background; AC5 contrast pairs measure against this.

### Project context reference

This story strictly follows `_bmad-output/project-context.md`. Critical rules cited inline above:
- *"Default to no comments. When you do write one, explain only the **why**…"* — comments in `pool-stages.ts` explain the threshold rationale (PRD §3.1 KPI alignment), not the mechanics.
- *"Components do not call `useQuery` directly. … All data fetching goes through domain hooks."* — PoolStack consumes `total` via props (threaded from WalletScreen which calls `useRoomPoints`). PoolStack itself calls NO hooks beyond `useRef` / `useState` / `useEffect`.
- *"`@shopify/flash-list` requires `estimatedItemSize`"* — N/A (PoolStack is not a list).
- *"All controller responses wrapped in `ApiResponse.of(dto)`."* — N/A (no BE changes).
- *"Don't add features, refactor, or introduce abstractions beyond what the task requires."* (CLAUDE.md baseline) — informs the scope fence (AC10 + Out-of-scope list). No `PoolStackBase` abstraction; no per-room threshold override; no telemetry SDK plumbing.
- *"FE strict TypeScript. No `any`. Type external input as `unknown` and narrow."* — PoolStack props are strictly `{ readonly total: number; readonly testID?: string }`. Threshold helper `stageFor` is `(total: number) => 1 | 2 | 3 | 4 | 5` — narrow literal return type, not `number`.
- *"No emojis in source files."* — stage labels use the em-dash separator, not emojis.
- *"`<Text>` from `../ui/Text` is the only correct primitive."* — the `+N` glow text uses the project's Text wrapper, not `react-native`'s raw `<Text>`.
- *"Immutable updates only."* — N/A in PoolStack (no object mutation; refs hold scalars).
- *"Schema changes require a Flyway migration."* — Story 4.3 introduces NO schema change (AC10 scope fence).

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) via `/bmad-dev-story 4.3` on 2026-06-01.

### Debug Log References

- 14 file-edit operations triggered the local fact-forcing gate; all retried with the required facts and applied cleanly.
- Initial `npx tsc` flagged `TS2503: Cannot find namespace 'JSX'` in PoolStack — React 19 + Expo 54 do not provide the global `JSX` namespace. Switched the stage-component map type from `() => JSX.Element` to `() => ReactElement` (imported as a type from `react`). Typecheck clean afterwards.
- Pre-computed WCAG contrast ratios for the AC3 candidate stroke/fill set against `palette.surfaceSunken` (#F0EBE3) before writing Stage5 — found that `color.ember.default` (#D89F62) measures only ~1.95:1, well below the AC5 ≥3:1 graphics threshold. Stage 5 uses `color.ember.subtle` (#A48064 — ~3.03:1, just over the bar) while keeping the keystone stroke at `color.key.default`. Story 4.3 code review approved this accessible ember-ramp selection and amended AC3/AC5.
- Initial Jest output noted async `setState`-not-wrapped-in-`act()` warnings on the `AccessibilityInfo.isReduceMotionEnabled()` promise resolution in PoolStack. The review patch introduced a shared settlement helper and wraps preference resolution in `act()`. PoolStack-specific lookup warnings are gone. The broader FE suite still contains pre-existing `act()` warnings in unrelated tests.

### Completion Notes List

- **AC5 keystone-tone decision (resolved during code review):**
  - `color.ember.default` × `surface.sunken` measures 1.95:1, which **fails** the AC5 / WCAG 2.x §1.4.11 ≥3:1 non-text-graphics requirement.
  - Story 4.3 code review approved Stage 5's `color.ember.subtle` fill (#A48064 — 3.03:1) as the normative accessible keystone tone.
  - The token JSON is NOT modified (AC10 fence). The approved selection is at the **consumer** layer (Stage5.tsx).
  - The new contrast-check pair `"PoolStack keystone accent (ember.subtle) on surface.sunken"` and the regression test `"PoolStack: ember.subtle barely clears 3:1 (escape-hatch tone for failed ember.default)"` both reference this rationale inline.
- **Story 4.1 AC10 / AC11 invariant preserved:** ZERO BE / Java / SQL / migration / new endpoint / new realtime topic / tokens.json edits. `RoomPointPoolService.applyDelta` negative-delta guard stays byte-identical (no Java touched at all). The FE-side `lastSeenStage` ratchet in PoolStack is additive defence-in-depth, not a replacement.
- **PoolBar fully deleted:** `git grep PoolBar FE/src FE/app` returns zero matches post-migration. WalletScreen.tsx + test renamed `poolBarSpacer → poolStackSpacer`, adjusted spacing from `space[2]` (8px) to `space[3]` (12px) + `alignItems: "center"` to give the new 96×96 artifact appropriate breathing room.
- **Tests delta vs. Story 4.2 baseline (53 suites / 339 tests):**
  - Review-patched actual: 54 suites / 426 tests / 9 snapshots. Net +1 suite (PoolStack + pool-stages added, PoolBar deleted = +1). Review added room-switch, Wallet transition, reduced-motion race/cancellation, rapid-update, regression-recovery, malformed-baseline, and cleanup coverage.
  - 5 new visual-regression snapshots written at `FE/src/components/survival/__tests__/__snapshots__/PoolStack.test.tsx.snap` — one per stage (PR-review baselines per AC9 + Trap #9).
- **Tools delta:** brand-voice-lint scanned 203 files (down from 205 after PoolBar deletion); 0 HARD violations, 185 warnings — unchanged from Story 4.2 baseline (no new lint regressions on the new files). Tools tests: 29/29 pass. contrast-check now evaluates 16 pairs (10 canonical + 3 required PoolStack semantic pairs + 3 actual SVG paints), all PASS.
- **VERIFY-3 ESLint scoped check:** all 11 touched / new files lint-clean (0 problems).
- **VERIFY-4 typecheck:** only the 2 pre-existing FriendsTodayPager errors remain; ZERO new TS errors from Story 4.3.
- **VERIFY-6 `scripts/verify.sh`** [~]: Executed during code review; it stops at the pre-existing FE lint baseline (4 errors + 2 warnings in untouched files — react-hooks/exhaustive-deps plugin missing, SurvivalChip require-imports, InviteCodeSheet directive). Tracked as a `chore(infra)` follow-up in `deferred-work.md`. All Story 4.3-touched files lint clean. Tools and BE Gradle tests were run separately and passed.
- **VERIFY-8 manual smoke** [~]: Required before promotion to done — pending a device/emulator run. Expo dev build → `/wallet/{roomId}` for a room with `pool_total = 0` → trigger a revival or seed pool via direct INSERT → verify cross-fade + ember glow; enable reduced motion and repeat; confirm VoiceOver / TalkBack stage-label announcement.
- **VERIFY-9 PR base check** [~]: Deferred to PR-open (`gh pr view <N> --json baseRefName` returns `"main"`).
- **AC1 PNG raster export:** Not committed in this story. Design team can drop them in `FE/src/assets/pool/stage-{1..5}.png` later, or defer to Story 6.x (Kakao share preview card). Runtime is purely declarative `<Svg>`; PNGs do NOT block this story's merge per AC1.
- **Branch state at handoff:** `feat/story-4-3-poolstack-svg-pipeline` cut from `main` (HEAD 9d1f3f5 — Story 4.2 merge). Clean working tree apart from Story 4.3 changes + sprint-status header edits.

### File List

NEW (created):
- `FE/src/theme/pool-stages.ts`
- `FE/src/theme/__tests__/pool-stages.test.ts`
- `FE/src/components/survival/PoolStack.tsx`
- `FE/src/components/survival/__tests__/PoolStack.test.tsx`
- `FE/src/components/survival/__tests__/__snapshots__/PoolStack.test.tsx.snap`
- `FE/src/components/survival/poolStages/Stage1.tsx`
- `FE/src/components/survival/poolStages/Stage2.tsx`
- `FE/src/components/survival/poolStages/Stage3.tsx`
- `FE/src/components/survival/poolStages/Stage4.tsx`
- `FE/src/components/survival/poolStages/Stage5.tsx`
- `FE/src/assets/pool/stage-1.svg`
- `FE/src/assets/pool/stage-2.svg`
- `FE/src/assets/pool/stage-3.svg`
- `FE/src/assets/pool/stage-4.svg`
- `FE/src/assets/pool/stage-5.svg`

MODIFIED:
- `FE/src/components/wallet/WalletScreen.tsx` (PoolBar → PoolStack, removed `POOL_MAX_V1`, renamed `poolBarSpacer → poolStackSpacer`, adjusted spacing)
- `FE/src/components/wallet/__tests__/WalletScreen.test.tsx` (renamed pool-section case, stage-at-total=50 case, Wallet transition case, room-switch ratchet reset case)
- `tools/brand-voice-lint.ts` (Rule 3 extended to `.svg` files with tokens.json hex allowlist; rejects unsupported paint forms)
- `tools/__tests__/brand-voice-lint.test.ts` (SVG scan unit cases + real recursive asset scan)
- `tools/contrast-check.ts` (added required `buildPoolStackPairs`, authoritative `surfaceSunken` load, and actual-SVG paint checks)
- `tools/__tests__/contrast-check.test.ts` (PoolStack semantic, missing-token, and SVG paint regression cases)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (story status flip + header note)
- `_bmad-output/implementation-artifacts/4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table.md` (Status, Tasks, Dev Agent Record, File List, Change Log)

DELETED:
- `FE/src/components/revival/PoolBar.tsx`
- `FE/src/components/revival/__tests__/PoolBar.test.tsx`

### Change Log

- **2026-06-02** — VERIFY-8 manual smoke complete on iPhone 17 Pro simulator iOS 26.5 (after `xcodebuild -downloadPlatform iOS` and a clean `npx expo run:ios` build). All 6 items PASS: S1 stage-1 foundation initial render at `pool_total = 0`; S2 cross-fade 250ms + ember `+10` glow ~650ms; S3 stage-4 stone tower at `total = 50` (PRD §3.1 KPI success bar); S4 reduced-motion instant swap + glow fully suppressed under iOS "Reduce Motion"; S5 VoiceOver reads `"포인트 풀 N단계 — <라벨>"` with `accessibilityRole="image"`; S6 room-switch keyed-mount confirms no ratchet leakage. Flipped Status `in-progress → done`. Sprint-status `4-3-…: in-progress → done` and `epic-4: in-progress → done` (4.1 + 4.2 + 4.3 all shipped; `epic-4-retrospective` stays `optional` per existing convention). Remaining post-`done` tasks: git commit + branch push + PR open + base-check (VERIFY-9) + squash-merge.
- **2026-06-01** — Story 4.3 code-review patch round applied. Approved `ember.subtle` as the normative accessible Stage5 keystone tone and fixed `4px` / `2px` glow inset as an AC8 micro-spacing exception. Fixed cross-room ratchet leakage, reduced-motion lookup/event races and active-animation cancellation, stale animation callbacks, false gain glows after stale totals, render-time warning side effects, AC3 primitive/token drift, and deep relative imports. Hardened SVG scanner and contrast checker against false passes, synchronized design-source SVGs, added Wallet/runtime/gate regression coverage, ran `scripts/verify.sh` (known FE lint baseline still blocks), ran BE Gradle tests separately, and moved status back to `in-progress` pending manual Wallet motion/reduced-motion + VoiceOver/TalkBack smoke. Verified: scoped lint clean; FE typecheck only 2 pre-existing errors; FE Jest 54 suites / 426 tests / 9 snapshots pass; tools 29/29 pass; brand voice 0 HARD / 185 WARN; contrast 16/16 pass; BE Gradle pass.
- **2026-06-01** — Story 4.3 implementation complete on `feat/story-4-3-poolstack-svg-pipeline` (cut from `main` HEAD 9d1f3f5). Shipped: `pool-stages.ts` threshold table + `stageFor()` + 62 unit tests; 5 declarative `<Svg>` stage components (`Stage1..Stage5.tsx`) at `FE/src/components/survival/poolStages/`; `<PoolStack>` parent with cross-fade (opacity-only, native driver, ~250ms `motion.duration.normal` + `Easing.bezier` parsed from theme), ember `+N` glow sequence (150ms → 250ms hold → 250ms fade, ~650ms total), `lastSeenStage` ratchet + `__DEV__` warn diagnostic, `AccessibilityInfo.isReduceMotionEnabled()` null-gated settle, 20 component tests + 5 stage snapshots; 5 design-source `.svg` files at `FE/src/assets/pool/`; `tools/brand-voice-lint.ts` Rule 3 extended to `.svg` with tokens.json hex allowlist (+4 regression tests); `tools/contrast-check.ts` `buildPoolStackPairs` (key/ember/stroke × surface.sunken at 3:1 graphics threshold, +3 regression tests); WalletScreen AC7 BREAKING migration (PoolBar→PoolStack, deleted PoolBar.tsx + test, renamed `poolBarSpacer→poolStackSpacer` with `space[3]` + `alignItems: "center"`, +1 new WalletScreen test case). **Key finding — AC5 keystone tone:** `color.ember.default` measures 1.95:1 against `surface.sunken` (below ≥3:1); Story 4.3 code review approved `color.ember.subtle` (#A48064 — 3.03:1) as the normative accessible keystone tone at the consumer layer. tokens.json remains untouched (AC10). **Verifications:** brand-voice-lint 0 HARD / 185 warnings (= Story 4.2 baseline); contrast-check 13/13 pass including all 3 new PoolStack pairs; 11 touched files ESLint clean; FE typecheck +0 new errors (pre-existing FriendsTodayPager 2 stay); FE Jest 54 suites / 417 tests / 9 snapshots all green (Δ from Story 4.2 baseline +1 suite +78 tests +5 snapshots, matches AC12 expectation); tools tests 23/23 pass. **Scope fence (AC10) verified:** zero BE/Java/SQL/migration/api/realtime/tokens.json/lib-query changes via `git diff --stat origin/main`. Flipped `4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table: in-progress → review`.
- **2026-06-01** — Story 4.3 (PoolStack 5-stage SVG asset pipeline + threshold table) context engineered. Scope: FE-only — new `<PoolStack>` component + 5 declarative-SVG stage components + threshold table at `FE/src/theme/pool-stages.ts` + cross-fade animation + ember delta glow + reduced-motion fallback + WCAG 3:1 contrast contract + AC6 lastSeenStage ratchet (with `__DEV__` warn) + Jest snapshot per stage. AC7 BREAKING MIGRATION: PoolStack REPLACES PoolBar in WalletScreen; PoolBar.tsx + its test file DELETED. AC10 scope-fenced — ZERO BE changes, ZERO new endpoints/topics/migrations/tokens; only additions are `tools/brand-voice-lint.ts` Rule 3 svg-scan extension + `tools/contrast-check.ts` new `POOL_STACK_PAIRS` set. Story file notes 14 implementation traps (declarative `<Svg>` runtime vs design-source .svg files, `react-native-svg-transformer` is NOT installed and is OUT-of-scope, PoolBar full deletion not parallel coexistence, threshold table PRD-locked per Day-30 KPI, ratchet is defence-in-depth not BE-substitute, `__DEV__` is RN constant not `process.env`, opacity-only animation, reduced-motion suppresses ember glow entirely, `AccessibilityInfo.isReduceMotionEnabled()` async + null-gated, snapshots are PR-review baselines, no emojis in source) + 12-item explicit out-of-scope list (no transformer dep, no per-room poolMax, no WalletPreview PoolStack mount, no Final-3 poster port, no analytics SDK telemetry, no Storybook, no PoolBar dead-code retention, no threshold retune, no GeneratedTokens.java pool entries, no new color tokens, no PoolMeter legacy export). Flipped sprint-status `4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table: backlog → ready-for-dev`. Epic 4 stays `in-progress` (already at in-progress per Story 4.1's auto-promote).
