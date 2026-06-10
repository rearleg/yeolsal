# Story 8.1: 5-screen onboarding flow

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a new user arriving from a Kakao share link or organic install,
I want a 5-screen onboarding that explains the loop, the no-money v1 stance, and the 14-day grace — all in brand voice — and that asks for analytics consent on screen 5,
so that I land on Day 0 with the right mental model (dignity, not penalty) and the SDK Story 8.5 shipped finally has a recorded consent decision.

**Strategic context (read once before opening any file):**

- This is the **PIPA-prompt seat** that Story 8.5 deliberately deferred (Story 8.5 AC4 "Default-state contract — Story 8.5 ships SDK + helpers; Story 8.1 owns the prompt"). The onboarding flow is fail-closed until the user records a decision; that's correct, expected behaviour while the user works through S1–S4.
- Per Story 8.5 AC5, `signup.completed`, `onboarding.screen.dwell_ms`, and `onboarding.completed` are listed in the locked taxonomy with emission site = "Story 8.1". **This story is the only place those three events get instrumented.** No other backfill (revival.* / friend_gift.* / spectator.* / final_three.*) belongs in this story — those stay deferred per Story 8.5 OOS #2.
- `first_daily_entry` is also listed in Funnel 1 but the emission site is `useDailyEntryMutation` success — that hook lives in Today, not onboarding. Story 8.1 OOS it explicitly.
- The epics-line "returning user post-cutover sees a single change-summary screen" is for accounts that authenticated against a prior app version that did NOT have onboarding state. We detect this via the absence of the `yeosal.onboardingState` SecureStore record AND the presence of refreshable auth tokens — i.e., a session that survives restoreSession but has no onboarding record. No BE column, no DB read.
- **Default PIPA state lock (this story authors the lock):** the S5 consent checkbox **defaults unchecked** (explicit opt-in). KR PIPA strict reading + brand-voice "invitation, not demand" both push to opt-in-only-when-affirmed. Story 8.5's `bootstrapAnalytics()` is already `defaultOptIn: false`, so an undecided + unchecked S5 path resolves to opt-out at runtime without any SDK reshape.
- This story is **FE-only**. Zero BE files. Zero migration. Zero new endpoints. Zero STOMP topics. Zero new RealtimeEvent variants. Zero new NotificationKind values. Zero tokens.json edits.

## Acceptance Criteria

### AC0 — Existing infrastructure inventory (NO REWORK, READ ONLY)

**Read these before writing a line of code. Every one of these MUST stay byte-identical (no edits unless the file appears in AC14 MODIFIED):**

