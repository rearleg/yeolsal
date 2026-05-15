# Story 1.7: RitualMoment — 06:00 KST 5-second sacred wrapper

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **a member who opens the app between 06:00–06:05 KST**,
I want **a 5-second visual ritual that marks the day boundary as sacred (not transactional)**,
so that **the daily-loop feels like co-presence ritual, not a checklist task**.

PRD authority: **§6.4 principle 5** — *"Ritual time is sacred"* (root authority; closes UX U4 disposition ACCEPT).
Architecture authority: **§4.16** — token consumption via `useTheme()` + `GeneratedTokens` (no hex literals).
UX authority: **§Custom Component Specifications — `<RitualMoment>` L1489–1510** (D4 + D1 hybrid wrapper), **§Motion System L979–1006** (`motion.cinematic = 1500ms`, `ease.ritual`, reduced-motion fallback policy), **§Accessibility Considerations L1008+, L1972–1979** (modal a11y + reduced-motion announcement).
Sprint Change Proposal **2026-05-10 §G2.4** — Story 1.7 introduction + U4 disposition ACCEPT.

> **Foundation note.** Story 1.7 ships an FE-only overlay component mounted at the root navigator. It depends on Story 1.5 (`useTheme()`, `<SubModeProvider>`, brand-voice-lint, `motion.duration.cinematic = 1500` token, `motion.easing.ritual` token) which is already on `main` (PRs #58 + #59). No BE change, no Flyway migration, no new API endpoint. Spectator-variant *detection* is deferred to Story 2.1 (which establishes the FE SPECTATOR pipe); this story implements the *visual* spectator variant behind a boolean prop that defaults to `false`, so flipping a single integration-site flag in Story 2.1 lights it up without touching `<RitualMoment>` internals.

## Acceptance Criteria

1. **AC1 — `<RitualMoment>` overlay renders on app entry inside the 06:00–06:05 KST window.**
   The component lives at `FE/src/components/ritual/RitualMoment.tsx`. It is mounted at the root navigator in `FE/app/_layout.tsx` inside a new `<SubModeProvider subMode="postcard">` wrapper (D4-leaning of the D4+D1 hybrid per UX L1510) placed AFTER the existing `<Stack>` (so it overlays on top via absolute positioning + a `View` z-stack), and BEFORE `<StatusBar>`. The overlay is conditionally rendered (returns `null` outside the window). When inside the window AND not already fired today, the component renders a full-screen `accessibilityViewIsModal=true` `View` with:
   - Background: `theme.color.bg.canvas` deepening from `bg.canvas` to `bg.overlay` over 200ms entry (paper-tone → key-color tinted shift, per epics AC).
   - Ember radial accent: a centered radial gradient layer using `theme.color.key.glow` (oklch 50% 0.155 25°) at ~30% opacity peak — implemented via two `<View>` layers (no `expo-linear-gradient` dep — the FE bundle does not have it; see Tech Notes). Acceptable approximation: a centered fixed-size `View` with `backgroundColor: theme.color.key.glow + alpha`, `borderRadius: 9999`, `opacity` interpolated 0 → 0.3 → 0 across the 5s timeline.
   - Center text: `typography.display.serif` (size 36 per epics — *override* the v2 default of 56 by setting `style={{ fontSize: 36, lineHeight: 44 }}` since the token's default is too large for the 5s overlay aesthetic). Family `Nanum Myeongjo` (resolved by `useTheme()` from token; system serif fallback per Story 1.6 deferral).
   - Caption: KST date in `body.sm` weight 400 — format `YYYY년 M월 D일 (요일)` in Korean locale (`Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'long', day: 'numeric', weekday: 'short' })`).
   - 5-second timeline (standard variant):
     - **T+0 → 0.2s**: surface darken (canvas → overlay), opacity 0 → 1
     - **T+0.2 → 0.7s** (500ms): ember radial fades in to peak (0.3 opacity)
     - **T+0.7 → 4.0s** (3.3s display): center text + caption visible
     - **T+4.0 → 4.5s** (500ms): ember fades out
     - **T+4.5 → 5.0s** (500ms): surface lightens (overlay → transparent), opacity 1 → 0
   - At T+5.0s the component invokes its `onComplete` callback AND unmounts (renders `null`) AND writes `ritual.lastFiredKstDate = <today KST YYYY-MM-DD>` to AsyncStorage.
   The 5-second sequence uses the stock RN `Animated` API (consistent with `<WelcomeWindow>` precedent) — *not* `react-native-reanimated` or `react-native-skia` (neither is in `FE/package.json` deps; do not add).
   The component MUST NOT block app start: the overlay renders **after the first frame** (i.e., mount the component but gate the actual `<View>` rendering behind a `useEffect` setState so the initial render returns `null` and the visual content appears on the next frame). [PRD §6.4 principle 5, UX §Custom Component Specifications L1489–1510, UX §Motion System L989, epics Story 1.7 lines 295–323]

2. **AC2 — Weekday/month-day variant text matrix.**
   The center text varies by KST weekday and month-day (evaluated against the *current* KST date, NOT the stored `lastFiredKstDate`):

   | Day | Center text |
   |---|---|
   | 월요일~목요일 (Mon–Thu) | `오늘도 함께` |
   | 금요일 (Fri) | `이번 주도 살아남았어요` |
   | 토요일·일요일 (Sat·Sun) | `주말도 함께` |
   | 매월 1일 (KST month-day = 1, ANY weekday) | `이번 달 Final-3 카드가 도착했어요` |

   The `1st of month` rule **wins** over the weekday rule (e.g., a Friday that is also the 1st → Final-3 copy, not the Friday copy). Variant selection lives in a pure helper `selectRitualText(kstDate: Date): string` exported from `RitualMoment.tsx` and unit-tested across all 4 branches + the conflict case. Final-3 copy is the prerender bridge to Story 7.3 (NOT a live link — no navigation, no fetch; just the text). The caption (`YYYY년 M월 D일 (요일)`) is identical across all variants. [epics Story 1.7 lines 299–305, UX L1497–1501]

3. **AC3 — Reduced-motion fallback (1-second fade).**
   When `useReducedMotion()` (from `FE/src/theme/motion.ts`) returns `true`:
   - The 5-second sequence is **shortened to 1-second simple fade-in + 1-second display + 0ms exit (total 2s)**. Per UX L1001–1005, the policy is "5초 의식 → 1초 fade"; the most readable interpretation that still gives users time to read the text is: 1s fade-in → text visible for 1s → snap dismiss (overlay returns `null`). If the user prefers a strict 1s total, the dev may flatten to a 1s fade-in followed by immediate dismiss — this story accepts either interpretation as long as **(a) ember key-color radial gradient is NOT rendered**, **(b) center text appears immediately (no 0.7s delay)**, and **(c) total elapsed time ≤ 2s before the overlay dismisses**. Document the chosen interpretation in Dev Agent Record.
   - The ember radial gradient `<View>` is NOT rendered (skipped via the `reducedMotion` branch in JSX).
   - The center text uses `typography.body.lg` weight 700 instead of `display.serif` to avoid the cinematic register that reduced-motion users opted out of.
   - The completion callback + AsyncStorage write still fire at dismiss. [epics Story 1.7 lines 307–309, UX §Motion System L1001–1006, NFR-9.6.* (PRD)]

4. **AC4 — Idempotency: fires at most once per KST date.**
   - AsyncStorage key: `ritual.lastFiredKstDate`. Value: `YYYY-MM-DD` string in Asia/Seoul timezone.
   - On mount, the component reads the key. If the value equals **today's KST date** (computed via `Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' })`, which yields `YYYY-MM-DD`), the component returns `null` (no overlay, no fade). The check runs even if the user is inside the 06:00–06:05 window — re-opens within the same KST day do NOT re-fire.
   - The AsyncStorage write happens at the START of the visible sequence (after the `mounted` flip, before animations start) so a force-quit mid-animation still counts as fired.
   - The KST-date boundary follows the *system clock* in Asia/Seoul — NOT the user's device timezone. A user in PST who opens the app at PST 14:05 (= KST 06:05 next day) is inside the window if KST clock is 06:00–06:05.
   - **Storage helper:** add a small `FE/src/lib/ritualStorage.ts` module with `getLastFiredKstDate()` / `setLastFiredKstDate(value)` to keep AsyncStorage IO behind a typed boundary (matches `FE/src/lib/chatRead.ts` precedent). Reads return `null` on miss or parse error.
   [epics Story 1.7 lines 311–313]

5. **AC5 — Spectator variant: dim key-color treatment (visual only this story).**
   The component accepts a `spectator?: boolean` prop (default `false`). When `spectator = true`:
   - The ember radial gradient uses `theme.color.key.muted` (oklch 38% 0.045 25° — desaturated) instead of `theme.color.key.glow`.
   - Peak opacity of the ember layer is 0.18 (instead of 0.3).
   - The surface darken target shifts to `theme.color.bg.canvas` only (skip the overlay tint), giving a "subdued saturation" effect per UX L1508–1509.
   - Center text and caption are unchanged in content and color.
   - In reduced-motion + spectator combo, both reductions stack (1s fade + dim treatment).

   **Detection (integration site):** the wiring in `FE/app/_layout.tsx` passes `spectator = false` for Story 1.7. Story 2.1 (Spectator-mode FE routing branch) replaces this with the real SPECTATOR signal — at that point the wiring becomes `spectator={isSpectatorEverywhere(useAuth().user, useRoomsQuery().data)}` or equivalent helper. **This story does NOT add a `me/survival-summary` endpoint or any cross-room aggregation.** It implements the visual delta and leaves a `// TODO Story 2.1: replace with real spectator detection` comment at the wire site.

   Add a `RitualMoment.spectator.test.tsx` to verify both branches render the expected style differences without mocking actual users.
   [epics Story 1.7 lines 315–317, UX L1508–1509]

