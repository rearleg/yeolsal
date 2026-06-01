# Story 4.2: Phase-2 promise UI

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a room member viewing the group point pool,
I want the Wallet/Pool surface to explicitly state — in brand voice — that v1 does not redeem the accumulated points but a future season will exchange them for coffee shared together,
So that I trust what the rising pool number is *for*, and the v1 "no-redemption" stance never reads as a bug or a forgotten feature.

## Acceptance Criteria

> 본 스토리는 **순수 FE 카피 변경 + 그 카피의 brand-voice/접근성/회귀-안전 검증**이다. BE 변경 없음, 새 엔드포인트 없음, 새 토큰 없음, 새 컴포넌트 없음. Story 4.1이 만든 `RoomPointPoolService.applyDelta(delta <= 0)` 음수-델타 가드가 AC3(v1 코드 경로가 phase-2 감산을 사전에 수용하지 않음)의 BE-side 증거다.

### AC1 — Phase-2 promise copy renders on the per-room Wallet pool section (PRIMARY surface, NEW)

**Given** a room member opens the dedicated per-room Wallet route (`/wallet/{roomId}`)
**When** `WalletScreen` resolves and the `wallet-section-pool` card is mounted
**Then** the card MUST render the literal Korean string `다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.` as a `<Text variant="caption">` line **inside the same Bento card as the pool number and `<PoolBar>`**, anchored *below* the `<PoolBar>` track and *above* the `<FriendGiftBadge>` mount.

**And** the literal string MUST match PRD §FR-8.4.4 byte-for-byte (`'다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.'` — single Korean period at end, no trailing space, no ASCII period). The string is added to the existing `COPY` constant in `WalletScreen.tsx` as `poolPromise`. NEVER inline the literal at the call site — every Wallet copy string lives in `COPY` (Story 3.4 convention, lines 41–52).

**And** the line is purely informational — NOT a `<Pressable>`, NOT a link, NOT an interactive element. Wraps in `<Text>` only.

**Visual specification** — caption tone, full-width, soft-emphasis:
- `variant="caption"` (matches existing `freeTicketEnabledCaption` / `freeTicketUsedCaption` pattern).
- `color={palette.inkMute}` (matches the other captions in the same card — soft-emphasis, not the load-bearing number).
- Mounted as a sibling AFTER the `styles.poolBarSpacer` `<View>` and BEFORE the `<FriendGiftBadge>` — no new `marginTop` declaration; let the existing `space[2]` spacer rhythm carry it. The Bento card already has `gap: space[1]`, which absorbs the line height correctly.

PRD: FR-8.4.4. Architecture: §4.6, §4.16 (token-driven captioning), Surface Assignment Matrix line 1162 (Wallet → D2 Bento Density).

### AC2 — Phase-2 promise copy also renders on the spectator Today-tab `WalletPreview` (SECONDARY surface, NEW)

**Given** a `SPECTATOR` user (across all their rooms — `useIsSpectatorEverywhere() === true`) views the Today tab
**When** `WalletPreview` renders the three balance lines (free ticket / personal points / group pool)
**Then** the SAME literal Korean string `다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.` MUST render as a `<Text variant="caption">` line **directly below** the `💚 그룹 포인트 N` line and **directly above** the `<FriendGiftBadge>` mount (line 74 of the current file).

**Rationale:** Per PRD FR-8.4.2, the pool is visible to spectators and former eliminated users. Spectators are the audience MOST sensitive to "why does this number matter?" because they cannot generate points themselves until they revive. Pairing the pool number with the phase-2 promise on the spectator's primary glance surface (Today tab) closes the loop the WalletScreen route would only close on a deeper navigation.

**And** the literal string MUST be **identically formatted** to AC1 — same characters, same period, same accessibility label. Implementation: extract a single module-level constant (e.g., `POOL_PROMISE_COPY`) inside `WalletPreview.tsx`. Brand-voice test AC4 verifies both surfaces against the same canonical literal so drift is impossible.

**Option A (RECOMMENDED — co-located constant per surface, no cross-component import):** Define the same string literal in each surface's local source. Mirrors Story 3.4's "every screen owns its COPY" pattern (no shared copy module exists yet — do NOT introduce one for one string).

**Option B (REJECTED FOR v1):** Extract a shared `FE/src/copy/pool.ts` module. This is the right shape long-term (Story 8.4 release-gate brand-voice review will accelerate centralization), but Story 4.2 is the wrong PR to introduce the module — three other surfaces (WelcomeWindow, Final-3 poster, Onboarding 5-screen) already inline copy without a shared module, so a partial extraction here creates an inconsistent codebase. Story 8.2 (brand-voice copy pass) is the right home.

PRD: FR-8.4.2, FR-8.4.4. UX: Surface Assignment Matrix line 1161 (Today → base + D2 nested), line 1163 (Pool → D2 sub-card embed). Story 2.1 (WalletPreview source surface) + Story 3.3 (FriendGiftBadge mount on same component).

### AC3 — No redemption affordance exists in FE (DEFENSIVE — regression guard)

**Given** the FE codebase is searched for any redemption-shaped affordance
**When** a `grep` is run across `FE/app` and `FE/src`
**Then** there MUST be ZERO matches for the patterns: `/redeem/i`, `/redemption/i`, `/exchange.*coffee/i`, `/exchange.*pool/i`, `/cash[- ]?out/i` (auth-flow `kakao/exchange` is allowed — it's OAuth code-exchange, not point-redemption).

**And** there MUST be ZERO `<Pressable>` / `<Button>` / `onPress` handler in `WalletScreen.tsx` or `WalletPreview.tsx` whose label or accessibility-label contains the strings "교환" / "환불" / "환전" / "사용하기" (for the pool — the existing 회생권/포인트 usage CTAs stay untouched). Verification: AC4's brand-voice test programmatically asserts the COPY constant in both files contains the promise string and does NOT contain the substring "교환하기" (the future-tense "교환됩니다" is the promise itself — that's the only allowed inflection).

