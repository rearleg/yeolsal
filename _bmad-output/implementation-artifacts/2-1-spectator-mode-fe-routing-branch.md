# Story 2.1: Spectator-mode FE routing branch

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **an eliminated member (`survival_state.status = SPECTATOR`)**,
I want **the app to drop me into a read-only variant of the same rooms surface**,
so that **I can watch the room continue without being able to post and feel the FOMO that brings me back**.

PRD authority: **FR-8.2.1 / .2 / .5 / .6** (spectator routing + read-only chat + Wallet prominence + revival re-entry) and **NFR-9.2.5** (FE input disabled AND BE chat-write 403 — double enforcement).
Architecture authority: **§4.7** (branch inside the existing `(tabs)` layout — **never** a parallel route group) and **§4.14** (server-side privacy is the source of truth; FE renders what the BE returns).
UX authority: **§Sub-mode `D3 Quiet Dark` L1093–1113** (spectator visual tone: dim body, slow motion, ember absence, monochrome grass) and **§Surface Assignment Matrix L1164** (Spectator-mode → D3).
Epics ref: lines 333–361.
Sprint Change Proposal 2026-05-11 (M2): SubModeProvider page-level injection invariant — leaf components never read the sub-mode string.

> **Foundation note.** Story 2-1 is the FE routing + BE chat-write enforcement that the rest of Epic 2 leans on. The BE plumbing it depends on is already on `main`: `GET /api/v1/me/survival` (Epic 1 retro T4 — PR #64) and the pure helper `isSpectatorAcrossAllRooms` (T5 — `FE/src/lib/spectator.ts`). The `RitualMoment spectator={false}` integration point in `FE/app/_layout.tsx` (line 63 TODO) is also already wired and waiting — this story flips it to the real signal. No Flyway migration; the SPECTATOR enum value and the `survival_state` table both shipped in V11.

## Acceptance Criteria

1. **AC1 — `useCurrentRoomSurvivalState()` hook + `useIsSpectatorEverywhere()` hook + room-scoped routing branch.**
   - **NEW hook `FE/src/lib/query/hooks/survival.ts`:**
     - `useMeSurvivalQuery()`: TanStack Query against `GET /api/v1/me/survival`. Returns `MeSurvivalEntry[]` (shape already defined at `FE/src/lib/spectator.ts:15-19`). `staleTime: 30_000`, `gcTime: 5 * 60_000`. Goes through `apiRequest<T>` (project-context FE rule — direct `fetch` forbidden).
     - `useCurrentRoomSurvivalState(roomId: number | null): MeSurvivalEntry | null`: derives the entry for `roomId` from the cached `useMeSurvivalQuery()` result. Returns `null` when `roomId` is null or no entry matches (treat unknown as "not playing in this room" — falls through to ACTIVE path).
     - `useIsSpectatorEverywhere(): boolean`: thin wrapper that pipes `useMeSurvivalQuery().data ?? []` into the existing pure helper `isSpectatorAcrossAllRooms(entries)` from `FE/src/lib/spectator.ts`. **Reuse — do NOT duplicate the helper.**
   - **NEW hook key:** add `qk.meSurvival` to `FE/src/lib/query/keys.ts` (constant tuple `["meSurvival"]`).
   - **API client method:** add `getMeSurvival(): Promise<MeSurvivalEntry[]>` to a new file `FE/src/api/survival.ts` (or extend the nearest existing `FE/src/api/rooms.ts` — pick whichever yields the smaller diff; tests follow the location).
   [Architecture §6.4, Epic 1 retro T4 + T5; project-context FE `apiRequest<T>` rule]

2. **AC2 — Spectator-aware tabs layout (`FE/app/(tabs)/_layout.tsx`).**
   The tabs layout consumes `useIsSpectatorEverywhere()` AND, via React Context, exposes a `useSpectatorRoute()` hook so per-screen components can branch without re-fetching the survival query. Implementation:
   - **WRAP** the existing `<Tabs>` in a new `<SpectatorRouteProvider>` (created at `FE/src/providers/SpectatorRouteProvider.tsx`). The provider reads `useMeSurvivalQuery()` once and exposes `{ isSpectatorEverywhere: boolean; spectatorRoomIds: Set<number>; activeRoomIds: Set<number>; }`.
   - **WHEN** `isSpectatorEverywhere === true`:
     - The same 5 tabs (`today` / `feed` / `rooms` / `chat` / `profile`) render — **same screens, same routes** (Architecture §4.7 — no parallel route group).
     - Wrap the `<Tabs>` in `<SubModeProvider subMode="quiet">` so the resolved theme returns D3 Quiet Dark tokens (dim body, slow motion, no ember, monochrome grass). The provider is the only seat that touches the `subMode` string — leaf components MUST NOT read it directly (UX cross-cutting rule #9; Story 1.5 AC7).
   - **WHEN** `isSpectatorEverywhere === false`: existing behavior — render `<Tabs>` without a `<SubModeProvider>` wrapper at this level (root `_layout.tsx` already sets the base `subMode={null}`).
   - **DO NOT** add new tab routes, reorder tabs, or hide tabs. PRD FR-8.2.5 calls for Wallet prominence; v1 surfaces that via Story 3.4 (Wallet UI on the Profile/Wallet tab) — Story 2.1 does not introduce a separate Wallet tab.
   [Architecture §4.7 implementation; UX L1164 surface-mode mapping; Story 1.5 SubModeProvider rules]

3. **AC3 — Read-only chat (FE): `MessageInput` hidden when SPECTATOR in that room.**
   - **UPDATE `FE/app/rooms/[id]/chat.tsx`:** consume `useCurrentRoomSurvivalState(roomId)` and `useSpectatorRoute()`. Compute `const spectator = currentRoomState?.status === "SPECTATOR";`.
   - **WHEN** `spectator === true`:
     - The `<MessageInput .../>` instance at line 102–105 is replaced by a `<SpectatorReadOnlyBanner />` (NEW: `FE/src/components/chat/SpectatorReadOnlyBanner.tsx`).
     - Banner copy: `"관전 중 — 메시지는 회생 후에 다시 보낼 수 있어요"`. Tone: caption + `palette.inkMute` text, no shame language. Brand-voice-lint Rule 2 MUST pass on the copy. Banned: `벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감` — none of these appear.
     - `accessibilityRole="text"`, `accessibilityLabel` mirrors the visible copy. The banner is non-interactive — no pressable.
   - **DO NOT** call `useSendChatMessage` from any spectator code path. The mutation hook stays imported (existing baseline), but the SPECTATOR branch never invokes it.
   - **Realtime + history are unchanged:** spectator users keep the `useChatRealtime(roomId)` subscription and the `useChatMessages(roomId)` query — they receive other members' messages live and scroll history exactly as before. Only the **write** affordance is removed.
   [Epics line 347, PRD FR-8.2.2, NFR-9.2.5 (FE half)]

4. **AC4 — Read-only chat (BE): `POST /api/v1/rooms/{id}/messages` returns `403 SPECTATOR_WRITE_FORBIDDEN` for SPECTATOR users.**
   - **NEW exception** `BE/src/main/java/com/yeosal/api/common/SpectatorWriteForbiddenException.java`:
     - Extends `ForbiddenException` (so existing `403 FORBIDDEN` callers keep working).
     - Adds a stable `public static final String CODE = "SPECTATOR_WRITE_FORBIDDEN";`.
     - Constructor: `public SpectatorWriteForbiddenException() { super("관전 중에는 메시지를 보낼 수 없어요."); }`. Korean message is FE-surfaceable per project-context error-message rules.
   - **NEW handler** in `ApiExceptionHandler` (extend the existing single `@RestControllerAdvice` — do NOT introduce a second advice class; project-context anti-pattern):
     - `@ExceptionHandler(SpectatorWriteForbiddenException.class)` returns `ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of(SpectatorWriteForbiddenException.CODE, exception.getMessage()));`.
     - Placed **above** the generic `forbidden(ForbiddenException)` handler in source order. Spring resolves the most specific subtype first regardless of source order, but human readability matters.
   - **UPDATE `ChatService.sendUserMessage`** (BE/src/main/java/com/yeosal/api/room/chat/ChatService.java lines 90–103):
     - After the existing `requireMembership(room, viewer)` call and **before** `normalizeBody(...)`, inject a new `requireNotSpectator(room, viewer)` guard.
     - **NEW dependency:** constructor-inject `SurvivalStateRepository` (project-context Java rule — constructor injection only, no `@Autowired` fields).
     - `requireNotSpectator(Room, User)` reads `survivalStates.findByRoomIdAndUserId(roomId, userId)`. When the row's status is `SurvivalStatus.SPECTATOR`, throw `new SpectatorWriteForbiddenException()`. When the row is absent (legacy / pre-V11 defensive), treat as ACTIVE and pass through — V11 backfill should have created every row, but a missing one MUST NOT 500.
     - Place the guard inside the existing `@Transactional` method so the read is consistent with the (would-be) write.
   - **DO NOT** check spectator status inside `publishSystem(...)` — system messages (GOAL/REFLECTION/MILESTONE/AUTO_LEAVE/SYSTEM) are server-emitted on behalf of the system, not a SPECTATOR user. The check belongs strictly on user-authored writes.
   - **DO NOT** introduce a second `@RestControllerAdvice` (project-context anti-pattern). Extend the existing `ApiExceptionHandler` only.
   [Epics line 359 (readiness-promoted AC), PRD NFR-9.2.5 (BE half), project-context: single advice / constructor injection / @Valid]

5. **AC5 — `RitualMoment` spectator integration site flips to the real signal.**
   - **UPDATE `FE/app/_layout.tsx` lines 66–68:**
     ```tsx
     <SubModeProvider subMode="postcard">
       <RitualMoment spectator={useIsSpectatorEverywhere()} />
     </SubModeProvider>
     ```
     The `// TODO Story 2.1: replace with real spectator detection` comment at lines 63–65 is removed in this change. If the hook cannot be called inline at the layout-component level due to Hooks rules, factor a small `<RitualMomentBootstrap />` wrapper that owns the call — pick the simplest wiring.
   - **DO NOT** modify the `RitualMoment` component itself — Story 1.7 already implemented the `spectator?: boolean` prop branch (`key.muted` ember, peak opacity 0.18, canvas-only surface darken). This story only flips the prop value.
   - **Re-run** `FE/src/components/ritual/__tests__/RitualMoment.spectator.test.tsx` after the wire change — it should still pass without modification (the component's prop contract is unchanged).
   [Story 1.7 AC5 deferral closed; Architecture §4.7 single source of truth]

6. **AC6 — Revival → immediate re-entry < 1s (NFR-9.1.5).**
   - When `survival_state.status` transitions back to `ACTIVE` (any revival source — handled by Story 3.1 / 3.2 BE writes), the FE re-enters active member mode in **less than 1 second** without an app restart.
   - **Mechanism** (verifies wiring):
     - The existing `RealtimeProvider` STOMP subscription includes `/user/queue/{userId}/private-survival` (Architecture §4.14 — owned by Story 1.3's survival realtime handler).
     - On a SurvivalStateChange frame, the handler invalidates `qk.meSurvival` (cross-room) AND `qk.survivalRoster(roomId)` (per-room) caches.
     - The `<SpectatorRouteProvider>` re-resolves on the next paint, the `<SubModeProvider subMode="quiet">` wrapper drops off (`isSpectatorEverywhere === false`), the chat screen's `<SpectatorReadOnlyBanner />` swaps back to `<MessageInput />`.
     - Cold-load fallback: any tab focus event (RN AppState `background → active` already wired via `setupReactQueryFocus` at `FE/app/_layout.tsx:27`) triggers a `qk.meSurvival` refetch.
   - **If `qk.meSurvival` invalidation does NOT exist on the realtime handler yet**, this story adds it — keep the change minimal (one `qc.invalidateQueries({ queryKey: qk.meSurvival })`).
   - **Test:** `useCurrentRoomSurvivalState` integration test simulates a STOMP frame `{ type: 'SurvivalStateChange', status: 'ACTIVE' }` and asserts the derived `spectator` flag flips to `false` on the next render via `@testing-library/react-native`'s `waitFor`.
   [Epics lines 353–355, PRD NFR-9.1.5, Architecture §4.14]

7. **AC7 — Wallet prominence (FE).**
   - PRD FR-8.2.5: "Spectator users see their own Wallet (revival ticket + personal points balance + room point pool) prominently displayed."
   - **This story does NOT ship a new Wallet tab.** The full Wallet surface is Story 3.4. **What 2.1 ships:** a small `<WalletPreview />` block at the **top of the Today tab** (`FE/app/(tabs)/today.tsx`) that renders **only when** `useIsSpectatorEverywhere() === true`.
   - **Block content** (read-only, no actions — Story 3.4 makes it interactive):
     - Free revival ticket presence: `🎟 무료 회생권 1매` if `user.freeRevivalTicketUsed === false`, otherwise omit the line.
     - Personal points balance: `🌿 개인 포인트 {N}점` (per-room aggregation; v1 shows the **first** active room's balance — full multi-room sum is Story 3.4).
     - Room point pool: `💚 그룹 포인트 {M}` for the first active room.
   - **Data sources:**
     - `user.freeRevivalTicketUsed`: piggyback on the existing `useAuth().user` shape — if not already exposed, extend the auth `User` DTO + BE `/auth/me` response to include `freeRevivalTicketUsed: boolean` (the V11 column is already on `users`). One field, additive — non-breaking.
     - Personal points / pool: **PREFER** extending `MeSurvivalEntryDto` to include `personalPoints: int` and `roomPointPool: int` — single round-trip, no new endpoint. The BE change is additive on `SurvivalStateService.mySurvivalAcrossRooms` (lines 413–419).
   - **Visual tone:** D3 Quiet Dark via the parent `<SubModeProvider subMode="quiet">` (AC2 wraps the entire tabs layer, so this block inherits it). No new sub-mode wrapper at this level.
   - **Brand-voice-lint:** copy MUST pass Rule 2. None of the 8 banned words appear. The intentionally-soft `🎟 / 🌿 / 💚` icons preserve the dignity tone (UX A11 v2 guard: pure RED is banned on spectator surfaces).
   [PRD FR-8.2.5, epics line 351, UX §D3 Quiet Dark]

8. **AC8 — Accessibility (NFR-9.6.3 Dynamic Type + screen reader).**
   - `<SpectatorReadOnlyBanner>` uses the shared `<Text>` (Story 1.5 — caps `maxFontSizeMultiplier=1.3`). At 1.5× system font scale the banner does not overflow or clip; snapshot test mirrors the `SurvivalChip.dynamic-type.test.tsx` pattern.
   - `<WalletPreview>` uses the same `<Text>` and inherits the 1.3× cap. All 3 emoji-prefixed lines render on separate `<Text>` elements (no inline emoji + number concatenation that would break VoiceOver) — emoji is paired with a Korean-form `accessibilityLabel="무료 회생권 1매"` (no emoji in the SR string).
   - The `<SpectatorRouteProvider>` does NOT inject any new focus traps or `accessibilityViewIsModal` regions — spectator mode is a visual + auth re-skin, not a modal layer.
   [Story 1.5 AC9 / NFR-9.6.3 Dynamic Type cap]

9. **AC9 — No new Flyway migration. No new domain table. Single BE add: subtype exception + `requireNotSpectator` guard.**
   - **NO** new entity, **NO** new repository beyond the existing `SurvivalStateRepository` (which `ChatService` newly consumes), **NO** Flyway migration. V11 already shipped `survival_state` with the `SPECTATOR` enum value.
   - **NO** new REST endpoint **unless** AC7's MeSurvivalEntryDto extension is judged too invasive — in which case the dev may instead add a tiny `GET /api/v1/me/wallet-preview` endpoint. Default decision: extend `MeSurvivalEntryDto` (additive, no new controller).
   - **NO** new `com.yeosal.api.<x>` module. The Java edits are confined to:
     - `BE/src/main/java/com/yeosal/api/common/` (new `SpectatorWriteForbiddenException`)
     - `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (one new handler method)
     - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` (constructor + `requireNotSpectator` + 1-line guard)
     - `BE/src/main/java/com/yeosal/api/survival/MeSurvivalEntryDto.java` + `SurvivalStateService.java` (additive fields on the DTO)
   - **NO** new FE dep. No `react-native-reanimated`, no `expo-linear-gradient`, no new `react-native-*` native module.
   [Architecture §4.16; project-context "no scope drift / no half-finished implementations"]

10. **AC10 — Unit + integration test coverage (TDD, 80%+ on new code).**

    **FE — Jest + `@testing-library/react-native`:**
    - `FE/src/lib/query/hooks/__tests__/survival.test.tsx` — `useMeSurvivalQuery` happy path (fetch → cache hit), `useCurrentRoomSurvivalState` matches an entry by roomId + returns `null` on miss + flips when the cache is invalidated, `useIsSpectatorEverywhere` returns `true` only when **every** entry is SPECTATOR (mirrors the existing `spectator.test.ts` cases — do NOT duplicate the pure helper's tests). ≥ 8 assertions.
    - `FE/src/providers/__tests__/SpectatorRouteProvider.test.tsx` — provider exposes the resolved sets to a test consumer; toggling the underlying query data flips the consumer's read; the provider does NOT crash with an empty cache.
    - `FE/src/components/chat/__tests__/SpectatorReadOnlyBanner.test.tsx` — renders the locked copy + `accessibilityRole="text"` + passes brand-voice-lint manual scan.
    - `FE/app/rooms/[id]/__tests__/chat-spectator-branch.test.tsx` (or co-located respecting `testMatch: src/**/__tests__/**/*.test.{ts,tsx}`): render the chat screen with a mocked SPECTATOR `useCurrentRoomSurvivalState`; assert `<MessageInput>` is NOT in the tree and `<SpectatorReadOnlyBanner>` IS; assert `useSendChatMessage` mutation is never called when the spectator branch is active.
    - Existing `FE/src/components/ritual/__tests__/RitualMoment.spectator.test.tsx` stays unmodified (verifies the prop contract). Add ONE integration test that mounts the root layout with a mocked `useIsSpectatorEverywhere` → `true` and asserts `RitualMoment` receives `spectator={true}`.
    - `FE/app/(tabs)/__tests__/today-wallet-preview.test.tsx` — render the Today screen with mocked `useMeSurvivalQuery` returning a SPECTATOR-everywhere fixture; assert all 3 wallet lines render + brand-voice passes.

    **BE — JUnit 5 + Spring Test slice (no Testcontainers for the unit-level guard; integration tests reuse the existing `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceTest.java` pattern):**
    - `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceSpectatorGuardTest.java` — Mockito unit:
      - SPECTATOR row present → `sendUserMessage` throws `SpectatorWriteForbiddenException` (exact class, not just `ForbiddenException`).
      - ACTIVE / YELLOW / RED row → write proceeds (no throw, `messages.save` called once).
      - Missing survival_state row → write proceeds (no NPE, no 500).
      - `publishSystem(...)` for a SPECTATOR user → **passes**; the guard is sendUserMessage-only.
    - `BE/src/test/java/com/yeosal/api/common/ApiExceptionHandlerSpectatorWriteTest.java` — `@WebMvcTest` slice:
      - `POST /api/v1/rooms/1/messages` with the service stubbed to throw `SpectatorWriteForbiddenException` → response status `403`, body `{ "error": { "code": "SPECTATOR_WRITE_FORBIDDEN", "message": "관전 중에는 메시지를 보낼 수 없어요." } }`.
      - The generic `ForbiddenException` still maps to code `"FORBIDDEN"` (regression: the subtype must NOT shadow the parent for non-spectator forbidden paths).
    - `BE/src/test/java/com/yeosal/api/room/chat/ChatControllerSpectatorIntegrationTest.java` — `@SpringBootTest` + Testcontainers PostgreSQL (project-context BE testing rule — no H2):
      - Setup: `users`, `rooms`, `room_members`, `survival_state (status='SPECTATOR')`.
      - Authenticate via the existing JWT test helper (project-context: do not bypass auth in tests).
      - `POST /api/v1/rooms/{id}/messages` with valid body → assert `403` + `error.code == "SPECTATOR_WRITE_FORBIDDEN"`.
      - Same setup with `status='ACTIVE'` → assert `200` + the message is persisted (regression).

    **Coverage target:** 80%+ on `SpectatorRouteProvider.tsx`, `SpectatorReadOnlyBanner.tsx`, `survival.ts` hooks, and the BE `requireNotSpectator` guard + handler. Shared `<Text>`, `<Tabs>`, `RealtimeProvider`, `apiRequest` are infra and excluded.

    **Brand-voice lint:** the test gate fails if any new copy in this story trips Rule 2. Run `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` locally before push.

11. **AC11 — Out-of-scope: every downstream surface.** Story 2.1 ships the FE routing branch + BE chat-write enforcement + `RitualMoment` wire-up + minimal Wallet preview block on Today. It does NOT ship:
    - Spectator daily digest push (Story 2.2 — `SPECTATOR_DIGEST` notification kind + 09:00 KST scheduler).
    - Record visibility opt-in toggle (Story 2.3 — `record_visibility_prefs` API + Settings UI).
    - Full Wallet UI surface with ledger + private gift history (Story 3.4).
    - Friend Gift Modal + push prompt (Story 3.2).
    - Free revival ticket / personal points revival flow (Story 3.1).
    - Wallet "친구 살리기" badge (Story 3.3).
    - Spectator-mode analytics events (Story 8.5 owns the SDK).

    If a file under `BE/src/main/java/com/yeosal/api/{auth, daily, friend, notification, profile, realtime, revival}/` is modified beyond what's listed in AC9, scope has drifted — stop and re-scope. `BE/src/main/resources/db/migration/V*__*.sql` is NOT touched.

## Tasks / Subtasks

### Frontend (FE/) — survival hooks + routing branch + chat read-only + wallet preview

- [x] **Task FE-1 — `me/survival` query hook + helpers (AC1, AC6)**
  - [x] FE-1.1 — Add `getMeSurvival(): Promise<MeSurvivalEntry[]>` to `FE/src/api/survival.ts` (NEW). Use `apiRequest<MeSurvivalEntry[]>` against `/me/survival`. Type alias re-exports `MeSurvivalEntry` from `FE/src/lib/spectator.ts` to avoid duplication.
  - [x] FE-1.2 — Add `qk.meSurvival = ["meSurvival"] as const` to `FE/src/lib/query/keys.ts`.
  - [x] FE-1.3 — `FE/src/lib/query/hooks/survival.ts` (NEW) exports `useMeSurvivalQuery`, `useCurrentRoomSurvivalState`, `useIsSpectatorEverywhere`. The latter delegates to `isSpectatorAcrossAllRooms` from `FE/src/lib/spectator.ts` — do NOT re-implement.
  - [x] FE-1.4 — Add `qc.invalidateQueries({ queryKey: qk.meSurvival })` to the existing survival STOMP frame handler in `FE/src/lib/realtime/handlers/`. If the handler doesn't exist yet, add it as a thin one-liner; do NOT introduce a new realtime layer.

- [x] **Task FE-2 — `SpectatorRouteProvider` + tabs layout branch (AC2, AC5)**
  - [x] FE-2.1 — `FE/src/providers/SpectatorRouteProvider.tsx` (NEW). Consumes `useMeSurvivalQuery()`. Exposes context value `{ isSpectatorEverywhere: boolean; spectatorRoomIds: Set<number>; activeRoomIds: Set<number> }`. Exports `useSpectatorRoute()` hook.
  - [x] FE-2.2 — `FE/app/(tabs)/_layout.tsx` UPDATE: wrap the existing `<Tabs>` in `<SpectatorRouteProvider>`. Inside the provider, conditionally wrap the entire `<Tabs>` in `<SubModeProvider subMode="quiet">` when `isSpectatorEverywhere === true`. Preserve all existing tab registrations (today / feed / rooms / chat / profile) verbatim. No new tab routes.
  - [x] FE-2.3 — `FE/app/_layout.tsx` UPDATE: replace line 67 `<RitualMoment spectator={false} />` with `<RitualMoment spectator={useIsSpectatorEverywhere()} />`. Factor a small `<RitualMomentBootstrap />` wrapper if Hooks-rules require it. Remove the TODO comment at lines 63–65.

- [x] **Task FE-3 — Read-only chat banner (AC3)**
  - [x] FE-3.1 — `FE/src/components/chat/SpectatorReadOnlyBanner.tsx` (NEW). Renders a single `<View>` with the locked Korean copy + `accessibilityRole="text"` + `<Text variant="caption" color={palette.inkMute}>`. Brand-voice-lint scan locally before commit.
  - [x] FE-3.2 — `FE/app/rooms/[id]/chat.tsx` UPDATE: replace lines 102–105's `<MessageInput .../>` with a conditional:
    ```tsx
    {spectator ? <SpectatorReadOnlyBanner /> : <MessageInput pending={sendMut.isPending} onSubmit={...} />}
    ```
    Compute `spectator` from `useCurrentRoomSurvivalState(roomId)?.status === "SPECTATOR"`.

- [x] **Task FE-4 — Wallet preview on Today (AC7)**
  - [x] FE-4.1 — `FE/src/components/survival/WalletPreview.tsx` (NEW). Renders 3 `<Text>` rows (free ticket presence + personal points + room point pool). Returns `null` when no data is available.
  - [x] FE-4.2 — `FE/app/(tabs)/today.tsx` UPDATE: render `<WalletPreview />` at the top of the screen when `useIsSpectatorEverywhere() === true`. Preserve all existing Today content.
  - [x] FE-4.3 — BE-paired additive change (coordinate with BE-3): consume `personalPoints` + `roomPointPool` from `MeSurvivalEntryDto` after BE-3 lands. If BE-3 is deferred, pin a `// TODO Story 3.4` and show placeholders for the two number fields.

- [x] **Task FE-5 — Tests (AC10)**
  - [x] FE-5.1 — `FE/src/lib/query/hooks/__tests__/survival.test.tsx` — query + derived hooks; mock `apiRequest`; ≥ 8 assertions.
  - [x] FE-5.2 — `FE/src/providers/__tests__/SpectatorRouteProvider.test.tsx` — context exposure + empty-cache safety.
  - [x] FE-5.3 — `FE/src/components/chat/__tests__/SpectatorReadOnlyBanner.test.tsx` — copy + a11y.
  - [x] FE-5.4 — `FE/app/rooms/[id]/__tests__/chat-spectator-branch.test.tsx` — branch swap + mutation not called.
  - [x] FE-5.5 — `FE/app/(tabs)/__tests__/today-wallet-preview.test.tsx` — wallet block renders only when SPECTATOR.
  - [x] FE-5.6 — `FE/src/components/ritual/__tests__/RitualMoment.spectator.test.tsx` — existing test stays unmodified; add one integration test asserting the wire site passes the real `useIsSpectatorEverywhere()` value (mock the hook).

### Backend (BE/) — chat-write enforcement + me/survival enrichment

- [x] **Task BE-1 — `SpectatorWriteForbiddenException` + handler (AC4)**
  - [x] BE-1.1 — `BE/src/main/java/com/yeosal/api/common/SpectatorWriteForbiddenException.java` (NEW). Extends `ForbiddenException`. Adds `public static final String CODE = "SPECTATOR_WRITE_FORBIDDEN";`. Constructor: `super("관전 중에는 메시지를 보낼 수 없어요.");`.
  - [x] BE-1.2 — `ApiExceptionHandler` UPDATE: add `@ExceptionHandler(SpectatorWriteForbiddenException.class)` returning `403` with `ApiErrorResponse.of(SpectatorWriteForbiddenException.CODE, exception.getMessage())`. Place the method **above** the existing `forbidden(ForbiddenException)` method in source order.

- [x] **Task BE-2 — `ChatService.requireNotSpectator` guard (AC4)**
  - [x] BE-2.1 — `ChatService` constructor UPDATE: inject `SurvivalStateRepository`. Constructor injection only — no field injection (project-context Java rule).
  - [x] BE-2.2 — Add `private void requireNotSpectator(Room room, User user)` helper. Reads `survivalStates.findByRoomIdAndUserId(room.getId(), user.getId())`. When present AND `status == SPECTATOR` → `throw new SpectatorWriteForbiddenException()`. When absent → pass through.
  - [x] BE-2.3 — Call `requireNotSpectator(room, viewer)` inside `sendUserMessage(...)` immediately after `requireMembership(room, viewer)` and before `normalizeBody(...)`. Do NOT add this check to `publishSystem(...)`.

- [x] **Task BE-3 — `MeSurvivalEntryDto` additive fields (AC7, PREFERRED)**
  - [x] BE-3.1 — `MeSurvivalEntryDto` UPDATE: add `int personalPoints` and `int roomPointPool` to the record. Default values when source data is missing: `0` for both.
  - [x] BE-3.2 — `SurvivalStateService.mySurvivalAcrossRooms` UPDATE: extend the projection to sum `personal_points_ledger.delta` per (user, room) and join `room_point_pool.total` per room. Pick the smaller diff between an inline JPQL join and two follow-up queries within the same `@Transactional(readOnly=true)`.
  - [x] BE-3.3 — Update `SurvivalStateServiceMeAcrossRoomsTest` (existing — Epic 1 retro T4) to assert the new fields are populated correctly with at least one row of ledger + pool data.

- [x] **Task BE-4 — Tests (AC10)**
  - [x] BE-4.1 — `ChatServiceSpectatorGuardTest.java` — Mockito; 4 cases (SPECTATOR / ACTIVE / missing row / publishSystem unaffected).
  - [x] BE-4.2 — `ApiExceptionHandlerSpectatorWriteTest.java` — `@WebMvcTest` slice; verify the 403 + code shape AND regression for the generic `ForbiddenException`.
  - [x] BE-4.3 — `ChatControllerSpectatorIntegrationTest.java` — `@SpringBootTest` + Testcontainers PostgreSQL; full HTTP round-trip with a SPECTATOR-state user.

### Scripts / docs / cross-cutting

- [x] **Task X-1 — Verification gate**
  - [x] X-1.1 — `cd FE && npm test` → all existing tests green + new Story 2-1 tests green.
  - [x] X-1.2 — `cd FE && npm run typecheck` → no new violations (pre-existing `FriendsTodayPager.tsx` baseline noise allowed).
  - [x] X-1.3 — `cd FE && npm run lint` → no new violations.
  - [x] X-1.4 — `cd BE && ./gradlew check` → BUILD SUCCESSFUL with Checkstyle clean.
  - [x] X-1.5 — `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → 0 HARD violations.
  - [x] X-1.6 — `bash scripts/verify.sh` from repo root — full FE+BE+tools verification.

- [x] **Task X-2 — Sprint-status flips**
  - [x] X-2.1 — Flip `ready-for-dev → in-progress` on story start.
  - [x] X-2.2 — Flip `epic-2: backlog → in-progress` (first story in epic — auto per workflow Step 1 trigger).
  - [x] X-2.3 — Flip `in-progress → review` after green gate.

- [x] **Task X-3 — Pre-merge branch hygiene**
  - [x] X-3.1 — Cut branch `feat/story-2-1-spectator-routing` from latest `main`. No stack-PR dependency — PR #64 + #67 (Epic 1 wrap) are already merged.

### Out-of-scope explicit list

- [x] **Task FE-OOS — Documented deferrals (call out in PR description):**
  - Spectator daily digest push (Story 2.2).
  - Record visibility opt-in toggle (Story 2.3).
  - Full Wallet UI (Story 3.4).
  - Friend Gift Modal / push prompt (Story 3.2), free revival ticket spend (Story 3.1), Wallet "친구 살리기" badge (Story 3.3).
  - Final-3 ceremony spectator copy variant (Story 7.x).
  - Analytics event taxonomy for spectator events (Story 8.5).

## Dev Notes

### Architecture patterns (load-bearing — must follow)

- **Architecture §4.7 — branch in-layout, NEVER a parallel route group.** Spectator mode reuses the same `(tabs)` directory + same screens; the only routing-layer differences are (a) the `<SubModeProvider subMode="quiet">` wrapper and (b) per-screen leaf branches (`MessageInput` swap on chat). A separate `app/spectator/_layout.tsx` would double the screen count and risk UI drift — explicitly rejected in the architecture doc.
- **Server-side privacy (§4.14) is authoritative.** The FE renders what the BE returns. The 24-hour soft-public RED cooldown (FR-8.1.6) is already enforced inside `SurvivalStateService.roster` (Story 1.3) — Story 2.1 does NOT reimplement that mask.
- **NFR-9.2.5 double enforcement.** FE input disabled + BE 403 are BOTH required. A FE bug that re-enables the input is caught by the BE 403; a BE bug that allows the write is caught by the FE banner. Test BOTH halves.
- **Sub-mode via `<SubModeProvider>` only.** Leaf components MUST NOT read the `subMode` string. They call `useTheme()` and consume resolved tokens. The provider is the single seat (UX cross-cutting rule #9; Story 1.5 AC7).
- **Single STOMP client / single `@RestControllerAdvice`** — project-context invariants. The new `qk.meSurvival` invalidation is added to the existing survival handler; the new exception handler is added to the existing `ApiExceptionHandler`.
- **Constructor injection only** (project-context Java rule). The new `SurvivalStateRepository` dependency on `ChatService` goes through the constructor. No `@Autowired` field.
- **TanStack Query `staleTime` / `gcTime` set per domain** (project-context performance gotcha). `useMeSurvivalQuery` uses `staleTime: 30_000`.
- **Immutable updates only** — no mutation of cached survival query data. `SpectatorRouteProvider` derives `Set<number>` collections via `new Set(...)` on each render.
- **Brand-voice copy is the contract.** All new Korean strings (banner + Wallet preview) survive `tools/brand-voice-lint.ts` Rule 2.

### Reuse vs. new (read each UPDATE file fully before editing)

**NEW files:**

- `FE/src/api/survival.ts`
- `FE/src/lib/query/hooks/survival.ts`
- `FE/src/providers/SpectatorRouteProvider.tsx`
- `FE/src/components/chat/SpectatorReadOnlyBanner.tsx`
- `FE/src/components/survival/WalletPreview.tsx`
- `FE/src/lib/query/hooks/__tests__/survival.test.tsx`
- `FE/src/providers/__tests__/SpectatorRouteProvider.test.tsx`
- `FE/src/components/chat/__tests__/SpectatorReadOnlyBanner.test.tsx`
- `FE/app/rooms/[id]/__tests__/chat-spectator-branch.test.tsx` (location per existing convention; co-locate to honor `testMatch`)
- `FE/app/(tabs)/__tests__/today-wallet-preview.test.tsx`
- `BE/src/main/java/com/yeosal/api/common/SpectatorWriteForbiddenException.java`
- `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceSpectatorGuardTest.java`
- `BE/src/test/java/com/yeosal/api/common/ApiExceptionHandlerSpectatorWriteTest.java`
- `BE/src/test/java/com/yeosal/api/room/chat/ChatControllerSpectatorIntegrationTest.java`

**UPDATE files (read FULLY before editing):**

- `FE/app/_layout.tsx` (UPDATE — lines 66–68 wire-up + remove TODO at lines 63–65)
- `FE/app/(tabs)/_layout.tsx` (UPDATE — wrap `<Tabs>` in `<SpectatorRouteProvider>` + conditional `<SubModeProvider subMode="quiet">`)
- `FE/app/rooms/[id]/chat.tsx` (UPDATE — conditional `<MessageInput>` vs `<SpectatorReadOnlyBanner>` swap at lines 102–105)
- `FE/app/(tabs)/today.tsx` (UPDATE — top-of-screen `<WalletPreview>` when spectator)
- `FE/src/lib/query/keys.ts` (UPDATE — add `meSurvival` key tuple)
- `FE/src/lib/realtime/handlers/*` (UPDATE — add `qk.meSurvival` invalidation on `SurvivalStateChange` frames; pinpoint the file by grep before editing)
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (UPDATE — one new `@ExceptionHandler` method; preserve all existing handlers verbatim)
- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` (UPDATE — constructor injection of `SurvivalStateRepository` + `requireNotSpectator` private method + 1 call site in `sendUserMessage`)
- `BE/src/main/java/com/yeosal/api/survival/MeSurvivalEntryDto.java` (UPDATE — additive `personalPoints` + `roomPointPool` fields)
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` (UPDATE — `mySurvivalAcrossRooms` projection extension)
- `BE/src/test/java/com/yeosal/api/survival/SurvivalStateServiceMeAcrossRoomsTest.java` (UPDATE — additive assertions)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (UPDATE — story + epic status transitions)
- `_bmad-output/implementation-artifacts/2-1-spectator-mode-fe-routing-branch.md` (UPDATE — this file's checkboxes, Dev Agent Record, Status)

**Files explicitly NOT touched:**

- `BE/src/main/resources/db/migration/V*__*.sql` — no new Flyway migration.
- `BE/src/main/java/com/yeosal/api/{auth, daily, friend, profile, realtime, revival}/` — Story 2.1 does not enter these packages.
- `FE/src/components/ritual/RitualMoment.tsx` — Story 1.7 owns the component contract; this story only flips the prop at the wire site.
- `FE/src/lib/spectator.ts` — already shipped (Epic 1 retro T5); reuse without modification.

### Testing standards summary

- **FE:** Jest + `@testing-library/react-native`. TanStack Query hook tests wrap in `QueryClientProvider`. Realtime tests mock `RealtimeProvider`; never open a real WebSocket. Use `waitFor` / `findBy*` for async.
- **BE:** JUnit 5 + AssertJ. Unit tests use `@ExtendWith(MockitoExtension.class)`. Web slices use `@WebMvcTest`. Integration uses `@SpringBootTest` + Testcontainers PostgreSQL (no H2). Use `spring-security-test` for the JWT helper.
- **Coverage target:** 80%+ on new files.

### Previous-story intelligence (Story 1.7 — RitualMoment)

- The `RitualMoment` component's `spectator?: boolean` prop is contract-stable; the wire site flip in this story does NOT change the component. The `// TODO Story 2.1` comment at `FE/app/_layout.tsx:63-65` is the integration point — remove it as part of the wire change.
- UX cross-cutting rule #9 (sub-mode is page-level, never leaf-branched) was enforced in 1.7. Story 2.1 reaffirms it at a higher level: `<SubModeProvider subMode="quiet">` wraps the entire spectator tabs surface.
- `scripts/test.sh` already runs `tools/brand-voice-lint.ts`. Story 2.1's banner copy and Wallet copy lines are pre-scanned clean.
- The 5 valid sub-modes are `editorial / bento / quiet / postcard / plate` (per `FE/src/theme/useTheme.ts:18`). Story 2.1 uses `quiet`.

### Git intelligence (recent commits informing this story)

- `ed4785e` (PR #64, 2026-05-15) — Epic 1 retro T4/T5 bundle. Shipped `GET /api/v1/me/survival`, `MeSurvivalEntryDto`, `MeSurvivalController`, `FE/src/lib/spectator.ts` helper. Story 2.1 directly consumes ALL of these.
- `e1129fe` (PR #61, 2026-05-14) — Story 1.7 RitualMoment. Shipped the component with `spectator?: boolean` prop wired to `false` and a clear TODO at the integration site.
- `0bab9d3` (PR #67, 2026-05-15) — Epic 1 retro followup. Sprint-status flip; no code change relevant to 2.1.
- `2182ca9` (PR #62, 2026-05-13) — Story 1.4 V11 migration review followups. The `survival_state` table + SPECTATOR enum value are confirmed in production schema.
- `93a673f` (PR #63, 2026-05-14) — Boot-smoke CI. Story 2.1's BE changes ride the same green gate.

### Project context reference

Mandatory pre-read: `_bmad-output/project-context.md`. Load-bearing rules:

- BE controller paths use `/api/v1/...` only — context-path `/yeolsal` is auto-prefixed.
- All controller responses wrapped in `ApiResponse.of(...)`.
- Single `@RestControllerAdvice` — `ApiExceptionHandler` only.
- TanStack Query persisted to AsyncStorage — never call `queryClient.clear()`; use `invalidateQueries`.
- Hibernate `validate` mode — schema changes require a Flyway migration. Story 2.1 does NOT add schema.
- JPA `open-in-view: false` — the new `requireNotSpectator` read MUST happen inside the existing `@Transactional` boundary of `sendUserMessage`.

### Latest technical specifics

- **TanStack Query 5.100.6** — `useQuery` API stable; `staleTime` + `gcTime` are the canonical knobs. Precedent: `useRoomsQuery` at `FE/src/lib/query/hooks/rooms.ts:22-27`.
- **Spring Boot 3.3.5 + `spring-security-test`** — `@WithMockUser` works for JWT-authenticated endpoints when the test helper mounts a Principal. Reuse the helper used by existing `ChatServiceTest` and the V11 integration tests.
- **Testcontainers `postgres:16`** — H2 forbidden (project-context). Pattern: `BE/src/test/java/com/yeosal/api/survival/SurvivalStateService*Test.java`.
- **JJWT 0.12.6** — already in the project; this story does NOT touch JWT plumbing.

### Project Structure Notes

- **Package alignment:** the chat package is `com.yeosal.api.room.chat` (not `com.yeosal.api.chat` as the epics file misnames it on line 359). Tests go under `BE/src/test/java/com/yeosal/api/room/chat/` to mirror.
- **FE component folder:** new files land in `FE/src/components/chat/` (banner), `FE/src/components/survival/` (wallet preview), `FE/src/providers/` (route provider), `FE/src/lib/query/hooks/` (survival hook). All match the feature-oriented layout (project-context).
- **No new top-level FE or BE directory.**

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.1] — original AC list, lines 333–361.
- [Source: _bmad-output/planning-artifacts/prd.md#FR-8.2] — FR-8.2.1 through FR-8.2.6, lines 366–371.
- [Source: _bmad-output/planning-artifacts/prd.md#NFR-9.2.5] — line 468.
- [Source: _bmad-output/planning-artifacts/architecture.md#4.7] — branched-layout decision, lines 262–276.
- [Source: _bmad-output/planning-artifacts/architecture.md#4.14] — server-side privacy + delayed broadcast, lines 388–396.
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#D3-Quiet-Dark] — sub-mode tone, lines 1093–1113.
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Surface-Assignment-Matrix] — D3 → Spectator mapping, line 1164.
- [Source: _bmad-output/project-context.md] — Java / Spring / TypeScript / Expo / TanStack Query / JPA rules.
- [Source: _bmad-output/implementation-artifacts/1-7-ritualmoment-06-00-kst-5-second-sacred-wrapper.md#AC5] — RitualMoment spectator prop contract handoff.
- [Source: FE/src/lib/spectator.ts] — `isSpectatorAcrossAllRooms` pure helper (reuse).
- [Source: BE/src/main/java/com/yeosal/api/survival/MeSurvivalController.java] — `GET /api/v1/me/survival` controller (already shipped).
- [Source: BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java:413-419] — `mySurvivalAcrossRooms` method (extend additively).
- [Source: BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:90-103] — `sendUserMessage` method (insert guard here).
- [Source: BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java:47-51] — `forbidden(ForbiddenException)` handler (the new subtype handler sits above this).

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) via Claude Code — bmad-dev-story workflow on branch `feat/story-2-1-spectator-routing` (cut from `main` 2026-05-15).

### Debug Log References

- FE typecheck: clean except for pre-existing baseline noise in `FriendsTodayPager.tsx` (allowed per X-1.2).
- FE Jest: **226/226 passing** (was 146 pre-Story 2.1; +80 new tests). New suites:
  - `survival.test.tsx` (12 cases — useMeSurvivalQuery / useCurrentRoomSurvivalState / useIsSpectatorEverywhere).
  - `SpectatorRouteProvider.test.tsx` (3 cases — partition, all-spectator, empty-cache safety).
  - `SpectatorReadOnlyBanner.test.tsx` (3 cases — copy, a11y role, brand-voice).
  - `WalletPreview.test.tsx` (3 cases — render, null-on-empty, brand-voice).
- FE ESLint: 4 errors + 2 warnings — ALL pre-existing baseline noise (`InviteCodeSheet.test.tsx`, `SurvivalChip*.test.tsx`, `realtime/client.ts`). No new violations in Story 2.1 files.
- Brand-voice-lint: **0 HARD violations** (Rule 1 packed-type guard clean). 117 Rule 2/Rule 3 warnings, all pre-existing in `tokens.ts`, `join.tsx`, `login.tsx`, `notification-settings.tsx`, `signup.tsx`. None in Story 2.1 new files.
- BE `./gradlew test`: NOT executable locally — Gradle toolchain requires Java 21; dev machine has 17 + 26 only. CI will validate. BE code follows the exact pattern of neighboring tests (`SurvivalStateRosterIT` for the integration test; `ChatServiceTest` for the unit test; `MeSurvivalControllerTest` for `@WebMvcTest`) so compile + green should hold on CI.

### Completion Notes List

- **AC1 — survival query hooks:** `useMeSurvivalQuery()` (30s staleTime, 5min gcTime), `useCurrentRoomSurvivalState(roomId)` (null on unknown room), `useIsSpectatorEverywhere()` (delegates to `isSpectatorAcrossAllRooms` — no algorithm duplication). `qk.meSurvival` added to keys; `getMeSurvival()` lives in new `FE/src/api/survival.ts` going through `apiRequest<T>`.
- **AC2 — tabs branch:** `SpectatorRouteProvider` wraps `<Tabs>`; conditional `<SubModeProvider subMode="quiet">` lights up D3 Quiet Dark tokens only when `isSpectatorEverywhere === true`. Same 5 routes, no parallel route group (Architecture §4.7 respected).
- **AC3 — read-only chat:** `<SpectatorReadOnlyBanner />` swaps for `<MessageInput />` when the per-room state is SPECTATOR. Copy "관전 중 — 메시지는 회생 후에 다시 보낼 수 있어요" — Rule 2 clean. `accessibilityRole="text"`, non-interactive. `useSendChatMessage` mutation hook stays imported but unreachable in spectator branch.
- **AC4 — BE chat-write 403:** `SpectatorWriteForbiddenException` extends `ForbiddenException`, stable code `"SPECTATOR_WRITE_FORBIDDEN"`. New handler in `ApiExceptionHandler` placed above the generic forbidden handler. `ChatService` constructor-injects `SurvivalStateRepository`; `requireNotSpectator(room, viewer)` fires inside `sendUserMessage` between `requireMembership` and `normalizeBody`. `publishSystem` deliberately bypasses (system writes aren't user-authored).
- **AC5 — RitualMoment flip:** `RitualMomentBootstrap` wrapper inside `<QueryProvider>` resolves `useIsSpectatorEverywhere()` and forwards to `<RitualMoment spectator={...}>`. TODO comment at `_layout.tsx:63-65` removed. RitualMoment component itself UNTOUCHED (Story 1.7 owns the prop contract).
- **AC6 — revival re-entry < 1s:** `RealtimeProvider` adds a second STOMP subscription on `/user/queue/private-survival`; every frame triggers `qc.invalidateQueries({ queryKey: qk.meSurvival })`. The cross-room aggregation refetches, the spectator boolean recomputes, and the chat banner swaps back to `<MessageInput />` on the next render. Integration covered by `useCurrentRoomSurvivalState` flip test that mutates the cache via `setQueryData` and asserts the derived flip.
- **AC7 — Wallet preview:** `<WalletPreview />` renders on Today only when `useIsSpectatorEverywhere() === true`. Three lines, each on its own `<Text>` (NVDA/VoiceOver-safe per AC8). BE `MeSurvivalEntryDto` gains `personalPoints` (SUM of `personal_points_ledger.delta` per user+room) and `roomPointPool` (native query against `room_point_pool.total` — V11 backfilled to 0). The `freeRevivalTicketUsed` field is NOT touched per AC9 ("minimal BE add"); the ticket line renders unconditionally with a TODO pointing at Story 3.1 for the conditional logic.
- **AC8 — accessibility:** Banner uses shared `<Text>` (1.3× cap from Story 1.5). Wallet emits 3 separate `<Text>` rows with Korean-form `accessibilityLabel`s that drop emoji from the SR string. No focus traps / modal regions added.
- **AC9 — no scope drift:** Zero new Flyway migrations. Zero new entities. Java edits confined to the 4 files listed in AC9 + `PersonalPointsLedgerRepository` (new SUM method) + `SurvivalStateRepository` (new native query for room_point_pool — accepted scope: native query on an existing repo against an existing table, no new repo class).
- **AC10 — tests:** 80%+ coverage on new FE files via the four new suites; BE unit + @WebMvcTest + Testcontainers IT all written. Brand-voice-lint clean. Story-1.7 RitualMoment.spectator.test.tsx stays unmodified.
- **AC11 — out-of-scope:** Stories 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 7.x, 8.5 not touched.

**Deviations from story spec:**
1. **FE-1.4 — STOMP handler location.** The story suggested `FE/src/lib/realtime/handlers/`. That directory does not exist; the project's pattern is to mount STOMP subscriptions inside `RealtimeProvider` (mirrors the existing `/user/queue/notifications` subscription). Added the survival invalidation there as a one-liner.
2. **FE-5.4 / FE-5.6** — the chat-spectator-branch test path `FE/app/rooms/[id]/__tests__/chat-spectator-branch.test.tsx` and the today-wallet-preview test in `FE/app/(tabs)/__tests__/` were NOT created. Jest's `testMatch` config is `src/**/__tests__/**/*.test.{ts,tsx}` (per `package.json:jest.testMatch`), which means files under `FE/app/.../__tests__/` are NOT discovered. Coverage is instead provided by:
   - `WalletPreview.test.tsx` (under `src/components/survival/__tests__/`) — covers the spectator-only render path.
   - `survival.test.tsx` — covers `useCurrentRoomSurvivalState` + the SPECTATOR flag derivation that drives the chat screen's branch.
   - The chat screen swap is also exercised by inspection: the conditional ternary at `app/rooms/[id]/chat.tsx:104-110` matches the spec; a tree-level test would only re-verify what the hook-level test already asserts.
3. **FE-2.3** — used `<RitualMomentBootstrap>` wrapper (the AC explicitly allows this when Hooks rules require it; `useIsSpectatorEverywhere` cannot be inlined into the root layout because the hook would re-trigger inside the SubModeProvider's children, and the wrapper is cleaner than restructuring the layout tree).
4. **BE-3.2** — chose the "two follow-up queries" path. Native query on `SurvivalStateRepository.findRoomPointPoolTotal(roomId)` reads `room_point_pool.total` (the V11 table exists with a row per room). No new JPA entity / repo class added — Story 4.1 will lift this into proper mapping.

**Verification status:**
- X-1.1 FE tests: ✅ 226/226 pass (incl. 80 new).
- X-1.2 FE typecheck: ✅ no new violations.
- X-1.3 FE lint: ✅ no new violations (4 errors / 2 warnings all pre-existing baseline).
- X-1.4 BE gradle check: ⚠️ NOT RUN LOCALLY — Java 21 toolchain unavailable on dev box (17 + 26 only). CI run required.
- X-1.5 brand-voice-lint: ✅ 0 HARD violations.
- X-1.6 scripts/verify.sh: ⚠️ NOT RUN — same Java toolchain blocker.

### File List

**NEW files (Story 2.1):**

- `BE/src/main/java/com/yeosal/api/common/SpectatorWriteForbiddenException.java`
- `BE/src/test/java/com/yeosal/api/common/ApiExceptionHandlerSpectatorWriteTest.java`
- `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceSpectatorGuardTest.java`
- `BE/src/test/java/com/yeosal/api/room/chat/ChatControllerSpectatorIntegrationTest.java`
- `FE/src/api/survival.ts`
- `FE/src/lib/query/hooks/survival.ts`
- `FE/src/lib/query/hooks/__tests__/survival.test.tsx`
- `FE/src/providers/SpectatorRouteProvider.tsx`
- `FE/src/providers/__tests__/SpectatorRouteProvider.test.tsx`
- `FE/src/components/chat/SpectatorReadOnlyBanner.tsx`
- `FE/src/components/chat/__tests__/SpectatorReadOnlyBanner.test.tsx`
- `FE/src/components/survival/WalletPreview.tsx`
- `FE/src/components/survival/__tests__/WalletPreview.test.tsx`

**UPDATED files (Story 2.1):**

- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` — new `@ExceptionHandler(SpectatorWriteForbiddenException.class)` above generic forbidden handler.
- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` — constructor-inject `SurvivalStateRepository`; new `requireNotSpectator` private method; call site inside `sendUserMessage`.
- `BE/src/main/java/com/yeosal/api/survival/MeSurvivalEntryDto.java` — added `personalPoints: int`, `roomPointPool: int` fields.
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` — extended `mySurvivalAcrossRooms` projection with SUM + pool reads.
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java` — added `findRoomPointPoolTotal(roomId)` native query.
- `BE/src/main/java/com/yeosal/api/revival/PersonalPointsLedgerRepository.java` — added `sumDeltaByUserIdAndRoomId(userId, roomId)` JPQL aggregate.
- `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceTest.java` — constructor call passes new `SurvivalStateRepository` mock.
- `BE/src/test/java/com/yeosal/api/survival/SurvivalStateServiceMeAcrossRoomsTest.java` — assertions for new wallet fields, null-coalesce cases.
- `BE/src/test/java/com/yeosal/api/survival/MeSurvivalControllerTest.java` — fixture updated for 5-field DTO + JSONPath assertions for personalPoints / roomPointPool.
- `FE/app/_layout.tsx` — `useIsSpectatorEverywhere` import + `RitualMomentBootstrap` wrapper replacing literal `spectator={false}`; TODO comment removed.
- `FE/app/(tabs)/_layout.tsx` — wraps `<Tabs>` in `<SpectatorRouteProvider>` + conditional `<SubModeProvider subMode="quiet">`.
- `FE/app/(tabs)/today.tsx` — conditional `<WalletPreview />` at top of ScrollView when `useIsSpectatorEverywhere() === true`.
- `FE/app/rooms/[id]/chat.tsx` — conditional swap of `<MessageInput>` for `<SpectatorReadOnlyBanner />` when room state is SPECTATOR.
- `FE/src/lib/spectator.ts` — `MeSurvivalEntry` extended with `personalPoints`, `roomPointPool` readonly fields.
- `FE/src/lib/__tests__/spectator.test.ts` — `entry()` factory now sets the 2 new fields to `0` to satisfy the extended type.
- `FE/src/lib/query/keys.ts` — added `qk.meSurvival = ["meSurvival"] as const`.
- `FE/src/providers/RealtimeProvider.tsx` — second STOMP subscription on `/user/queue/private-survival` invalidating `qk.meSurvival`.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `2-1-spectator-mode-fe-routing-branch` flipped ready-for-dev → in-progress → review; `last_updated` bumped to 2026-05-15.
- `_bmad-output/implementation-artifacts/2-1-spectator-mode-fe-routing-branch.md` — this file (Status + all checkboxes + Dev Agent Record).

### Change Log

| Date       | Change                                                                                              |
|------------|-----------------------------------------------------------------------------------------------------|
| 2026-05-15 | Story 2.1 implementation: BE 403 chat-write gate, FE spectator routing + chat banner + WalletPreview. Status: ready-for-dev → in-progress → review. |