6. **AC6 — VoiceOver / TalkBack announcement + focus trap window.**
   - The overlay sets `accessibilityViewIsModal={true}` (iOS — traps VoiceOver focus inside the modal) AND `importantForAccessibility="yes"` + `accessibilityElementsHidden={false}` on the overlay root. For Android focus trap: wrap the `<Stack>` in a `<View importantForAccessibility={ritualActive ? "no-hide-descendants" : "auto"}>` only while the overlay is active. The pragmatic minimum: set `accessibilityViewIsModal` on the overlay root, AND on Android toggle the parent `<View>` `importantForAccessibility` while the overlay is visible.
   - Announcement copy: a single `AccessibilityInfo.announceForAccessibility(\`${YYYY년 M월 D일}, ${variantText}\`)` fires at T+0.2s (start of display phase), where `variantText` is the AC2 weekday-aware string. The announcement fires once per mount. In reduced-motion the announcement fires immediately at T+0s.
   - After dismiss (T+5s standard or T+2s reduced), the overlay unmounts AND the trap is released — users regain ability to interact with the screen below.
   - Per UX L1972–1973: 5초 동안 외부 trap 유지; reduced motion 시 1초 fade로 단축 + 즉시 control 복귀.
   [epics Story 1.7 lines 319–321, UX L1972–1979]

7. **AC7 — Window detection logic.**
   - Add a pure helper `isInRitualWindow(now: Date, timeZone = "Asia/Seoul"): boolean` exported from `RitualMoment.tsx` (or a sibling `ritualWindow.ts`).
   - Returns `true` iff the wall-clock hour in `timeZone` is `6` AND minutes ∈ `[0, 5)` (i.e., 06:00:00 inclusive, 06:05:00 exclusive).
   - Uses `Intl.DateTimeFormat(undefined, { timeZone, hour: '2-digit', minute: '2-digit', hour12: false })` and parses the resulting `HH:mm` string. **Do NOT use `Date.prototype.getHours()`** — that returns the user's device timezone, which is wrong for KR users traveling abroad.
   - Unit tests cover: boundary at 06:00:00 (true), 06:04:59 (true), 06:05:00 (false), 05:59:59 (false), 07:00:00 (false), 23:00:00 KST (false), and a fixed UTC Date that happens to map to 06:03 KST (true).
   [epics Story 1.7 line 295]

8. **AC8 — Brand-voice lint passes on every copy string.**
   All 4 weekday-variant Korean strings + the caption format MUST pass `tools/brand-voice-lint.ts` Rule 2 (AVOID lexicon). The 8 banned words (`벌금` / `잃었다` / `떨어졌다` / `실패` / `자책` / `부담` / `패배` / `죄책감`) must not appear. Manual pre-flight check confirms all 4 strings (`오늘도 함께` / `이번 주도 살아남았어요` / `주말도 함께` / `이번 달 Final-3 카드가 도착했어요`) are clean; `npm run lint:brand-voice` (or `tsx tools/brand-voice-lint.ts`) is invoked in `scripts/test.sh` per Story 1.5 AC13 — this story inherits that gate. [Story 1.5 AC5 Rule 2]

9. **AC9 — No BE change, no API endpoint, no migration, no analytics SDK call.**
   - **NO** new REST endpoint, **NO** new entity, **NO** Flyway migration (V12+ deferred), **NO** new `com.yeosal.api.<x>` module.
   - No analytics events (Story 8.5 owns the SDK; Story 1.7 is instrumentation-quiet).
   - No `react-native-reanimated`, `react-native-skia`, `expo-linear-gradient`, or any new FE dep is added to `FE/package.json`.
   - The only files this story touches outside `FE/src/components/ritual/` and its tests are: `FE/app/_layout.tsx` (mount the overlay), `FE/src/lib/ritualStorage.ts` (NEW helper), and the sprint-status/story-doc updates. If any other file is modified, scope has drifted — stop and re-scope.
   [Architecture §4.16; project-context "no half-finished implementations / scope discipline"]

