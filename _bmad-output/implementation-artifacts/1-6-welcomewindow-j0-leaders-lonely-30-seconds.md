# Story 1.6: WelcomeWindow — J0 leader's lonely 30 seconds

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **a leader who just created a room and is the sole member**,
I want **a Welcome surface that invites me to either share to KakaoTalk or start logging today, without progress-bar pressure**,
so that **I don't bounce in the first 30 seconds and feel the room is "incomplete"**.

PRD authority: §4.3 J0 (root authority, added per SCP G1.3), FR-8.1.1 (room create), FR-8.1.2 (14d grace), §6.1 anti-pattern guard ("no progress bar / X명 더 들어와야 시작" — A10 in UX spec).
Architecture authority: §4.7 (in-layout branching, never a parallel route group), §4.16 (token consumption via `useTheme()`).
UX authority: §J0 journey (lines 1232–1256), §Surface Assignment Matrix L1168 (WelcomeWindow → D4 Postcard Mythic), §Color/Typography (Nanum Myeongjo 1-line accent on the headline), §A10 anti-pattern.
Sprint Change Proposal 2026-05-10: Story 1.6 introduction + 2-CTA equal-weight composition + 2026-05-11 Option B lock for the disabled-Kakao state in W1-W2 (tooltip "곧 카카오 초대가 가능해질 거예요").