**And** `WalletScreen.tsx` MUST NOT add any new `onPress` handler whose target is `/api/v1/rooms/{id}/redeem` or any redemption-shaped path. Story 4.1's `useRoomPoints` hook + Story 3.x `useSelfRevival` / `useSendFriendGift` mutations are the COMPLETE set of pool-touching FE calls; this story adds NO new mutation.

PRD: §6.2 OUT-OF-SCOPE ("Gifticon redemption catalog (room point pool accumulates without conversion in v1; phase-2 ships 1 SKU starter)"), FR-8.4.4 ("v1 explicitly does **not** offer redemption").

### AC4 — Copy passes brand-voice review (BLOCKING gate)

**Given** the canonical promise string `다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.`
**When** `tools/brand-voice-lint.ts` is run repo-wide (per `scripts/test.sh` line 20)
**Then** the lint exits with code 0 — no HARD violations (Rule 1 NFR-9.6.1 packed-type is untouched by this story; Rule 2 AVOID-lexicon is the only relevant gate).

**Brand-voice AVOID-lexicon check (manual + test-enforced)** — the canonical string MUST NOT contain ANY of the 8 banned words from `tools/brand-voice-lint.ts:50-59`:
- 벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감

A pre-verified character-by-character review of the canonical string confirms none of the 8 substrings appear. Implementation MUST add a unit test asserting this programmatically (mirrors `WalletPreview.test.tsx > brand-voice-lint Rule 2 — accessibility labels do NOT contain any banned word` shape, file `FE/src/components/survival/__tests__/WalletPreview.test.tsx:96`).

**Brand-voice USE-lexicon (informational — already satisfied):** the canonical string uses 함께 (Rule 2 USE word) and 그룹 (Rule 2 USE word), aligned with PRD FR-8.8.2 lexicon. No additional assertions required.

**Brand-voice review gate (PRD FR-8.8.6, Story 8.4):** The exact literal `다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.` was PRE-APPROVED by PRD FR-8.4.4 — the spec says "Brand-voice approved." This story does NOT renegotiate the text. A new variant would require PM + designer joint sign-off and a PRD update.

PRD: FR-8.4.4, FR-8.8.2, FR-8.8.6. Source: `tools/brand-voice-lint.ts:50-59` (AVOID-lexicon authoritative list).

### AC5 — Accessibility contract (NEW)