- `FE/app/_layout.tsx:42–103` — `RootLayout` JSX tree. `<AuthProvider>` / `<QueryProvider>` / `<KakaoSdkBootstrap>` / `<RealtimeProvider>` / `<ToastProvider>` / `<ErrorBoundary>` / Stack mount order. Story 8.1 inserts an `<OnboardingGate>` SIBLING component (not a wrapper) that watches `auth.user` + onboarding state and routes — no Stack restructuring.
- `FE/src/auth/AuthContext.tsx:85–113` — `signIn` / `signUp` / `signInWithKakao` flow. **Each one already returns `string | null` (`tryConsumePendingInvite`'s deeplink destination).** Story 8.1 does NOT change those signatures; instead, the caller (login.tsx, signup.tsx) defers to `OnboardingGate` for the final navigation when onboarding is required. The "deferred destination" handoff happens via a SecureStore slot, not a context value, to survive force-quit between auth + onboarding.
- `FE/src/auth/AuthContext.tsx:147–164` — `tryConsumePendingInvite()` reads-and-deletes `yeosal.pendingInviteCode` (the Kakao deeplink bridging slot). It runs **inside** `signIn / signUp / signInWithKakao` AND returns `/rooms/{id}/settings?onboarding=1` on success. Story 8.1's deferred-destination slot piggybacks this return value when onboarding interrupts.
- `FE/app/index.tsx` — root redirect (`auth.user ? "/today" : "/login"`). Story 8.1 inserts the onboarding gate BEFORE `/today` so authed users without an `onboardingState` record land on `/onboarding` instead. **Do NOT redirect from `index.tsx` to `/onboarding` directly** — keep the gate centralized in one place (`<OnboardingGate>` inside `_layout.tsx`) so login/signup/kakao all share it.
- `FE/app/login.tsx` + `FE/app/signup.tsx` — `router.replace(destination ?? "/today")` on success. Story 8.1 stashes the returned `destination` to SecureStore and lets `<OnboardingGate>` consume it; the call site becomes `router.replace("/today")` (gate redirects if onboarding owed). Mirror the Story 6.2 `pendingInviteCode` bridging shape exactly.
- `FE/src/lib/playedRevivalEvents.ts` — SecureStore-backed JSON record reference shape (15 lines of file-walk). **This is the reference shape for `FE/src/lib/onboardingState.ts`.** Same `STORAGE_KEY` prefix (`yeosal.*`), same JSON encode/decode, same defensive `unknown` narrowing, same "missing/parse-failure → treat as not yet completed" fallback semantics.
- `FE/src/lib/analyticsConsent.ts` — `setAnalyticsConsent("opt_in" | "opt_out")` is the seat the S5 checkbox writes to on "시작하기" submit. **Do NOT instantiate PostHog directly from onboarding code; do NOT call `posthog.optIn/optOut` directly.** Always go through `setAnalyticsConsent` — that helper centralizes the SecureStore write + SDK runtime flip (Story 8.5 AC4 lock).
- `FE/src/lib/analytics.ts` — `captureEvent(name: AnalyticsEventName, properties?)` is the seat for the three new emit points. The name set is a string-literal union — TS will reject any typo at compile time (`signup.complete` vs `signup.completed`).
- `FE/src/components/welcome/WelcomeWindow.tsx:18–24` + `FE/src/components/welcome/WelcomeWindow.tsx:75–101` — D4 Postcard sub-mode + `useTheme()` + native-driver fade-in shape. **This is the reference for S1's D4 hint card.** Story 8.1's S1 wraps in `<SubModeProvider subMode="postcard">` and reads the same `display.serif` / `motion.entry.duration` / `radius.pronounced` tokens.
- `FE/src/components/ritual/RitualMoment.tsx:58` + `FE/src/theme/motion.ts:40–58` — `useReducedMotion()` hook. **S1's D4 hint MUST gate the fade-in behind `useReducedMotion() === false`.** Reduced-motion path renders the card opaque from frame 1.
- `FE/src/providers/SubModeProvider.tsx` — `<SubModeProvider subMode="postcard">` wraps S1. The component never reads the sub-mode string (UX cross-cutting rule #9 — leaf components consume `useTheme()` only).
- `FE/app/privacy-settings.tsx` — Settings → Privacy → "사용 통계 공유" toggle precedent. Story 8.1 mirrors the same `getAnalyticsConsent / setAnalyticsConsent` API surface; the difference is the seat (S5 first-prompt vs Settings revocation) and the default (S5 = unchecked, Settings = whatever-was-recorded). **A user who toggles off in Settings then later wipes the SecureStore won't be re-prompted by Story 8.1** — the gate keys on `onboardingState.completed`, not on `analyticsConsent` presence.
- `FE/app/notification-settings.tsx:160–164` — `<Switch>` toggle-row JSX is fine reuse for S5's checkbox surface; alternatively, the spec accepts a `<Pressable>`-and-Checkbox-icon variant (RN has no native `<Checkbox>`). Either is acceptable; the brand-voice copy and the SecureStore wire-up are the load-bearing parts.
- `FE/src/lib/deepLinking.ts:21–98` — `yeosal.pendingInviteCode` slot is the only existing onboarding bridge. The room name + rule preview pre-population for S5 (epics line 999–1001) reads `tryConsumePendingInvite`'s **returned join destination** AND the room snapshot fetched at S5 mount (via `useRoomsQuery` since the join already happened). Do NOT add a second SecureStore slot for "pre-populated room preview" — `tryConsumePendingInvite` already happened before onboarding starts, so the user IS a member at S5 time.
- `FE/src/api/rooms.ts:101–116` — `listRooms()` + `createRoom()` shape. S5 reads the just-joined room via `useRoomsQuery()` (TanStack cache) and renders name + min-days label. No new endpoint.
- `FE/package.json` jest `testMatch` = `<rootDir>/src/**/__tests__/**/*.test.{ts,tsx}` — **tests under `FE/app/__tests__/` are NOT discovered.** Story 8.5 already learned this (Story 8.5 PR #102 review patch: privacy-settings test moved to `FE/src/__tests__/`). Story 8.1's `app/onboarding.tsx` tests live at `FE/src/__tests__/onboarding.test.tsx`. Do NOT create `FE/app/__tests__/` — it stays empty by design.
- `tools/brand-voice-lint.ts:50–58` — AVOID lexicon (`벌금`, `잃었다`, `떨어졌다`, `실패`, `자책`, `부담`, `패배`, `죄책감`). The 5 onboarding screen bodies are byte-locked from epics line 992–997 and contain ZERO of these terms. Verify before-and-after run shows 0 HARD new violations.

### AC1 — `FE/src/lib/onboardingState.ts` (NEW — SecureStore-backed durable record)

**File:** `FE/src/lib/onboardingState.ts` — NEW.
**Shape mirrors `FE/src/lib/playedRevivalEvents.ts` exactly** (SecureStore for durability, module-level cache for hot-path reads, JSON encode/decode, defensive `unknown` narrowing).

**Storage:**
- SecureStore key: `"yeosal.onboardingState"` (`yeosal.*` namespace per project-context.md:100).
- Stored value: JSON `{ version: 1, completedAt: ISO8601 string, deferredDestination: string | null }`. **`version` is a forward-compat hook** so a future onboarding refresh (Epic 9 / v1.5) can re-prompt without breaking the current contract. **`deferredDestination` carries the post-auth `tryConsumePendingInvite` URL** so onboarding's "시작하기" final tap routes to the right destination after S5.
- In-memory cache: module-level `let cache: OnboardingStateRecord | null = null; let cachePrimed = false;`. First read populates from SecureStore; subsequent reads hit RAM.

**Helper API:**

```typescript
export interface OnboardingStateRecord {
  readonly version: number;
  readonly completedAt: string;
  readonly deferredDestination: string | null;
}

export async function getOnboardingState(): Promise<OnboardingStateRecord | null>;
export async function markOnboardingCompleted(deferredDestination: string | null): Promise<void>;
export async function setDeferredDestination(destination: string | null): Promise<void>;
export async function clearOnboardingState(): Promise<void>;  // tests only
```

**Given** `getOnboardingState()` is called before `markOnboardingCompleted()`,
**Then** it returns `null` (no decision yet). Callers MUST treat `null` as "needs S1–S5".

**Given** `markOnboardingCompleted(destination)` is called,
**Then** SecureStore is written with `{ version: 1, completedAt: now.toISOString(), deferredDestination: destination }` AND the in-memory cache is updated.

**Given** `setDeferredDestination(destination)` is called (during pre-S1 auth handoff),
**Then** SecureStore is written with the destination but `completedAt` left as `null`-marker (encoded as an absent field). The next `getOnboardingState()` read recognizes the partial record and treats the user as NOT-completed but WITH a pending destination.

**Given** `clearOnboardingState()` is called,
**Then** SecureStore key is deleted AND in-memory cache is cleared. **NOT** called on sign-out — onboarding completion is per-device, not per-account (matches Story 8.5's analyticsConsent per-device semantics).

**Failure modes:**
- SecureStore read failure → return `null` (fail-open: user re-onboards once). Mirrors `playedRevivalEvents.ts:42–48`.
- SecureStore write failure → swallow + `__DEV__ && console.warn(...)`. Mirrors `analyticsConsent.ts:74–82`.
- Parse failure → return `null` (treat as not-completed).

### AC2 — `/onboarding` route + 5-screen carousel scaffold

**File:** `FE/app/onboarding.tsx` — NEW. Single page; internal carousel state. **Do NOT split into `FE/app/onboarding/_layout.tsx` + 5 child screens** — UX line 1731 lock: "Multi-step form ❌: v1 모든 form은 single-step (onboarding은 carousel이지 multi-step form ❌)".

**Carousel mechanics:**
- React `useState<1|2|3|4|5>(1)` for current screen index.
- Horizontal swipe via `react-native` `ScrollView` `horizontal pagingEnabled`. **No new dep** — `react-native-pager-view` / `react-native-snap-carousel` are explicitly OOS.
- Dot indicator row at the bottom (5 dots, current = filled, others = outlined). Uses theme tokens — no hex literals.
- Each screen is a `<View>` sized to `Dimensions.get("window").width` so horizontal paging snaps to a single screen at a time.
- "다음" and "이전" buttons in the footer; "이전" hidden on S1; on S5 the "다음" becomes "시작하기".
- "건너뛰기" link in the top-right is **explicitly OOS** for v1 — the 5 screens are short and force the mental-model reframing per PRD §13 #1 falsification trigger ("KR users will adopt an effort-only economy without 챌린저스 deposit-refund mental model"). Skipping defeats the purpose.

**Reduced-motion path:** when `useReducedMotion() === true`, snap directly to the target screen instead of animating the swipe (React Native handles this via `ScrollView.scrollTo({ animated: false })`).

**Routing:**
- Component reads `getOnboardingState()` on mount; if already completed (`completedAt` present) → `router.replace(state.deferredDestination ?? "/today")` immediately. **Defensive** — protects against direct URL navigation to `/onboarding` after completion.
- On "시작하기" tap (S5 footer):
  1. Persist S5's PIPA decision via `setAnalyticsConsent("opt_in" | "opt_out")`.
  2. Emit `captureEvent("onboarding.completed")` AFTER `setAnalyticsConsent` resolves (consent must be flipped before downstream events fire).
  3. Read `getOnboardingState()` to get the deferred destination.
  4. `markOnboardingCompleted(deferredDestination)` — clears `deferredDestination` field but persists `completedAt`.
  5. `router.replace(deferredDestination ?? "/today")`.

**Per-screen dwell tracking:**
- On each screen change (1→2, 2→3, etc.) AND on "시작하기" tap, emit `captureEvent("onboarding.screen.dwell_ms", { screen, dwellMs })` where `screen` is the screen the user is LEAVING and `dwellMs = Date.now() - screenEnteredAt`. **Emit before incrementing `screenEnteredAt`.**
- Initial S1 `screenEnteredAt = Date.now()` set in component mount `useEffect`.
- If the user backs out via hardware back / app background, do NOT emit a dwell event for the partial screen — only emit on forward navigation or final submit. (Otherwise dwell distribution gets polluted by abandonment events; the analytics taxonomy lint helper already enforces the locked event set so an "onboarding.abandoned" cannot leak in.)

### AC3 — Per-screen copy (BYTE-LOCKED FROM EPICS LINE 992–997)

These exact strings are byte-identical-locked in source:

| Screen | Locked body text |
|---|---|
| S1 (concept) | `열살방은 친구와 함께 살아남는 방입니다.` |
| S2 (mechanic) | `매일 약속을 지키면 살아남습니다. 빠지면 친구가 살릴 수 있어요.` |
| S3 (no-money v1) | `v1에서는 돈을 받지 않습니다 — 살아남는 것 자체가 자산입니다.` |
| S4 (no obligation) | `친구를 살리는 건 옵션이지 의무가 아닙니다.` |

S5 is the composite preview screen (AC4 + AC6 cover the structure and the consent surface).

**Additional locked surface copy (AC12 expands the table):**

| Surface | Locked text |
|---|---|
| Page route title | `소개` |
| "다음" button label (S1–S4) | `다음` |
| "이전" button label (S2–S5) | `이전` |
| Final CTA label (S5) | `시작하기` |
| Returning-user change-summary headline | `yeolsal이 열살방으로 바뀌었어요` |
| Returning-user change-summary continue button | `확인했어요` |

**Brand-voice constraints (verified at AC15 gate 8):**
- Zero AVOID-lexicon terms in any of the above. `tools/brand-voice-lint.ts` Rule 2 verifies this on every CI run.
- S5 "Wallet preview / Room preview / 14-day grace" sub-copy uses brand-voice "환영 기간" (matches WelcomeWindow `SOLO_PERIOD_COPY` at WelcomeWindow.tsx:20) — NOT "탈락 유예", "벌점 면제", or any AVOID lexicon variant.

### AC4 — S5 structure (Wallet preview + Room preview + 14-day grace banner + PIPA consent)

**Given** the user reaches S5,
**When** the screen mounts,
**Then** it renders four stacked sections in this order:

1. **14-day grace banner (top)** — body: `처음 14일은 환영 기간이에요`. Surface uses `theme.color.ember.subtle` background (matches WelcomeWindow ember surface at WelcomeWindow.tsx:114), `theme.radius.pronounced`. Single-line layout.
2. **Wallet preview** — title `Wallet`, body `처음 합류한 그룹에서 무료 회생권 1장이 자동으로 발급돼요.`. Card surface = `theme.color.bg.surface`. Includes a single muted icon row showing "🎟️ 무료 회생권 ×1" — **no live wallet fetch**, this is descriptive. (The real Wallet route still shows the real ticket after onboarding.)
3. **Room preview** — AC6 governs the populated vs default state. Card surface = `theme.color.bg.surface`.
4. **PIPA consent surface (bottom, above CTA)** — title `사용 통계 공유 (선택)`, body `개인을 직접 식별하지 않는 앱 이용 통계를 수집해 서비스 개선에 사용해요.`. Checkbox or toggle row labeled `사용 통계 공유에 동의합니다`. **Default unchecked (default = opt-out, explicit opt-in by tap).** State is local to the screen until "시작하기" tap, then persisted via `setAnalyticsConsent`.

**Given** the user taps "시작하기" with the checkbox checked,
**Then** `setAnalyticsConsent("opt_in")` is called.

**Given** the user taps "시작하기" with the checkbox unchecked,
**Then** `setAnalyticsConsent("opt_out")` is called. **(Both branches MUST resolve to a recorded decision — null persists only while the user is still on S5.)**

**Privacy-policy link:** body sub-text includes `자세히 보기` link to `https://yeolsal.app/privacy` (target lives outside the repo; the link is a `<Text>`-wrapped `Linking.openURL` per project-context.md "Direct fetch is forbidden" rule applies to API only, not external URLs). If the URL is not yet live at implementation time, link to `about:blank` is acceptable temporary fallback flagged in the implementation completion notes.

### AC5 — Returning-user change-summary screen

**Given** a user authenticates successfully (signIn / signInWithKakao only — signUp creates a fresh account, never returns) **AND** `getOnboardingState() === null`,
**When** `<OnboardingGate>` evaluates,
**Then** it routes to `/onboarding`.

**Given** at the time of evaluation the user has any pre-existing rooms (`useRoomsQuery().data?.length > 0`) **AND** the auth session was a `signIn` (returning user — established by AuthContext exposing a transient `lastAuthEvent: "signUp" | "signIn" | "signInKakao" | null`),
**When** `<OnboardingScreen>` mounts,
**Then** the page renders the **single change-summary screen** instead of the 5-screen carousel:
- Headline: `yeolsal이 열살방으로 바뀌었어요`
- Body: `이름이 바뀌었어요. 그동안의 친구들, 그룹, 잔디는 그대로 함께해요.`
- Single CTA: `확인했어요`

**On CTA tap:** `setAnalyticsConsent("opt_out")` (returning user gets opt-out default — no surprise capture-on for someone who already had the app), `captureEvent("onboarding.completed")`, `markOnboardingCompleted(null)`, `router.replace("/today")`.

**Given** the user is a returning user but `useRoomsQuery().data?.length === 0` (edge case — they signed up before but never joined a room),
**Then** the FULL 5-screen carousel runs (treat them as new for mental-model purposes). This avoids the awkward "change summary but you have nothing" path.

**`lastAuthEvent` mechanism:** AuthContext exposes a non-persisted ref + getter that records which method most recently produced the current `user` state. Cleared on sign-out. Survives mount→unmount of `<OnboardingGate>` because it lives in the AuthProvider seat. (Do NOT persist this — a force-quit + restoreSession resolves to a `signIn`-like flow and the change-summary path is correct for that case.)

### AC6 — S5 Room preview pre-population

**Given** the user arrived via Kakao share link (epics line 999–1001 — `tryConsumePendingInvite` succeeded in AuthContext.signUp / signIn),
**When** S5 renders,
**Then** the Room preview card shows:
- Room name (from `useRoomsQuery().data.find(r => /* the just-joined room */).name`)
- Min-days label (from `MIN_DAYS_LABELS[room.minDailyGoalDays]` — already exists at `FE/src/api/rooms.ts:11–17`)
- Optional 1-line `${memberCount}명 함께 살아남는 중` (read from `useRoomMembersQuery(roomId).data?.length`)

**Resolving "the just-joined room":** the deferred destination URL (stored in `onboardingState.deferredDestination`) has shape `/rooms/{id}/settings?onboarding=1`. Parse the `{id}` and look up the corresponding `Room` in `useRoomsQuery().data`. **If the lookup fails** (TanStack cache cold, race condition) → fall through to the default-state copy (next paragraph) rather than throw.

**Given** the user did NOT arrive via a deep link (`deferredDestination` is null) AND has zero rooms,
**Then** the Room preview card shows:
- Title `Room`, body `다음 단계에서 그룹을 만들거나 친구의 초대 코드를 입력하세요.`
- No "Create" / "Join" CTAs on this card — onboarding's "시작하기" already routes to `/today`, and `/today` already surfaces the empty-rooms path; duplicating CTAs here would create two onboarding-style funnels.

**Given** the user has one or more existing rooms but did NOT arrive via deep link (returning user with a fresh-install OR a force-quit-mid-onboarding),
**Then** the Room preview card shows the first room (`useRoomsQuery().data[0]`).

### AC7 — Post-onboarding routing

**Given** the user completes S5 / change-summary,
**When** the gate runs after `markOnboardingCompleted`,
**Then** `router.replace(deferredDestination ?? "/today")`.

**Routing trace for the 4 entry paths:**

| Entry path | `tryConsumePendingInvite` returns | `setDeferredDestination` writes | Onboarding shown | Post-onboarding lands at |
|---|---|---|---|---|
| Email signup, no Kakao share | `null` | nothing | 5-screen | `/today` |
| Email signup + pending Kakao code | `/rooms/42/settings?onboarding=1` | `/rooms/42/settings?onboarding=1` | 5-screen (S5 shows room 42 preview) | `/rooms/42/settings?onboarding=1` |
| Email sign-in (returning user) | `null` | nothing | change-summary (1 screen) | `/today` |
| Sign-in via Kakao (returning user, no pending code) | `null` | nothing | change-summary (1 screen) | `/today` |

**Note:** A returning user with a pending Kakao code is theoretically possible but unusual (the bridging slot lives in SecureStore on a per-device basis and the device only receives a pending code from a fresh-tap → install path). If it happens, the change-summary still wins (returning user > new device — they already know the mental model) and the `deferredDestination` still carries through so post-summary they land on the joined-room settings.

### AC8 — Telemetry emit-points (Story 8.5 catalogue rows)

**Story 8.1 emits exactly these three events from Story 8.5's locked catalogue:**

| Event name | Properties | Emit site (Story 8.1) |
|---|---|---|
| `signup.completed` | `{ authMethod: "EMAIL" \| "KAKAO" }` | `AuthContext.signUp` returns success → emit `{ authMethod: "EMAIL" }`; `AuthContext.signInWithKakao` returns success → emit `{ authMethod: "KAKAO" }`. **Emit inside AuthContext** (single seat), not at the call site. Fires BEFORE `tryConsumePendingInvite` so a deeplink-failed signup still records. |
| `onboarding.screen.dwell_ms` | `{ screen: 1\|2\|3\|4\|5, dwellMs: number }` | On every forward screen-change OR on "시작하기" tap. See AC2 carousel mechanics for emission timing. |
| `onboarding.completed` | `{}` | On "시작하기" tap (5-screen path) AND on "확인했어요" tap (change-summary path). Both flows count as "onboarding completed" for activation funnel purposes — analytics consumer aggregates the two. |

**Sign-in events (`signin.completed` etc.) are NOT in the locked catalogue and MUST NOT be added.** The taxonomy lint helper (Story 8.5 AC12) will flag rogue events.

**No BE-side emission.** Server-side activation events (`activation.24h_complete` per Story 8.5 AC5) are explicitly deferred and stay BE-owned in a later story.

**Story 8.1 OOS on `first_daily_entry`:** the emit site is `useDailyEntryMutation` success — that hook lives outside onboarding. Adding it inside this story would couple onboarding to the daily-entry surface and exceed the scope fence. Defer per Story 8.5 AC5 "emission site: Story 8.1 backfill scope — deferred" — but defer means "not in this story", not "Story 8.1 owns it via backfill". The right home is a future polish story bundled with Today screen instrumentation.

### AC9 — AuthContext integration (signup/signin → onboarding gate handoff)

**File:** `FE/src/auth/AuthContext.tsx` — MODIFY.

**Changes (small surface):**

1. **Emit `signup.completed` from `signUp`** — at the very top of the `try` block, after `await apply(response.data)`. `captureEvent("signup.completed", { authMethod: "EMAIL" })`. Place BEFORE `tryConsumePendingInvite`.
2. **Emit `signup.completed` from `signInWithKakao` ONLY on first-time-account creation** — the BE `/auth/kakao/exchange` returns the same shape for both first-time and returning Kakao users. The FE cannot reliably distinguish without a BE flag. **For v1, emit `signup.completed` on `signInWithKakao` only if `getOnboardingState() === null`** (proxy for "first-time on this device with no onboarding record"). This conflates "new device for returning user" with "new account" — flag in Story 8.1 completion notes as a known v1 approximation; the right fix is a BE response flag in a follow-up story.
3. **Stash deferred destination before returning** — the existing `tryConsumePendingInvite` returns a string-or-null URL. **Before** returning it, the modified `signUp` / `signIn` / `signInWithKakao` calls `await setDeferredDestination(returnedDestination)`. The login/signup screens then `router.replace("/today")` unconditionally; `<OnboardingGate>` reads the stashed destination and routes accordingly.
4. **Expose `lastAuthEvent`** — add a non-persisted ref `lastAuthEventRef: "signUp" | "signIn" | "signInKakao" | null` on the AuthContext value. Set in each success path. Cleared in `signOut`. Used by `<OnboardingScreen>` to pick the 5-screen vs change-summary path.

**Test files affected:** `FE/src/auth/__tests__/AuthContext.bridging.test.tsx` (extend with two new assertions — see AC13 table).

### AC10 — SubMode wrap (S1 D4 hint per UX line 1171)

**Given** S1 renders,
**When** the carousel mounts,
**Then** S1 ONLY is wrapped in `<SubModeProvider subMode="postcard">`. S2–S5 use the base sub-mode (`subMode={null}`).

**Implementation pattern (matches `RitualMomentBootstrap` at FE/app/_layout.tsx:223–226):**

```tsx
function OnboardingScreen1() {
  return (
    <SubModeProvider subMode="postcard">
      <OnboardingScreen1Content />
    </SubModeProvider>
  );
}
```

The leaf `OnboardingScreen1Content` reads `useTheme()` → resolved D4 tokens (`display.serif`, `motion.entry.duration: 1500`, `radius.pronounced: 16`, `bg.surface: oklch(20% 0.012 30)`). **No leaf code reads the sub-mode string.**

**S1 visual treatment:**
- Card surface: `theme.color.bg.surface` (resolves to D4 dark surface).
- Headline typography: `theme.typography["display.serif"]` (resolves to D4 serif family).
- Fade-in animation: `Animated.timing(opacity, { toValue: 1, duration: theme.motion.entry.duration, useNativeDriver: true })` — matches WelcomeWindow.tsx:96–101.
- Reduced-motion fallback: skip the `Animated.timing`, render opacity 1 from frame 1. Mirrors RitualMoment.tsx:58 pattern.

S2–S5 use **base** sub-mode (the `<SubModeProvider subMode={null}>` already wraps the outer Stack at FE/app/_layout.tsx:58) — no per-screen wrapping needed.

### AC11 — Reduced-motion fallback (UX cross-cutting rule #6)

**Given** `useReducedMotion() === true`,
**When** S1's D4 hint card mounts,
**Then** the fade-in animation is skipped; the card renders fully opaque from frame 1.

**Given** `useReducedMotion() === true`,
**When** the user advances between carousel screens,
**Then** `ScrollView.scrollTo({ animated: false })` is used (vs `{ animated: true }` for the default path).

**Given** `useReducedMotion() === true`,
**When** the dot indicator updates,
**Then** the dot transition is instant (no opacity / scale animation).

Verified via `RitualMoment.reduced-motion.test.tsx`-style test (Jest spy on `useReducedMotion`).

### AC12 — Brand-voice locked-text table

These exact strings are byte-identical-locked in source. No edits, no translations, no synonym swaps without spec author approval.

| Surface | Locked text |
|---|---|
| S1 body | `열살방은 친구와 함께 살아남는 방입니다.` |
| S2 body | `매일 약속을 지키면 살아남습니다. 빠지면 친구가 살릴 수 있어요.` |
| S3 body | `v1에서는 돈을 받지 않습니다 — 살아남는 것 자체가 자산입니다.` |
| S4 body | `친구를 살리는 건 옵션이지 의무가 아닙니다.` |
| S5 grace banner | `처음 14일은 환영 기간이에요` |
| S5 Wallet card body | `처음 합류한 그룹에서 무료 회생권 1장이 자동으로 발급돼요.` |
| S5 Wallet card row | `🎟️ 무료 회생권 ×1` |
| S5 Room card body (no rooms) | `다음 단계에서 그룹을 만들거나 친구의 초대 코드를 입력하세요.` |
| S5 PIPA section title | `사용 통계 공유 (선택)` |
| S5 PIPA body | `개인을 직접 식별하지 않는 앱 이용 통계를 수집해 서비스 개선에 사용해요.` |
| S5 PIPA checkbox label | `사용 통계 공유에 동의합니다` |
| S5 PIPA privacy link | `자세히 보기` |
| Final CTA (S5) | `시작하기` |
| "다음" button | `다음` |
| "이전" button | `이전` |
| Returning-user change-summary headline | `yeolsal이 열살방으로 바뀌었어요` |
| Returning-user change-summary body | `이름이 바뀌었어요. 그동안의 친구들, 그룹, 잔디는 그대로 함께해요.` |
| Returning-user change-summary CTA | `확인했어요` |
| Page route title | `소개` |

**AVOID-lexicon check:** none of the AVOID terms (`벌금`, `잃었다`, `떨어졌다`, `실패`, `자책`, `부담`, `패배`, `죄책감`) appear in any of the above. `tools/brand-voice-lint.ts` Rule 2 verifies on every CI run.

### AC13 — Test matrix (NET-ADDITIVE, RED → GREEN order)

**Baseline at session start (verify with `cd FE && npm test`):** 78 suites / 542 tests / 9 snapshots (post-Story 8.5 / PR #102 / commit 0dce1c1 baseline).

**Net-additive minimums (numbers are floors, not caps):**

| File | Test count | Cases |
|---|---|---|
| `FE/src/lib/__tests__/onboardingState.test.ts` | 6 | (1) `getOnboardingState()` null on first read / (2) `markOnboardingCompleted(null)` writes `completedAt` + `deferredDestination=null` / (3) `markOnboardingCompleted("/rooms/42/settings?onboarding=1")` writes both / (4) `setDeferredDestination(...)` writes destination without `completedAt` / (5) SecureStore read failure → return null / (6) SecureStore write failure swallowed with `__DEV__` warn |
| `FE/src/__tests__/onboarding.test.tsx` | 12 | (1) S1 renders byte-identical AC12 body / (2) S2 renders byte-identical AC12 body / (3) S3 renders byte-identical AC12 body / (4) S4 renders byte-identical AC12 body / (5) S5 renders grace banner + Wallet + Room + PIPA / (6) "다음" advance from S1→S2 emits `onboarding.screen.dwell_ms` with `screen: 1` / (7) "이전" from S2 back to S1 does NOT emit a dwell event (only forward emits — see AC2 — abandonment rule) / (8) Already-completed state on mount → `router.replace(deferredDestination ?? "/today")` immediately / (9) S5 "시작하기" with checkbox unchecked → `setAnalyticsConsent("opt_out")` + `captureEvent("onboarding.completed")` + `markOnboardingCompleted` + `router.replace("/today")` / (10) S5 "시작하기" with checkbox checked → `setAnalyticsConsent("opt_in")` path / (11) S5 room preview shows pre-populated room name when `deferredDestination` parses to a room id present in `useRoomsQuery().data` / (12) S5 falls through to default-state copy when room lookup fails |
| `FE/src/__tests__/onboarding.changeSummary.test.tsx` | 4 | (1) Returning user (`lastAuthEvent === "signIn"`, has rooms) → renders change-summary headline AC12 byte-match / (2) Change-summary CTA → `setAnalyticsConsent("opt_out")` + `captureEvent("onboarding.completed")` + `markOnboardingCompleted(null)` + `router.replace("/today")` / (3) Returning user with zero rooms → full carousel (NOT change-summary) / (4) Signup flow (`lastAuthEvent === "signUp"`) → full carousel even if rooms exist (race-case via deeplink-join-before-onboarding-mount) |
| `FE/src/__tests__/onboarding.reducedMotion.test.tsx` | 3 | (1) Jest spy `useReducedMotion → true` → S1 D4 card renders opacity 1 from frame 1 (no `Animated.timing` call) / (2) `useReducedMotion → true` → `ScrollView.scrollTo` called with `{ animated: false }` / (3) `useReducedMotion → false` → S1 D4 card runs the fade |
| `FE/src/auth/__tests__/AuthContext.bridging.test.tsx` | 3 (extension) | (1) `signUp` success emits `captureEvent("signup.completed", { authMethod: "EMAIL" })` before `tryConsumePendingInvite` / (2) `signUp` success calls `setDeferredDestination` with the URL returned by `tryConsumePendingInvite` / (3) `signInWithKakao` success when `getOnboardingState() === null` emits `signup.completed` with `authMethod: "KAKAO"`; when state exists, does NOT emit |
| `FE/app/__tests__/login-signup-onboarding.test.tsx` | (intentionally absent) | Tests for `app/`-level files MUST live in `FE/src/__tests__/` per Story 8.5 PR #102 review patch — see AC0 Jest testMatch line. |

**Total new FE tests:** ≥ 28. Total tools tests: 0 (taxonomy lint already wired; Story 8.1 just consumes it). Total BE tests: 0 (no BE work).

**TDD order:** RED → GREEN per task. `setAnalyticsConsent` mocked via `jest.mock("../lib/analyticsConsent")`. `captureEvent` mocked via `jest.mock("../lib/analytics")`. SecureStore mocked via `jest.mock("expo-secure-store")` (same pattern as `playedRevivalEvents.test.ts` if it exists, otherwise inline).

### AC14 — File / scope fence (LOCKED ALLOW LIST)

**Story 8.1 modifies exactly these files. The reviewer's diff sanity gate (Gate 11 in AC15) MUST find no other modifications.**

**NEW (8 files):**

```
FE/app/onboarding.tsx
FE/src/lib/onboardingState.ts
FE/src/components/onboarding/OnboardingCarousel.tsx
FE/src/components/onboarding/OnboardingDotIndicator.tsx
FE/src/lib/__tests__/onboardingState.test.ts
FE/src/__tests__/onboarding.test.tsx
FE/src/__tests__/onboarding.changeSummary.test.tsx
FE/src/__tests__/onboarding.reducedMotion.test.tsx
```

**MODIFIED (5 files):**

```
FE/app/_layout.tsx                                       (insert <OnboardingGate /> sibling component + register /onboarding Stack.Screen)
FE/src/auth/AuthContext.tsx                              (captureEvent signup.completed, setDeferredDestination, lastAuthEvent ref)
FE/app/login.tsx                                         (router.replace("/today") unconditional — gate handles re-route)
FE/app/signup.tsx                                        (router.replace("/today") unconditional — gate handles re-route)
FE/src/auth/__tests__/AuthContext.bridging.test.tsx      (3 new cases per AC13)
_bmad-output/implementation-artifacts/sprint-status.yaml (this story's status flip)
_bmad-output/implementation-artifacts/8-1-5-screen-onboarding-flow.md (Tasks/File List/Completion Notes)
```

**Banned paths (`git diff origin/main --stat` MUST show zero hits):**

- `BE/**` — NO backend work (verified at AC15 gate 1: BE test count delta = 0)
- `BE/src/main/resources/db/migration/**` — NO new migration
- `FE/src/theme/tokens.json` — NO token edits (S1 D4 hint reads existing tokens via useTheme)
- `FE/src/lib/sentry.ts` — NO Sentry touches
- `FE/src/lib/analytics.ts` — NO API changes (only consume `captureEvent`)
- `FE/src/lib/analyticsConsent.ts` — NO API changes (only consume `setAnalyticsConsent` / `getAnalyticsConsent`)
- `FE/app/privacy-settings.tsx` — NO edits (the revocation toggle stays Story 8.5's seat)
- `FE/src/providers/{Realtime,Query,SubMode}Provider.tsx` — NO provider extensions
- `FE/src/lib/realtime/**` — NO STOMP topic additions
- `FE/src/lib/query/**` — NO new query hooks (onboarding doesn't fetch — it reads existing caches)
- `FE/src/api/**` — NO new endpoint wrappers
- `FE/app.config.ts` / `FE/app.json` — NO Expo config additions
- `FE/package.json` — NO new deps (carousel built from `ScrollView`; no `react-native-pager-view` etc.)
- `infra/**` — NO infra changes
- `.github/workflows/**` — NO CI workflow edits
- `RUNBOOK.md` — NO RUNBOOK edits (onboarding is FE behaviour, not ops)
- `docs/analytics.md` — NO taxonomy edits (the 3 events Story 8.1 emits are already listed)
- `tools/**` — NO tooling edits (taxonomy lint already wired; brand-voice lint already covers AC12 surfaces)

**Cross-cutting test path rule:** any test for an `FE/app/`-level component MUST live in `FE/src/__tests__/` (Jest testMatch in `FE/package.json`). Tests under `FE/app/__tests__/` will silently fail to run.

### AC15 — Pre-merge verify gates (14-GATE MATRIX)

These gates MUST all pass before squash-merge to main. Manual-only gates are flagged.

| # | Gate | How | Pass criterion |
|---|---|---|---|
| 1 | BE Gradle test (delta-only check) | `cd BE && ./gradlew test` | 685 tests pre-existing baseline; **delta = 0** (no BE changes) |
| 2 | BE Checkstyle | `cd BE && ./gradlew checkstyleMain` | GREEN (no BE edits) |
| 3 | BE compile | `cd BE && ./gradlew compileJava compileTestJava` | GREEN (no BE edits) |
| 4 | BE token-codegen | `cd BE && ./gradlew validateTokens generateTokens` | GREEN (no tokens.json edits) |
| 5 | FE typecheck | `cd FE && npm run typecheck` | 0 new errors (pre-existing FriendsTodayPager 2-error baseline allowed per PR #98 precedent) |
| 6 | FE Jest | `cd FE && npm test` | 78+1+3 = 82 suites / 542+28 = 570 tests / 9 snapshots / 0 failures |
| 7 | FE ESLint scoped | `cd FE && npx eslint FE/app/onboarding.tsx FE/src/lib/onboardingState.ts 'FE/src/components/onboarding/*.tsx' FE/src/lib/__tests__/onboardingState.test.ts 'FE/src/__tests__/onboarding*.test.tsx' FE/app/_layout.tsx FE/src/auth/AuthContext.tsx FE/app/login.tsx FE/app/signup.tsx FE/src/auth/__tests__/AuthContext.bridging.test.tsx` | 0 errors / 0 new warnings (pre-existing 2-error baseline OK per PR #98) |
| 8 | Brand-voice lint | `cd tools && npx tsx brand-voice-lint.ts` | 0 HARD violations (post-Story 8.5 baseline preserved); WARN delta on new files = 0 (the 5 locked screen bodies are AVOID-lexicon-clean by construction) |
| 9 | Contrast lint | `cd tools && npx tsx contrast-check.ts` | 13/13 pairs PASS (unchanged — no tokens.json edits) |
| 10 | Analytics taxonomy lint | `cd tools && npx tsx analytics-taxonomy-lint.ts` | exits 0; 0 rogue events (3 new emit sites all use locked names from docs/analytics.md) |
| 11 | Diff sanity (scope fence) | `git diff origin/main --stat \| grep -E "^(BE/\|infra/\|RUNBOOK\|docs/analytics\|tools/\|.github/\|FE/src/theme/tokens.json\|FE/src/lib/(sentry\|analytics)\.ts\|FE/src/lib/analyticsConsent\.ts\|FE/app/privacy-settings\.tsx\|FE/app/__tests__)"` | 0 hits (banned paths from AC14) |
| 12 | Test path sanity | `find FE/app/__tests__ -name "*.test.*" 2>/dev/null \| wc -l` | 0 (re-confirms Story 8.5 lesson: tests under `FE/app/__tests__/` don't run) |
| 13 | EAS preview smoke (MANUAL) | Run on a device or simulator; open the app fresh-install, complete email signup, walk all 5 screens, S5 leave checkbox unchecked + tap "시작하기", verify "/today" loads and Settings → Privacy → "사용 통계 공유" toggle reads off | PASS (deferred to PR-CI reviewer per Story 5.4 / 6.1 / 7.x precedent — "merge on deferral") |
| 14 | EAS preview returning-user smoke (MANUAL) | Same device; signOut + signIn again; verify change-summary screen renders + "확인했어요" lands on "/today" | PASS (deferred per gate 13 precedent) |

**Note on gates 13–14:** the "merge on deferral" norm established across PRs #90, #93, #95, #98, #99, #100, #102 applies here. Manual EAS-device smoke is reviewer-runnable; story can squash-merge if all 12 non-manual gates pass.

## Developer Context

### Why this story matters (business + KPI signal)

PRD §3.1 KPI: activation ≥ 60% within 24h. PRD §13 #1 falsification trigger: "KR users will adopt an effort-only economy without 챌린저스 deposit-refund mental model" — fails when activation < 60% within 24h despite functional onboarding. UX §C1 hypothesis: "60초" mental-model deprogramming via the 5 screens (telemetered via `onboarding.screen.dwell_ms`). Without Story 8.1, the activation funnel has no measurable entry point — Story 8.5 shipped the SDK but no event ever fires.

### Previous-story intelligence (last 5 commits + branch context)

**Story 8.5 (PR #102, 0dce1c1, 2026-06-09) — PostHog SDK + 21-event locked taxonomy:**
- Ships `FE/src/lib/analytics.ts` (`captureEvent`, `setAnalyticsUser`, `bootstrapAnalytics`), `FE/src/lib/analyticsConsent.ts` (`getAnalyticsConsent`, `setAnalyticsConsent`), `FE/app/privacy-settings.tsx` (Settings revocation seat), `infra/posthog.sh` (upstream installer wrapper), `tools/analytics-taxonomy-lint.ts` (warn-only).
- Default opt-in/out state was **deliberately deferred** to Story 8.1 — Story 8.5 ships `defaultOptIn: false` (fail-closed) so the SDK never captures until the user explicitly opts in.
- **Critical handoff:** Story 8.5 review patch absorbed `AnalyticsUserBinding` into outer + inner (`AuthenticatedAnalyticsUserBinding`) — the inner ONLY mounts when authenticated, so a `setAnalyticsUser` call from onboarding before sign-in is impossible. Story 8.1 doesn't need to defend against this; the binding pattern already handles it.
- **Test path lesson:** `FE/app/__tests__/privacy-settings.test.tsx` was moved to `FE/src/__tests__/privacy-settings.test.tsx` post-merge because Jest testMatch only discovers `FE/src/**/__tests__/`. Story 8.1's `app/`-level tests live in the same place.

**Epic 7 retro (PR #97) + T1/A1 follow-ups (PRs #98–#100):**
- Workflow timeout (be-it-boot-smoke.yml 30→60min) is **still broken** after A1 option (a) failed and option (d) reverted. "Merge on deferral" is now the norm; Story 8.1's gates 13–14 reflect this.
- FE baseline cleanup landed in PR #98 — the 2-error FriendsTodayPager ESLint floor is the current baseline; Story 8.1's gate 7 explicitly preserves it.

**Story 1.6 (PR #62, WelcomeWindow):** D4 Postcard sub-mode usage pattern. Story 8.1's S1 D4 hint reuses the same `useTheme()` + `SubModeProvider subMode="postcard"` + `Animated.timing` shape; the WelcomeWindow tests are the structural reference for the S1 D4 test.

**Story 1.7 (PR #63, RitualMoment):** `useReducedMotion()` gating pattern. Story 8.1's AC11 mirrors this exactly.

**Story 3.2 (PR #69, playedRevivalEvents):** SecureStore-backed JSON record reference. Story 8.1's `onboardingState.ts` mirrors this exactly.

### Git intelligence summary

Branch: `main` (clean post-PR #102 merge). Working tree: clean. **Create branch:** `feat/story-8-1-5-screen-onboarding`.

Recent FE-side patterns from PRs #90 / #93 / #95 / #102:
- New libs go through Story 8.5's `setDeferredDestination`-style fail-closed pattern (SecureStore + module cache).
- New `FE/app/` routes are registered as `Stack.Screen` in `_layout.tsx` (not just file-system based — see notification-settings, privacy-settings precedent).
- Brand-voice locked text uses byte-identical assertions in tests (Story 8.5 `expect(getByText("사용 통계 공유")).toBeTruthy()` precedent).

### Latest tech information (libs to verify at implementation start)

- **`react-native` ScrollView pagingEnabled** — stable since RN 0.40; no version concerns. Default behavior on iOS + Android.
- **`expo-router 6`** — Stack.Screen registration via `<Stack.Screen name="onboarding" />` in `_layout.tsx`. Pattern matches `notification-settings` / `privacy-settings` registrations.
- **No new deps.** The story is built entirely from existing imports (React, RN, expo-router, expo-secure-store, existing theme + analytics + analyticsConsent + auth surfaces).

### Project context reference

- `project-context.md:97` — never read `process.env` directly; use the guarded `runtime.process?.env?.EXPO_PUBLIC_*` pattern. Story 8.1 reads NO env vars (no new ones needed).
- `project-context.md:100` — SecureStore keys use `yeosal.*` namespace. `yeosal.onboardingState` complies.
- `project-context.md:98` — All API calls through `apiRequest<T>`. Story 8.1 makes ZERO API calls (consumes existing TanStack cache only).
- `project-context.md:103` — Immutable updates only. Carousel state uses `useState` setters; no in-place mutation.
- `project-context.md:124` — New routes must be registered in `Stack.Screen` in `_layout.tsx`.
- `project-context.md:150` — Tests must match `FE/src/**/__tests__/**/*.test.{ts,tsx}`. AC14 enforces this.
- `project-context.md:155` — TanStack hook tests wrap in `QueryClientProvider`. `useRoomsQuery()` mocked at the module level in onboarding tests to keep them lean (mirrors privacy-settings.test.tsx jest.mock pattern).

### Story completion status (filled by spec author, validated by dev-story workflow)

- Status: **ready-for-dev**
- Spec author note: ultimate context engine analysis completed — comprehensive developer guide created.
- Spec confidence (1-5): 4 — the only soft surface is the PIPA default (default unchecked, explicit opt-in) which the spec author locked per KR PIPA strict reading. If PM wants opt-in default, only AC4 checkbox initial state + AC13 case (9) reverses.

## Traps (LLM-vulnerable pitfalls — read first)

1. **DO NOT create `FE/app/__tests__/`.** Jest testMatch in `FE/package.json` is `<rootDir>/src/**/__tests__/`. Tests outside `FE/src/` are silently not discovered. Story 8.5 PR #102 already paid this cost; do not re-pay it. AC15 gate 12 enforces this.
2. **DO NOT add a "건너뛰기" link to the carousel.** The 5 screens are short and force the mental-model reframing. Skipping defeats the PRD §13 #1 falsification trigger's measurability. AC2 explicitly OOS this.
3. **DO NOT call `posthog.optIn()` / `posthog.optOut()` directly from onboarding code.** Always go through `setAnalyticsConsent()` — that helper centralizes SecureStore write + SDK runtime flip (Story 8.5 AC4 lock).
4. **DO NOT emit any event NOT in Story 8.5's locked catalogue.** No "onboarding.abandoned", no "onboarding.screen.viewed", no "onboarding.consent.shown". The taxonomy lint (Gate 10) will flag rogue events at warn level — but reviewer will reject. Three events only: `signup.completed`, `onboarding.screen.dwell_ms`, `onboarding.completed`.
5. **DO NOT add backfill emission for revival.* / friend_gift.* / spectator.* / final_three.*** Those stay OOS per Story 8.5 §6 OOS #2. A future polish story owns them.
6. **DO NOT make the dwell event fire on backward navigation.** Backward navigation in a carousel is an exploration signal, not a screen-completion signal. Only forward advance + final "시작하기" emit dwell. AC2 + AC13 case (7) lock this.
7. **DO NOT default the S5 PIPA checkbox to checked.** KR PIPA strict reading requires explicit opt-in; brand-voice "invitation, not demand" reinforces. Story 8.5's `bootstrapAnalytics({ defaultOptIn: false })` is already fail-closed — checking the box by default would create a state where the bootstrap is opt-out but the UI shows opt-in, which is misleading and PIPA-fragile. **Default unchecked. If PM wants opt-in default later, AC4 checkbox initial state + AC13 case (9) reverses.**
8. **DO NOT clear `onboardingState` on signOut.** Onboarding is per-device, not per-account. A user who signs out + back in should NOT re-onboard. AC1 explicitly OOS this.
9. **DO NOT add a new SecureStore key for "pre-populated room preview".** `tryConsumePendingInvite` already happens in AuthContext (existing code path) BEFORE onboarding starts, so the user IS already a member of the joined room by S5 mount. Read via `useRoomsQuery()` from the TanStack cache. AC0 enforces this.
10. **DO NOT branch `signInWithKakao` on a BE "is new account" flag.** The BE response shape doesn't expose one. For v1, proxy "first-time on this device with no onboarding record" via `getOnboardingState() === null`. Document as known approximation in completion notes — the right fix is a BE response flag in a follow-up story.
11. **DO NOT re-litigate the SDK choice or the 21-event catalogue.** Story 8.5 (PR #102) shipped it. Story 8.1 consumes it as-is.
12. **DO NOT register tests for `app/onboarding.tsx` in any other test file.** Each test path is single-purpose (12 cases in `onboarding.test.tsx`; 4 cases in `onboarding.changeSummary.test.tsx`; 3 cases in `onboarding.reducedMotion.test.tsx`). Crossing them creates flaky setup/teardown.
13. **DO NOT animate position / margin / padding properties for the carousel transition.** Project-context performance rule + UX cross-cutting rule #6 (reduced motion). The `ScrollView pagingEnabled` is compositor-friendly out of the box; do not wrap it in any `Animated.View` position bling.
14. **DO NOT touch `app/_layout.tsx` beyond the `<OnboardingGate />` insertion + `<Stack.Screen name="onboarding" />` registration.** The Stack mount order in lines 57–102 is load-bearing for QueryProvider / RealtimeProvider / SubModeProvider / AuthProvider initialization order. A re-shape will introduce subtle race conditions.

## Out-of-scope items (DO NOT IMPLEMENT)

1. ❌ Backfill emission of `revival.*` / `friend_gift.*` / `spectator.*` / `final_three.*` events — stays Story 8.5 OOS #2.
2. ❌ Emission of `first_daily_entry` — the emit site (`useDailyEntryMutation` success) lives outside onboarding. A future polish story bundled with Today screen instrumentation owns this.
3. ❌ Emission of `activation.24h_complete` — BE-side, scheduled-job-based; a future story owns this.
4. ❌ "건너뛰기" / skip link in the carousel — defeats the PRD §13 #1 falsification trigger's measurability (Trap #2).
5. ❌ Multi-step form rendering of the 5 screens — UX line 1731 lock; carousel only.
6. ❌ A second SecureStore slot for pre-populated room preview — `tryConsumePendingInvite` already does the bridging (AC0 read of `FE/src/auth/AuthContext.tsx:147–164`).
7. ❌ BE response flag for "first-time vs returning Kakao account" — known v1 approximation; document in completion notes.
8. ❌ Per-room PIPA opt-in (방마다 다른 결정) — consent is per-device (Story 8.5 AC4 lock + decision doc §6 OOS #21).
9. ❌ Onboarding state synced to BE / server-recorded — local-only is correct for v1; resync would need a BE column + privacy review.
10. ❌ Push notification permission prompt on S5 — UX line 1781 says "Notification permission: Onboarding S5에서 1회 prompt" but that already happens via `PushTokenBootstrap` at `FE/app/_layout.tsx:126–136` (calls `registerForPushAsync` which prompts via expo-notifications when the user has `auth.user`). Adding a second prompt on S5 would create a double-prompt UX bug. The existing seat already covers this; no work needed here.
11. ❌ Privacy-policy page itself — `https://yeolsal.app/privacy` is external infra; story OOS. If URL is not live at implementation time, `about:blank` is an acceptable temporary fallback flagged in completion notes.
12. ❌ Custom carousel library (`react-native-pager-view`, `react-native-snap-carousel`, etc.) — adds a native dep + EAS rebuild cost. `ScrollView pagingEnabled` is sufficient.
13. ❌ Per-screen telemetry property beyond `screen` + `dwellMs` — the locked catalogue defines exactly two props; adding a third would trip the taxonomy lint.
14. ❌ Bumping `onboardingState.version` mid-implementation — version 1 is the fresh-launch baseline; future stories that re-prompt own the bump.
15. ❌ Settings → Privacy "사용 통계 공유" toggle re-design — Story 8.5 owns the seat; Story 8.1 only adds the first-prompt surface.
16. ❌ Brand-voice copy review process — Story 8.4 owns the release-gate checklist; Story 8.1 only writes copy that PASSES the checklist.
17. ❌ ASO copy (App Store / Google Play KR) — Story 8.3 owns the storefront copy.
18. ❌ A "previously saw onboarding on iOS, now on Android" cross-device path — onboardingState is per-device; users re-onboard on a new device. Acceptable v1 cost.
19. ❌ Telemetry on `Linking.openURL("https://yeolsal.app/privacy")` privacy-policy tap — not in the locked catalogue.
20. ❌ A11y label customization for the dot indicator — RN's default `accessibilityLabel` on `<Pressable>` (auto-derived from children text) is acceptable. The dots are decorative; the load-bearing a11y is on the "다음" / "이전" / "시작하기" buttons.

## Tasks / Subtasks (RED → GREEN → refactor)

- [x] T1 — `FE/src/lib/onboardingState.ts` (AC1)
  - [x] T1.1 — Write `FE/src/lib/__tests__/onboardingState.test.ts` 6 RED cases (AC13)
  - [x] T1.2 — Implement helper API → GREEN
- [x] T2 — AuthContext integration (AC9)
  - [x] T2.1 — Extend `FE/src/auth/__tests__/AuthContext.bridging.test.tsx` 3 RED cases (AC13)
  - [x] T2.2 — Wire `captureEvent("signup.completed", ...)` + `setDeferredDestination` + `lastAuthEvent` ref → GREEN
- [x] T3 — Carousel components (AC2)
  - [x] T3.1 — Write `FE/src/__tests__/onboarding.test.tsx` 12 RED cases (AC13)
  - [x] T3.2 — Build `FE/src/components/onboarding/OnboardingCarousel.tsx` + `OnboardingDotIndicator.tsx` (base sub-mode for S2–S5)
  - [x] T3.3 — Build S1 D4 hint with `<SubModeProvider subMode="postcard">` wrap + `Animated.timing` fade (AC10)
  - [x] T3.4 — Build S5 composite (grace banner + Wallet preview + Room preview + PIPA checkbox) (AC4 + AC6)
  - [x] T3.5 — Wire telemetry: `signup.completed` already in AuthContext; emit `onboarding.screen.dwell_ms` on forward advance; emit `onboarding.completed` on "시작하기" (AC8) → GREEN
  - [x] T3.6 — Wire `setAnalyticsConsent` on "시작하기" (AC4 PIPA + Trap #3 — go through helper) → GREEN
- [x] T4 — Returning-user change-summary (AC5)
  - [x] T4.1 — Write `FE/src/__tests__/onboarding.changeSummary.test.tsx` 4 RED cases (AC13)
  - [x] T4.2 — Branch `<OnboardingScreen>` on `lastAuthEvent` + `useRoomsQuery().data?.length` → GREEN
- [x] T5 — Reduced-motion fallback (AC11)
  - [x] T5.1 — Write `FE/src/__tests__/onboarding.reducedMotion.test.tsx` 3 RED cases (AC13)
  - [x] T5.2 — Gate `Animated.timing` + `scrollTo` behind `useReducedMotion()` → GREEN
- [x] T6 — `<OnboardingGate>` insertion in `app/_layout.tsx` + Stack.Screen registration (AC14 MODIFIED)
  - [x] T6.1 — Insert `<OnboardingGate />` sibling component (similar pattern to `<PushTokenBootstrap />` at line 65); component reads `auth.user` + `getOnboardingState()` and `router.replace`s appropriately
  - [x] T6.2 — Register `<Stack.Screen name="onboarding" options={{ headerShown: false, gestureEnabled: false }} />` (gestureEnabled false — onboarding is non-dismissable)
  - [x] T6.3 — Update `FE/app/login.tsx` + `FE/app/signup.tsx` to `router.replace("/today")` unconditional
- [x] T7 — Verify pass per AC15 14-gate matrix
  - [x] T7.1 — Local FE run (gates 5, 6, 7, 11, 12)
  - [x] T7.2 — Local tools run (gates 8, 9, 10)
  - [x] T7.3 — BE delta check (gates 1, 2, 3, 4) — should all be no-op
  - [x] T7.4 — Tag gates 13–14 as deferred-to-reviewer-EAS per "merge on deferral" norm

## Review Findings

- [x] [Review][Patch] Add a BE Kakao exchange response flag that distinguishes newly created accounts from returning accounts, then map it to `signUp` versus `signInKakao` behavior in AuthContext. Decision: use an explicit BE contract change rather than the current device-state approximation. [FE/src/auth/AuthContext.tsx:138]
- [x] [Review][Patch] Preserve the post-login invite destination for users who already completed onboarding; login currently discards the returned destination while the gate exits on `completedAt`, leaving the user on `/today` after a successful auto-join. [FE/app/login.tsx:43]
- [x] [Review][Patch] Reject or redirect signed-out direct access to `/onboarding`; the route currently lets an anonymous visitor persist device completion and cause the next authenticated account to skip onboarding and consent. [FE/app/onboarding.tsx:36]
- [x] [Review][Patch] Clear stale deferred destinations on null auth handoffs; `stashDeferredDestination` skips null despite AC9 requiring every auth path to write the returned destination. [FE/src/auth/AuthContext.tsx:213]
- [x] [Review][Patch] Render a retry/error state when the returning-user rooms query fails; treating `isError` as an empty room list incorrectly selects the full new-user carousel. [FE/app/onboarding.tsx:64]
- [x] [Review][Patch] Preserve authenticated share links received while onboarding is incomplete; the global gate replaces `/join` with `/onboarding` without retaining the invite destination. [FE/app/_layout.tsx:162]
- [x] [Review][Patch] Prevent a fast swipe from skipping multiple required screens and losing intermediate dwell events; `pagingEnabled` alone can land more than one page ahead while telemetry records only the screen left. [FE/src/components/onboarding/OnboardingCarousel.tsx:118]
- [x] [Review][Patch] Make carousel width responsive to rotation and split-screen resizing; the one-time `Dimensions.get("window").width` value leaves page widths and offsets stale. [FE/src/components/onboarding/OnboardingCarousel.tsx:81]
- [x] [Review][Patch] Split the new carousel functions to comply with the project-context 50-line function limit, especially `OnboardingCarousel` and `ScreenFive`. [FE/src/components/onboarding/OnboardingCarousel.tsx:72]

## Review Patch Completion

All 9 review patches completed on 2026-06-10. The user selected the explicit BE contract option for Kakao account classification. V14 adds `login_codes.new_account`; `/auth/kakao/exchange` now returns `newAccount`, and FE uses that authoritative flag. Full FE lint/typecheck/Jest passed (82 suites, 572 tests, 9 snapshots). Full BE tests and `checkstyleMain` passed.

## Dev Agent Record

### Agent Model Used

`claude-fable-5[1m]`

### Implementation Plan

Execute T1 → T7 strictly in story order, RED → GREEN per task:

1. **T1** `onboardingState.ts` — mirror `analyticsConsent.ts` revision-guarded module cache + `playedRevivalEvents.ts` defensive narrowing. `completedAt` typed `string | null` (partial record = destination stashed pre-completion, encoded as absent field per AC1).
2. **T2** AuthContext — `lastAuthEventRef` + `getLastAuthEvent()` getter (AC5 "ref + getter"); `restoreSession` success counts as `"signIn"` (AC5 force-quit note); `captureEvent("signup.completed")` before `tryConsumePendingInvite`; Kakao emit proxied on `getOnboardingState() === null`; `setDeferredDestination` only on non-null destination (AC7 trace: null writes nothing — preserves force-quit-stashed slot).
3. **T3–T5** `app/onboarding.tsx` (route + change-summary + completion sequences) + `OnboardingCarousel.tsx` (5 screens incl. S1 D4 wrap + S5 composite, dwell tracking forward-only) + `OnboardingDotIndicator.tsx`. S5 dwell emitted AFTER `setAnalyticsConsent` resolves (same downstream-event rule as `onboarding.completed`). Change-summary honors a stashed destination (AC7 note) while degrading to `markOnboardingCompleted(null)` + `/today` in the common case (AC5/AC13 byte-match).
4. **T6** `_layout.tsx` `<OnboardingGate />` sibling (pathname-aware re-evaluation so the gate wins the race against login/signup's unconditional `router.replace("/today")`) + first-ever `<Stack.Screen name="onboarding">` registration + login/signup call-site flips.
5. **T7** AC15 gates 1–12 locally; 13–14 tagged deferred.

### Architecture deviations (recorded at story authoring)

1. **No multi-page route for onboarding (`FE/app/onboarding/_layout.tsx` + per-screen children)** — single page with internal carousel state instead. **Rationale:** UX line 1731 explicit lock ("Multi-step form ❌"); also single-page is byte-cheaper and avoids per-screen `Stack.Screen` registration overhead. **Mitigation:** carousel mechanics are isolated in `OnboardingCarousel.tsx` (testable in isolation).

2. **PIPA default = explicit opt-in (unchecked checkbox) not opt-in-by-default** — Story 8.5 AC4 left this decision to Story 8.1 PM-lock. **Rationale (spec author lock):** KR PIPA strict reading + brand-voice "invitation, not demand" both push to unchecked. Story 8.5's `bootstrapAnalytics({ defaultOptIn: false })` already fail-closes. **Mitigation:** AC13 case (10) tests the checked path equally; reversing only takes AC4 checkbox initial state flip.

3. **Review-resolved deviation: Kakao account classification now uses a BE response flag** — the original FE device-state approximation was rejected during code review. V14 persists `new_account` on the one-time login code so callback-time account creation survives the later exchange request.

4. **`<OnboardingGate>` is a sibling component inside `RootLayout` (matches `<PushTokenBootstrap />` pattern at line 65), NOT a wrapper around `<Stack />`** — wrapping `<Stack />` would intercept ALL routes and create a re-render storm on auth state change. Sibling pattern lets the gate `router.replace` once on mount + on `auth.user` change, then return null. **Mitigation:** test case (8) in `onboarding.test.tsx` verifies the already-completed-state immediate-redirect path; integration test (none required) is covered by the manual EAS smoke gates 13–14.

5. **Reading `useRoomsQuery()` from S5 directly instead of passing the room snapshot in as a prop** — couples S5 to TanStack but avoids prop-drilling through the carousel. **Rationale:** matches Story 4.1's `useRoomPoints` direct-hook pattern; the carousel parent doesn't need to know about rooms. **Mitigation:** AC13 case (12) tests the fallback when the lookup fails (cold cache / race condition).

### Completion Notes

Story 8.1 implementation complete on `feat/story-8-1-5-screen-onboarding` (2026-06-10). All 12 non-manual AC15 gates GREEN locally; gates 13–14 (manual EAS fresh-install + returning-user smokes) deferred to PR-CI reviewer per the merge-on-deferral norm (PRs #90/#93/#95/#98/#99/#100/#102 precedent).

**Verification results (AC15):**
- Gate 1–4 (BE): review expanded scope by explicit user decision. Full `./gradlew test` and `checkstyleMain` BUILD SUCCESSFUL; V14 carries Kakao `newAccount` through the one-time login code.
- Gate 5: FE typecheck 0 errors (the 2-error FriendsTodayPager baseline is gone since PR #98 — gate passes outright).
- Gate 6: FE Jest **82 suites / 572 tests / 9 snapshots**.
- Gate 7: scoped ESLint 0 errors / 0 warnings; repo-wide `npm run lint` also clean.
- Gate 8: brand-voice 0 HARD / 198 warnings (= Story 8.5 baseline; new-file WARN delta 0).
- Gate 9: contrast-check 16/16 pairs PASS (Story 4.3 patched-tool baseline; AC15's "13/13" label predates the 4.3 review round).
- Gate 10: analytics-taxonomy-lint 0 warnings — the 3 emit sites use locked names only.
- Gate 11: banned-path grep 0 hits. Gate 12: `FE/app/__tests__` 0 files.

**Implementation deviations / notes for the reviewer:**
1. `OnboardingStateRecord.completedAt` is typed `string | null` (AC1's sketch showed `string`) — required so `getOnboardingState()` can represent the AC1 partial record (destination stashed pre-completion). Storage encoding follows AC1 exactly: `completedAt` absent on partial records.
2. **One consequential file outside the AC14 allow list:** `FE/src/lib/__tests__/deepLinking.test.ts` (+1 line) — the AC9 `AuthContextValue.getLastAuthEvent` extension compile-broke its auth fixture; added `getLastAuthEvent: jest.fn(() => null)`. Not on the gate-11 banned-path list; test semantics unchanged.
3. `restoreSession` success sets `lastAuthEvent = "signIn"` (AC5 note: "force-quit + restoreSession resolves to a signIn-like flow") — this is the path that lets the epics returning-user (app-upgrade) case reach the change-summary without any explicit sign-in.
4. `<OnboardingGate>` is pathname-aware (`usePathname()` in the effect deps, skips while on `/onboarding`) so it deterministically wins the race against login/signup's unconditional `router.replace("/today")` — without the pathname dep the gate's async SecureStore read could lose the last-replace race.
5. Change-summary completion honors a stashed `deferredDestination` when present (AC7 note: returning user with pending Kakao code) and degrades to `markOnboardingCompleted(null)` + `/today` when absent — byte-matching AC5/AC13 in the common case.
6. Kakao `signup.completed` now uses the authoritative BE `newAccount` flag selected during review; the device-state approximation was removed.
7. S5's own dwell event (screen 5) is emitted AFTER `setAnalyticsConsent` resolves — the AC2 downstream-event rule applied consistently (an opt-in user's S5 dwell is capturable; S1–S4 dwells fired while fail-closed are dropped by the SDK by design).
8. PIPA surface uses the RN `<Switch>` row (privacy-settings.tsx precedent; AC0 explicitly allows Switch or Pressable-checkbox). **Default unchecked — explicit opt-in (architecture deviation #2 lock).** Privacy link targets `https://yeolsal.app/privacy` (page live-ness is external infra per OOS #11).
9. Brand-voice lint false positive avoided: a comment reading "PR #102" was reworded to "PR 102" because the Rule-3 hex guard parses `#102` as a 3-digit color literal (+1 WARN otherwise).
10. First-ever `<Stack.Screen>` child registration in `_layout.tsx` (the repo previously used pure file-based routing with zero explicit registrations): `name="onboarding"`, `title: "소개"`, `headerShown: false`, `gestureEnabled: false`.

### File List

**NEW (10):**

```
FE/app/onboarding.tsx
FE/src/lib/onboardingState.ts
FE/src/components/onboarding/OnboardingCarousel.tsx
FE/src/components/onboarding/OnboardingDotIndicator.tsx
FE/src/lib/__tests__/onboardingState.test.ts
FE/src/__tests__/onboarding.test.tsx
FE/src/__tests__/onboarding.changeSummary.test.tsx
FE/src/__tests__/onboarding.reducedMotion.test.tsx
BE/src/main/resources/db/migration/V14__login_code_new_account.sql
BE/src/test/java/com/yeosal/api/auth/AuthServiceKakaoTest.java
```

**MODIFIED (16):**

```
FE/app/_layout.tsx                                       (<OnboardingGate /> sibling + <Stack.Screen name="onboarding"> registration)
FE/src/auth/AuthContext.tsx                              (signup.completed emits + deferred-destination stash + lastAuthEvent ref/getter)
FE/app/login.tsx                                         (unconditional router.replace("/today"))
FE/app/signup.tsx                                        (unconditional router.replace("/today"))
FE/src/auth/__tests__/AuthContext.bridging.test.tsx      (3 new AC13 cases + analytics/onboardingState mocks)
FE/src/lib/__tests__/deepLinking.test.ts                 (consequential: +1 fixture line for AuthContextValue.getLastAuthEvent — see Completion Notes #2)
FE/src/api/client.ts                                     (`AuthTokens.newAccount`)
FE/src/lib/deepLinking.ts                                (preserve authenticated invite during incomplete onboarding)
BE/src/main/java/com/yeosal/api/auth/AuthController.java (Kakao callback/exchange new-account contract)
BE/src/main/java/com/yeosal/api/auth/AuthService.java    (authoritative Kakao account classification)
BE/src/main/java/com/yeosal/api/auth/LoginCode.java      (`new_account` persistence)
BE/src/main/java/com/yeosal/api/auth/LoginCodeService.java (issue/exchange flag)
BE/src/test/java/com/yeosal/api/auth/LoginCodeServiceTest.java
BE/src/test/java/com/yeosal/api/migration/V11MigrationIT.java (top Flyway version 14)
_bmad-output/implementation-artifacts/sprint-status.yaml (status flips + dev-start/review comments)
_bmad-output/implementation-artifacts/8-1-5-screen-onboarding-flow.md (this file)
```

### Change Log

- 2026-06-10 — Story 8.1 implemented (T1–T7, RED→GREEN per task): `onboardingState` SecureStore record (revision-guarded cache, partial-record encoding); AuthContext `signup.completed` emits (EMAIL always, KAKAO proxied on absent onboarding record) + deferred-destination stash + `lastAuthEvent` ref/getter incl. restoreSession="signIn"; 5-screen `ScrollView pagingEnabled` carousel (S1 D4 postcard fade, S5 grace/Wallet/Room/PIPA composite, forward-only dwell telemetry); returning-user change-summary branch; pathname-aware `<OnboardingGate>` + first `Stack.Screen` registration; login/signup unconditional replace. 28 net-additive FE tests (6+3+12+4+3). Gates 1–12 GREEN; 13–14 deferred (manual EAS) per merge-on-deferral norm. Status → review.
- 2026-06-10 — Code review patches complete: authoritative Kakao `newAccount` BE contract + V14 migration, completed-user invite routing, anonymous-route guard, stale destination clearing, rooms retry state, mid-onboarding share-link preservation, single-step swipe clamp, responsive carousel width, and function-size refactor. Status → done.

### References

- Epics: `_bmad-output/planning-artifacts/epics.md:982–1007` (Story 8.1 user story + 4 ACs)
- PRD: `_bmad-output/planning-artifacts/prd.md:432–448` (Epic 8.8 FR-8.8.1 onboarding script + brand-voice)
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md:1258–1278` (J1 cold-start onboarding flowchart)
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md:1133–1134` (D4 Postcard surface assignment)
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md:1171` (Onboarding 5스크린 = base + D4 hint on welcome screen)
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md:1731` (Multi-step form ❌ — carousel only)
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md:1781–1782` (Notification permission seat — already covered by `PushTokenBootstrap`)
- Architecture: `_bmad-output/planning-artifacts/architecture.md:419–485` (§4.16 FE↔BE token codegen + D4 sub-mode override)
- Architecture: `_bmad-output/planning-artifacts/architecture.md:148` (NFR-9.6.1 packed-type enforcement = brand-voice lint Rule 1)
- Story 8.5 (PR #102): `_bmad-output/implementation-artifacts/8-5-analytics-sdk-selection-event-taxonomy.md` (AC4 PIPA helper, AC5 locked taxonomy, AC9 Settings revocation seat, AC10 fail-closed bootstrap)
- Story 8.5 decision: `_bmad-output/planning-artifacts/analytics-sdk-decision-2026-06-09.md:97` (default state deferred to Story 8.1)
- Story 8.5 decision: `_bmad-output/planning-artifacts/analytics-sdk-decision-2026-06-09.md:138` (Story 8.1 onboarding screen 5 PIPA copy explicit deferral)
- Story 1.6 (PR #62): `_bmad-output/implementation-artifacts/1-6-welcomewindow-j0-leaders-lonely-30-seconds.md` (D4 sub-mode + `useTheme()` + Animated fade pattern reference)
- Story 1.7 (PR #63): `_bmad-output/implementation-artifacts/1-7-ritualmoment-06-00-kst-5-second-sacred-wrapper.md` (`useReducedMotion()` gating pattern reference)
- Story 3.2 (PR #69): `_bmad-output/implementation-artifacts/3-2-friend-gift-revival-push-prompt-friend-gift-modal.md` (`playedRevivalEvents.ts` SecureStore shape reference)
- Epic 7 retro: `_bmad-output/implementation-artifacts/epic-7-retro-2026-06-08.md` §10 Discovery 1 (W1 SDK mandate slip → Story 8.5 first → Story 8.1 second sprint reorder)
- project-context: `_bmad-output/project-context.md:97` (env var pattern), `:100` (SecureStore namespace), `:124` (Stack.Screen registration), `:150` (Jest testMatch)