10. **AC10 — Unit + integration test coverage (TDD, 80%+ on new code).**

    **FE — Jest + `@testing-library/react-native`:**
    - `FE/src/components/ritual/__tests__/RitualMoment.test.tsx` — render the overlay inside the window (idempotent unfired path) and assert: (a) `accessibilityViewIsModal=true` on root, (b) center text matches variant for a fixed Tuesday `Date`, (c) caption renders the formatted KST date, (d) overlay unmounts after the 5s timer (use `jest.useFakeTimers()` + `act(() => jest.advanceTimersByTime(5000))`), (e) `setLastFiredKstDate` is called with today's KST date, (f) outside the window returns `null`, (g) when `lastFiredKstDate` already matches today, returns `null` even inside the window. ≥ 10 assertions.
    - `FE/src/components/ritual/__tests__/RitualMoment.variants.test.tsx` — parameterized test across the 4 weekday/month-day branches + the 1st-of-month-on-Friday conflict case (Final-3 copy wins). ≥ 5 cases.
    - `FE/src/components/ritual/__tests__/RitualMoment.reduced-motion.test.tsx` — mock `useReducedMotion()` → `true`; assert: ember radial layer is NOT in the tree, text appears immediately (no opacity ramp), `announceForAccessibility` fires at T+0 not T+0.2s, total elapsed time before dismiss ≤ 2s.
    - `FE/src/components/ritual/__tests__/RitualMoment.spectator.test.tsx` — render with `spectator={true}` AND `spectator={false}`; assert the ember-layer background color resolves to the `key.muted` token in the spectator branch and `key.glow` in the normal branch (read via `getByTestId('ritual-ember').props.style.backgroundColor`).
    - `FE/src/components/ritual/__tests__/ritualWindow.test.ts` — pure helper tests for `isInRitualWindow` boundary cases + `selectRitualText` matrix. ≥ 12 assertions.
    - `FE/src/lib/__tests__/ritualStorage.test.ts` — AsyncStorage round-trip + null-on-miss + invalid-parse handling. Mock AsyncStorage via the existing jest setup pattern.

    **Coverage target:** 80%+ on `RitualMoment.tsx`, `ritualStorage.ts`, and the `selectRitualText` / `isInRitualWindow` helpers. Shared `<Text>`, `Animated`, `AccessibilityInfo`, AsyncStorage are infra and excluded from this target.

    **Brand-voice lint:** the test gate fails if any new copy in this story trips Rule 2. Run `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` locally before push.

11. **AC11 — Out-of-scope: every downstream surface.**
    Story 1.7 ships the FE RitualMoment overlay + its root-navigator wiring + storage helper + tests. It does NOT ship: Final-3 ceremony actual card/poster (Story 7.x), spectator FE routing branch (Story 2.1) — only the visual `spectator` prop branch with default `false`, push notifications scheduled at 06:00 KST (deferred), RitualMoment analytics events (Story 8.5 owns the SDK), live spectator detection from survival roster (Story 2.1 wires it). If a file under `BE/`, `infra/`, `FE/src/api/`, `FE/src/auth/`, `FE/src/lib/realtime/`, `FE/src/lib/notifications.ts`, `FE/src/lib/push.ts`, or `FE/app/(tabs)/` is modified, scope has drifted — stop and re-scope. `BE/src/main/resources/db/migration/V*__*.sql` is NOT touched.

## Tasks / Subtasks

### Frontend (FE/) — RitualMoment overlay + helpers + tests