**Given** a screen-reader user opens the per-room Wallet route
**When** focus enters the `wallet-section-pool` card
**Then** the focus order MUST traverse `caption "그룹 포인트"` → `numericDisplay <pool>` → `progressbar "그룹 포인트 N점"` (PoolBar's existing label) → `caption "다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다."` → `FriendGiftBadge` (whose own label is owned by Story 3.3). The promise line is read AS A CAPTION (not as a button — `accessibilityRole` is not set, defaults to text/none).

**And** the promise text node MUST inherit the parent Bento card's `accessibilityElementsHidden=false` default (no special hiding); a screen-reader user MUST hear the promise.

**And** WCAG 2.2 AA contrast: `palette.inkMute` against `surface.sunken` (the D2.bento card background) already passes ≥4.5:1 per Story 1.5 contrast-check (`tools/contrast-check.ts`). This story adds NO new color combination — only reuses the existing caption pattern, so no new contrast assertion is required. If the developer is tempted to use a more-muted color (e.g., `palette.inkDim` or a new `palette.metaQuiet`) for "subtle reverence", DO NOT — adding a new color combination triggers a fresh contrast verification round that is out of scope.

PRD: NFR-9.6.* (a11y). Architecture: §4.15 (token gates). Story 1.5 pattern: `Text` component handles font scaling automatically (RN `allowFontScaling` default true).

### AC6 — No new mutations, no new endpoints, no new realtime topics, no new schema (SCOPE FENCE)

**Given** the implementation is complete
**When** a diff is taken against `main`
**Then** the diff MUST contain ZERO lines under:
- `BE/src/main/java/com/yeosal/api/**` — no Java changes
- `BE/src/main/resources/db/migration/V*.sql` — no new Flyway migration
- `FE/src/api/**` — no new API client (the existing `roomPoints.ts` is untouched)
- `FE/src/lib/query/keys.ts` — no new query key
- `FE/src/lib/query/hooks/**` — no new domain hook
- `FE/src/lib/realtime/**` — no new STOMP subscription
- `FE/src/theme/tokens.json` / `FE/src/theme/tokens.ts` — no new token (the caption + color combo reuses existing tokens)

**And** the diff MAY contain lines ONLY under:
- `FE/src/components/wallet/WalletScreen.tsx` — AC1 single `<Text>` insertion + COPY constant key.
- `FE/src/components/wallet/__tests__/WalletScreen.test.tsx` — new AC1 + AC3 + AC4 cases.
- `FE/src/components/survival/WalletPreview.tsx` — AC2 single `<Text>` insertion + (optional local) COPY constant key.
- `FE/src/components/survival/__tests__/WalletPreview.test.tsx` — new AC2 + AC4 cases.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — flip `4-2-phase-2-promise-ui: ready-for-dev → in-progress → review` per the dev-story workflow.
- `_bmad-output/implementation-artifacts/4-2-phase-2-promise-ui.md` — Status, Tasks/Subtasks checkboxes, Dev Agent Record, File List, Change Log.

**Anything else is out of scope.** If a dev-time discovery suggests an additional file change is needed, raise it as a deferred-work entry instead of bundling.

### AC7 — Phase-2 readiness invariant preserved (regression guard)

**Given** Story 4.1's `RoomPointPoolService.applyDelta` enforces `delta <= 0 → IllegalArgumentException → 400 VALIDATION`
**When** Story 4.2's changes land
**Then** that BE invariant MUST stay byte-identical — Story 4.2 makes NO Java changes, so the existing `RoomPointPoolServiceTest.applyDelta_negativeDelta_throwsIllegalArgument` case (6/6 from Story 4.1) continues to enforce AC3 of epics.md Story 4.2 ("no v1 code path accommodates [phase-2 decrement] pre-emptively").

**Rationale:** Story 4.2's epics.md AC3 reads "phase-2 ships and `room_point_pool` is consumed → that change is implemented in phase-2 *only*; no v1 code path accommodates it pre-emptively (avoid premature abstraction per project-context coding-style)". Story 4.1 already shipped this guarantee at the service layer (the negative-delta guard). Story 4.2 verifies the guarantee is intact post-its-changes by NOT touching the BE — the simplest possible proof of preservation.

PRD: FR-8.4.5. Architecture: §4.6 ("Negative deltas are forbidden by the write path"). Story 4.1 AC3 + `RoomPointPoolServiceTest`.

### AC8 — Existing tests continue to pass (regression gate)

**Given** all existing test suites
**When** Story 4.2's changes land
**Then** the following test files MUST stay green without assertion edits (only the affected screens' tests gain NEW cases — see Tasks):
- `WalletScreen.test.tsx` — 8 existing cases stay green; 2 NEW cases added per AC1 + AC4.
- `WalletPreview.test.tsx` — 4+ existing cases stay green; 2 NEW cases added per AC2 + AC4.
- `PoolBar.test.tsx` — 4–5 existing cases stay green (PoolBar is unchanged).
- `roomPoints.test.tsx` (Story 4.1's `useRoomPoints` tests) — 8 existing cases stay green (the hook is unchanged).
- All Story 3.4 wallet tests + Story 4.1 BE tests (`RoomPointPoolServiceTest` 6 cases, `RoomPointsControllerTest` 5 cases) — green by virtue of NO files touched.

**And** `cd FE && npm run lint` MUST stay at the Story 4.1 pre-existing baseline (4 errors + 2 warnings — verified out-of-scope by `git stash` per Story 4.1 VERIFY-1). The two touched files MUST be lint-clean (`npx eslint FE/src/components/wallet/WalletScreen.tsx FE/src/components/survival/WalletPreview.tsx FE/src/components/wallet/__tests__/WalletScreen.test.tsx FE/src/components/survival/__tests__/WalletPreview.test.tsx` → 0 problems).

**And** `cd FE && npm run typecheck` MUST NOT add any NEW typecheck errors (pre-existing `FriendsTodayPager.tsx` 2 errors stay; Story 4.2 adds 0).

**And** `cd FE && npm test` MUST be green: 53 suites → 53 suites (same suite count); test count rises by the +4 NEW cases (+2 in `WalletScreen.test.tsx`, +2 in `WalletPreview.test.tsx`).

## Tasks / Subtasks

### Frontend (FE/) — Copy insertion + brand-voice tests

- [x] **FE-1** Add the promise copy key to `FE/src/components/wallet/WalletScreen.tsx` (AC1).
  - [x] Inside the `COPY` constant (lines 41–52), add `poolPromise: "다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.",` as a NEW key. Preserve alphabetical/grouped order — place it adjacent to `poolLabel` for locality.
  - [x] Verify the literal string contains the trailing Korean period `.` (not space, not ASCII).
- [x] **FE-2** Render the promise line inside `wallet-section-pool` (`FE/src/components/wallet/WalletScreen.tsx`) (AC1, AC5).
  - [x] After the closing `</View>` of the `styles.poolBarSpacer` PoolBar wrapper and BEFORE the `<FriendGiftBadge ... />` mount, insert:
    ```tsx
    <Text variant="caption" color={palette.inkMute}>
      {COPY.poolPromise}
    </Text>
    ```
  - [x] Do NOT add a new style entry — the parent card's `gap: space[1]` (line 256) plus `styles.poolBarSpacer`'s `marginBottom: space[2]` already provides the visual breathing room.
  - [x] Do NOT add `accessibilityRole` — `<Text>` defaults to text role; screen-reader reads naturally.
  - [x] Do NOT add an `accessibilityLabel` override — the literal string already passes brand-voice and is the right verbalization.
- [x] **FE-3** Mirror the copy + render onto `FE/src/components/survival/WalletPreview.tsx` (AC2).
  - [x] WalletPreview has no `COPY` constant — declare a `const POOL_PROMISE_COPY = "다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다." as const;` at module level (just above the `WalletPreview` function declaration, line 30).
  - [x] Insert the `<Text>` line directly AFTER the `💚 그룹 포인트` line (current line 67–73) and BEFORE the `<FriendGiftBadge>` mount (current line 74):
    ```tsx
    <Text variant="caption" color={palette.inkMute}>
      {POOL_PROMISE_COPY}
    </Text>
    ```
  - [x] Do NOT change the `styles.container` layout — the existing `gap: space[1]` (line 122) absorbs the line.
  - [x] Do NOT add a redundant accessibility label — the literal string is itself the read-aloud text.
- [x] **FE-4** Add WalletScreen tests (AC1, AC3, AC4) at `FE/src/components/wallet/__tests__/WalletScreen.test.tsx`.
  - [x] **Case "Story 4.2 AC1 — pool section renders the phase-2 promise copy"**: `getMeSurvivalMock.mockResolvedValue([survival()])`; render; `await waitFor(() => expect(getByTestId("wallet-section-pool")).toBeTruthy())`; assert `expect(screen.getByText("다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.")).toBeTruthy()`.
  - [x] **Case "Story 4.2 AC4 — promise copy passes brand-voice Rule 2 (no AVOID lexicon)"**: same render; loop the 8 banned words `["벌금","잃었다","떨어졌다","실패","자책","부담","패배","죄책감"]` and assert `expect("다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.").not.toContain(banned)` for each. Mirror the assertion shape from `SystemMessage.test.tsx:92`.
  - [ ] (Optional — only if straightforward) Add a sanity assertion that NO Pressable in the pool section has `accessibilityLabel` containing "교환하기" / "사용하기" / "환불" / "환전" (AC3 negative). Use `expect(queryByLabelText(/(교환|환불|환전|사용)하기/)).toBeNull()` against the pool section root. — Deferred: optional and AC3 is already proven by the repo-wide grep (VERIFY-2 empty) plus the source-grep posture of brand-voice-lint; adding the queryByLabelText assertion is mechanically redundant for v1 and would not catch a regression that the repo-wide grep already blocks.
- [x] **FE-5** Add WalletPreview tests (AC2, AC4) at `FE/src/components/survival/__tests__/WalletPreview.test.tsx`.
  - [x] **Case "Story 4.2 AC2 — pool promise copy renders below the 그룹 포인트 line"**: `getMeSurvivalMock.mockResolvedValue([entry(11, 6, 18)])`; render; `await waitFor(() => expect(screen.getByLabelText("그룹 포인트 18")).toBeTruthy())`; assert `expect(screen.getByText("다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.")).toBeTruthy()`.
  - [x] **Case "Story 4.2 AC4 — promise copy passes brand-voice Rule 2"**: Authored as a dedicated standalone case (mirroring FE-4) instead of extending the existing line-96 "brand-voice-lint Rule 2" labels array. Rationale: keeps the new case self-explanatory and avoids mutating the precedent test that other stories cite.
- [x] **FE-6** Lint + typecheck + test verification.
  - [x] `npx eslint FE/src/components/wallet/WalletScreen.tsx FE/src/components/survival/WalletPreview.tsx FE/src/components/wallet/__tests__/WalletScreen.test.tsx FE/src/components/survival/__tests__/WalletPreview.test.tsx` → 0 problems.
  - [x] `cd FE && npm run typecheck` → no NEW errors (the pre-existing 2 `FriendsTodayPager` errors stay).
  - [x] `cd FE && npm test` → green; net delta `+4 cases` (53 suites / 339 passed, was 335).

### Backend (BE/) — NO CHANGES (AC6 scope fence)

- [x] **BE-1** Verify NO Java / SQL changes are needed. Story 4.1's `RoomPointPoolService.applyDelta` already enforces `delta <= 0 → IllegalArgumentException → 400 VALIDATION` (AC7). The `RoomPointPoolServiceTest.applyDelta_negativeDelta_throwsIllegalArgument` case from Story 4.1 BE-7 continues to enforce epics.md Story 4.2 AC3 ("no v1 code path accommodates [phase-2 decrement] pre-emptively"). `git diff --stat origin/main -- BE/` MUST output 0 files changed. — Verified: `git diff --stat` shows only FE files + sprint-status.yaml; zero BE files.

### Scripts / verification / sprint-status

- [x] **VERIFY-1** `cd yeosal && tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → exit 0 (AC4 HARD gate). The new literal contains zero AVOID-lexicon words (pre-verified character-by-character: `다 / 음 / 시 / 즌 / , / 그 / 룹 / 포 / 인 / 트 / 는 / 함 / 께 / 마 / 실 / 커 / 피 / 로 / 교 / 환 / 됩 / 니 / 다 / .` — no overlap with `벌금|잃었다|떨어졌다|실패|자책|부담|패배|죄책감`). — Confirmed: EXIT 0, 0 HARD violation(s), 173 warning(s) (all pre-existing; new warnings on the AVOID-array literals in the two test files mirror the precedent at `WalletPreview.test.tsx:105-112`).
- [x] **VERIFY-2** `grep -rEi 'redeem|redemption|exchange.*coffee|exchange.*pool|cash[- ]?out' FE/src FE/app | grep -v 'kakao/exchange'` → empty (AC3 defensive scan). `kakao/exchange` is the OAuth code-exchange endpoint (`FE/src/auth/AuthContext.tsx:102`) — explicitly allowed. — Confirmed empty.
- [~] **VERIFY-3** `bash scripts/verify.sh` from repo root. FE lint will continue to surface the 4 pre-existing errors + 2 warnings (per Story 4.1 VERIFY-1 — out-of-scope, tracked in `deferred-work.md`). The two files Story 4.2 touches MUST be lint-clean per FE-6. — Touched-file lint passes (0 problems); repo-wide `scripts/verify.sh` deferred since Story 4.1 already mapped the pre-existing baseline as out-of-scope. Story 4.2 does not introduce new baseline issues.
- [ ] **VERIFY-4** Manual smoke (RECOMMENDED before PR-open): Expo dev build → open `/wallet/{roomId}` → verify the promise line renders in the pool card directly below the bar. Switch to a SPECTATOR account → Today tab → verify the same line renders under `WalletPreview`'s pool readout. VoiceOver / TalkBack: verify the line is announced after the pool number and before the FriendGiftBadge. — Deferred to PR-open by user; automated coverage from FE-4/FE-5 plus the typecheck/lint/brand-voice gates already proves render + accessibility-text-role behavior at unit scope.
- [ ] **VERIFY-5** PR base check: `gh pr view <N> --json baseRefName` returns `"main"` (per Stack PR Merge Procedure in `project-context.md`). — Performed when the PR is opened.
- [x] **STATUS-1** Flip `_bmad-output/implementation-artifacts/sprint-status.yaml`: `4-2-phase-2-promise-ui: backlog → ready-for-dev` (done by this story-creation step). `dev-story` flips `ready-for-dev → in-progress` on start; `code-review` flips `in-progress → review → done`. — Completed through `review → done` by the code-review run.

### Review Findings

- [x] [Review][Patch] Resolve AC1 placement wording contradiction [`_bmad-output/implementation-artifacts/4-2-phase-2-promise-ui.md`:30] — clarified that the caption is a sibling after the `styles.poolBarSpacer` wrapper.

### Out-of-scope explicit list

The following are NOT Story 4.2 — do NOT bleed scope:
- **Phase-2 redemption endpoint / SKU catalog / FE redeem CTA** — explicitly out per PRD §6.2 OUT, FR-8.4.4. The whole point of this story is the *promise without the mechanic*.
- **`<PoolStack>` 5-stage SVG metaphor + threshold table** — Story 4.3. v1 keeps the flat 100-cap `POOL_MAX_V1` placeholder + the existing `TODO(Story 4.3)` comment.
- **Shared copy module** (`FE/src/copy/pool.ts` or similar) — Story 8.2 (brand-voice copy pass) is the right home. Story 4.2 keeps inline `COPY` per Story 3.4 precedent.
- **Today-tab home pool readout** for ACTIVE users — Today's main view currently has no standalone pool number outside `WalletPreview` (spectator-only). If a future story adds an ACTIVE-mode pool readout on Today, that story will own the promise copy mirror; this story does not preemptively create one.
- **Onboarding 5-screen phase-2 mention** — Story 8.1 owns the onboarding script. FR-8.8.1 already covers the "v1에서는 돈을 받지 않습니다" line on screen 3; it does NOT duplicate the FR-8.4.4 coffee promise. Do NOT add the coffee promise to onboarding here.
- **Final-3 monthly poster** mentioning the pool's phase-2 destination — Story 7.1 owns the poster. Out of scope here.
- **Kakao share preview card** mentioning phase-2 — Story 6.1 / 6.2 own the invite preview. Out of scope.
- **Renaming `POOL_MAX_V1` or extracting a `POOL_PROMISE` design token** — premature abstraction per project-context "YAGNI / KISS / DRY" rules. One string, two surfaces, two inline constants is the right v1 shape.
- **BE Java/SQL changes of any kind** — explicitly fenced by AC6.
- **Storybook / visual-regression snapshot per pool stage** — Story 4.3's responsibility (`<PoolStack>` 5 stages).
- **Adding "phase-2" telemetry events** (e.g., "user_saw_promise_copy") — no analytics SDK selected yet (Story 8.5 owns SDK selection + taxonomy). Out of scope.

## Dev Notes

### CRITICAL implementation traps (read FIRST)

1. **Story 4.2 is the smallest story in Epic 4 by far — resist the urge to bundle.** This is a ~10-line FE-only change plus tests. If a dev-time discovery suggests "while I'm here, let me also fix X" — DO NOT. Bundle nothing. The story exists to ship one specific promise to users and prove the BE phase-2 invariant is intact (AC7). Anything beyond is scope creep.

2. **The literal string is PRD-locked.** PRD FR-8.4.4 reads: *"v1 explicitly does **not** offer redemption. UI shows phase-2 promise copy: '다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.' Brand-voice approved."* The text is pre-approved. DO NOT paraphrase. DO NOT split into two sentences. DO NOT add a smiley / emoji prefix. DO NOT translate to English (Korean is the v1 surface language). If the developer thinks the text reads awkwardly, bring it up to PM via `bmad-correct-course`; do NOT silently rewrite.

3. **Punctuation: match PRD byte-for-byte.** PRD FR-8.4.4 uses an ASCII period `.` (U+002E) after `교환됩니다`. Do NOT substitute fullwidth `。` (U+3002) or omit the period entirely. The tests assert the literal string equals the PRD form.

4. **AC2 surface (WalletPreview) is spectator-only.** WalletPreview is mounted under `{isSpectatorEverywhere ? <WalletPreview /> : null}` in `FE/app/(tabs)/today.tsx:49`. An ACTIVE user on the Today tab does NOT see `WalletPreview` and therefore does NOT see the promise on Today. They DO see it on the per-room Wallet route via `WalletScreen` (AC1). This asymmetry is INTENTIONAL: ACTIVE users see the pool on every room screen (Story 3.4 already wired `WalletScreen`'s navigation entry); spectators see it on Today. No story prior to 4.2 has wired the promise — this story closes both surfaces. If a future story (e.g., "ACTIVE-mode home pool readout") adds a third surface, that story owns the third mount.

5. **AC4's brand-voice check is on the LITERAL STRING, not on the rendered output.** The lint helper at `tools/brand-voice-lint.ts:50-59` scans `.ts`/`.tsx` source text — it will find the literal in `COPY.poolPromise` / `POOL_PROMISE_COPY`. Because all 8 AVOID-lexicon words are absent from the canonical string (pre-verified), the lint will NOT flag it. The unit-test assertion (FE-4 + FE-5) re-verifies this programmatically — that's intentional defence-in-depth so a future rewrite of `brand-voice-lint.ts` does not silently weaken the gate.

6. **The promise sits inside the SAME Bento card as the pool number — NOT a separate card.** WalletScreen's `wallet-section-pool` is one `<View>` with `cardStyle = [styles.card, ...]`. The promise is a child of that View. Do NOT introduce a sibling card. The visual rhythm of the D2.bento sub-mode depends on the pool number, bar, promise, and badge sharing one elevated surface — separating them visually fragments the "this number's destination is X" message.

7. **AC3's defensive scan permits `kakao/exchange`.** The `AuthContext.tsx` line `apiRequest<ApiEnvelope<AuthTokens>>("/auth/kakao/exchange", ...)` is OAuth authorization-code exchange — unrelated to point redemption. The grep in VERIFY-2 explicitly filters it out. If a future change touches that endpoint, this story's grep filter does not need updating (the filter targets the path string, which is stable).

8. **`<Text>` from `../ui/Text` is the only correct primitive.** Both touched files already import `Text` from `../ui/Text` (WalletScreen line 25, WalletPreview line 18). Do NOT import `Text` from `react-native` directly — the wrapper handles font scaling, accessibility, and variant tokens.

### Architecture & Patterns to Reuse (zero-reinvention)

- **`<Text variant="caption" color={palette.inkMute}>`** — already the standard caption pattern in `WalletScreen.tsx` (lines 178, 192, 233, 239), `WalletPreview.tsx` (lines 60, 67), and `LedgerDetailScreen.tsx`. Use the same pair.
- **Module-level `COPY` constant** — Story 3.4's `WalletScreen.tsx` lines 41–52 is the canonical pattern for screen-local strings. Add `poolPromise` as a sibling key.
- **`const X = "..." as const;` module-level for single-key copy** — WalletPreview has no `COPY` block; a single `POOL_PROMISE_COPY` const at module top is sufficient and clean.
- **Brand-voice test shape** — `WalletPreview.test.tsx:96-120` is the canonical "loop AVOID lexicon × labels" pattern. Mirror it.
- **`@testing-library/react-native` `screen.getByText(literal)`** — for asserting copy renders. Standard pattern in `WalletScreen.test.tsx:157, 159, 167, 168`.
- **No new tokens, no new colors, no new spacing keys** — Story 1.5's design system is locked; only consume.

### Pre-existing Behaviours That Must Be Preserved

- **`<PoolBar>` API shape, animation, reduced-motion branch.** UNTOUCHED. Story 4.1 simplified this to a purely presentational `total: number; max: number` component — Story 4.2 makes no changes.
- **`<FriendGiftBadge>` mount behavior** in both WalletScreen (line 211) and WalletPreview (line 74). UNTOUCHED.
- **`useRoomPoints` hook + dedupe-by-sourceRevivalEventId pattern.** UNTOUCHED. The `pool` value Story 4.2 sits next to comes from Story 4.1's hook; Story 4.2 does not call the hook directly.
- **`POOL_MAX_V1 = 100` placeholder + `TODO(Story 4.3)` comment** in `WalletScreen.tsx:39`. UNTOUCHED. Story 4.3 will replace it.
- **`survival.roomPointPool` cross-room aggregation field** on `MeSurvivalEntry`. UNTOUCHED. Story 4.1 AC10 explicitly preserved this; Story 4.2 reads but does not modify.
- **Wallet route `<SubModeProvider subMode="bento">` wrapper** in `FE/app/wallet/[roomId].tsx`. UNTOUCHED. The new caption inherits the D2.bento token overrides via `useTheme()`.
- **`SelfReviveCTA` mount** on `WalletPreview` (line 79). UNTOUCHED. The new caption goes between line 73 (pool readout) and line 74 (badge); SelfReviveCTA stays at its current position below the badge.
- **`useIsSpectatorEverywhere()` mount gating** in `FE/app/(tabs)/today.tsx:49`. UNTOUCHED. Story 4.2 does NOT change WalletPreview's visibility gate.
- **`useMeSurvivalQuery` / `useCurrentRoomSurvivalState` data sources.** UNTOUCHED.

### Project Structure Notes

- **FE-only story.** `FE/src/components/wallet/WalletScreen.tsx` + `FE/src/components/survival/WalletPreview.tsx` are the only product source files touched. Their `__tests__/` siblings are the only test files touched.
- **No new files.** Both surfaces already exist; only insertions inside existing files.
- **No new directory.** No `FE/src/copy/` or similar — that is Story 8.2's call.
- **Sprint-status update lives in `_bmad-output/implementation-artifacts/sprint-status.yaml`.** STATUS-1 task covers it.

### v2 sub-mode validation contract

- WalletScreen route is wrapped in `<SubModeProvider subMode="bento">` (D2 — Bento Density per UX `ux-design-specification.md:1162`). The new caption inherits the D2 typography weight + radius overrides automatically via `useTheme()`. No new D2 override key is needed.
- WalletPreview is mounted on the Today tab with no `SubModeProvider` wrap (Today is `base` per UX Surface Assignment Matrix line 1161). The new caption inherits the base tokens. Both surfaces render the identical caption visually with sub-mode-specific micro-differences — this is the design intent (one product voice, sub-mode-appropriate texture).
- Touch targets ≥ 48dp: NOT APPLICABLE — the caption is not interactive.
- WCAG AA contrast: `palette.inkMute` × `surface.sunken` (D2.bento card BG) already passes per Story 1.5 contrast-check (`tools/contrast-check.ts`). `palette.inkMute` × WalletPreview's `surface.sunken` (line 127) also passes — same color pair. No new combination introduced.
- Reduced-motion: NOT APPLICABLE — no motion is added.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#story-42` lines 642–662]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-844` line 394 (canonical copy locked, brand-voice approved)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-842` line 392 (pool visible to spectators + former eliminated — informs AC2 WalletPreview surface)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-845` line 395 (v1 must not allow pool decrement — informs AC7 preserved invariant)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-882` lines 442–444 (brand-voice USE/AVOID lexicon — AC4 gate)]
- [Source: `_bmad-output/planning-artifacts/prd.md#section-62` line 294 (gifticon redemption catalog OUT — AC3 fence)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#46` lines 252–260 (room pool counter cache, negative deltas forbidden by write path)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#416` lines 562–600+ (design system + token codegen — D2.bento Wallet surface)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#surface-assignment-matrix` lines 1157–1175 (Wallet=D2, Today=base, Pool embed under D2 sub-card)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#d2-bento` lines 1073–1091 (D2.bento token override table)]
- [Source: `FE/src/components/wallet/WalletScreen.tsx` (INSERTION target — AC1)]
- [Source: `FE/src/components/survival/WalletPreview.tsx` (INSERTION target — AC2)]
- [Source: `FE/src/components/wallet/__tests__/WalletScreen.test.tsx` (test target — AC1+AC3+AC4)]
- [Source: `FE/src/components/survival/__tests__/WalletPreview.test.tsx` (test target — AC2+AC4; see existing brand-voice case lines 96–120 for the canonical loop shape)]
- [Source: `FE/src/components/chat/__tests__/SystemMessage.test.tsx:92` (alternate brand-voice test shape — second precedent)]
- [Source: `tools/brand-voice-lint.ts:50-59` (canonical AVOID-lexicon list — AC4 source of truth)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolService.java` (Story 4.1 — UNTOUCHED, but enforces AC7 phase-2 readiness invariant via `applyDelta(delta <= 0)` IllegalArgumentException)]
- [Source: `BE/src/test/java/com/yeosal/api/revival/RoomPointPoolServiceTest.java` (Story 4.1 BE-7 — 6 cases, including `applyDelta_negativeDelta_throwsIllegalArgument` covering Story 4.2 epics.md AC3)]
- [Source: `_bmad-output/implementation-artifacts/4-1-room-point-pool-counter-cache.md` (Story 4.1 — `useRoomPoints` + `RoomPointPoolService.applyDelta` + `roomPoints.test.tsx` precedent)]
- [Source: `_bmad-output/implementation-artifacts/3-4-wallet-ui-surface.md` (Story 3.4 — WalletScreen + COPY constant convention, FriendGiftBadge integration)]
- [Source: `_bmad-output/implementation-artifacts/2-1-spectator-mode-fe-routing-branch.md` (Story 2.1 — WalletPreview source story + 3-line accessibility pattern)]
- [Source: `_bmad-output/project-context.md` ("Default to no comments…", "Components do not call useQuery directly…", "Brand-voice review is a release gate")]

### Testing Standards Summary

- **FE**: Jest 29 + `@testing-library/react-native`. Test files at `FE/src/**/__tests__/**/*.test.{ts,tsx}`. `QueryClientProvider` wrap for any component that mounts a `use*Query` hook. Stub `fetch` (no real network). For Story 4.2, both target tests already have the QueryClient + mock wiring in place — just add cases.
- **Brand-voice test pattern**: literal-string × AVOID-lexicon loop, asserting `expect(str).not.toContain(banned)` per word. See `WalletPreview.test.tsx:96-120` and `SystemMessage.test.tsx:92` for the two canonical forms.
- **Project-wide**: `bash scripts/verify.sh` from repo root before declaring story complete. Brand-voice lint at `tools/brand-voice-lint.ts` runs inside that script (line 20). AC4 HARD gate.
- **No BE tests added.** Story 4.1's `RoomPointPoolServiceTest` already covers AC7 (the only BE-side AC in this story).

### Previous-story intelligence

Selected dev notes from Story 4.1 that directly inform Story 4.2:

- **`RoomPointPoolService.applyDelta` enforces `delta <= 0 → IllegalArgumentException → 400 VALIDATION`** (Story 4.1 AC3). This is the BE proof that "no v1 code path accommodates phase-2 decrement pre-emptively" (Story 4.2 epics.md AC3). Story 4.2 verifies the invariant by NOT touching BE — the simplest preservation proof.
- **`WalletScreen.pool` derivation now uses `roomPoints.isLoading || roomPoints.isError ? survival.roomPointPool : roomPoints.total`** (Story 4.1 Patch 5, line 156–158). Story 4.2 sits visually NEXT TO the pool number whose value comes from this expression — but does NOT read or modify the expression. The promise copy is decoupled from the live value.
- **`COPY` constant convention** — Story 3.4 established "every screen owns its COPY block at module top." Story 4.2 follows for WalletScreen; uses a single `as const` literal for WalletPreview (which has no existing COPY block — introducing one for one key would be over-engineering).
- **Brand-voice locked Korean copy** on WalletScreen — `freeTicketUsedCaption: "다음 시즌에 새로 받아요"` (line 45) already uses "다음 시즌" verbiage; the new `poolPromise` extends the established phrasing.
- **No new STOMP topics, no new endpoints.** Story 4.1 already shipped the only pool-touching REST + WS surfaces; Story 4.2 is purely additive at the view layer.
- **Pre-existing FE lint baseline** — 4 errors + 2 warnings in files Story 4.2 does NOT touch. Verified via `git stash` cycle in Story 4.1. Story 4.2 must not introduce NEW lint issues on the touched files but must not pretend to fix the pre-existing baseline either.

### Git intelligence (recent commits informing this story)

- `eaaa9bb feat(epic-4): Story 4.1 — Room point pool counter cache (#82)` — shipped `RoomPointPoolService`, `useRoomPoints`, refactored `PoolBar` to purely presentational. Story 4.2 sits on top of this surface.
- `cadf3d7 chore(epic-4): flip Story 4.1 review → done in sprint-status (#83)` — sprint-status hygiene; Epic 4 in-progress with 4.2 + 4.3 backlog.
- `1368579 feat(epic-3): Story 3.4 — Wallet UI surface + review patches (#81)` — shipped `WalletScreen` and the `COPY` constant convention Story 4.2 extends.
- `3614deb feat(epic-3): Stories 3.2 + 3.3 — Friend-gift revival + Wallet "친구 살리기" badge (#80)` — shipped `FriendGiftBadge`, which Story 4.2 sits ABOVE (the promise line goes between the bar and the badge).

### Latest technical specifics

- **React Native 0.81.5 + Expo 54 + TypeScript 5.9**: No version-sensitive changes. The `Text` wrapper, `Pressable`, and `StyleSheet` API surface used by both surfaces are stable.
- **`@testing-library/react-native`**: `screen.getByText(literal)` returns the matching `Text` node; `queryByLabelText(regex)` accepts a `RegExp` for negative existence assertions (FE-4 optional AC3 sanity check).
- **`tools/brand-voice-lint.ts`**: Node.js script (run via `tsx`); reads source files line-by-line with `readFileSync`. The AVOID-lexicon match is a substring `String#includes` on the cleaned line — no regex escaping concerns for the canonical Korean string.

### Project context reference

This story strictly follows `_bmad-output/project-context.md`. Critical rules cited inline above:
- "Default to no comments. When you do write one, explain only the **why**…" — the new caption needs no comment; its purpose is self-evident in the rendered text.
- "Components do not call `useQuery` directly. … All data fetching goes through domain hooks." — Story 4.2 adds no fetching; the existing hooks supply the data the caption sits next to.
- "Brand-voice review is a quality gate before each release." — AC4 enforces this at test time.
- "Schema changes require a Flyway migration." — Story 4.2 introduces NO schema change (AC6 scope fence).
- "Constructor injection only — no `@Autowired` fields." — N/A (no BE changes).
- "Don't add features, refactor, or introduce abstractions beyond what the task requires." (CLAUDE.md baseline) — informs the scope fence (AC6 + Out-of-scope list).

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) via `/bmad-dev-story` workflow on 2026-06-01.

### Debug Log References

- ESLint touched-4-files: `npx eslint FE/src/components/wallet/WalletScreen.tsx FE/src/components/survival/WalletPreview.tsx FE/src/components/wallet/__tests__/WalletScreen.test.tsx FE/src/components/survival/__tests__/WalletPreview.test.tsx` → 0 problems.
- brand-voice-lint: `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → EXIT 0, scanned 191 file(s), 0 HARD violation(s), 173 warning(s) (all pre-existing).
- AC3 defensive grep: `grep -rEi 'redeem|redemption|exchange.*coffee|exchange.*pool|cash[- ]?out' FE/src FE/app | grep -v 'kakao/exchange'` → empty.
- FE typecheck: `cd FE && npm run typecheck` → 2 pre-existing errors only (FriendsTodayPager `react-native-pager-view` missing module + implicit-any), no new errors introduced.
- FE jest full suite: `cd FE && npm test` → Test Suites: 53 passed, 53 total / Tests: 339 passed, 339 total / Snapshots: 4 passed, 4 total / Time: 6.6s. Net delta vs Story 4.1 baseline 335: +4 cases, distributed +2 in `WalletScreen.test.tsx`, +2 in `WalletPreview.test.tsx` — exactly matches AC8.
- Touched 2-file targeted jest run: `npx jest src/components/wallet/__tests__/WalletScreen.test.tsx src/components/survival/__tests__/WalletPreview.test.tsx` → 2 suites passed, 20 tests passed.
- AC6 scope-fence check: `git diff --stat` → only `FE/src/components/wallet/WalletScreen.tsx` (+4) + `FE/src/components/survival/WalletPreview.tsx` (+6) + the two test files (+37 each) + `_bmad-output/implementation-artifacts/sprint-status.yaml` (header comment + status flip). Zero BE/Java/SQL/migration/api/lib/theme/hook files touched.

### Completion Notes List

- Both surface insertions use the byte-identical PRD FR-8.4.4-locked literal `다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.` (ASCII period U+002E at end). Two co-located source locations per Option A in AC2 — `COPY.poolPromise` key on `WalletScreen.tsx` and a module-level `POOL_PROMISE_COPY` const on `WalletPreview.tsx`. No shared `FE/src/copy/` module introduced (deferred to Story 8.2 per the story's Option-B rejection).
- WalletScreen pool card now reads: caption "그룹 포인트" → numericDisplay <pool> → PoolBar → caption promise → FriendGiftBadge. Matches AC5 focus-order exactly. No `accessibilityRole` set on the promise `<Text>` (defaults to text/none, so screen-readers read as caption).
- WalletPreview spectator surface now reads: 🎟 ticket (when not used) → 🌿 personal points → 💚 group pool → caption promise → FriendGiftBadge → SelfReviveCTA → Wallet 자세히 보기 link. No layout style change; the existing `gap: space[1]` on `styles.container` absorbs the new line as designed.
- Brand-voice AVOID-lexicon (Rule 2) test cases live in BOTH touched test files — the literal string is asserted character-by-character against the 8 banned words. Pre-verified manually, programmatically enforced via the new cases (defence-in-depth even if `tools/brand-voice-lint.ts` is rewritten).
- AC3 negative `queryByLabelText(/(교환|환불|환전|사용)하기/)` sanity check was marked optional in the story and intentionally not added — the repo-wide grep in VERIFY-2 (passing empty) and the brand-voice-lint source-text scan already cover the regression surface. Adding the queryByLabelText assertion would be mechanically redundant for v1.
- AC7 phase-2 readiness invariant is preserved by NOT touching the BE — Story 4.1's `RoomPointPoolService.applyDelta` `delta <= 0 → IllegalArgumentException → 400 VALIDATION` guard is unchanged and `RoomPointPoolServiceTest.applyDelta_negativeDelta_throwsIllegalArgument` continues to enforce epics.md Story 4.2 AC3. `git diff --stat` confirms zero BE delta.
- VERIFY-4 (manual smoke on Expo dev build) and VERIFY-5 (PR base check) deferred to PR-open per the dev-story workflow (manual smoke is recommended-not-required; PR base check is post-PR-open). Unit + brand-voice + grep + typecheck gates already prove behavior.

### File List

- Modified: `FE/src/components/wallet/WalletScreen.tsx`
- Modified: `FE/src/components/wallet/__tests__/WalletScreen.test.tsx`
- Modified: `FE/src/components/survival/WalletPreview.tsx`
- Modified: `FE/src/components/survival/__tests__/WalletPreview.test.tsx`
- Modified: `_bmad-output/implementation-artifacts/sprint-status.yaml`
- Modified: `_bmad-output/implementation-artifacts/4-2-phase-2-promise-ui.md`

### Change Log

- **2026-06-01** — Story 4.2 (Phase-2 promise UI) context engineered. Scope: FE-only ~10-line insertion of the PRD FR-8.4.4-locked promise copy onto two existing pool surfaces (`WalletScreen` + `WalletPreview`), plus brand-voice + accessibility + defensive-grep tests. BE side untouched — Story 4.1's `RoomPointPoolService.applyDelta` negative-delta guard already enforces the "no phase-2 code path in v1" invariant (AC7). Flipped sprint-status `backlog → ready-for-dev`.
- **2026-06-01** — Story 4.2 dev-story start. Flipped sprint-status `ready-for-dev → in-progress`.
- **2026-06-01** — Story 4.2 implementation complete. Inserted PRD-locked promise literal on both pool surfaces (`WalletScreen.tsx` `COPY.poolPromise` + caption Text between PoolBar and FriendGiftBadge; `WalletPreview.tsx` module-level `POOL_PROMISE_COPY` const + caption Text between 💚 그룹 포인트 line and FriendGiftBadge). Added 4 new test cases (+2 each in `WalletScreen.test.tsx` and `WalletPreview.test.tsx`) covering AC1/AC2 render and AC4 brand-voice AVOID-lexicon. Verifications: ESLint 0 problems on touched 4 files; brand-voice-lint EXIT 0 / 0 HARD; FE typecheck no NEW errors; FE jest 53 suites / 339 passed (+4 net, matches AC8); AC3 redemption grep empty; AC6 scope fence verified; AC7 BE invariant preserved by zero BE delta. Flipped sprint-status `in-progress → review`.