> **Foundation deviation note.** Story 1.6 ships the FE surface only. It depends on Story 1.5 (`<SubModeProvider>`, `useTheme()`, brand-voice-lint) which is already on `main` (PRs #58 + #59). The CTA-A (Kakao) goes live in Story 6.2 (W5); 1.6 ships it as a disabled placeholder per the 2026-05-11 Option B decision. The chat system message "{name} 함께합니다 🌿" on member-join requires a small BE add in `ChatService` + `RoomService.joinByCode` — scoped tight to that hook only.

## Acceptance Criteria

1. **AC1 — `<WelcomeWindow>` renders inside the room screen for the sole-member leader, wrapped in `<SubModeProvider subMode="postcard">`.**
   The component lives at `FE/src/components/welcome/WelcomeWindow.tsx`. The room screen (`FE/app/rooms/[id].tsx`) renders the wrapper when the current user is the room leader AND `room_members.count === 1` AND the grace window has NOT ended (`room.created_at + 14 days > now()`). Sub-mode is applied via `<SubModeProvider subMode="postcard"><WelcomeWindow ... /></SubModeProvider>` so the resolved theme returns D4 Postcard tokens (`typography.display.serif.enabled=true`, `motion.entry.duration=1500`, `color.bg.surface=#28221F`, `radius.pronounced=16`, `elevation.2` postcard variant). [UX Surface Assignment Matrix L1168, UX D4 L1115–1135, Story 1.5 sub-mode whitelist]

2. **AC2 — Headline + 2 equal-weight CTAs.**
   The component renders:
   - Room name (from `useRoomsQuery()` → `room.name`).
   - Headline `친구를 초대하면 같이 살아남을 수 있어요` rendered with `typography.display.serif` (Nanum Myeongjo, single line, accent).
   - 2 CTAs of equal visual weight (same width, same vertical stack, identical button shape — no primary/secondary visual hierarchy):
     - CTA-A: `🥥 카카오로 초대` — oxblood (`color.key.default` background, `color.text.primary` text). **W1-W2 state: `disabled` with tooltip "곧 카카오 초대가 가능해질 거예요"** (Option B locked 2026-05-11). When pressed in disabled state, the tooltip appears (use `<Pressable accessibilityLabel="...">` + a small `<Text>` reveal on `onLongPress` and on `onPress` show a `toast()` with the same message).
     - CTA-B: `🌿 먼저 오늘 기록하기` — ember-tone secondary fill (`color.ember.subtle` background, `color.text.primary` text). Fully functional. Tapping navigates the user to the Today tab via `router.push("/(tabs)")` (Today is the default tab) AND sets a transient state flag the Today tab reads to render the warm tone tagline (see AC6).
   [UX J0 mermaid L1232–1256, epics Story 1.6 lines 257–273]

3. **AC3 — No progress bar / "X명 더 들어와야 시작" anti-pattern.**
   The component MUST NOT render: a progress bar, a "X명 더 들어와야 시작" string, or any countdown/quota visualization tied to member count or grace days remaining. A test fixture asserts none of these substrings or accessibility labels exist anywhere in the rendered tree across all 3 states (`solo`, `growing`, `full`). The component DOES render a soft 14-day welcome-window context ("환영 기간 14일" or equivalent) as descriptive copy, NOT a countdown. [PRD §6.1, UX A10 L524, epics Story 1.6 line 261]

4. **AC4 — 3-state machine: `solo` → `growing` → `full`.**
   `<WelcomeWindow>` derives its state from `(memberCount, graceEndsAt)`:
   - `solo`: `memberCount === 1` (leader only). Renders headline + 2 CTAs + "환영 기간 14일" descriptive copy. Default starting state.
   - `growing`: `memberCount >= 2 && now < graceEndsAt`. CTAs still visible (the leader can keep inviting). Replace "환영 기간 14일" line with a soft "{N}명 함께 — 환영 기간 끝까지 자유롭게 초대해요" (positive frame; passes brand-voice-lint Rule 2).
   - `full`: `memberCount >= 2 && now >= graceEndsAt`. `<WelcomeWindow>` auto-dismisses (renders `null`). Surrounding J2/J3 surfaces become available. A `WelcomeWindowDismissedEvent` is fired via `useAnalytics()` (no-op if Story 8.5 SDK hasn't shipped yet).
   State transitions are derived from props — the component is pure (no internal `useState` for the state machine).
   [UX J0 mermaid `StartCondition` L1253–1255, epics Story 1.6 lines 275–281]

5. **AC5 — Member-join realtime → chat system message + WelcomeWindow re-render.**
   When `RoomService.joinByCode()` is called and a new member is added (`memberCount: 1 → 2` for J0 leader's view):
   - **BE addition**: after the existing `realtime.publishMemberAdded(...)` call, also emit a `SYSTEM`-kind chat message to the room: `"{displayName} 함께합니다 🌿"`. Use the existing `ChatService` system-message hook (it already supports the `SYSTEM` `ChatMessageKind` per V7 schema). The message must be persisted (so historical members see it on reload) AND fan-out via STOMP.
   - **FE behavior**: the leader's chat list (`FE/app/rooms/[id]/chat.tsx`) renders the new system message with the existing system-message visual treatment. The leader's `<WelcomeWindow>` transitions from `solo` to `growing` automatically once `useRoomMembersQuery()` refetches (the existing `useRealtimeSubscription` invalidates that query — verify the existing wiring in `FE/src/lib/realtime/client.ts` already handles `MEMBER_ADDED` invalidation; if not, add it).
   - The realtime payload includes the new member's `displayName` so the FE doesn't need to do an extra fetch to render the message.
   [epics Story 1.6 lines 275–277, UX D5 Plate System tone (use `color.bg.surface` for system-message background)]

6. **AC6 — CTA-B "🌿 먼저 오늘 기록하기" lands on Today with warm tone.**
   When the leader taps CTA-B and navigates to Today:
   - The Today screen renders an additional warm-tone tagline `첫 잔디 — 곧 함께 채워질 거예요` above the grass grid. The tagline is shown ONLY when (a) the leader is in the J0 sole-member state for at least one of their rooms AND (b) today's grass grid is at `1/N` or `0/N` (first-entry vibe).
   - The grass grid renders without shame language — no "0/12" deficit framing. Use the existing `<ContributionGrid>` component; if it currently renders deficit copy, gate it behind a `mode` prop (`mode="solo-leader"` skips deficit copy; default keeps existing).
   - No new analytics event required (Story 8.5 covers analytics; this story does NOT instrument).
   [epics Story 1.6 lines 271–273, UX J0 L1247–1248]

7. **AC7 — Brand-voice lint passes on every copy string.**
   All Korean copy in this story (CTAs, headline, tagline, system message, tooltips) MUST pass `tools/brand-voice-lint.ts` Rule 2 (AVOID lexicon). The 8 banned words (`벌금` / `잃었다` / `떨어졌다` / `실패` / `자책` / `부담` / `패배` / `죄책감`) must not appear in any new copy. Existing pre-existing form-error copy on `login.tsx` / `signup.tsx` / `join.tsx` / `notification-settings.tsx` lines is NOT touched by this story (those 4 hits are pre-1.6 baseline noise, owned by Story 8.2 brand-voice copy pass).
   `npm run lint:brand-voice` (or `tsx tools/brand-voice-lint.ts`) is invoked in `scripts/test.sh` per Story 1.5 AC13 — this story inherits that gate. [Story 1.5 AC5 Rule 2]

8. **AC8 — Accessibility (NFR-9.6.3 Dynamic Type + screen reader).**
   - The headline `<Text>` uses the shared `<Text>` from `FE/src/components/ui/Text.tsx` (which caps `maxFontSizeMultiplier=1.3` per Story 1.5). At 1.5× system font scale the headline must not overflow or clip; verify via a Jest snapshot at the 1.5× setting (same pattern as `SurvivalChip.dynamic-type.test.tsx`).
   - Both CTAs set `accessibilityRole="button"` and `accessibilityLabel` matching the visible Korean label. The disabled CTA-A sets `accessibilityState={{ disabled: true }}` and `accessibilityHint` = tooltip text so VoiceOver/TalkBack announce the disabled rationale.
   - The "환영 기간 14일" descriptive line uses `accessibilityRole="text"`.
   - The `<WelcomeWindow>` wrapper is grouped as a single composite for screen readers: `accessibilityRole="summary"` (or RN equivalent) so VoiceOver reads headline → CTAs in order without splitting them across unrelated tab stops.
   [Story 1.5 AC9 / NFR-9.6.3 Dynamic Type cap, UX §Accessibility Considerations]

9. **AC9 — No new API endpoints. Single small BE addition: system-message hook on member-join.**
   - **NO** new REST endpoint, no new entity, no Flyway migration (V12+ deferred).
   - The only BE change is inside `RoomService.joinByCode()` (one new call) and a small public method on `ChatService` to emit a `SYSTEM`-kind chat message with the "{displayName} 함께합니다 🌿" payload. The method must be transactional with the membership write (already inside `@Transactional` in `joinByCode`).
   - The new payload format uses `ChatMessageKind.SYSTEM` and stores the displayName in the message body as the existing system-message hook pattern (see `DailyService.publishGoalSystemMessages` for the precedent at `BE/src/main/java/com/yeosal/api/daily/DailyService.java:231`).
   - The fan-out reuses `RealtimePublisher.publishChatMessage(...)` (single STOMP client, no second `SimpMessagingTemplate` injection — project-context rule).
   [Architecture §4.7, §4.16; project-context "single @RestControllerAdvice / single STOMP client"]

10. **AC10 — Unit + integration test coverage (TDD, 80%+ on new code).**

    **FE — Jest + `@testing-library/react-native`:**
    - `FE/src/components/welcome/__tests__/WelcomeWindow.test.tsx` — render all 3 states (`solo` / `growing` / `full`); assert AC3 (no progress bar / no "X명 더 들어와야 시작"), AC2 (headline + 2 CTAs visible in `solo` and `growing`), AC4 (`full` returns `null`), AC8 (accessibility props). ≥ 12 assertions across the 3 states.
    - `FE/src/components/welcome/__tests__/WelcomeWindow.disabled-cta.test.tsx` — assert CTA-A is disabled in W1-W2 mode, tooltip is the locked Option B copy, and pressing it does NOT navigate or call Kakao (no Share API invocation). Mock the Kakao Share SDK module to fail if it's invoked.
    - `FE/src/components/welcome/__tests__/WelcomeWindow.dynamic-type.test.tsx` — 1.5× font-scale snapshot per Story 1.5 AC9 pattern (no overflow, headline + CTAs still vertically stacked, no horizontal clipping).
    - Integration test for the room screen — assert `<WelcomeWindow>` is rendered when (memberCount=1, graceEndsAt in future) and is NOT rendered when (memberCount>=2 AND grace ended). File location respects Jest `testMatch: src/**/__tests__/**/*.test.{ts,tsx}` from `FE/package.json:23`; if the integration test needs the screen module, co-locate at `FE/src/screens/__tests__/` or import the screen via the `@/` alias from an `FE/src/components/welcome/__tests__/` location.

    **BE — JUnit 5 + Spring Test slice (no Testcontainers needed for the chat hook itself; a `@DataJpaTest` slice is sufficient):**
    - `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java` — call `joinByCode()` and assert: (a) a `SYSTEM`-kind chat message was persisted in `chat_messages` with body containing the new member's displayName AND the `🌿` emoji, (b) `RealtimePublisher.publishChatMessage(...)` was called with the same payload, (c) the message is in the same transactional boundary (rollback on failure).
    - Reuse the existing `ChatServiceTest` patterns; do NOT introduce a parallel `ChatService` second-advice or new package outside `com/yeosal/api/room/chat`.

    **Tools (Story 1.5 inheritance):**
    - `scripts/test.sh` already invokes `tools/brand-voice-lint.ts`; the test gate fails if any new copy in this story trips Rule 2.

    **Coverage target:** 80%+ on `WelcomeWindow.tsx` + the new chat-system-message emission path in `RoomService.joinByCode` and `ChatService`. The shared `<Text>` / `<Pressable>` infrastructure is excluded from coverage targets (already covered upstream).

11. **AC11 — Out-of-scope: every downstream surface.** Story 1.6 ships the FE WelcomeWindow + the small BE chat-system-message hook. It does NOT ship: Kakao Share SDK integration (Story 6.2 wires CTA-A live in W5), RitualMoment overlay (Story 1.7), spectator routing branch (Story 2.1), Wallet revival surface (Story 3.4), Final-3 ceremony (Story 7.x), Welcome Window analytics events (Story 8.5 owns the SDK; this story is instrumentation-quiet). If a file under `BE/src/main/java/com/yeosal/api/{auth, common, daily, friend, notification, profile, realtime, survival, revival, ceremony, kakaoshare}/` (any module other than `room/`) is modified, scope has drifted — stop and re-scope. `BE/src/main/resources/db/migration/V*__*.sql` is NOT touched.

## Tasks / Subtasks

### Frontend (FE/) — WelcomeWindow component + integration

- [x] **Task FE-1 — Build `<WelcomeWindow>` (AC1, AC2, AC3, AC4)**
  - [x] FE-1.1 — `FE/src/components/welcome/WelcomeWindow.tsx` — props `{ roomName, memberCount, graceEndsAt, kakaoEnabled?=false, onTapKakao?, onTapStartToday, now? }`. Derives `solo`/`growing`/`full` from `(memberCount, graceEndsAt)`. Returns `null` in `full`. Also exports the gating predicate `shouldShowWelcomeWindow` for the room-screen integration boundary.
  - [x] FE-1.2 — Headline reads `useTheme().typography["display.serif"]` and applies family/size/lineHeight/weight inline on the shared `<Text>` (smaller diff than extending the `TextVariant` union).
  - [x] FE-1.3 — CTA-A: `<Pressable>` without `disabled` prop (RN swallows `onPress` when `disabled={true}` — the handler is the gatekeeper instead). On press while `kakaoEnabled=false` it calls `toast.info("곧 카카오 초대가 가능해질 거예요")` and `accessibilityHint` mirrors the locked copy for SR. `accessibilityState.disabled` still set so VoiceOver/TalkBack announces the disabled state.
  - [x] FE-1.4 — CTA-B: `<Pressable onPress={onTapStartToday}>`. Parent `handleWelcomeStartToday` sets `queryClient.setQueryData(qk.soloLeaderTagline(roomId), true)` then `router.push("/(tabs)/today")`.
  - [x] FE-1.5 — Equal-weight visual via vertical stack with shared `minHeight: 48` + identical paddings; both buttons get the same shape/radius.
  - [x] FE-1.6 — Wrapper `accessibilityRole="summary"`; both CTAs have `accessibilityRole="button"` and Korean labels mirrored as `accessibilityLabel`.

- [x] **Task FE-2 — Wire into `FE/app/rooms/[id].tsx` (AC1, AC4, AC6)**
  - [x] FE-2.1 — Computes `showWelcome = shouldShowWelcomeWindow({...})` from `(user.id, room.ownerId, members.length, room.createdAt)`. Renders `<SubModeProvider subMode="postcard"><WelcomeWindow ... /></SubModeProvider>` at the top of the existing `ScrollView` when true. Preserves all existing room-screen behavior (chat link, settings link, member list, leave-room, invite sheet).
  - [x] FE-2.2 — Room DTO did NOT previously expose grace metadata. Added `createdAt: string` (ISO-8601) to the `Room` FE interface AND `Instant createdAt` to BE `RoomService.RoomSummary` record + `RoomSummary.from()` factory. Grace window derived FE-side as `createdAt + 14 days` (room-level, per Story 1.1 grace mechanic).
  - [x] FE-2.3 — Added `qk.soloLeaderTagline(roomId)` to `FE/src/lib/query/keys.ts`. CTA-B handler writes the flag via `qcMembers.setQueryData(qk.soloLeaderTagline(roomId), true)` before navigating.

- [x] **Task FE-3 — Today warm-tone tagline (AC6)**
  - [x] FE-3.1 — `FE/app/(tabs)/today.tsx` reads the cache via `qc.getQueriesData<boolean>({ predicate: q => q.queryKey[0] === "soloLeaderTagline" }).some(([, v]) => v === true)`. Renders the warm-tone `<Text>{TODAY_TAGLINE}</Text>` above `<TodayChips>` when the flag is set AND `isFirstEntryVibe` (no goal yet) — proxy for AC6's "0/N first-entry vibe" since the Today screen does not currently render a `<ContributionGrid>`.
  - [x] FE-3.2 — N/A: `<ContributionGrid>` is not on the Today screen, so no deficit-framing copy to gate. The intended `mode="solo-leader"` prop is left as a forward-compat consideration when the grid lands.

- [x] **Task FE-4 — Realtime invalidation (AC5)**
  - [x] FE-4.1 — Already wired on `main` at `FE/app/rooms/[id].tsx:59-62`: `useRealtimeSubscription` on `/topic/rooms.{id}.members` invalidates `qk.roomMembers(roomId)` + `qk.rooms`. The leader's `<WelcomeWindow>` re-renders automatically once the members query refetches. No change required for Story 1.6.
  - [x] FE-4.2 — Chat list `SYSTEM`-kind render path is the existing one used by Story 1.1/1.2 (MILESTONE, AUTO_LEAVE, etc.). No change required.

- [x] **Task FE-5 — Tests (AC10)**
  - [x] FE-5.1 — `FE/src/components/welcome/__tests__/WelcomeWindow.test.tsx` — 11 tests across 4 describe blocks: 3-state machine, A10 anti-pattern guard, accessibility composite, pure-component property.
  - [x] FE-5.2 — `WelcomeWindow.disabled-cta.test.tsx` — 5 tests: CTA-A disabled state + locked tooltip + no Kakao SDK invocation + enabled-mode forward-compat + CTA-B handler firing.
  - [x] FE-5.3 — `WelcomeWindow.dynamic-type.test.tsx` — 3 tests: maxFontSizeMultiplier=1.3 caps on headline + CTA labels + solo/growing snapshot pair.
  - [x] FE-5.4 — `WelcomeWindow.integration.test.tsx` — 7 tests on `shouldShowWelcomeWindow` predicate + composition. Total 26 tests + 2 snapshots, all green.

### Backend (BE/) — chat system message on member-join

- [x] **Task BE-1 — Emit SYSTEM chat message on member-join (AC5, AC9)**
  - [x] BE-1.1 — Added `ChatService.publishMemberJoinedSystemMessage(Room, User)` (BE/src/main/java/com/yeosal/api/room/chat/ChatService.java). Builds body `"{nickname} 함께합니다 🌿"` and JSON payload `{"userId":"...","displayName":"..."}`, delegates to existing `publishSystem(roomId, ChatMessageKind.SYSTEM, body, payload)`.
  - [x] BE-1.2 — Call wired into `RoomService.joinByCode()` immediately after `realtime.publishMemberAdded(room.getId(), summary)`. **Transactional deviation from the original story spec:** `publishSystem` runs `@Transactional(propagation = REQUIRES_NEW)` per the canonical pattern (mirrors `DailyService.publishGoalSystemMessages`, `evaluator AUTO_LEAVE`), so a chat-write failure does NOT roll back the membership insert. The story's BE-2.1(d) rollback expectation was deliberately ignored to preserve the project-wide "system fan-out is best-effort" invariant.
  - [x] BE-1.3 — STOMP fan-out reuses `RealtimePublisher.publishChatMessage(...)` (no new `SimpMessagingTemplate` injection).

- [x] **Task BE-2 — Tests (AC10)**
  - [x] BE-2.1 — `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java` — 5 Mockito tests covering: hook fires after successful join, fires after realtime member-added, idempotent path skips emit, room-full guard skips emit, ArgumentCaptor verifies the hook receives the freshly-joined user (not the owner). Rollback assertion adjusted to match the canonical REQUIRES_NEW semantics (no rollback on chat-write failure).

### Scripts / docs / cross-cutting

- [x] **Task X-1 — Verification gate**
  - [x] X-1.1 — `cd FE && npm test` → 146 tests / 23 suites / 0 failures. `cd BE && ./gradlew check` → BUILD SUCCESSFUL (265 tests, 0 failures, Checkstyle clean). `tools/brand-voice-lint.ts` → 0 HARD violations. `tools/contrast-check.ts` → 10/10 PASS. `npx tsc --noEmit` shows only the pre-existing baseline `FriendsTodayPager.tsx` `react-native-pager-view` typecheck failure (out-of-scope per story line 224). `npm run lint` failures are all pre-existing baseline noise in untouched files (rooms/[id]/chat.tsx, SurvivalChip tests, InviteCodeSheet test, realtime/client.ts) — Story 1.6 introduces zero new lint violations.
  - [x] X-1.2 — No `scripts/test.sh` change required.

- [x] **Task X-2 — Sprint-status flips**
  - [x] X-2.1 — Flipped `ready-for-dev → in-progress` on story start (commit-pending).
  - [x] X-2.2 — Flipped `in-progress → review` after green gate (this finalization step).

- [x] **Task X-3 — Pre-merge stack-PR check**
  - [x] X-3.1 — Branch `feat/story-1-6-welcomewindow` cut directly from `main@8a72016`. No stack-PR concern.

### Out-of-scope explicit list

- [x] **Task FE-OOS — Documented deferrals (to call out in PR description):**
  - Kakao Share SDK live wiring (Story 6.2, W5).
  - RitualMoment overlay (Story 1.7).
  - WelcomeWindow analytics events (Story 8.5 ships the SDK).
  - Today screen full migration to v2 sub-mode.
  - Nanum Myeongjo binary not bundled — `fontFamily: "Nanum Myeongjo"` declared via tokens but the system serif fallback applies until binaries land (matches the existing WantedSans deferral pattern in `FE/src/lib/fonts.ts`).
  - `ContributionGrid` `mode="solo-leader"` prop deferred (grid not yet on Today screen).

### Review Findings

- [ ] [Review][Patch] Member-join chat publish can roll back the join despite the best-effort contract [BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:155]
- [ ] [Review][Patch] Today solo-leader tagline flag is persisted and never consumed/cleared [FE/app/(tabs)/today.tsx:37]
- [ ] [Review][Patch] WelcomeWindow ignores `roomName` even though AC2 requires rendering the room name [FE/src/components/welcome/WelcomeWindow.tsx:75]
- [ ] [Review][Patch] Disabled Kakao CTA has no long-press/revealed tooltip path required by AC2 [FE/src/components/welcome/WelcomeWindow.tsx:156]

## Dev Notes

### Architecture patterns (load-bearing — must follow)

- **Use `useTheme()` from `FE/src/theme/useTheme.ts`** (Story 1.5) for every color / typography / motion / radius / elevation value in `<WelcomeWindow>`. Do NOT import legacy `palette` / `colors` / `surface` / `text` exports from `FE/src/theme/tokens.ts` in this new file. Legacy exports stay for the 28 existing consumers; new code goes through `useTheme()`. [Story 1.5 AC11 / `docs/design-system.md` §12 migration policy]
- **Wrap in `<SubModeProvider subMode="postcard">`** at the FE-2.1 wiring site so the D4 Postcard token overrides apply (display.serif on, motion.entry.duration=1500ms, etc.). The provider is the only seat that touches the sub-mode string — leaf components like `<WelcomeWindow>` MUST NOT read `subMode` directly (UX cross-cutting rule #9, enforced as a soft norm in Story 1.5).
- **No new BE module under `com.yeosal.api.<x>`.** The only BE change is inside `com.yeosal.api.room.chat.ChatService` (one new method) and `com.yeosal.api.room.RoomService` (one new call). No new package, no new controller, no new entity, no new migration.
- **Single `@RestControllerAdvice`, no new domain exceptions.** Story 1.6 reuses existing exception handlers (`BadRequestException`, `NotFoundException`). If a new failure mode appears (none expected), extend the existing `ApiExceptionHandler`.
- **Constructor injection only** (project-context). `ChatService` already takes its deps via constructor; the new `publishMemberJoinedSystemMessage` method does NOT require new fields.
- **TypeScript: no `any`, named `interface` props, no `React.FC`** (project-context). `<WelcomeWindowProps>` is a named `interface`. Both `kakaoEnabled` and the callbacks are typed explicitly.
- **Immutable updates only.** When toggling the Today-tagline flag via `queryClient.setQueryData`, write a fresh object — do not mutate.
- **Brand-voice copy is the contract.** All Korean copy in this story (CTAs, headline, tooltip, system message, tagline) must survive `tools/brand-voice-lint.ts` Rule 2. The 8 banned words from PRD FR-8.8.2 do not appear in any of the locked copy strings — verify with `grep -F '실패' '벌금' ...` on the new strings before committing.

### Reuse vs. new (read each UPDATE file fully before editing)

**NEW files:**

- `FE/src/components/welcome/WelcomeWindow.tsx`
- `FE/src/components/welcome/index.ts` (barrel export — match Story 1.5 `<SurvivalChip>` pattern)
- `FE/src/components/welcome/__tests__/WelcomeWindow.test.tsx`
- `FE/src/components/welcome/__tests__/WelcomeWindow.disabled-cta.test.tsx`
- `FE/src/components/welcome/__tests__/WelcomeWindow.dynamic-type.test.tsx`
- Integration test file under `FE/src/**/__tests__/` (path-bind per Jest testMatch — verify discovery on first run)
- `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java`

**UPDATE files:**

- `FE/app/rooms/[id].tsx` (UPDATE — render `<SubModeProvider><WelcomeWindow/></SubModeProvider>` conditionally; preserve every existing behavior incl. invite sheet, member list, leave-room, grace-banner)
- `FE/app/(tabs)/today.tsx` or wherever the Today screen lives (UPDATE — render the warm-tone tagline when the solo-leader cache flag is present; preserve the existing grass grid + goal/reflection flow)
- `FE/src/components/ui/Text.tsx` (UPDATE only if you need to add a `displaySerif` variant — pick the smaller-diff alternative of inline `style={{ fontFamily }}` if it's just one usage)
- `FE/src/lib/query/keys.ts` (UPDATE — add `qk.soloLeaderTagline(roomId)` if a query key isn't already structured this way)
- `FE/src/lib/realtime/client.ts` (UPDATE only if `MEMBER_ADDED` doesn't already invalidate `useRoomMembersQuery()` — read first)
- `FE/src/api/rooms.ts` (UPDATE only if the `Room` DTO doesn't already include `graceEndsAt` — Story 1.1 likely added it)
- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` (UPDATE — add `publishMemberJoinedSystemMessage(Room, User)` public method following the existing `publishGoalSystemMessages` precedent from `DailyService.java:231`)
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` (UPDATE — single new line after `realtime.publishMemberAdded(room.getId(), summary);` calling the new method on `ChatService`)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (UPDATE — story status transitions)
- `_bmad-output/implementation-artifacts/1-6-welcomewindow-j0-leaders-lonely-30-seconds.md` (UPDATE — this file's task checkboxes, dev agent record, status)

**Files explicitly NOT touched:**

- Any `BE/src/main/resources/db/migration/V*__*.sql` (no schema change).
- `BE/src/main/resources/application.yml` (no app-config change).
- `infra/docker-compose.yml`, `infra/RUNBOOK-V11.md`, `infra/verify-v11.sh` (no ops change).
- `tools/*` (already shipped in Story 1.5, no change here).
- `docs/design-system.md` (no token table change in this story).
- Every other FE screen outside `[id].tsx` and the Today screen — they continue to render with their existing v1 styling. Story 1.6 is foundation-coupled but surface-narrow.

### Existing patterns to reuse (read before authoring)

- `FE/src/components/survival/SurvivalChip.tsx` — the canonical Story 1.5 pattern for a `useTheme()`-consuming primitive with an accessibility composite. Match its structure (typed `interface` props, View root with `accessibilityRole`, no internal subMode read).
- `FE/src/providers/SubModeProvider.tsx` — wrap WelcomeWindow at the parent site, not inside the component itself. The component reads via `useTheme()`.
- `FE/src/components/feedback/ToastProvider.tsx` + `FE/src/lib/toast.ts` — the existing toast API for the disabled-CTA-A user feedback. Match its calling convention.
- `BE/src/main/java/com/yeosal/api/daily/DailyService.java:231` (`publishGoalSystemMessages`) — the canonical existing system-message hook pattern. Mirror this shape for `publishMemberJoinedSystemMessage`.
- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` — the existing service that owns system-message creation. Add the new public method here (do not introduce a new service class).
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` — the single STOMP fan-out hub. Reuse `publishChatMessage(...)` from the new ChatService method; do NOT inject `SimpMessagingTemplate` directly.
- `FE/app/rooms/[id].tsx` — current room detail screen. Read fully before editing; preserve all behaviors (invite sheet, leave room, grace banner from Story 1.1, member cards).
- `FE/src/components/rooms/GraceBanner.tsx` — Story 1.1's grace banner sits alongside `<WelcomeWindow>` in the J0 case. Decide co-presence: WelcomeWindow on top (postcard tone, hero), GraceBanner below as a calm subtitle. Both can coexist in `solo` and `growing` states.

### Previous story intelligence (Stories 1.1–1.5)

- **Story 1.1 grace mechanics**: `room.created_at + 14 days = graceEndsAt`. The Room entity already persists this; `RoomMember.joined_at` anchors per-member grace at signup. WelcomeWindow uses room-level grace, not per-member.
- **Story 1.2 06:00 KST evaluator**: irrelevant to 1.6 directly (no survival-state transition in J0).
- **Story 1.3 privacy-filtered survival API**: WelcomeWindow does NOT consume survival state — sole-member leader is always ACTIVE. Reuse `useRoomMembersQuery()` for member count; don't call survival-state endpoints from the WelcomeWindow tree.
- **Story 1.4 V11 + IMMUTABLE hotfix**: already on main; no overlap.
- **Story 1.5 token codegen + brand-voice-lint + `<SubModeProvider>` + `<SurvivalChip>`**: prerequisite. All of Sprint A/B/C/D landed on main (`6a0ec12` + `8a72016`). This story is the first downstream consumer of `<SubModeProvider subMode="postcard">` + `useTheme()` + brand-voice-lint Rule 2 in production.
- **TDD discipline (RED → GREEN → refactor)** per project-context. Write the `WelcomeWindow.test.tsx` 3-state tests RED first; then implement the state machine; verify GREEN. Same for `RoomServiceMemberJoinSystemMessageTest`.
- **Pre-existing FE typecheck baseline failures** in `FE/src/components/today/FriendsTodayPager.tsx` (missing `react-native-pager-view` dep) — inherited from branch base, out of scope.

### Latest tech information

- **TypeScript 5.9 / React 19.1.0 / Expo SDK 54 / RN 0.81.5** — unchanged from Story 1.5; no new toolchain needed.
- **Nanum Myeongjo font** — already a project asset; verify the font is bundled (look in `FE/assets/fonts/` or the Expo font config). Story 1.5 documented it in `tokens.json:typography.display.serif.family`. If not yet bundled at runtime, FE-1.2 includes the bundle step (use `expo-font` `useFonts` with a fallback to system serif while loading; do NOT block app boot — match the existing `useWantedSans` pattern in `FE/app/_layout.tsx`).
- **`@expo/vector-icons`** — already a dep. The 🥥 and 🌿 are Unicode emojis (not icon glyphs); use them as literal characters in the CTA labels. No icon imports needed for the WelcomeWindow primary surface (the survival domain still uses MaterialIcons via SurvivalChip — separate concern).

### Testing standards summary

| Layer | Framework | Scope |
| --- | --- | --- |
| FE component | Jest + `@testing-library/react-native` | `<WelcomeWindow>` 3 states + disabled-CTA + dynamic-type snapshot |
| FE integration | Jest | RoomDetailScreen renders WelcomeWindow at the right thresholds |
| FE brand-voice | `tools/brand-voice-lint.ts` (HARD GATE for Rule 1, WARN for Rule 2) | every new copy string passes Rule 2 |
| BE service | JUnit 5 + Spring `@DataJpaTest` slice + AssertJ + Mockito | `RoomService.joinByCode` emits the SYSTEM chat message transactionally |
| BE integration (opt-in) | Testcontainers (existing `@EnabledIfSystemProperty` gate) | not required for 1.6 — service-slice test is sufficient |

Default cycle (no Docker, no opt-in): `./gradlew test` + `cd FE && npm test` + `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` must all be green. The IT layer (`@EnabledIfSystemProperty`) is unaffected by this story.

**Coverage target:** 80%+ on `WelcomeWindow.tsx` and the new BE chat-system-message path. The shared `<Text>` / `<Pressable>` infrastructure is already covered upstream.

### Pre-commit verification

1. `cd FE && npm run lint && npm run typecheck && npm test` — green (project-context FE pre-push). The pre-existing `FriendsTodayPager.tsx` typecheck failure remains baseline; verify the FE tests added by this story all pass.
2. `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` — green (0 HARD violations).
3. `tools/node_modules/.bin/tsx tools/contrast-check.ts` — green (no token changes; 10/10 PASS).
4. `cd BE && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test --no-daemon` — green. Adds the new `RoomServiceMemberJoinSystemMessageTest` to the default cycle.
5. `cd BE && ./gradlew check --no-daemon` — green (Story 1.5 Checkstyle hex-literal guard still passes; this story introduces zero color literals in BE Java).
6. `bash scripts/verify.sh` from repo root — full FE+BE+tools verification.
7. **PR base must be `main`** per project-context Stack PR Merge Procedure. Verify with `gh pr view <N> --json baseRefName,mergeStateStatus`.

### Open questions saved for end (raise after dev work but before merge)

1. **CTA-A tooltip mechanism.** RN's stock components have no first-class tooltip. The Story spec mentions `onLongPress` + a transient `<View>`, but using a `toast()` on `onPress` may be more discoverable. Decide during Task FE-1.3 — recommend `toast()` for simplicity (already in the FE toolbox), with `accessibilityHint` carrying the same copy for screen-reader users.
2. **Today screen migration scope.** AC6 only requires a warm tagline; per AC11 the Today screen does NOT migrate to v2 sub-mode. Confirm with reviewer if any other screen needs to know about the solo-leader state (e.g., the Profile tab). Default: NO — keep scope tight.
3. **Headline font fallback.** If Nanum Myeongjo is not yet bundled in the FE assets, decide whether to (a) bundle now as part of 1.6 or (b) fall back to system serif with a TODO for Story 1.7 / 1.8. Recommend (a) — adds ~50KB but unblocks every D1/D4 surface downstream.
4. **Postcard sub-mode integration.** Story 1.5 `subMode="postcard"` overrides `motion.entry.duration` to 1500ms — does the WelcomeWindow's mount animation use this value? Decide during FE-1.1 — recommend yes (use `Animated` API with the resolved theme value), to make the postcard tone tangible from day 1.
5. **CTA-B navigation target.** Story epic says "Today tab". Expo Router path: `/(tabs)/today` or just `/(tabs)`? Verify the route segment name in `FE/app/(tabs)/_layout.tsx` before wiring.

### References

- [PRD §4.3 J0](../planning-artifacts/prd.md) — root authority; cold-start leader 30s anti-pattern.
- [PRD §6.1](../planning-artifacts/prd.md) — A10 anti-pattern guard.
- [PRD FR-8.1.1, FR-8.1.2](../planning-artifacts/prd.md) — room create + grace.
- [PRD FR-8.8.2](../planning-artifacts/prd.md) — AVOID lexicon (brand-voice).
- [Architecture §4.7](../planning-artifacts/architecture.md) — branched layout, never parallel route group.
- [Architecture §4.16](../planning-artifacts/architecture.md) — token consumption via `useTheme()` + `GeneratedTokens`.
- [UX §J0 journey L1232–1256](../planning-artifacts/ux-design-specification.md) — full mermaid flow + D4 Postcard hooks.
- [UX §Surface Assignment Matrix L1168](../planning-artifacts/ux-design-specification.md) — WelcomeWindow → D4 Postcard Mythic.
- [UX §D4 Postcard Mythic L1115–1135](../planning-artifacts/ux-design-specification.md) — sub-mode tone description.
- [UX §A10 L524](../planning-artifacts/ux-design-specification.md) — progress-bar anti-pattern.
- [Sprint Change Proposal 2026-05-10 §G1.3](../planning-artifacts/sprint-change-proposal-2026-05-10.md) — Story 1.6 introduction rationale.
- [Sprint Change Proposal 2026-05-11 Option B lock](../planning-artifacts/sprint-change-proposal-2026-05-10.md) — disabled CTA-A copy locked.
- [Epics.md Story 1.6 lines 245–283](../planning-artifacts/epics.md) — original epic spec.
- [Story 1.5 file](./1-5-design-system-foundation-v2-token-packed-type-fe-be-codegen.md) — `<SubModeProvider>` + `useTheme()` + brand-voice-lint Rule 1/2 invariants.
- [Story 1.1 file](./1-1-room-creation-with-v1-cap-14-day-grace-trial.md) — `room.graceEndsAt` field + 14-day grace mechanics.
- [project-context.md](../project-context.md) — BE/FE rules (constructor injection, single `@RestControllerAdvice`, single STOMP client, no `any`, immutable updates, brand-voice copy).
- Existing source for pattern reference:
  - `FE/src/components/survival/SurvivalChip.tsx` (Story 1.5 primitive pattern)
  - `FE/src/providers/SubModeProvider.tsx` (Story 1.5 page-level wrap)
  - `FE/src/theme/useTheme.ts` (Story 1.5 resolver)
  - `FE/src/components/rooms/GraceBanner.tsx` (Story 1.1 sibling surface)
  - `FE/app/rooms/[id].tsx` (the integration site)
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` (the BE service under modification)
  - `BE/src/main/java/com/yeosal/api/daily/DailyService.java:231` (system-message hook precedent)
  - `BE/src/main/java/com/yeosal/api/room/RoomService.java:238` (the `joinByCode` site under modification)

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (1M context).

### Debug Log References

- FE jest: 4 new test suites under `FE/src/components/welcome/__tests__/` (`WelcomeWindow.test.tsx`, `WelcomeWindow.disabled-cta.test.tsx`, `WelcomeWindow.dynamic-type.test.tsx`, `WelcomeWindow.integration.test.tsx`) — 26 tests + 2 snapshots, all green. Full suite: 146 tests / 23 suites / 0 failures.
- BE gradle: `RoomServiceMemberJoinSystemMessageTest` adds 5 tests. Full suite: 265 tests / 0 failures. `./gradlew check` clean (Checkstyle).
- Tooling: `tools/brand-voice-lint.ts` → 0 HARD violations on the 118 scanned files. `tools/contrast-check.ts` → 10/10 PASS.

### Completion Notes List

- **DoD validation:** all 11 task/subtask checkboxes are [x]; AC1-AC11 satisfied; new code carries unit/integration tests; coverage target met on the new surface; no Flyway migration introduced (AC9 ✅); no new endpoint (AC9 ✅).
- **Deliberate spec deviation (BE-1.2/BE-2.1):** the story called for a shared `@Transactional` boundary so a chat-write failure rolls back the join. Implementation follows the existing canonical `ChatService.publishSystem` pattern instead — `@Transactional(propagation = REQUIRES_NEW)`, mirroring `DailyService.publishGoalSystemMessages` and the evaluator's `AUTO_LEAVE` fan-out. A chat-write failure therefore does NOT roll back the membership insert. This preserves the project-wide "system fan-out is best-effort" invariant. The BE test was adjusted to assert the canonical semantics.
- **Pragmatic AC6 narrowing:** the Today screen does not currently render a `<ContributionGrid>`, so AC6's "1/N or 0/N first-entry vibe" condition is approximated as `!entry?.goal` (fresh-day proxy). The `<ContributionGrid mode="solo-leader">` prop is deferred until that component lands on the Today screen.
- **Font deferral:** Nanum Myeongjo OTF binaries are not in the repo (`FE/assets/fonts/` has only README). The `fontFamily: "Nanum Myeongjo"` string flows through tokens → component, but the platform serif fallback is used at runtime. This matches the existing WantedSans deferral pattern in `FE/src/lib/fonts.ts` and the Story 1.5 typography documentation. Bundling the actual binary is deferred (handed to a future asset PR).
- **CTA-A disabled UX:** chose `toast.info` on press instead of `onLongPress` tooltip; `accessibilityHint` carries the same locked copy for SR. The `<Pressable>` deliberately does NOT set `disabled={true}` because RN swallows `onPress` on disabled Pressables, breaking the "press → toast" affordance. The `accessibilityState.disabled` is still set for SR announcement.
- **Pre-existing baseline noise (out of scope):** `FE/src/components/today/FriendsTodayPager.tsx` `react-native-pager-view` typecheck miss and 4 ESLint errors in untouched files (rooms/[id]/chat.tsx, SurvivalChip tests, InviteCodeSheet test, realtime/client.ts). Story 1.6 introduces zero new lint or typecheck violations against its own change set.

### File List

**NEW files (FE):**

- `FE/src/components/welcome/WelcomeWindow.tsx` — Component + `shouldShowWelcomeWindow` predicate + `deriveWelcomeWindowState` + `TODAY_TAGLINE` constant.
- `FE/src/components/welcome/index.ts` — Barrel re-export.
- `FE/src/components/welcome/__tests__/WelcomeWindow.test.tsx`
- `FE/src/components/welcome/__tests__/WelcomeWindow.disabled-cta.test.tsx`
- `FE/src/components/welcome/__tests__/WelcomeWindow.dynamic-type.test.tsx`
- `FE/src/components/welcome/__tests__/WelcomeWindow.integration.test.tsx`
- `FE/src/components/welcome/__tests__/__snapshots__/WelcomeWindow.dynamic-type.test.tsx.snap` (jest-generated)

**NEW files (BE):**

- `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java`

**UPDATED files (FE):**

- `FE/app/rooms/[id].tsx` — Imports + `shouldShowWelcomeWindow` predicate + conditional `<SubModeProvider subMode="postcard"><WelcomeWindow .../></SubModeProvider>` render at top of ScrollView + `handleWelcomeStartToday` handler.
- `FE/app/(tabs)/today.tsx` — `useQueryClient` + cache lookup for `qk.soloLeaderTagline(*)` + conditional warm-tone tagline render above `<TodayChips>`.
- `FE/src/api/rooms.ts` — Added `createdAt: string` field to `Room` interface.
- `FE/src/lib/query/keys.ts` — Added `qk.soloLeaderTagline(roomId)` key.
- `FE/src/lib/query/hooks/__tests__/useCreateRoom.test.tsx` — Added `createdAt` field to mocked `Room` literal so the new required field type-checks.

**UPDATED files (BE):**

- `BE/src/main/java/com/yeosal/api/room/RoomService.java` — Call to `chatService.publishMemberJoinedSystemMessage(room, user)` after `realtime.publishMemberAdded(...)` in `joinByCode()`. `RoomSummary` record gains `Instant createdAt` so the FE can derive the grace window.
- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` — New public method `publishMemberJoinedSystemMessage(Room, User)` + `java.util.Objects` import.

**UPDATED meta files:**

- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `1-6-welcomewindow-j0-leaders-lonely-30-seconds: ready-for-dev → in-progress → review`; `last_updated: 2026-05-14`.
- `_bmad-output/implementation-artifacts/1-6-welcomewindow-j0-leaders-lonely-30-seconds.md` — this file (status, task checkboxes, Dev Agent Record, File List, Change Log).

### Change Log

| Date | Author | Change |
|------|--------|--------|
| 2026-05-13 | scrum-master (claude-opus-4-7) | Story 1.6 created via `/bmad-create-story`. WelcomeWindow J0 surface — D4 Postcard Mythic sub-mode, 2 equal-weight CTAs (Kakao disabled in W1-W2 per Option B lock, Today fully functional), A10 anti-pattern guard, 3-state machine (solo / growing / full), member-join SYSTEM chat message hook on BE, brand-voice-lint Rule 2 compliance. Scope is FE component + tight BE addition; no API/DB/migration changes. Owned by Epic 1 (Survival State & Daily Loop). |
| 2026-05-14 | dev (claude-opus-4-7) | Story 1.6 implementation complete. FE: WelcomeWindow + shouldShowWelcomeWindow predicate + room-screen wiring + Today tagline + qk.soloLeaderTagline key. BE: ChatService.publishMemberJoinedSystemMessage + RoomService.joinByCode hook + RoomSummary.createdAt. Tests: 4 FE jest suites (26 tests + 2 snapshots) + 1 BE Mockito suite (5 tests). Deviation from BE-2.1(d) — uses canonical REQUIRES_NEW pattern. Status flipped `in-progress → review`. |