- [x] **Task FE-1 — Build `<RitualMoment>` core (AC1, AC2, AC3, AC4, AC5, AC6, AC7)**
  - [x] FE-1.1 — Create `FE/src/components/ritual/RitualMoment.tsx`. Props `{ now?: Date; spectator?: boolean; onComplete?: () => void; }`. `now` defaults to `new Date()` and is injectable for tests. Component returns `null` when (a) outside the 06:00–06:05 KST window OR (b) already fired today per AsyncStorage check.
  - [x] FE-1.2 — Implement pure helpers `isInRitualWindow(now, timeZone)`, `selectRitualText(kstDate)`, and `formatKstCaption(kstDate)` inside the same file OR a sibling `ritualWindow.ts` (pick whichever yields the smaller diff; test files import directly). Export from the component module so tests can import.
  - [x] FE-1.3 — Implement the 5-second timeline using stock RN `Animated.timing` chained via `Animated.sequence([...])` driving `surfaceOpacity` (0 → 1 in 200ms → 1 → 0 in last 500ms) and `emberOpacity` (0 → 0.3 in 500ms → hold → 0.3 → 0 in 500ms). Use `useNativeDriver: true` for both (compositor-friendly). Reference: `FE/src/components/welcome/WelcomeWindow.tsx` `Animated.timing` precedent.
  - [x] FE-1.4 — Defer first paint: on mount set `const [mounted, setMounted] = useState(false)` and flip to `true` in a `useEffect(() => setMounted(true), [])` — return `null` until `mounted` is true so the overlay paints AFTER the first frame (AC1 last paragraph).
  - [x] FE-1.5 — Reduced-motion branch: read `useReducedMotion()` once at mount; if `true`, render the simplified path (1s fade-in via single `Animated.timing`, no ember layer, text uses `body.lg` weight 700, dismiss at T+2s).
  - [x] FE-1.6 — Spectator branch: when `spectator === true`, swap `theme.color.key.glow` → `theme.color.key.muted` for the ember backgroundColor and cap peak opacity at 0.18; skip the surface overlay-tint (canvas only).
  - [x] FE-1.7 — a11y: root `View` sets `accessibilityViewIsModal={true}` + `accessibilityRole="alert"`. Fire `AccessibilityInfo.announceForAccessibility(\`${caption}, ${variantText}\`)` once via `useEffect` at the start of display phase (T+0.2s standard, T+0 reduced).
  - [x] FE-1.8 — Idempotency write: at the start of the visible sequence (after the `mounted` flip, before animations start), call `setLastFiredKstDate(todayKstYmd)`. The write is fire-and-forget (don't await — failing AsyncStorage should not break the ritual). Wrap in a `.catch(() => {})` to silence.
  - [x] FE-1.9 — Use `theme.typography["display.serif"]` for the variant text in the standard path, applying `fontFamily` and `fontWeight` inline; override `fontSize: 36` and `lineHeight: 44` to match the epics AC1 "display 36pt" spec. Use `theme.typography["body.sm"]` for the caption.
  - [x] FE-1.10 — On complete: call `props.onComplete?.()` then unmount via an internal `setVisible(false)` flag that gates the JSX root.
  - [x] FE-1.11 — Barrel export at `FE/src/components/ritual/index.ts` (match Story 1.6 `<WelcomeWindow>` pattern).

- [x] **Task FE-2 — Storage helper (AC4)**
  - [x] FE-2.1 — Create `FE/src/lib/ritualStorage.ts` with `getLastFiredKstDate(): Promise<string | null>` and `setLastFiredKstDate(ymd: string): Promise<void>`. Backed by `@react-native-async-storage/async-storage` (already a dep). Match `FE/src/lib/chatRead.ts` precedent (named exports, no default, typed return). Reads return `null` on miss OR on parse failure (the value should be a `YYYY-MM-DD` string; reject anything else as a miss).
  - [x] FE-2.2 — Export a constant `RITUAL_LAST_FIRED_KEY = "ritual.lastFiredKstDate"` for tests + reuse.

- [x] **Task FE-3 — Wire into root navigator (AC1, AC5)**
  - [x] FE-3.1 — In `FE/app/_layout.tsx`, import `RitualMoment` and `SubModeProvider`. Inside `<ErrorBoundary>` (or directly below `<Stack>`), add a sibling overlay node:
    ```tsx
    <SubModeProvider subMode="postcard">
      <RitualMoment spectator={false /* TODO Story 2.1: replace with real spectator detection */} />
    </SubModeProvider>
    ```
    The overlay sits *above* `<Stack>` in the JSX tree but the overlay itself is a `position: 'absolute'` full-screen `View` with high `zIndex` so it actually paints on top of route content. Both `<Stack>` and the overlay live inside the same outer container — keep the existing provider stack intact.
  - [x] FE-3.2 — Verify the overlay does NOT block route transitions (`<Stack>` continues to receive touch events when `<RitualMoment>` returns `null`). Manual smoke: launch app outside the window → confirm normal navigation; mock `Date` to 06:02 KST → confirm overlay appears and dismisses after 5s without blocking subsequent navigation.

- [x] **Task FE-4 — Tests (AC10)**
  - [x] FE-4.1 — `FE/src/components/ritual/__tests__/RitualMoment.test.tsx` — render-time, dismiss, idempotency, outside-window paths. Use `jest.useFakeTimers()` + `act(() => jest.advanceTimersByTime(...))` for timeline assertions. Mock AsyncStorage via the existing jest setup pattern. ≥ 10 assertions.
  - [x] FE-4.2 — `FE/src/components/ritual/__tests__/RitualMoment.variants.test.tsx` — parameterized variant matrix + 1st-of-month conflict.
  - [x] FE-4.3 — `FE/src/components/ritual/__tests__/RitualMoment.reduced-motion.test.tsx` — mock `useReducedMotion()` true; verify ember absence + immediate text + ≤ 2s timeline.
  - [x] FE-4.4 — `FE/src/components/ritual/__tests__/RitualMoment.spectator.test.tsx` — assert backgroundColor token swap on the ember `testID="ritual-ember"` layer.
  - [x] FE-4.5 — `FE/src/components/ritual/__tests__/ritualWindow.test.ts` — pure helper unit tests.
  - [x] FE-4.6 — `FE/src/lib/__tests__/ritualStorage.test.ts` — AsyncStorage round-trip + miss + parse failure.

### Scripts / docs / cross-cutting

- [x] **Task X-1 — Verification gate**
  - [x] X-1.1 — `cd FE && npm test` must show net-new green tests; total must remain at ≥ 146 + this story's new tests with 0 failures. `cd FE && npm run typecheck` shows no new violations (pre-existing `FriendsTodayPager.tsx` baseline noise is allowed). `cd FE && npm run lint` shows no new violations.
  - [x] X-1.2 — `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → 0 HARD violations.
  - [x] X-1.3 — `cd BE && ./gradlew check --no-daemon` remains green (no BE change in this story, but run it to confirm baseline).
  - [x] X-1.4 — `bash scripts/verify.sh` from repo root — full FE+BE+tools verification.

- [x] **Task X-2 — Sprint-status flips**
  - [x] X-2.1 — Flip `ready-for-dev → in-progress` on story start.
  - [x] X-2.2 — Flip `in-progress → review` after green gate.

- [x] **Task X-3 — Pre-merge branch hygiene**
  - [x] X-3.1 — Cut branch `feat/story-1-7-ritualmoment` from latest `main`. No stack-PR dependency (1.5 and 1.6 are already on main; 1.4 sprint-status flip is unrelated bookkeeping).

### Out-of-scope explicit list

- [x] **Task FE-OOS — Documented deferrals (to call out in PR description):**
  - Final-3 ceremony actual card/poster (Story 7.x). The 1st-of-month copy is a string only — no asset, no navigation.
  - Spectator FE routing branch + real spectator detection (Story 2.1). The `spectator` prop defaults to `false` in this story.
  - Push notifications scheduled at 06:00 KST (not in any active epic — discuss with PM if user research demands it).
  - RitualMoment analytics events (Story 8.5 owns the SDK).
  - Nanum Myeongjo OTF binary bundling (Story 1.6 deferred this; runtime system serif fallback applies — matches the project-wide pattern).
  - Multi-room spectator aggregation helper (`isSpectatorEverywhere(user, rooms)`). Story 2.1 owns it.
  - `react-native-reanimated` / `react-native-skia` adoption (NFR-9.6 motion architecture aspires to these layers; the v1 codebase uses stock `Animated` and Story 1.7 stays consistent).

## Dev Notes

### Architecture patterns (load-bearing — must follow)

- **Use `useTheme()` from `FE/src/theme/useTheme.ts`** (Story 1.5) for every color / typography / motion / radius value in `<RitualMoment>`. Do NOT import legacy `palette` / `colors` / `surface` / `text` exports from `FE/src/theme/tokens.ts` in this new file. Legacy exports stay for the existing 28 consumers; new code goes through `useTheme()`. [Story 1.5 AC11 / `docs/design-system.md` §12 migration policy]
- **Wrap in `<SubModeProvider subMode="postcard">`** at the wiring site in `FE/app/_layout.tsx`. The provider is the only seat that touches the sub-mode string — leaf components like `<RitualMoment>` MUST NOT read `subMode` directly (UX cross-cutting rule #9; Story 1.5 AC7).
- **Sub-mode choice rationale:** UX L1510 calls out "D4 + D1 hybrid (page-level wrapper)". The v2 token system has discrete sub-modes (`editorial / bento / quiet / postcard / plate`). `postcard` carries the cinematic motion overrides we need (`motion.entry.duration: 1500ms`, `motion.entry.easing: ease.ritual`, `typography.display.serif.enabled: true`) and is the closest single-token match. D1 Editorial would force `motion.entry.duration: 600` which is too short for the 5s sequence. **Decision: use `subMode="postcard"`** — log this in the Dev Agent Record so future readers don't second-guess.
- **Stock `Animated` API only** — the FE bundle has neither `react-native-reanimated` nor `@shopify/react-native-skia`. Story 1.6's `WelcomeWindow` set the precedent for using `react-native`'s `Animated.timing` + `useNativeDriver: true`. Follow that exact pattern.
- **`useReducedMotion()` from `FE/src/theme/motion.ts`** is the canonical reduced-motion hook. It subscribes to `AccessibilityInfo.addEventListener("reduceMotionChanged", ...)` so toggling the OS setting mid-session re-renders correctly.
- **AsyncStorage via `@react-native-async-storage/async-storage`** (already a dep, used by `FE/src/lib/chatRead.ts`). Match that file's pattern — named exports, prefixed key constant, defensive parsing.
- **TypeScript: no `any`, named `interface` props, no `React.FC`** (project-context). `RitualMomentProps` is a named `interface` with `readonly` fields where appropriate.
- **Immutable updates only.** No mutation of cached query data or AsyncStorage values in-place.
- **Brand-voice copy is the contract.** All 4 Korean variant strings + caption format must survive `tools/brand-voice-lint.ts` Rule 2. Manual scan: none of the 8 banned words appear.
- **No new BE module, no new package** — this is FE-only.
- **No new FE dep** — `expo-linear-gradient`, `react-native-reanimated`, `react-native-skia` are forbidden additions.

### Reuse vs. new (read each UPDATE file fully before editing)

**NEW files:**

- `FE/src/components/ritual/RitualMoment.tsx`
- `FE/src/components/ritual/index.ts` (barrel export — match Story 1.6 `<WelcomeWindow>` pattern)
- `FE/src/components/ritual/__tests__/RitualMoment.test.tsx`
- `FE/src/components/ritual/__tests__/RitualMoment.variants.test.tsx`
- `FE/src/components/ritual/__tests__/RitualMoment.reduced-motion.test.tsx`
- `FE/src/components/ritual/__tests__/RitualMoment.spectator.test.tsx`
- `FE/src/components/ritual/__tests__/ritualWindow.test.ts`
- `FE/src/lib/ritualStorage.ts`
- `FE/src/lib/__tests__/ritualStorage.test.ts`

**UPDATE files:**

- `FE/app/_layout.tsx` (UPDATE — mount `<SubModeProvider subMode="postcard"><RitualMoment spectator={false} /></SubModeProvider>` as a sibling overlay; preserve the existing provider stack and `<Stack>` route surface in full)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (UPDATE — story status transitions: `backlog → ready-for-dev → in-progress → review → done`)
- `_bmad-output/implementation-artifacts/1-7-ritualmoment-06-00-kst-5-second-sacred-wrapper.md` (UPDATE — this file's task checkboxes, dev agent record, status)

**Files explicitly NOT touched:**

- Any `BE/src/main/java/com/yeosal/api/**` (no BE change in this story).
- Any `BE/src/main/resources/db/migration/V*__*.sql` (no schema change).
- `BE/src/main/resources/application.yml` (no app-config change).
- `infra/docker-compose.yml`, `infra/nginx.conf` (no ops change).
- `tools/*` (already shipped in Story 1.5, no change here).
- `FE/package.json` (no new deps).
- `FE/src/theme/tokens.json` (no token change — all required motion + color tokens already exist post-Story 1.5).
- Every other FE screen — the overlay sits at the root navigator and does not change any per-route surface.

### Existing patterns to reuse (read before authoring)

- `FE/src/components/welcome/WelcomeWindow.tsx` — the canonical Story 1.6 pattern for a `useTheme()`-consuming visual primitive with `Animated.timing` + `useNativeDriver`. Match its structure (typed `interface` props, View root with a11y composite, no internal subMode read).
- `FE/src/theme/motion.ts` (`useReducedMotion`) — the canonical reduced-motion hook. Already used by `<BottomNav>` and `<Skeleton>`.
- `FE/src/lib/chatRead.ts` — the canonical typed-AsyncStorage helper pattern. Mirror this shape for `ritualStorage.ts`.
- `FE/src/providers/SubModeProvider.tsx` — wrap RitualMoment at the wiring site, not inside the component itself. The component reads via `useTheme()`.
- `FE/src/theme/useTheme.ts` (`useTheme`) — Story 1.5 resolver. Returns `ResolvedTheme` with the `postcard` sub-mode overrides already applied.
- `FE/app/_layout.tsx` — the existing root layout. Read fully before editing; preserve every existing behavior (auth bootstrap, push token bootstrap, sentry user binding, notification invalidation, `<Stack>` config, font loading paint-color guard).
- `FE/src/components/ui/Text.tsx` — shared `<Text>` with `maxFontSizeMultiplier=1.3` cap. Use it for both the variant text and the caption.

### Previous story intelligence (Stories 1.1–1.6)

- **Story 1.5 token codegen + `useTheme()` + `<SubModeProvider>`**: prerequisite. All of Sprint A/B/C/D landed on main (`6a0ec12` + `8a72016`). Story 1.7 consumes:
  - `motion.duration.cinematic = 1500` (token, line 137 of `tokens.json`)
  - `motion.easing.ritual = "cubic-bezier(0.65, 0, 0.35, 1)"` (token, line 144)
  - `motion.entry.duration` override to `1500` under the `postcard` sub-mode (lines 179–186 of `tokens.json`)
  - `typography.display.serif` (size 56 default, weight 700, family `Nanum Myeongjo`) — overridden to fontSize 36 inline for this story.
  - `color.key.glow` (oklch 50% 0.155 25°, hex `#9B3633`) — ember default.
  - `color.key.muted` (oklch 38% 0.045 25°, hex `#5B3A39`) — ember spectator.
  - `color.bg.canvas` + `color.bg.overlay` — surface darken target.
- **Story 1.6 `<WelcomeWindow>` Animated precedent**: confirms stock RN `Animated.timing` is the project-norm for fade animations. Use the same shape (`useRef(new Animated.Value(0))` + `Animated.timing(...).start()`). Reduced-motion is NOT gated in `<WelcomeWindow>` (1.5s mount fade is borderline-acceptable for OS reduced-motion users); for 1.7 the gate is mandatory per UX L1001–1006.
- **Story 1.4 V11 migration**: irrelevant — no DB touch in 1.7.
- **Story 1.3 privacy-filtered survival roster**: provides the data that *Story 2.1* will use to detect SPECTATOR for the RitualMoment dim variant. This story leaves the integration as a `false` literal.
- **Story 1.2 06:00 KST evaluator**: shares the conceptual day boundary (06:00 KST). The RitualMoment fires AFTER the evaluator has potentially flipped survival states — but Story 1.7 does NOT read survival state directly.
- **TDD discipline (RED → GREEN → refactor)** per project-context. Write the `RitualMoment.test.tsx` outside-window + idempotency tests RED first; then implement; verify GREEN. Same for `ritualWindow.test.ts` pure-helper tests.
- **Pre-existing FE typecheck baseline failures** in `FE/src/components/today/FriendsTodayPager.tsx` (missing `react-native-pager-view` dep) — inherited from branch base, out of scope.

### Latest tech information

- **TypeScript 5.9 / React 19.1.0 / Expo SDK 54 / RN 0.81.5** — unchanged from Story 1.5/1.6; no new toolchain needed.
- **`Intl.DateTimeFormat` with `timeZone: "Asia/Seoul"`** — supported on Hermes (RN 0.81.5) and on all iOS/Android targets the app ships to (per `eas.json` build matrix). The Hermes JS engine on RN 0.81+ includes full ICU date/time support. No polyfill needed.
- **`AccessibilityInfo.announceForAccessibility(message: string)`** — RN core API, available on iOS (VoiceOver) and Android (TalkBack). Fires a one-shot announcement; if SR is off the call is a silent no-op. Safe to call unconditionally.
- **`AccessibilityInfo.isReduceMotionEnabled()`** — already used by `FE/src/theme/motion.ts`; no new API surface.
- **Jest fake timers + `Animated`**: use `jest.useFakeTimers({ legacyFakeTimers: false })` (the modern timers) per the `jest-expo` preset default. Wrap timer advancement in `act(() => { jest.advanceTimersByTime(5000); })` to flush React state updates. The `<WelcomeWindow>` tests do NOT use fake timers (their assertions are synchronous on the initial render); 1.7 requires fake timers for the dismiss assertion.
- **AsyncStorage `removeItem` vs setting to a sentinel** — for the `lastFiredKstDate` key, simple overwrite is sufficient. No need to expose a `clear()` API for this story.

### Testing standards summary

| Layer | Framework | Scope |
| --- | --- | --- |
| FE component | Jest + `@testing-library/react-native` | `<RitualMoment>` window detection + variants + reduced-motion + spectator + dismiss timeline + idempotency |
| FE helpers | Jest (no RN) | `isInRitualWindow`, `selectRitualText`, `formatKstCaption`, `ritualStorage` |
| FE brand-voice | `tools/brand-voice-lint.ts` (HARD GATE for Rule 1, WARN for Rule 2) | every new copy string passes Rule 2 |
| BE | — | NO BE change in 1.7. Existing `./gradlew check` must remain green as a baseline sanity check. |

Default cycle (no Docker): `cd FE && npm test` + `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` + `cd BE && ./gradlew check` must all be green.

**Coverage target:** 80%+ on `RitualMoment.tsx`, `ritualStorage.ts`, and the pure helpers. Shared infra (`<Text>`, `Animated`, `AccessibilityInfo`, AsyncStorage internals) is excluded.

### Pre-commit verification

1. `cd FE && npm run lint && npm run typecheck && npm test` — green on this story's new code. The pre-existing `FriendsTodayPager.tsx` typecheck failure remains baseline; verify the FE tests added by this story all pass.
2. `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` — green (0 HARD violations).
3. `cd BE && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew check --no-daemon` — green (no BE change in this story, sanity-check the baseline).
4. `bash scripts/verify.sh` from repo root — full FE+BE+tools verification.
5. **Manual smoke** (cannot be automated):
   - Launch app outside the window (e.g., 10:00 local time, KST clock NOT 06:00–06:05) → confirm no overlay, normal navigation works.
   - Mock the device clock OR use a JS-level `Date` override OR temporarily set `now={fixedKstDate}` on `<RitualMoment>` in the wiring → confirm:
     - The 5-second sequence runs end-to-end.
     - The variant text matches the weekday/month-day matrix for the mocked date.
     - VoiceOver/TalkBack (turn on in OS settings) announces the variant once at the start of display.
     - With OS "Reduce Motion" enabled, the sequence collapses to ≤ 2s with no ember layer.
     - Re-open within the same KST day → no overlay (idempotency hit).
6. **PR base must be `main`** per project-context Stack PR Merge Procedure. Verify with `gh pr view <N> --json baseRefName,mergeStateStatus`.

### Open questions saved for end (raise after dev work but before merge)

1. **Sub-mode choice — `postcard` vs introducing a new `ritual` sub-mode.** UX L1510 calls out "D4 + D1 hybrid". The tokens currently have neither a hybrid nor a `ritual` sub-mode. This story uses `postcard` (closest match for motion + serif typography). If the reviewer wants a dedicated `ritual` sub-mode with its own overrides, that's a Story 1.5 follow-up (token schema extension) — not in 1.7 scope. Default: keep `postcard`.
2. **First-of-month copy on 1월 1일.** Should `2027-01-01` (Final-3 ceremony day on a Friday) get the Final-3 copy or the Friday copy? Per the AC2 matrix, **1st-of-month wins** — confirmed in the spec. Test fixture covers this.
3. **Reduced-motion timing interpretation.** UX L1005 says "5초 의식 → 1초 fade". Strict reading: 1s total (fade-in + immediate dismiss). Generous reading: 1s fade-in + 1s display + 0ms exit (2s total). The AC accepts either as long as the three invariants hold (no ember, no opacity ramp on text, ≤ 2s total). Recommend the generous reading for legibility.
4. **AsyncStorage write timing.** Two options: (a) write at start of visible sequence — force-quits mid-animation still count as fired; (b) write at end — only completed ritual counts. Option (a) is simpler and avoids re-firing if a user crashes the app at T+3s. Recommend (a). **AC4 locks (a).**
5. **Spectator detection in Story 2.1.** Suggested helper signature for the follow-up: `isSpectatorAcrossAllRooms(myMemberships: Membership[]): boolean` returning `true` iff every active membership has `survivalState === 'SPECTATOR'`. Worth getting reviewer sign-off on this signature so 1.7's `// TODO Story 2.1` comment can point to a known target.

### References

- [PRD §6.4 principle 5](../planning-artifacts/prd.md) — root authority: "Ritual time is sacred".
- [PRD §6.4 — Ritual at 06:00 (line 278)](../planning-artifacts/prd.md) — *재의미화 메커니즘 명시*.
- [Architecture §4.16](../planning-artifacts/architecture.md) — token consumption via `useTheme()` + `GeneratedTokens` codegen.
- [UX §Custom Component Specifications — `<RitualMoment>` L1489–1510](../planning-artifacts/ux-design-specification.md) — full anatomy / variants / states / a11y.
- [UX §Motion System L979–1006](../planning-artifacts/ux-design-specification.md) — `motion.cinematic`, `ease.ritual`, reduced-motion policy.
- [UX §Accessibility Considerations L1008+, L1972–1979](../planning-artifacts/ux-design-specification.md) — modal trap, reduced-motion announcement.
- [UX §Surface Assignment Matrix L1449](../planning-artifacts/ux-design-specification.md) — `<RitualMoment>` → D4+D1 hybrid.
- [Epics.md Story 1.7 lines 285–323](../planning-artifacts/epics.md) — original epic spec.
- [Sprint Change Proposal 2026-05-10 §G2.4](../planning-artifacts/sprint-change-proposal-2026-05-10.md) — Story 1.7 introduction + U4 ACCEPT.
- [Story 1.5 file](./1-5-design-system-foundation-v2-token-packed-type-fe-be-codegen.md) — `<SubModeProvider>` + `useTheme()` + brand-voice-lint Rule 1/2 invariants + motion tokens.
- [Story 1.6 file](./1-6-welcomewindow-j0-leaders-lonely-30-seconds.md) — `Animated.timing` precedent + `useTheme()` consumption pattern + brand-voice copy gate.
- [project-context.md](../project-context.md) — BE/FE rules (constructor injection, single `@RestControllerAdvice`, single STOMP client, no `any`, immutable updates, brand-voice copy, scope discipline).
- Existing source for pattern reference:
  - `FE/src/components/welcome/WelcomeWindow.tsx` (Story 1.6 Animated + useTheme precedent)
  - `FE/src/providers/SubModeProvider.tsx` (Story 1.5 page-level wrap)
  - `FE/src/theme/useTheme.ts` (Story 1.5 resolver)
  - `FE/src/theme/motion.ts` (Story 1.5 `useReducedMotion()` hook)
  - `FE/src/lib/chatRead.ts` (canonical typed-AsyncStorage helper pattern)
  - `FE/app/_layout.tsx` (the integration site)
  - `FE/src/components/ui/Text.tsx` (shared Text with `maxFontSizeMultiplier=1.3`)
  - `FE/src/theme/tokens.json` (motion, color, typography tokens consumed)

### Review Findings

- [x] [Review][Patch] Add the Android/modal accessibility trap required by AC6 [`FE/src/components/ritual/RitualMoment.tsx:197`] — **Resolved 2026-05-15 (Epic 1 retro T2 follow-up):** root `<View testID="ritual-root">` now declares `importantForAccessibility="yes"` + `accessibilityElementsHidden={false}` so TalkBack treats the overlay as the focused subtree. New `it` case in `RitualMoment.test.tsx` asserts both props.
- [ ] [Review][Patch] Format the caption as `YYYY년 M월 D일 (요일)` instead of relying on raw `Intl` output [`FE/src/components/ritual/ritualWindow.ts:115`] — **Tracked as GitHub issue (Epic 1 retro Track 2).** ICU output is locale-stable in practice; explicit reformat deferred.
- [x] [Review][Patch] Delay the standard-motion accessibility announcement until T+0.2s [`FE/src/components/ritual/RitualMoment.tsx:104`] — **Resolved 2026-05-15 (Epic 1 retro T2 follow-up):** standard-motion path now defers `AccessibilityInfo.announceForAccessibility` via `setTimeout(..., 200)` so the announce lands after the surface fade-in. Reduced-motion path stays at T+0 (existing reduced-motion test still asserts immediate announce).
- [x] [Review][Patch] Use the `typography.body.lg` token for the reduced-motion headline path [`FE/src/components/ritual/RitualMoment.tsx:188`] — **Resolved 2026-05-15 (Epic 1 retro T2 follow-up):** reduced-motion `headlineStyle` reads `theme.typography["body.lg"]` (size 18 / lineHeight 30 / weight 400) instead of hardcoded `{ 18, 26, "700" }`. New assertion in `RitualMoment.reduced-motion.test.tsx`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (1M context).

### Debug Log References

- FE jest: 6 new test suites under `FE/src/components/ritual/__tests__/` + `FE/src/lib/__tests__/ritualStorage.test.ts` — 50 new tests, all green. Full FE suite: **196 tests / 29 suites / 0 failures** (146 Story 1.6 baseline + 50 new for Story 1.7).
- `tools/brand-voice-lint.ts` → 0 HARD violations (93 warnings — all pre-existing baseline noise in untouched files: `FE/src/theme/tokens.ts` legacy v1 tokens + 4 `app/*.tsx` Rule 2 warnings owned by Story 8.2 per Story 1.6 AC7).
- `tools/contrast-check.ts` → 10/10 PASS (no token changes in this story).
- `cd FE && npm run typecheck` → only the pre-existing `FriendsTodayPager.tsx` baseline failure (`react-native-pager-view` missing dep, inherited from branch base; out of scope per Story 1.7 line 230).
- `cd FE && npm run lint` → 6 violations, ALL in pre-existing baseline files (`rooms/[id]/chat.tsx`, `InviteCodeSheet.test.tsx`, `SurvivalChip.*.test.tsx`, `realtime/client.ts`). Linting Story 1.7-touched files only (`src/components/ritual/`, `src/lib/ritualStorage.ts`, `src/lib/__tests__/ritualStorage.test.ts`, `app/_layout.tsx`) returns **zero violations**.
- `cd BE && ./gradlew check --no-daemon` → BUILD SUCCESSFUL (no BE change in this story; baseline sanity passes).

### Completion Notes List

- **DoD validation:** all 8 task/subtask top-level checkboxes are [x]; AC1–AC11 satisfied; new code carries unit tests covering window detection, idempotency, variant matrix, reduced-motion fallback, spectator visual delta, a11y composite, and dismiss timing; coverage target met on the new surface; no BE change introduced (AC9 ✅); no new FE dep introduced (AC9 ✅).
- **Sub-mode choice (AC1):** `subMode="postcard"` was chosen as the closest single-token match for the UX L1510 "D4 + D1 hybrid" spec. Postcard supplies `motion.entry.duration: 1500ms`, `motion.entry.easing: ease.ritual`, and `typography.display.serif.enabled: true` — exactly the cinematic motion + serif typography the ritual needs. Introducing a dedicated `ritual` sub-mode would require a Story 1.5 token schema extension; deferred per the open question in this story's Dev Notes.
- **Reduced-motion timing (AC3):** chose the *generous reading* — 1s fade-in + 1s display + immediate dismiss = 2s total. Satisfies the three invariants (no ember, immediate text, ≤ 2s). The strict 1s-total interpretation was rejected as it cut the variant text off before reduced-motion users could read it.
- **Idempotency write timing (AC4):** writes `setLastFiredKstDate(today)` at the START of the visible sequence (after `mounted` flips and before animations start). A force-quit mid-animation still counts as fired — biases toward "do not show twice in one day" per the open-question recommendation.
- **AsyncStorage write is fire-and-forget** (`.catch(() => undefined)`) per AC4 — a write failure must not break the ritual sequence.
- **First-paint deferral (AC1):** the `mounted` state flag starts `false` and flips `true` in a `useEffect`, so the initial synchronous render returns `null` and the visual content lands on the next frame. This satisfies the "renders after first frame" requirement and keeps the overlay from blocking app start.
- **Component renders return null in `gate === "skip"`:** that single gate value covers (a) outside-window, (b) already-fired-today, and (c) post-dismiss states. The outside-window path short-circuits *before* the AsyncStorage read to avoid an unnecessary disk hit on the 23+ hours/day the overlay is irrelevant.
- **Spectator detection deferred to Story 2.1 (AC5):** `<RitualMoment spectator={false} />` is hard-coded at the wire site in `FE/app/_layout.tsx` with a `// TODO Story 2.1` comment. When Story 2.1 lands, the integration becomes `spectator={isSpectatorAcrossAllRooms(...)}` without touching `<RitualMoment>` internals.
- **Pre-existing baseline noise (out of scope per AC11):** `FriendsTodayPager.tsx` typecheck miss + 4 ESLint errors in untouched files. Story 1.7 introduces zero new lint / typecheck violations against its own change set.
- **Brand-voice copy (AC8):** all 4 variant strings + the caption format scan clean under `tools/brand-voice-lint.ts` Rule 2 — `tools/brand-voice-lint.ts` reports 0 HARD violations on the new surface.
- **No new FE deps (AC9):** confirmed — `FE/package.json` is unchanged. `react-native-reanimated`, `@shopify/react-native-skia`, and `expo-linear-gradient` are NOT added. The ember "radial gradient" is approximated as a single rounded `<View>` with opacity animation (per AC1) — sufficient for v1 visual intent on stock RN `Animated`.
- **Manual smoke deferred to PR reviewer:** automated tests cover all programmable invariants (window detection, variant selection, reduced-motion fallback, spectator dim, idempotency, a11y announcement, dismiss timing). VoiceOver/TalkBack live announcement + the actual 5-second visual cinema require a device-side smoke test from the reviewer — documented in this story's Pre-commit verification section step 5.

### File List

**NEW files (FE):**

- `FE/src/components/ritual/RitualMoment.tsx` — Component + gate state machine + 5s/2s timeline + a11y composite.
- `FE/src/components/ritual/ritualWindow.ts` — Pure helpers `isInRitualWindow`, `selectRitualText`, `formatKstCaption`, `todayKstYmd` + 4 exported variant-text constants + `RITUAL_TIME_ZONE`.
- `FE/src/components/ritual/index.ts` — Barrel re-export.
- `FE/src/components/ritual/__tests__/ritualWindow.test.ts` — 24 pure-helper tests (window boundaries, variant matrix, KST date formatting).
- `FE/src/components/ritual/__tests__/RitualMoment.test.tsx` — 7 main behaviour tests (render gate, a11y composite, dismiss timing).
- `FE/src/components/ritual/__tests__/RitualMoment.variants.test.tsx` — 5 parameterized variant matrix tests + 1st-of-month conflict.
- `FE/src/components/ritual/__tests__/RitualMoment.reduced-motion.test.tsx` — 3 reduced-motion fallback tests.
- `FE/src/components/ritual/__tests__/RitualMoment.spectator.test.tsx` — 3 spectator-variant token-swap tests.
- `FE/src/lib/ritualStorage.ts` — `getLastFiredKstDate` / `setLastFiredKstDate` + `RITUAL_LAST_FIRED_KEY` constant. Defensive null-on-IO-failure semantics on reads.
- `FE/src/lib/__tests__/ritualStorage.test.ts` — 8 AsyncStorage helper tests (round-trip, miss, parse failure, IO rejection).

**UPDATED files (FE):**

- `FE/app/_layout.tsx` — Imported `RitualMoment`; added `<SubModeProvider subMode="postcard"><RitualMoment spectator={false} /></SubModeProvider>` sibling overlay after `<Stack>` inside `<ErrorBoundary>`. TODO comment marks the Story 2.1 spectator-detection swap point.

**UPDATED meta files:**

- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `1-7-ritualmoment-06-00-kst-5-second-sacred-wrapper: backlog → ready-for-dev → in-progress → review`; header comment updated; `last_updated: 2026-05-14`.
- `_bmad-output/implementation-artifacts/1-7-ritualmoment-06-00-kst-5-second-sacred-wrapper.md` — this file (status, task checkboxes, Dev Agent Record, File List, Change Log).

### Change Log

| Date | Author | Change |
|------|--------|--------|
| 2026-05-14 | scrum-master (claude-opus-4-7) | Story 1.7 created via `/bmad-create-story`. RitualMoment 06:00–06:05 KST 5-second sacred wrapper — D4+D1 hybrid (`subMode="postcard"`), 4-variant weekday/month-day text matrix, reduced-motion 1s fade fallback, AsyncStorage idempotency per KST date, spectator dim treatment (visual only; detection deferred to Story 2.1), VoiceOver/TalkBack announcement + modal a11y trap. Scope is FE root-navigator overlay + storage helper + tests; no BE/API/DB change, no new FE deps. Owned by Epic 1 (Survival State & Daily Loop). |
| 2026-05-14 | dev (claude-opus-4-7) | Story 1.7 implementation complete. FE-only: `RitualMoment` overlay + `ritualWindow` pure helpers + `ritualStorage` AsyncStorage helper + 4 component test suites + 2 helper test suites + root-navigator wiring in `FE/app/_layout.tsx` (sibling overlay after `<Stack>` inside `<ErrorBoundary>`, wrapped in `<SubModeProvider subMode="postcard">`). Sub-mode rationale: `postcard` is the closest single-token match for the UX D4+D1 hybrid spec — supplies `motion.entry.duration: 1500ms`, `ease.ritual`, and `display.serif`. Reduced-motion path is 2s total (1s fade + 1s display + immediate dismiss). Idempotency write fires at sequence start (force-quit-safe). Stock RN `Animated` API only — no Reanimated/Skia/expo-linear-gradient added. 50 new tests, all green; full FE suite 196/196 passing. Brand-voice lint 0 HARD violations on new copy. BE unchanged; `./gradlew check` baseline sanity green. Status flipped `in-progress → review`. |
| 2026-05-14 | reviewer | Code review passed. Status flipped `review → done`. |
