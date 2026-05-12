---
stepsCompleted:
  - step-01-validate-prerequisites
  - step-02-design-epics
  - step-03-create-stories
  - step-04-final-validation
inputDocuments:
  - '_bmad-output/planning-artifacts/prd.md'
  - '_bmad-output/planning-artifacts/architecture.md'
  - '_bmad-output/planning-artifacts/prfaq-yeolsal.md'
  - '_bmad-output/planning-artifacts/prfaq-yeolsal-distillate.md'
  - '_bmad-output/project-context.md'
  - 'docs/index.md'
project_name: 'yeolsal (열살방)'
date: '2026-05-10'
status: 'draft'
---

# yeolsal (열살방) — Epic Breakdown

## Overview

This document decomposes the v1 build of 열살방 into 8 epics and 32 stories, ready for `/bmad-sprint-planning` and the dev-story cycle. Each story:

- Maps to one or more PRD §8 FR-IDs and Architecture §4 decisions.
- Carries Given/When/Then acceptance criteria written for the dev-story agent.
- References concrete file paths (BE Java packages, FE component locations, V11 SQL fragments) drawn from Architecture §6.

Sprint W1–W8 alignment with PRD §11 is shown in §3 (Epic List).

---

## Requirements Inventory

### Functional Requirements (PRD §8)

- **FR-8.1** Survival State & Daily Loop (FR-8.1.1 through FR-8.1.7)
- **FR-8.2** Spectator Mode (FR-8.2.1 through FR-8.2.6)
- **FR-8.3** Revival Economy (FR-8.3.1 through FR-8.3.8)
- **FR-8.4** Group Point Pool & Future Redemption Promise (FR-8.4.1 through FR-8.4.5)
- **FR-8.5** Group Leader & Rule Versioning (FR-8.5.1 through FR-8.5.8)
- **FR-8.6** KakaoTalk SDK Invite Virality (FR-8.6.1 through FR-8.6.6)
- **FR-8.7** Final-3 Monthly Ceremony (FR-8.7.1 through FR-8.7.6)
- **FR-8.8** Brand Voice & Onboarding (FR-8.8.1 through FR-8.8.6)

### Non-Functional Requirements (PRD §9)

- **NFR-9.1** Performance (5 NFRs)
- **NFR-9.2** Reliability (5 NFRs)
- **NFR-9.3** Security & Privacy (7 NFRs)
- **NFR-9.4** Observability & Telemetry (4 NFRs)
- **NFR-9.5** Compatibility & Migration (5 NFRs)
- **NFR-9.6** Accessibility (3 NFRs)
- **NFR-9.7** Internationalization (3 NFRs)
- **NFR-9.8** Build & Deploy (6 NFRs)

### Additional Requirements (Architecture §4 decisions)

- 4.1 materialized survival_state · 4.2 push evaluator · 4.3 SQL-window 7d · 4.4 advisory lock + partial unique · 4.5 append-only ledger · 4.6 counter cache · 4.7 layout-branched spectator · 4.8 push + Wallet badge · 4.9 SVG poster · 4.10 nginx-cached preview · 4.11 V11 backfill · 4.12 boolean flag ticket · 4.13 batch SQL evaluator · 4.14 two-channel realtime privacy · 4.15 brand-voice gate

### FR Coverage Map

> **Updated 2026-05-11** — the authoritative, refreshed FR Coverage Map (including the post-SCP additions Stories 1.5/1.6/1.7/3.5/4.3/8.5 and FR-8.3.9) now lives at the **bottom of this document** under `Validation Summary § FR Coverage Map (refreshed 2026-05-11)`. The legacy table previously in this position was stale and has been retired to avoid divergence with the implementation reality.

---

## Epic List

| # | Epic | Goal | Sprint Week |
|---|------|------|-------------|
| 1 | Survival State & Daily Loop | State machine + V11 + daily evaluator | W1–W2 |
| 2 | Spectator Mode | Read-only routing branch + digest push | W2–W3 |
| 3 | Revival Economy | Free ticket / personal-points / friend-gift flows | W3–W4 |
| 4 | Group Point Pool | Counter cache + phase-2 promise UI | W4 |
| 5 | Leader & Rule Versioning | Next-month-only contract + leader transfer | W3–W4 |
| 6 | KakaoTalk SDK Invite | Native module + share + preview card | W5 |
| 7 | Final-3 Monthly Ceremony | Server-rendered SVG + Home-tab share | W6 |
| 8 | Brand Voice & Onboarding | 5-screen onboarding + lint helper + ASO | W7 |

W8 = bug fix, telemetry instrumentation, App Store submission, internal QA, prod deploy.

---

## Epic 1: Survival State & Daily Loop

**Goal:** Replace the existing daily-entries surface with a survival-state-aware experience. Add the V11 migration that creates the new tables, the 06:00 KST daily evaluator job that drives the state machine, and the privacy-filtered survival API. Maintains backward compatibility with existing schema.

**Sprint:** W1–W2.

### Story 1.1: Room creation with v1 cap + 14-day grace trial

As a primary user (친구 그룹 자기관리 동호인),
I want to create a 열살방 room with a configurable member cap (12 default, up to 30) and have my first 14 days run as a stake-free grace trial,
So that I can shape the room to my friend group's size and learn the loop before consequences land.

**Acceptance Criteria:**

**Given** a signed-in user opens room creation
**When** they pick `max_members = 12` (default) or any value between 2 and 30
**Then** the BE validates the cap server-side and persists it to `rooms.max_members` via the V11 widened CHECK constraint
**And** every member receives a `survival_state` row with `status='ACTIVE'` and `grace_ends_at = joined_at + 14 days`

**Given** a member is within the grace window
**When** the daily evaluator runs and the member missed yesterday's rule
**Then** state may transition to `YELLOW` but **never** to `RED` until `grace_ends_at` has passed (PRD FR-8.1.4)

**Given** a member's `grace_ends_at` is reached
**When** the next 06:00 KST evaluator runs
**Then** the member is fully subject to YELLOW→RED transitions per the rolling 7-day window

**Given** I am the user calling `POST /api/v1/rooms`
**When** the room is successfully created
**Then** `rooms.owner_id = creatorUserId` is persisted, and the creator is treated as the room leader by all subsequent leader-only endpoint authorizations (PRD FR-8.5.1 — added per readiness review 2026-05-11 to make the implicit "creator = default leader" rule explicit at room creation).

**And** the FE Room Creation screen shows the 14-day grace banner and `max_members` picker constrained to `[2, 30]`. PRD ref: FR-8.1.1, FR-8.1.2, FR-8.1.4, **FR-8.5.1**. Architecture ref: §4.1, §6.3 V11 (3, 4).

### Story 1.2: 06:00 KST survival-state evaluator job

As the system,
I want a scheduled job that evaluates every active member's compliance against the previous day's rule at 06:00 KST,
So that the survival state machine progresses deterministically and idempotently.

**Acceptance Criteria:**

**Given** the clock reaches 06:00 KST
**When** `SurvivalStateEvaluatorJob.@Scheduled` triggers
**Then** for each room, the per-room batch SQL (Architecture §4.13) computes compliance for the prior `entry_date` and either:
  - emits `personal_points_ledger += 2 (SURVIVAL)` for compliant members, OR
  - applies the 1/month `streak_freezes` row (no state change), OR
  - transitions `survival_state.status` to `YELLOW` (first miss in 7-day window) or `RED` with `eliminated_at = now()` and `broad_visibility_at = now() + 24h`

**Given** the job runs twice for the same date (retry / replay)
**When** the second run encounters a `notification_log (user_id, kind='SURVIVAL_STATE', key='{date}:{userId}')` row already present
**Then** that user is skipped — no double-progress (PRD NFR-9.2.1)

**Given** a state transition succeeds inside the per-room transaction
**When** the transaction commits
**Then** a `Spring TransactionalEventListener` emits `RealtimeEvent.SurvivalStateChange` to:
  - `/user/queue/{user_id}/private-survival` (immediately, for the affected user + room leader)
  - `pending_realtime_broadcasts` row scheduled at `broad_visibility_at` for the broad `/topic/rooms/{roomId}/survival` (Architecture §4.14)

**And** total wall-clock for 50,000 active members across all rooms < 5 minutes (NFR-9.1.1). PRD ref: FR-8.1.3, FR-8.1.5, FR-8.1.7. Architecture ref: §4.2, §4.3, §4.13, §4.14, §6.3 V11 (3, 5).

### Story 1.3: Privacy-filtered survival roster API

As an active member of a room,
I want `GET /api/v1/rooms/{id}/survival` to return everyone's status with privacy correctly applied,
So that the FE can render rosters without leaking eliminations during the 24-hour soft-public cooldown.

**Acceptance Criteria:**

**Given** I am a non-leader member calling `GET /api/v1/rooms/{id}/survival`
**When** another member is in `RED` status with `broad_visibility_at > now()`
**Then** that member appears as `ACTIVE` to me in the response (PRD FR-8.1.6)

**Given** I am the room leader (`rooms.owner_id == me`) calling the same endpoint
**When** another member is in `RED` status with `broad_visibility_at > now()`
**Then** I see their actual `RED` status (server-side privacy filter is leader-aware)

**Given** I call `GET /api/v1/rooms/{id}/survival` for a room I am not a member of
**When** the request reaches the controller
**Then** the response is `403 FORBIDDEN` with code `FORBIDDEN`

**Given** `broad_visibility_at <= now()` for a member in `RED`
**When** I call the endpoint as a non-leader member
**Then** the member's actual `RED` status is returned (cooldown has elapsed)

**And** the response is wrapped in `ApiResponse.of(...)`. PRD ref: FR-8.1.6. Architecture ref: §4.7, §4.14, §5.1.

### Story 1.4: V11 migration + production backfill

As a deployment engineer,
I want the V11 Flyway migration to be idempotent, safe to roll forward against production rooms, and to backfill `survival_state`, free-revival-ticket flags, and `room_point_pool`,
So that existing yeolsal users continue to function without data loss on first deploy after cutover.

**Acceptance Criteria:**

**Given** the V11 migration runs against a fresh empty Postgres
**When** Flyway invokes the script
**Then** all 12 schema steps (Architecture §6.3) succeed and `flyway_schema_history.success = true` for V11

**Given** the V11 migration runs against the existing prod schema (V1–V10 already applied)
**When** Flyway invokes the script
**Then** the `rooms.max_members` CHECK is widened to `BETWEEN 2 AND 30`, the default becomes 12, and **existing rooms keep their current cap** (NFR-9.5.1, NFR-9.5.2)

**Given** the V11 migration completes
**When** the backfill SQL (steps 13, 14, 15) executes inside the same migration
**Then**:
  - every existing `room_members` row produces a `survival_state` row with `status='ACTIVE'` (`ON CONFLICT DO NOTHING` for re-runs)
  - every existing `rooms` row produces a `room_rule_versions` row with `effective_from_month = current month KST` and the default rule preset
  - every existing `rooms` row produces a `room_point_pool` row with `total = 0`

**Given** the V11 migration ran once and a deployment retry runs it again
**When** Flyway re-invokes the script
**Then** all `ON CONFLICT DO NOTHING` clauses ensure no duplicates, no errors

**Given** any Postgres-specific feature is used (e.g., `to_char(... at time zone 'Asia/Seoul')`, partial unique indexes)
**When** the migration is tested in CI
**Then** Testcontainers PostgreSQL is used (H2 forbidden per project-context). PRD ref: NFR-9.5.*. Architecture ref: §4.11, §6.3.

### Story 1.5: Design System Foundation v2 — token packed type + FE↔BE codegen

> Added per Sprint Change Proposal 2026-05-10. Closes Implementation Readiness M5/H4 (NFR-9.6.1) + A5 (sub-mode token sync). Depends on `/bmad-create-ux-design` round output.

As an engineer building any FE or BE surface,
I want a single source-of-truth token bundle (`FE/src/theme/tokens.json`) that both FE and BE consume via codegen, with the `semantic.survival` token structurally requiring the label,
So that NFR-9.6.1 is enforced at compile-time and FE/BE design drift is structurally impossible.

**Acceptance Criteria:**

**Given** the design 라운드 산출물 (palette / typography / sub-mode tokens) is locked
**When** the design owner commits `FE/src/theme/tokens.json`
**Then** the JSON validates against the schema (Architecture §4.16) — `semantic.survival` has all four states (ACTIVE/YELLOW/RED/SPECTATOR), each with `{ color, label, icon, grass-treatment }` populated; missing any field fails CI (`./gradlew validateTokens`)

**Given** `tokens.json` is committed
**When** `cd BE && ./gradlew build` runs
**Then** the `generateTokens` task produces `BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java` with public-static fields per token, and the build incorporates the generated source set; `SvgRenderer.java` and any future BE renderer references those constants only (no hex literals — enforced by Checkstyle / ArchUnit rule)

**Given** any FE component imports `survival.{state}.color` (or `semantic.survival.*.color`)
**When** `tools/brand-voice-lint.ts` runs in CI
**Then** the lint **fails the workflow** (hard CI gate per Architecture §4.15) if no sibling `<Text>{label}</Text>` or `accessibilityLabel={label}` exists within the same JSX subtree

**Given** an L3 component receives a `subMode` prop
**When** it renders with `subMode='editorial'`
**Then** it consumes `tokens.subMode.editorial.*` overrides via the FE theme provider — no string-literal sub-mode names hardcoded inside components

**Given** a contrast WCAG 2.2 AA test runs against the v2 palette
**When** every foreground/background combination is tested
**Then** `text.primary`-on-`bg.canvas`, `text.primary`-on-`bg.surface`, `text.primary`-on-`bg.elevated`, `text.secondary`-on-`bg.canvas`, `text.primary`-on-`key.default`, `text.inverse`-on-`bg.inverse` all pass AA at body 14pt; `text.tertiary` text combinations are scoped to caption use only (≥18pt or bold ≥14pt); results codified in `docs/design-system.md` contrast matrix.

**Given** any FE component needs to display survival state (ACTIVE/YELLOW/RED/SPECTATOR)
**When** an engineer adds the status to a screen
**Then** the only allowed entry point is the `<SurvivalChip state={...} />` primitive in `FE/src/components/survival/SurvivalChip.tsx`. It consumes the packed-type `semantic.survival.{state}` token internally and renders `{color dot + icon + label}` as a non-splittable composite View. Direct references to `survival.{state}.color` or `semantic.survival.*.color` from any component file are blocked by `tools/brand-voice-lint.ts` (hard CI gate per Architecture §4.15). Added per readiness review 2026-05-11 (M1).

**Given** any page-level surface needs a sub-mode (D1 Editorial / D2 Bento / D3 Quiet / D4 Postcard / D5 Plate) different from the base
**When** the page mounts
**Then** the page wraps its content tree in `<SubModeProvider subMode="D2.bento" />` (or equivalent) from `FE/src/providers/SubModeProvider.tsx`. The provider injects resolved override tokens into `useTheme()`; leaf components remain sub-mode-agnostic (per UX cross-cutting rule #9 — sub-mode is page-level only, never branched inside leaf components). E2E verification: Wallet screen (D2) + Spectator route wrapper (D3) both render correctly when the SubModeProvider injects their respective override sets. Added per readiness review 2026-05-11 (M2).

**Given** the schema validator runs against `tokens.json`
**When** a `subMode.*` override block contains a key outside the documented whitelist (`color.bg.surface`, `color.bg.elevated`, `typography.heading.weight`, `typography.heading.tracking`, `typography.display.serif.enabled`, `motion.entry.duration`, `motion.entry.easing`, `radius.default`, `radius.pronounced`, `elevation.1`, `elevation.2`, `space.layout.padding`)
**Then** `./gradlew validateTokens` fails CI with a clear error message naming the rejected key (per UX "Token Surface Override 정책" — prevents per-surface visual drift). Added per readiness review 2026-05-11 (L1).

**And** `docs/design-system.md` is regenerated from `tokens.json` (or hand-synced per W1 lock decision) — both files are checked into the same PR. PRD ref: §14.2 (visual identity / NFR-9.6.1 enforcement / FE↔BE token sync), NFR-9.6.1, NFR-9.6.3. Architecture ref: §4.15, §4.16. Readiness ref: M1, M2, L1.

### Story 1.6: WelcomeWindow — J0 leader's lonely 30 seconds

> Added per Sprint Change Proposal 2026-05-10. Closes UX U1 disposition. PRD §4.3 J0 root authority.

As a leader who just created a room and is the sole member,
I want a Welcome surface that invites me to either share to KakaoTalk or start logging today, without progress-bar pressure,
So that I don't bounce in the first 30 seconds and feel the room is "incomplete".

**Acceptance Criteria:**

**Given** I am a signed-in user who just created a room (POST `/api/v1/rooms` returned 201)
**When** the FE redirects to the room
**Then** the room screen renders `<WelcomeWindow>` (D4 Postcard Mythic sub-mode in v2 system, per UX Surface Assignment Matrix) with: room name, headline ("친구를 초대하면 같이 살아남을 수 있어요"), and 2 CTA of equal weight: (a) "🥥 카카오로 초대" / (b) "🌿 먼저 오늘 기록하기"

**Given** I am the only member (`room_members` count = 1)
**When** the screen renders
**Then** **NO** progress bar / "11명 더 들어와야 시작" message is shown (PRD §6.1 anti-pattern guard — A10 in UX spec)

**Given** I tap "🥥 카카오로 초대" during W1-W2 (before Story 6.2 ships in W5)
**When** the CTA is rendered before the Kakao Share SDK lands
**Then** the CTA is shown in a `disabled` state with tooltip "곧 카카오 초대가 가능해질 거예요" (brand-voice copy; decision locked 2026-05-11 — Option B). The "🌿 먼저 오늘 기록하기" CTA-B remains fully functional in W1-W2 so the J0 anti-pattern guard still holds.

**Given** Story 6.2 has shipped (W5+) and I tap "🥥 카카오로 초대"
**When** the deep-link fires
**Then** the existing Kakao Share SDK flow (Story 6.2) opens with this room's invite-code preserved

**Given** I tap "🌿 먼저 오늘 기록하기"
**When** I navigate
**Then** I land on Today tab with a warm tone ("첫 잔디 — 곧 함께 채워질 거예요") and the 잔디 grid shows 1/N with no shame language

**Given** another member joins (room_members count: 1→2)
**When** my client receives the realtime event
**Then** the chat receives a system message ("민지 함께합니다 🌿") in D5 Plate System tone, and `<WelcomeWindow>` transitions from `solo` → `growing` state (CTA still visible until ≥2 members + grace ended)

**Given** room_members count ≥ 2 AND `room.created_at + 14 days` reached
**When** the next render runs
**Then** `<WelcomeWindow>` transitions to `full` state (auto-dismissed) and J2/J3 surfaces become available

**And** all copy passes brand-voice lint (`tools/brand-voice-lint.ts`). PRD ref: §4.3 J0, FR-8.1.1, FR-8.1.2. Architecture ref: §4.7. UX ref: J0 + `<WelcomeWindow>` working name.

### Story 1.7: RitualMoment — 06:00 KST 5-second sacred wrapper

> Added per Sprint Change Proposal 2026-05-10. Closes UX U4 disposition. Root authority: PRD §6.4 principle 5 ("Ritual time is sacred").

As a member who opens the app between 06:00–06:05 KST,
I want a 5-second visual ritual that marks the day boundary as sacred (not transactional),
So that the daily-loop feels like co-presence ritual, not a checklist task.

**Acceptance Criteria:**

**Given** I open the app at any time between 06:00:00 and 06:05:00 KST
**When** the root navigator mounts
**Then** `<RitualMoment>` overlays the screen with `accessibilityViewIsModal=true` for 5 seconds: paper-tone surface → key-color tinted shift (500ms) → return (500ms) + center text "오늘도 함께" (display 36pt) + 일자 (caption)

**Given** today is one of the variant days (Maya 우려: ritual ↔ drudgery, 3일째 무너짐 방지)
**When** RitualMoment renders
**Then** the center text varies:
  - 월~목: "오늘도 함께"
  - 금: "이번 주도 살아남았어요"
  - 토일: "주말도 함께"
  - 매월 1일 (Final-3 ceremony day): "이번 달 Final-3 카드가 도착했어요" (Story 7.3 prerender preload)

**Given** the user has reduced-motion preference enabled (iOS / Android system setting)
**When** RitualMoment fires
**Then** the 5-second sequence is **shortened to 1 second fade**, with no key-color tint shift; center text appears immediately and dismisses (NFR-9.6.* + UX A6 reduced-motion gap closed)

**Given** I dismiss the app and reopen within the same 06:00–06:05 window on the same KST day
**When** RitualMoment evaluates
**Then** it does **not** re-fire (idempotent per KST date — track via AsyncStorage key `ritual.lastFiredKstDate`)

**Given** I am in spectator mode
**When** RitualMoment evaluates
**Then** it still fires (spectator dignity tone — they are still part of "함께"), but with dimmed key-color treatment (subdued saturation per `subMode='quiet'` + spectator variant)

**Given** VoiceOver / TalkBack is active
**When** RitualMoment fires
**Then** the announcement reads "{date}, 오늘도 함께" once; users can interact with the underlying screen after the 5s window

**And** the component does NOT block app start (renders after first frame). PRD ref: §6.4 principle 5 (root authority). Architecture ref: §4.16 (token consumption). UX ref: `<RitualMoment>` working name, U4 disposition ACCEPT.

---

## Epic 2: Spectator Mode

**Goal:** Eliminated users see what they're missing — the FOMO engine. Implements the layout-branched read-only routing in FE and the corresponding service-layer rejection in BE chat-write API.

**Sprint:** W2–W3.

### Story 2.1: Spectator-mode FE routing branch

As an eliminated member (`status = SPECTATOR`),
I want the app to drop me into a read-only variant of the same rooms surface,
So that I can watch the room continue without being able to post and feel the FOMO that brings me back.

**Acceptance Criteria:**

**Given** my `useSurvivalState()` hook returns `status: 'SPECTATOR'` for the current room
**When** the `app/(tabs)/_layout.tsx` renders
**Then** the same tabs (Today/Feed/Monthly/Profile) render the spectator variant of each screen — no separate route group (Architecture §4.7)

**Given** I am in spectator mode
**When** the chat list screen renders
**Then** `MessageInput` is hidden and pressing send is impossible at the FE; the BE chat-write API also returns `403 FORBIDDEN` with code `FORBIDDEN` if any client bypasses (NFR-9.2.5)

**Given** I am in spectator mode
**When** the Wallet tab renders
**Then** my free revival ticket presence + personal points balance + room point pool are prominently displayed (PRD FR-8.2.5)

**Given** I successfully revive (any source) and `status` transitions back to `ACTIVE`
**When** the next render cycle runs
**Then** I immediately re-enter active member mode in < 1s (NFR-9.1.5)

**Given** I am a `SPECTATOR` user and attempt to POST to `/api/v1/rooms/{id}/messages` (chat write) by any means (FE bug bypass, manual curl, etc.)
**When** the BE controller receives the request
**Then** the response is `403 FORBIDDEN` with code `SPECTATOR_WRITE_FORBIDDEN`; the rejection is enforced inside the existing `chat/` module's controller / service path; an integration test under `BE/src/test/java/com/yeosal/api/chat/` covers this specific status × write-action combination with `@WithMockUser` mounting a SPECTATOR-state user via the test helper (added per readiness review 2026-05-11 — promotes the previously-implicit double-enforcement of NFR-9.2.5 to an explicit owned AC).

**And** spectator-mode screens reuse existing screens with a `mode='spectator'` prop drilled through; no parallel route copies. PRD ref: FR-8.2.1, FR-8.2.2, FR-8.2.5, FR-8.2.6. NFR-9.2.5. Architecture ref: §4.7.

### Story 2.2: Spectator daily digest push

As an eliminated member,
I want a single daily digest push notification at 09:00 KST summarizing my room's activity rather than realtime per-message pushes,
So that the room's life is felt without surveillance-tier notification spam.

**Acceptance Criteria:**

**Given** I am in spectator mode and my room had ≥1 message / state change yesterday
**When** the digest job runs at 09:00 KST
**Then** I receive **one** push notification with brand-voice copy ("오늘도 운동방이 함께 살아남고 있어요" tone)

**Given** my room had no activity yesterday
**When** the digest job runs
**Then** no push is sent

**Given** the current time falls within my `notification_prefs.quiet_*_hour` quiet window
**When** the digest job evaluates
**Then** the push is suppressed (NFR-9.3.5)

**Given** I am in active mode (`status = 'ACTIVE'` or `'YELLOW'`)
**When** the digest job evaluates
**Then** I am **not** included — the digest path is spectator-only

**And** the push payload is logged via `notification_log` with `kind='SPECTATOR_DIGEST'`, `key='{date}:{userId}'` for idempotency. PRD ref: FR-8.2.3.

### Story 2.3: Record visibility opt-in for eliminated users

As an eliminated member,
I want my daily history to default to private (only I see it) and to have an explicit opt-in to share with my room,
So that elimination doesn't auto-expose private reflections.

**Acceptance Criteria:**

**Given** I am in spectator mode
**When** my profile / archive page is viewed by another room member (active, leader, or otherwise)
**Then** my `daily_entries`, `reflections`, `todo_items` for that room are **not visible** unless `record_visibility_prefs.share_on_elimination = true` for `(my_user_id, room_id)`

**Given** I open Settings → Privacy → "이 방에서 내 기록 공유" toggle
**When** I switch it on and confirm
**Then** `POST /api/v1/me/visibility-prefs` updates `record_visibility_prefs (user_id, room_id, share_on_elimination)` and returns the new value

**Given** the toggle is off (default)
**When** another member's API call would render my history
**Then** the BE returns an empty/redacted payload — no FE-side filtering (NFR-9.3.2)

**And** the toggle's brand-voice copy uses 그룹/공유 vocabulary, not 노출/탈락. PRD ref: FR-8.2.4.

---

## Epic 3: Revival Economy

**Goal:** v1's full economy — three revival sources (free ticket, personal points, friend gift), with strict idempotency and concurrency-safety. No money. Defines the load-bearing emotional moment.

**Sprint:** W3–W4.

### Story 3.1: Free revival ticket + self-revival via personal points

As an eliminated member,
I want to use my free revival ticket immediately at signup or, after that, spend my own personal points to revive myself,
So that I can return to the room without depending on anyone else.

**Acceptance Criteria:**

**Given** I am a new user
**When** my account is created
**Then** `users.free_revival_ticket_used = false` (Architecture §4.12)

**Given** I am `RED` or `SPECTATOR` and `users.free_revival_ticket_used = false`
**When** I call `POST /api/v1/rooms/{id}/revival` with `{ source: 'FREE_TICKET' }`
**Then** within a single transaction:
  - advisory lock on `(room_id, user_id, eliminated_at::date)` is acquired
  - `UPDATE users SET free_revival_ticket_used = true WHERE id = ? AND free_revival_ticket_used = false RETURNING ...` runs (succeeds exactly once)
  - `revival_events` row inserted with `source='FREE_TICKET'`, `points_spent=0`
  - `survival_state.status = 'ACTIVE'`
  - `room_point_pool.total += 5` (NFR-9.2.4 with `SELECT … FOR UPDATE`)
  - `RealtimeEvent.SurvivalStateChange` and `.PointPoolChange` emitted post-commit

**Given** my free ticket is already used and I have ≥3 personal points in `(user_id, room_id)`
**When** I call the same endpoint with `{ source: 'PERSONAL_POINTS' }`
**Then** 3 points are deducted via `personal_points_ledger` row (`reason='REVIVAL_SPEND'`), the same revival flow runs, and `room_point_pool.total += 3`

**Given** my free ticket is used and personal points balance < 3
**When** I call with `{ source: 'PERSONAL_POINTS' }`
**Then** the response is `400 BAD_REQUEST` with code `INSUFFICIENT_POINTS`

**Given** two clients race the same revival request
**When** both reach the advisory-lock contender
**Then** one succeeds (status `ACTIVE`); the other receives `409 CONFLICT` with code `ALREADY_REVIVED` (Architecture §4.4)

**And** the `revival_events` partial unique `ux_revival_events_one_per_elimination` is the second line of defense — duplicate INSERT throws `DataIntegrityViolationException`, mapped to `409 CONFLICT` by `ApiExceptionHandler`. PRD ref: FR-8.3.1, FR-8.3.2, FR-8.3.8. Architecture ref: §4.4, §4.6, §4.12, §6.3 V11 (5, 6, 7), §6.4.

### Story 3.2: Friend-gift revival — push prompt + Friend Gift Modal

As a room member with sufficient personal points and a friendship to an eliminated room-mate,
I want to receive a single invitation-toned push notification when my friend can be revived, and to spend 5 of my points to revive them via the Friend Gift Modal,
So that the load-bearing emotional moment of the product is captured.

**Acceptance Criteria:**

**Given** member 수진 transitions to `RED` (or `SPECTATOR`) at time T
**When** the post-commit event listener runs
**Then** for **every** eligible giver (room member with `personal_points_balance ≥ 5` AND active `friendships` to 수진), exactly **one** push to `/user/queue/friend-gifts` is sent — no follow-up reminders (PRD FR-8.3.4)

**Given** I receive the push and tap it
**When** the FE deep-links to the Friend Gift Modal
**Then** the modal shows: 수진's nickname (+ avatar + 잔디 thumbnail + 상태 라벨), my balance (X points, 영수증 톤), gift cost (5), and **3 CTA of equal visual + a11y weight** (per Sprint Change Proposal 2026-05-10):
  - 💗 "회생권 선물 (5점)" — primary key-emphasis variant
  - 💚 "응원만 보내기 (0점)" — KudosButton (PRD FR-8.3.9, Story 3.5)
  - "닫기" — ghost variant
  + 안심 메시지 하단: "선물해도 안 해도 친구는 모릅니다." (FR-8.3.7)

**Given** I tap "회생권 선물 (5점)"
**When** `POST /api/v1/rooms/{id}/revivals/gifts { targetUserId: 수진_id }` runs
**Then**:
  - the same advisory-lock + partial-unique-index pattern as Story 3.1 ensures exactly-one revival
  - my `personal_points_ledger` row inserts `delta=-5, reason='FRIEND_GIFT_SPEND'`
  - 수진's `survival_state.status = 'ACTIVE'`
  - `revival_events` row inserts with `source='FRIEND_GIFT'`, `giver_user_id=me`, `points_spent=5`, `source_subtype='PUSH_INITIATED'`
  - `room_point_pool.total += 5`
  - 수진 receives a separate push to `/user/queue/{userId}/private-survival` confirming donor name (only visible to 수진 by default)

**Given** I tap "응원만 보내기 (0점)" with optional 1-line message (Story 3.5 endpoint)
**When** `POST /api/v1/rooms/{id}/kudos { targetUserId: 수진_id, message? }` runs (PRD FR-8.3.9)
**Then** a `chat_messages` row inserts with `kind='KUDOS'`; receiver gets one push (invitation tone "수진이 응원을 보냈어요"); donor gets toast "응원이 도착했어요 🌿"; modal closes; **NO** `revival_events` row, **NO** `survival_state` change, **NO** points deducted (UX U3 disposition ACCEPT)

**Given** my balance < 5 points
**When** the modal renders
**Then** "회생권 선물 (5점)" CTA is **disabled** with tooltip "잔액 부족"; "응원만 보내기" remains enabled (0점이라 잔액 무관); "닫기" 정상

**Given** another giver beat me to it
**When** my request reaches the lock
**Then** I receive `409 CONFLICT` with code `ALREADY_REVIVED` and the FE shows "이미 회생되었습니다" message; modal auto-closes after 1.5s

**Given** I am the receiver and this is my **first** ever FRIEND_GIFT *send* (lifetime-1 marker — UX U2 disposition ACCEPT)
**When** I later tap "회생권 선물" for any room-mate and the revival succeeds
**Then** post-success, the M3.5 lifetime-1 moment fires: a 1-second display "이제 너는 누군가의 어둠을 비춘다" overlay with key-color accent; subsequent FRIEND_GIFT sends do NOT fire M3.5; lifetime-1 check via `EXISTS (SELECT 1 FROM revival_events WHERE giver_user_id = me AND source = 'FRIEND_GIFT')`

**Given** Receiver was successfully revived via FRIEND_GIFT at time T (UX U8 — 7-day echo footnote)
**When** Receiver opens daily entry on T+0d through T+6d
**Then** the daily-entry screen shows a footer footnote: "{donorName}가 너를 살린 지 {N}일째" (caption tone, not pressure tone). On T+7d, footnote is no longer rendered. Footnote consumes `revival_events` query joined to current user's most-recent FRIEND_GIFT receive within 7-day window

**Given** the receiver's M3 부활 시퀀스 fires (5-phase animation per UX `<RevivalSequence>`)
**When** the receiver opens the app post-push
**Then** the FE plays the 5-phase sequence (T+0–3s paper→ink fade · T+1.5–3s donor name handwriting fade-in · T+3–4.5s "너를 위해 자기 것을 썼다" fade-in · T+4.5–5s key-color card · T+5s+ control 복귀 + "방으로 돌아가기" CTA); reduced-motion = 1s즉시 카드 + handwriting fade ❌ + donor name 직접 표시

**And** revival rejection / non-action by friends is **never visible** to anyone but the giver (FR-8.3.7). PRD ref: FR-8.3.3, FR-8.3.4, FR-8.3.5, FR-8.3.7, FR-8.3.9 (Kudos). Architecture ref: §4.4, §4.5, §4.8, §4.16. UX ref: `<FriendGiftModal>` 3-CTA + lifetime-1 M3.5 + 7-day footnote dispositions ACCEPT.

### Story 3.3: Wallet "친구 살리기" badge (passive discoverability)

As a member who may have notifications disabled or missed the push,
I want a subtle badge on my Wallet tab that lights up when a friend in my room is eligible for friend-gift revival,
So that I have a backup discoverability surface that doesn't pressure me but lets me act if I want.

**Acceptance Criteria:**

**Given** I open the Wallet tab and at least one room-mate friend has `survival_state.status ∈ {RED, SPECTATOR}` AND I have ≥5 personal points
**When** the Wallet renders
**Then** a small "친구 회생 대기 (N)" badge appears next to the room point pool stat

**Given** I tap the badge
**When** the badge action runs
**Then** the same Friend Gift Modal opens (with picker for which friend to revive if N > 1)

**Given** I act through the badge
**When** the revival is recorded via `POST /api/v1/rooms/{id}/revivals/gifts`
**Then** `revival_events.source_subtype = 'WALLET_INITIATED'` (Architecture §4.8)

**Given** at Day 30 telemetry shows wallet-initiated > push-initiated
**When** the team reviews the data
**Then** the friend-gift discoverability hypothesis (PRD §13.4) is reopened with concrete data

**And** the badge follows brand-voice copy ("친구 살리기" not "친구 구하기 / 친구 부담"). PRD ref: FR-8.3.6. Architecture ref: §4.8.

### Story 3.4: Wallet UI surface

As an active or spectator member,
I want a single Wallet view that shows my free revival ticket presence, my personal points balance, the room's point pool, and a private "받은 회생권" history,
So that the entire economy is legible from one place.

**Acceptance Criteria:**

**Given** I open the Wallet tab
**When** the screen renders
**Then** I see four sections in order: free revival ticket (used or not), personal points balance, room point pool, received-revival history

**Given** I tap the personal points section
**When** the detail view opens
**Then** I see my `personal_points_ledger` entries chronologically (REVIVAL_SPEND / SURVIVAL / FRIEND_GIFT_SPEND / ROOM_LEAVE / ADJUSTMENT)

**Given** I tap "받은 회생권" history
**When** the detail view renders
**Then** I see entries where I am the receiver of a revival event with `source ∈ {FREE_TICKET, PERSONAL_POINTS, FRIEND_GIFT}`, donor name shown for FRIEND_GIFT entries (private to me)

**Given** another room member views my profile
**When** they reach the Wallet section
**Then** they see only the room point pool (group-level, not personal); my personal points and history are hidden

**And** the Wallet uses yeolsal v2 design tokens (per FE→BE codegen Architecture §4.16) with the pool growth animated as a positive bar fill on real-time WS update. PRD ref: FR-8.3.6. Architecture ref: §4.5, §4.6, §4.16.

### Story 3.5: Kudos message endpoint + chat_messages.kind extension

> Added per Sprint Change Proposal 2026-05-10. Closes UX U3 disposition. PRD FR-8.3.9 root authority.

As a room member who wants to support a friend in `RED`/`SPECTATOR` without spending points,
I want a 0-cost endpoint that posts an invitation-toned message into the room chat with a 1/day per-receiver dedupe rule,
So that "응원만 보내기" is a real first-class supportive action, not vaporware UI.

**Acceptance Criteria:**

**Given** the V11 migration includes the `chat_messages.kind` enum extension (decision locked 2026-05-11: single migration, batched with all v1 schema deltas per brownfield Flyway convention — see Architecture §4.11)
**When** the migration runs
**Then** `kind` accepts `'KUDOS'` (in addition to existing `'TEXT'` / `'SYSTEM'`); the partial unique index `ux_kudos_one_per_day (sender_user_id, target_user_id, date_part('day', created_at at time zone 'Asia/Seoul'))` is created (Postgres-specific feature — Testcontainers required, H2 forbidden per project-context)

**Given** I am an authenticated room member with an active friendship to `targetUserId` (same room, status `RED`/`SPECTATOR`)
**When** I `POST /api/v1/rooms/{id}/kudos { targetUserId, message?: string (max 60 chars) }`
**Then**:
  - input validation: `message` length ≤ 60 chars; UTF-8; brand-voice lint flag if AVOID lexicon (warn-level only)
  - dedupe check: same sender+receiver same KST day → `409 CONFLICT` code `KUDOS_ALREADY_SENT_TODAY`
  - on success: insert `chat_messages` row `kind='KUDOS'`, payload `{ sender_id, target_id, message }`, return `201 Created`
  - emit one push to receiver `/user/queue/{targetUserId}/kudos` invitation tone ("정민이 응원을 보냈어요")
  - emit `RealtimeEvent.KudosSent` to `/topic/rooms/{id}/kudos` with `{ senderId, targetId, messagePreview, occurredAt }` for room awareness

**Given** target user is not in `RED`/`SPECTATOR`
**When** kudos is sent
**Then** `400 VALIDATION` code `KUDOS_TARGET_NOT_ELIGIBLE` (kudos는 회생 대기 상태 한정)

**Given** sender and target are not friends
**When** kudos is sent
**Then** `403 FORBIDDEN` code `NOT_FRIENDS`

**Given** sender is `SPECTATOR` themselves
**When** kudos is sent
**Then** `403 FORBIDDEN` code `SPECTATOR_WRITE_FORBIDDEN` (consistent with NFR-9.2.5 spectator-write enforcement)

**Given** the receiver opens the kudos push
**When** they enter the room
**Then** the chat list renders the KUDOS row with sender name + message in a distinct visual variant (`<SystemMessage subMode='postcard'>` or v2 equivalent — different from TEXT and SYSTEM rows)

**Given** all input validation paths
**When** integration tests run (Testcontainers PostgreSQL)
**Then** every error path is covered (validation, dedupe-conflict, eligibility, friendship, spectator-write)

**And** Flyway migration adds the partial unique index in **V11** (decision locked 2026-05-11). PRD ref: FR-8.3.9. Architecture ref: §4.4, §4.11, §4.14, §4.16, §6.3 V11.

---

## Epic 4: Group Point Pool & Future Redemption Promise

**Goal:** Visible accumulating shared progress; no redemption in v1; phase-2 promise copy in place.

**Sprint:** W4.

### Story 4.1: Room point pool counter cache

As the system,
I want a per-room `room_point_pool` counter that updates atomically with every revival and is the source of truth for the pool number displayed everywhere,
So that the pool shown across all FE surfaces is consistent and never lost under concurrency.

**Acceptance Criteria:**

**Given** a room is created
**When** `RoomService.createRoom` returns
**Then** a `room_point_pool` row exists with `total = 0` (V11 backfill ensures this for existing rooms)

**Given** a revival succeeds (any source)
**When** the revival transaction is committed
**Then** the same transaction has acquired `SELECT … FOR UPDATE` on the room's pool row, incremented `total` by the appropriate amount (5 for FREE_TICKET / FRIEND_GIFT, 3 for PERSONAL_POINTS), and written `last_event_at = now()`

**Given** the write path detects a negative delta
**When** the service layer is called with `delta < 0`
**Then** the call throws `IllegalArgumentException` (mapped to 400 VALIDATION) — pool decrement is forbidden in v1 (PRD FR-8.4.5)

**Given** the pool changes
**When** `RealtimePublisher` fires `PointPoolChange`
**Then** the event is broadcast on `/topic/rooms/{id}/points` with `{ delta, newTotal, sourceRevivalEventId, occurredAt }`

**And** the FE `useRoomPoints` hook subscribes via `RealtimeProvider` and updates the FE pool display in real-time. PRD ref: FR-8.4.1, FR-8.4.2, FR-8.4.3, FR-8.4.5. Architecture ref: §4.6, §6.3 V11 (7), §6.4.

### Story 4.2: Phase-2 promise UI

As a room member,
I want the Wallet/Pool surface to clearly explain that v1 doesn't redeem the points but a future season will, in brand voice,
So that I trust what I'm building toward and don't experience phase-1's no-redemption as a bug.

**Acceptance Criteria:**

**Given** the Wallet pool section renders
**When** a member views it
**Then** brand-voice copy reads "다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다." (or equivalent, vetted by §FR-8.8.2 lexicon)

**Given** the FE attempts any redemption call
**When** the request reaches BE
**Then** there is no redemption endpoint in v1 to call (PRD §6.2 OUT) — the FE has no UI affordance to trigger one

**Given** phase-2 ships and `room_point_pool` is consumed
**When** the pool counter is decremented at redemption time
**Then** that change is implemented in phase-2 *only*; no v1 code path accommodates it pre-emptively (avoid premature abstraction per project-context coding-style)

**And** the copy passes the brand-voice review (Story 8.4 release gate). PRD ref: FR-8.4.4.

### Story 4.3: `<PoolStack>` 5-stage SVG asset pipeline + threshold table

> Added per readiness review 2026-05-11 (M4). Closes the UX-flagged pool metaphor implementation gap — UX explicitly limits pool to "5단계 정적 SVG/PNG swap" (5-stage static SVG/PNG swap; candidates: 돌탑 / 천 짜기 / 도자기 굽기 metaphors). Story 4.1 covers the counter cache + STOMP topic + FE hook; this story covers the visual asset side.

As a room member,
I want the room's accumulating point pool to be visualized as a 5-stage growing artifact (e.g., a stone pagoda gaining stones) that visibly progresses as my room and friends survive together,
So that the pool number is felt — not just read — as a shared, growing thing.

**Acceptance Criteria:**

**Given** the design owner has commissioned the 5-stage pool artifact (one of: 돌탑·천 짜기·도자기 굽기; final selection at W3 design lock — D2 Bento Density sub-mode token consumption)
**When** the SVG assets land in `FE/src/assets/pool/stage-{1..5}.svg` and corresponding PNGs in `FE/src/assets/pool/stage-{1..5}.png`
**Then** each stage SVG uses `tokens.json` color references only (no hex literals — verified by repo-level grep CI rule, mirror of Architecture §4.15 BE rule for FE assets); ships in oklch oxblood + neutral palette per D2 sub-mode.

**Given** the pool grows from 0 to N
**When** the FE `<PoolStack>` component renders the current stage
**Then** the stage thresholds are sourced from `FE/src/theme/pool-stages.ts`:
  - Stage 1: total ∈ [0, 9]   (seedling — empty foundation)
  - Stage 2: total ∈ [10, 24]  (early — first layer formed)
  - Stage 3: total ∈ [25, 49]  (mid — multi-layer presence; matches NFR success metric "≥50pt by Day-30" approach to Stage 4)
  - Stage 4: total ∈ [50, 99]  (mature — recognizable artifact)
  - Stage 5: total ≥ 100       (complete — keystone present)
  These thresholds align with PRD §3.1 KPI "average active room ≥50 pool points by day 30" so Stage 4 corresponds to the success bar. Final threshold tuning reviewed with PM at Day-30 telemetry checkpoint.

**Given** the pool transitions between stages (e.g., total goes from 9 → 10)
**When** `<PoolStack>` receives the new total via `useRoomPoints` (Story 4.1)
**Then** the stage-N → stage-(N+1) swap uses a cross-fade per `motion.normal` duration + `ease.entry` easing token (no janky pop); the increment delta number animates briefly with `ember.default` glow (per UX feedback pattern "Success — 1초 toast, `bg.elevated` + `ember.default` dot stroke"); reduced-motion fallback → instant swap, no glow.

**Given** the WCAG 2.2 AA contrast test runs against each stage asset
**When** the assets are tested
**Then** all icon strokes maintain ≥3:1 contrast against `bg.surface` (room screen background); the keystone (stage 5) accent uses `key.default` oxblood with full label/icon a11y treatment via wrapped `<View accessibilityLabel="포인트 풀 5단계 — 완성">`.

**Given** the pool decreases attempt (forbidden by Story 4.1 / FR-8.4.5)
**When** any code attempts to render `<PoolStack total={negative_or_lower_than_current}>`
**Then** the component throws a dev-time warning (and in production fallback-renders the highest stage observed via `lastSeenStage` ref; positive-only invariant matches BE FR-8.4.5 guarantee).

**And** the 5 SVGs and the threshold table are checked in together with `<PoolStack>` (`FE/src/components/survival/PoolStack.tsx`) and a Storybook / visual-regression snapshot per stage. PRD ref: FR-8.4.1, FR-8.4.2, FR-8.4.3. Architecture ref: §4.6, §4.16. UX ref: O2 Pool 메타포 + L3 `<PoolStack>` 컴포넌트 + Surface Assignment Matrix (D2 Bento).

---

## Epic 5: Group Leader & Rule Versioning

**Goal:** Leader has authority but cannot break the contract members joined under. Implements next-month-only rule changes, member-cap edits, leader transfer, and auto-promotion on leader elimination.

**Sprint:** W3–W4.

### Story 5.1: Rule edit with next-month-only application

As a room leader,
I want to edit my room's rule (currently the daily-update preset + weekend-include toggle) and have the change apply only from the next calendar month,
So that the contract my members joined under is preserved through the current month.

**Acceptance Criteria:**

**Given** I am `rooms.owner_id` for room R and I open Room Settings
**When** I propose a new rule (e.g., toggle `weekendInclude` from true to false)
**Then** the FE shows "변경된 규칙은 다음 달 1일부터 적용됩니다." preview before confirmation

**Given** I confirm the change
**When** `PATCH /api/v1/rooms/{id}/rule` runs
**Then** a new `room_rule_versions` row is inserted with `effective_from_month = nextMonthKST` (`'YYYY-MM'` format), `rule_payload = jsonb_build_object(...)`, `created_by_user_id = me`. `unique (room_id, effective_from_month)` prevents duplicate edits per month

**Given** the daily evaluator runs during the current month
**When** it reads the rule effective for `current_month_kst`
**Then** it picks the row with the highest `effective_from_month <= current_month_kst` — last-month's rule is still in force (FR-8.5.3)

**Given** I am not the leader and call the endpoint
**When** the request reaches the controller
**Then** the response is `403 FORBIDDEN` with code `FORBIDDEN`

**And** the change emits a `RealtimeEvent` to the room's chat as a system message: "다음 달부터 새 규칙이 적용됩니다 [preview]" (Story 5.4). PRD ref: FR-8.5.2, FR-8.5.3, FR-8.5.8. Architecture ref: §4.* + V11 (8), §6.4.

### Story 5.2: Member-cap edit + leader transfer

As a room leader,
I want to update the room's member cap (within `[2, 30]`) on a next-month-only basis, and to transfer leadership to any active room member,
So that I can grow/shrink the room responsibly and pass the baton when needed.

**Acceptance Criteria:**

**Given** I am the leader and I call `PATCH /api/v1/rooms/{id}/members/cap` with `{ maxMembers: 20 }`
**When** the BE validates `2 ≤ maxMembers ≤ 30` and the value passes the V11 CHECK constraint
**Then** the change applies on the same next-month-only basis as rule edits (the new cap takes effect at the next month boundary; current month's roster is unaffected even if the new cap would be smaller than current member count)

**Given** I call `POST /api/v1/rooms/{id}/transfer-leadership { targetUserId: X_id }` and X is an active member of the room
**When** the BE service runs
**Then** `rooms.owner_id` is updated to `X_id` atomically and a `RealtimeEvent.LeadershipChange` is emitted

**Given** I attempt to transfer to a non-member or eliminated member
**When** the request reaches the service
**Then** the response is `400 VALIDATION` (non-member) or `409 CONFLICT` with code `INELIGIBLE_LEADER` (eliminated)

**And** all leader-only endpoints require `rooms.owner_id == authenticatedUserId` checked server-side. PRD ref: **FR-8.5.4**, FR-8.5.5, FR-8.5.6 (FR-8.5.4 citation added per readiness review 2026-05-11 — the "next-month-only member cap" requirement was already functionally covered by the first AC but missing from the citation line). Architecture ref: §4.* / §6.4.

### Story 5.3: Auto-leader-promotion on elimination

As the system,
I want to auto-promote the longest-tenured `ACTIVE` member to leader if the current leader transitions to `RED`,
So that no room is leaderless even when the leader misses two days.

**Acceptance Criteria:**

**Given** the current leader (rooms.owner_id = L) transitions to `RED`
**When** the post-commit event listener runs
**Then** the service queries `room_members` for the longest-tenured active member (`MIN(joined_at)` among `survival_state.status = 'ACTIVE'`) and updates `rooms.owner_id` to that user

**Given** no active members remain (entire room eliminated)
**When** the auto-promotion path runs
**Then** the leadership stays with the eliminated leader (room is dormant until any member revives) — no error

**Given** a leader transition occurs
**When** the transition completes
**Then** a `RealtimeEvent.LeadershipChange` is emitted to `/topic/rooms/{roomId}/survival` with `{ previousLeader, newLeader, reason: 'AUTO_ELIMINATION' }`

**Given** the previous leader later revives
**When** they re-enter
**Then** they do **not** automatically reclaim leadership; explicit transfer is required (preserves trust)

**And** all of the above happens atomically with the elimination transition. PRD ref: FR-8.5.7. Architecture ref: §4.*, project-context decision §6.3.

### Story 5.4: Rule-change broadcast in chat

As a room member,
I want a clear, non-shaming chat system message whenever the rule changes for the next month,
So that I know what the room agreed to before it takes effect.

**Acceptance Criteria:**

**Given** a leader successfully edits a rule (Story 5.1)
**When** the change commits
**Then** a `chat_messages` row is inserted with `kind='SYSTEM'`, `payload = { ruleVersionId, effectiveFromMonth, preview }`, `body = '다음 달부터 새 규칙이 적용됩니다: …'`

**Given** the chat list loads
**When** a member opens the room chat
**Then** the system message renders with a distinct visual treatment (existing SystemMessage component)

**Given** the rule change is reverted before the next month
**When** the leader edits again with the same `effective_from_month`
**Then** the existing `room_rule_versions` row is replaced (UNIQUE on `(room_id, effective_from_month)`) and a new system message is sent

**And** the system message body uses brand-voice lexicon (no "벌금/실패"). PRD ref: FR-8.5.8.

---

## Epic 6: KakaoTalk SDK Invite Virality

**Goal:** Friction-free room invitation via Kakao Share SDK + server-rendered preview card. Native module addition triggers RUNBOOK update.

**Sprint:** W5.

### Story 6.1: Server-side preview card renderer + cache

As the system,
I want a renderable PNG preview card for any room's invite, served from a fast cache and invalidated on rule/member-count change,
So that the Kakao share preview is consistent, fast, and always reflects the current room state.

**Acceptance Criteria:**

**Given** a `POST /api/v1/rooms/{id}/invites` request
**When** the BE generates the share payload
**Then** the response includes `inviteCode`, `kakaoShareUrl`, and `previewCardImageUrl` pointing at a stable PNG endpoint

**Given** the preview card hasn't been rendered yet for the current room state
**When** the renderer runs
**Then** a server-side SVG → PNG conversion uses yeolsal v2 design tokens via the FE→BE codegen pipeline (Architecture §4.16 — `GeneratedTokens` constants only, no hex literals) with sub-mode `D1 Editorial` overrides applied, the result is stored in `room_invite_preview_cache (room_id, png_url, rendered_at, rule_version_id, member_count_at_render)`, served via nginx with a TTL of 1 hour

**Given** the room rule changes (`room_rule_versions` insert) or `room_members` count changes
**When** the cache invalidation listener runs
**Then** the existing cache row is purged and the next request re-renders

**Given** a cache miss while rendering
**When** another request arrives mid-render
**Then** nginx serves the existing PNG (avoiding cache stampede); the in-flight render completes

**And** the renderer p95 latency < 1s for cold render. PRD ref: FR-8.6.1, FR-8.6.2. Architecture ref: §4.10, §6.3 V11 (11).

### Story 6.2: Kakao Share SDK integration + deep-linking

As a primary user,
I want a 1-tap "Share to KakaoTalk" CTA on the room invite sheet that uses the Kakao Share SDK to post a preview card with my room's invite code, and 1-tap join from any tap on the shared card,
So that my friends can join in two taps directly from KakaoTalk.

**Acceptance Criteria:**

**Given** I tap "Share to KakaoTalk" inside the existing `RoomInviteSheet`
**When** the FE invokes `kakaoShare.sendCustomFeed(...)` (extends existing Kakao OAuth dependency)
**Then** the Kakao Share SDK opens KakaoTalk with the `previewCardImageUrl` and the invite-code-bearing deep link

**Given** a friend taps the shared card on a device with the app installed
**When** the deep link resolves
**Then** the FE deep-links to the room preview screen and offers a 1-tap join (subject to `max_members` capacity, returning `409 CONFLICT` with code `ROOM_FULL` if at cap)

**Given** a friend taps the shared card without the app installed
**When** the deep link resolves to the App Store / Google Play
**Then** the invite-code is preserved via **platform-native deep-link query parameters** (decision locked 2026-05-11: iOS Universal Links + Android App Links; **no Branch.io** — avoids paid SaaS dependency and extra native-module rebuild cycle), and post-install signup carries `inviteCode` to `/api/v1/auth/signup`

**Given** the Kakao Share SDK fails (network error, SDK version mismatch)
**When** the share attempt errors
**Then** the FE falls back to plain `Share.share()` with a copyable invite-code text (always-works backup)

**And** all share text uses brand-voice copy ("같이 살아남자" tone, not "도전" or "챌린지"). PRD ref: FR-8.6.3, FR-8.6.4, FR-8.6.5. Architecture ref: §3.3, §4.10.

### Story 6.3: RUNBOOK + native module reinstall guidance

As a developer,
I want clear documentation that adding the Kakao Share SDK is a native module addition requiring `adb uninstall app.yeosal.mobile` + clean rebuild, plus updated RUNBOOK steps,
So that my dev cycle doesn't silently fail with "the SDK call is undefined" errors.

**Acceptance Criteria:**

**Given** the Kakao Share SDK is added to `FE/package.json`
**When** a dev runs `npm run android` against a stale Metro cache
**Then** RUNBOOK.md (existing repo doc) has a section explicitly noting the rebuild requirement

**Given** the dev follows RUNBOOK
**When** they run `adb uninstall app.yeosal.mobile && npm run android`
**Then** the new native module is included in the binary and the SDK calls resolve

**Given** an EAS build runs for `preview` profile
**When** the build completes
**Then** the resulting APK includes the Kakao Share SDK and the share flow works on a real device

**And** CI integration (if any) is documented to require a native rebuild step. PRD ref: FR-8.6.6. Architecture ref: §3.3, §5.2 (project-context.md rule).

---

## Epic 7: Final-3 Monthly Ceremony

**Goal:** Server-rendered Editorial-aesthetic SVG poster (yeolsal v2 — Oxblood Editorial, sub-mode `D1`) generated at month-end via the FE→BE token codegen pipeline (Architecture §4.16) + Home-tab share UI.

**Sprint:** W6.

### Story 7.1: Server-side SVG poster renderer

As the system,
I want a Java string-templated SVG renderer that produces an Editorial-aesthetic poster card (yeolsal v2 — Oxblood Editorial) for a room's monthly Final-3,
So that the brand visual is consistent across all rooms and shareable on KakaoTalk.

**Acceptance Criteria:**

**Given** a room has ≥1 member completing the prior month with `survival_state.status = 'ACTIVE'`
**When** the `SvgRenderer.render(roomId, yearMonth)` method is called
**Then** the output is a valid SVG `<svg>` document using **`GeneratedTokens` constants** (Architecture §4.16 codegen — NO hex literals in renderer code) with: room name at top, all surviving member nicknames listed, top-3 by tenure highlighted with `tokens.subMode.editorial.*` overrides applied (D1 Editorial sub-mode token override set per UX `<FinalThreeCard>` working name)

**Given** the FE updates `tokens.json` (e.g., key color tweak from oxblood-deep to oxblood-bright)
**When** BE rebuilds (`./gradlew generateTokens build`)
**Then** `SvgRenderer` outputs reflect the new color values **without any code change in `SvgRenderer.java`** — verified by an integration test that diffs SVG output before/after a token-only change (Story 1.5 + Architecture §4.16)

**Given** any new BE renderer (e.g., `InvitePreviewRenderer` for FR-8.6.2)
**When** it references token values
**Then** it MUST go through `GeneratedTokens` — direct hex literals are blocked by a Checkstyle / ArchUnit rule (Architecture §4.15 enforcement extension)

**Given** the room has 30 members and 25 survived
**When** the renderer runs
**Then** the layout dynamically scales to fit 25 nicknames; the top-3 highlight remains visually clear; secondary "25명 생존" stat is shown (PRD FR-8.7.5)

**Given** a poster is requested for a month with no survivors
**When** the renderer evaluates
**Then** no poster is generated; `final_three_posters` row is not inserted; the room shows a soft message in chat "이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요"

**Given** the SVG is rendered
**When** PNG fallback is requested (for Kakao card thumbnail)
**Then** Apache Batik `org.apache.xmlgraphics:batik-transcoder` rasterizes the SVG; the resulting PNG is cached at `final_three_posters.png_url`; the rasterizer also reads colors from `GeneratedTokens` (no separate color path)

**And** the renderer's p99 latency < 3s per poster (NFR-9.1.4). PRD ref: FR-8.7.2, FR-8.7.3, FR-8.7.5. Architecture ref: §4.9, §4.15, §4.16, §6.3 V11 (10).

### Story 7.2: Monthly Final-3 scheduled job

As the system,
I want a scheduled job that runs at 06:30 KST on the first day of each month and generates posters for every eligible room,
So that the Home tab card is ready to be displayed on the morning of month transition.

**Acceptance Criteria:**

**Given** the clock reaches 06:30 KST on the 1st day of a calendar month
**When** `FinalThreeJob.@Scheduled` triggers
**Then** for every room with ≥1 active surviving member from the prior month, an immutable `final_three_posters` row is inserted with `(room_id, year_month, svg_text, png_url, generated_at)`

**Given** the job runs twice for the same `year_month` (retry / replay)
**When** the second run encounters an existing `final_three_posters` row (PK `(room_id, year_month)`)
**Then** that room is skipped — posters are immutable (PRD FR-8.7.6)

**Given** the entire batch must complete within 10 minutes for up to 5,000 active rooms (NFR-9.1.4)
**When** the job runs
**Then** it parallelizes across rooms with a thread pool sized to fit the 10-min budget

**And** the job emits `RealtimeEvent.MonthlyPosterReady` per room so members get a Home tab refresh signal. PRD ref: FR-8.7.1, FR-8.7.6. Architecture ref: §4.9, §6.3 V11 (10).

### Story 7.3: Home tab Final-3 card with Kakao share

As a surviving member of a room,
I want a Home tab card displaying my room's monthly Final-3 poster + a Share-to-KakaoTalk CTA,
So that I can share the win with people outside the app.

**Acceptance Criteria:**

**Given** I am a surviving member and my room has a fresh `final_three_posters` row for the just-ended month
**When** I open the Home tab
**Then** a `FinalThreeCard` component shows the SVG inline + member names + my room name

**Given** I tap "Share to KakaoTalk"
**When** the FE calls the Kakao Share SDK via `src/lib/kakaoShare.ts`
**Then** the share payload includes the PNG poster + my room's invite-code (so external readers can join the room directly from the shared card)

**Given** I am not a surviving member of the room (eliminated, or never reached `ACTIVE` at month-end)
**When** I open the Home tab
**Then** the card is **not shown** (eliminated members do not get the marketing asset; preserves dignity)

**And** the share button uses brand-voice copy: "이번 달, 우리 살아남았어 🎉 함께 마실 커피 만들어가는 중" (or vetted equivalent). PRD ref: FR-8.7.4.

---

## Epic 8: Brand Voice & Onboarding

**Goal:** Defuse the 챌린저스 mental model in the first session. Make survival feel like dignity, not penalty. Includes the 5-screen onboarding, the brand-voice lint helper, ASO copy lock, and the release-gate checklist.

**Sprint:** W7.

### Story 8.1: 5-screen onboarding flow

As a new user from a Kakao share link or organic install,
I want a 5-screen onboarding that explains the loop, the no-money v1 stance, and the 14-day grace, all in brand voice,
So that I land on Day 0 with the right mental model — not the 챌린저스 deposit-refund expectation.

**Acceptance Criteria:**

**Given** a new user lands post-signup
**When** the onboarding flow starts
**Then** screens 1–5 (per PRD FR-8.8.1) render in order:
  1. "열살방은 친구와 함께 살아남는 방입니다." (concept)
  2. "매일 약속을 지키면 살아남습니다. 빠지면 친구가 살릴 수 있어요." (mechanic)
  3. "v1에서는 돈을 받지 않습니다 — 살아남는 것 자체가 자산입니다." (defuses 챌린저스)
  4. "친구를 살리는 건 옵션이지 의무가 아닙니다." (defuses social pressure)
  5. Wallet preview (free revival ticket visible) + Room preview + 14-day grace banner

**Given** a user joins via a deep-linked Kakao share link
**When** the onboarding runs
**Then** the room name + rule preview are pre-populated on screen 5 (smoother first-room experience)

**Given** a user already exists (returning user post-cutover)
**When** they sign in
**Then** the 5-screen onboarding does **not** repeat; a single change-summary screen ("yeolsal이 열살방으로 바뀌었어요") shows once

**And** copy is brand-voice-compliant per Story 8.4 release gate. PRD ref: FR-8.8.1.

### Story 8.2: Brand-voice copy pass + lint helper

As an engineer,
I want a `tools/brand-voice-lint.ts` script that scans all FE/BE user-facing strings against the AVOID lexicon (벌금/잃었다/실패 etc.) and prints occurrences,
So that brand-voice drift is caught early without blocking releases on false positives.

**Acceptance Criteria:**

**Given** the script `tools/brand-voice-lint.ts` exists at the repo root
**When** I run `npx tsx tools/brand-voice-lint.ts`
**Then** it scans `FE/src/**/*.{ts,tsx}`, `FE/app/**/*.tsx`, and `BE/src/main/resources/messages*.properties` (if present)

**Given** a string contains an AVOID-lexicon term ("벌금", "잃었다", "떨어졌다", "실패", "자책", "부담", "패배", "죄책감")
**When** the script runs
**Then** it prints the file path, line number, and matched term

**Given** a CI workflow runs the script
**When** matches are found
**Then** the workflow **warns** (not fails) — human review remains authoritative (Architecture §4.15)

**Given** all FE/BE user-facing copy has been authored / reviewed
**When** the script runs against the v1 codebase
**Then** zero AVOID-lexicon matches exist in user-facing strings (excluding code comments and non-user-facing logs)

**And** the script ignores `*.test.{ts,tsx}` files. PRD ref: FR-8.8.2, FR-8.8.3, FR-8.8.5. Architecture ref: §4.15, §5.5.

### Story 8.3: ASO copy lock for App Store + Google Play KR

As the PM,
I want a finalized App Store + Google Play KR storefront copy where Korean uses "회생권" but English avoids "revival ticket" / "second chance pass" in favor of "comeback pass",
So that automated content scans don't surface gambling adjacency.

**Acceptance Criteria:**

**Given** I prepare the storefront metadata
**When** I review against the ASO copy rules (PRD FR-8.8.4, Architecture §5.5)
**Then** the **Korean** name + description uses "회생권" naturally
**And** the **English** name + description uses "comeback pass" — no "revival ticket" / "second chance pass"

**Given** the metadata is submitted
**When** the App Store and Google Play KR storefront review runs
**Then** the app ships in the standard category (no gambling / NC-17 escalation; PRD KPI: app-store policy review passes on first submission)

**Given** any future copy update
**When** it touches ASO surfaces
**Then** it goes through the same brand-voice + ASO rule check

**And** screenshots accompanying the copy use yeolsal v2 design tokens (Oxblood Editorial — Architecture §4.16). PRD ref: FR-8.8.4. Architecture ref: §5.5, §4.16.

### Story 8.4: Release-gate brand-voice review

As the PM and lead designer,
I want a documented release-gate checklist for brand-voice review that runs before every major release,
So that brand integrity is preserved as new copy is written for new features.

**Acceptance Criteria:**

**Given** a release candidate is built
**When** the release-gate runs
**Then** the brand-voice review checklist (in `docs/brand-voice-review.md`, deliverable in W7) runs and is signed off by **PM + designer** jointly. **Sign-off mechanism (locked 2026-05-11):** the checklist lives as a Markdown file in the repo; each release's instance is duplicated as `docs/releases/brand-voice-review-<version>.md` and signed via a **GitHub PR review approval** from both the PM-designated reviewer (GH handle in `CODEOWNERS`) and the designer-designated reviewer. CI requires both approvals before allowing the release-tag push (enforced by `CODEOWNERS` + branch-protection rule on `release/*` branches).

**Given** the checklist includes:
  - All push notification copy paths in BE / FE source
  - All onboarding screens
  - All error message strings
  - All store metadata (KR + EN)
  - System message templates in `chat_messages`
**When** any item is unsigned
**Then** the release is held — concretely, the `release/v*` branch's `CODEOWNERS`-required reviews block the merge, so no production deploy can run without joint sign-off (no override; only re-run the checklist after fixing the flagged item).

**Given** the brand-voice lint helper (Story 8.2) flags an item
**When** the human reviewer decides
**Then** the decision (accept / reject / needs-rewrite) is recorded inline in the checklist Markdown file with the reviewer's handle and the date. **Needs-rewrite SLA (locked 2026-05-11):** flagged items must be addressed within 1 business day; if the next release window is < 24h away, the affected feature is feature-flagged off rather than ship with unresolved brand-voice flag.

**And** the checklist is versioned in the repo and updated as new surfaces are added in future releases. PRD ref: FR-8.8.6. Readiness ref: MIN-3 (sign-off mechanism + SLA tightening).

### Story 8.5: Analytics SDK selection + event taxonomy

> Added per readiness review 2026-05-11 (M3). Closes the UX W1 Spec Lock #4 gap — without this story, the PRD §3.1 KPIs, §13 phase-2 trigger gates, and §2.3 #5 v2 visual-falsification trigger are not measurable. Sentry remains BE-error only.
>
> **Sprint scheduling note:** runs in **W1**, not W7 — every downstream story must emit events through the chosen SDK, so it has to land before Story 1.1 implementation starts.

As a PM and v1 release-team member,
I want a product analytics SDK selected, integrated into the FE app, and a canonical event taxonomy committed,
So that activation / retention / friend-gift conversion / spectator→revival cohort / Day-30 Final-3 share-rate KPIs are measurable from launch day forward.

**Acceptance Criteria:**

**Given** the W1 kickoff (or earlier)
**When** the PM + tech lead evaluate analytics SDK options
**Then** a single SDK is committed and documented in `docs/analytics.md` with: chosen SDK name, version, EXPO_PUBLIC env-var name(s), data-residency note (KR users → KR or AP region), privacy-policy alignment (PIPA-compatible), and rationale (no Sentry replacement; complement only).

**Given** the SDK is integrated
**When** an engineer reads `docs/analytics.md`
**Then** the canonical event taxonomy is documented as a typed table covering five funnels:
  1. **Activation funnel**: `signup.completed`, `onboarding.screen.dwell_ms` (per screen 1–5), `onboarding.completed`, `first_daily_entry`, `activation.24h_complete` (FR-8.8.1; PRD §3.1 activation ≥60%/24h).
  2. **Revival flows**: `revival.attempted` × `{FREE_TICKET|PERSONAL_POINTS|FRIEND_GIFT|KUDOS}`, `revival.succeeded`, `revival.failed.{INSUFFICIENT_POINTS|ALREADY_REVIVED|FORBIDDEN|NETWORK}`, `kudos.sent`, `kudos.received` (FR-8.3.*).
  3. **Friend-gift conversion funnel**: `friend_gift.push_sent` (BE event), `friend_gift.push_opened`, `friend_gift.modal_opened` × `{PUSH_INITIATED|WALLET_INITIATED}`, `friend_gift.modal_closed.{revival_sent|kudos_sent|cancelled}` (FR-8.3.3, FR-8.3.4; PRD §3.1 friend-gift ≥1·room/월).
  4. **Spectator → revival cohort**: `spectator.entered` (BE event), `spectator.app_opened`, `spectator.wallet_viewed`, `spectator.revival_succeeded.day_n` for n ∈ [1..30] (PRD §13.1 spectator-FOMO falsification — < 15% at Day 30 kills the hypothesis).
  5. **Final-3 share-rate (v2 visual falsification trigger)**: `final_three.poster_viewed`, `final_three.share_tapped`, `final_three.share_completed` (PRD §2.3 #5; trigger threshold: Day-30 share-rate < 15% of surviving members → SCP).

**Given** any user-property assignment
**When** the SDK initializes for an authenticated user
**Then** a small, fixed set of user properties is set: `user_id` (BE primary key), `account_age_days`, `room_count` (always 1 for v1 mandatory single room), `current_survival_state` (ACTIVE/YELLOW/RED/SPECTATOR), `is_room_leader` boolean. No PII, no email, no name. KR-only locale assumed (NFR-9.7.1).

**Given** the user denies analytics consent (PIPA-compliant opt-in surface on onboarding screen 5)
**When** the SDK initializes
**Then** the SDK is initialized in `disabled` / `opt-out` mode (depending on SDK capability); no events are emitted; the user can revoke at any time from Settings → Privacy → "사용 통계 공유" toggle (defaults to **opt-in** for KR convention but with clear explanation; alternatively defaults to opt-out — PM decides at W1 lock; tested for both paths).

**Given** the BE emits server-side events (e.g., `friend_gift.push_sent`, `spectator.entered`)
**When** the BE service emits via `RealtimePublisher` or `notification_log`
**Then** the same event is *also* sent to the analytics SDK via a server-side capture path (or, if SDK lacks server SDK, via a typed `POST /api/v1/internal/analytics-events` endpoint that the FE doesn't see). Avoids relying on client-only events for retention-critical metrics.

**Given** the CI workflow runs
**When** a new event name is introduced in code without being added to `docs/analytics.md` taxonomy
**Then** a lint rule (or script `tools/analytics-taxonomy-lint.ts`) **warns** (not fails) in CI, listing the rogue event names. Keeps the taxonomy as the source of truth without blocking developer velocity for one-off debugging events.

**And** Story 8.5 is **completed in W1**, before Story 1.1 / 1.2 / 1.6 / 1.7 implementation begins, so that downstream stories can emit events from day one. PRD ref: §3.1 KPIs, §13.1 phase-2 trigger gates, §2.3 #5 falsification trigger, NFR-9.4.1–4. Architecture ref: §3 Sentry note, §5.1 BE patterns. Readiness ref: M3.

---

## Validation Summary

### Coverage check

- [x] All 50+ PRD FR-IDs (FR-8.1.* through FR-8.8.*) are covered by at least one story.
- [x] All cross-cutting NFRs (NFR-9.1 through NFR-9.8) are referenced in story-level ACs or by `bash scripts/verify.sh` gate.
- [x] All 15 Architecture decisions (§4.1 through §4.15) appear in story ACs or implementation notes.
- [x] V11 migration is owned by a single dedicated story (Story 1.4).
- [x] Brand-voice gate is its own epic (Epic 8) with manual sign-off + automated helper.
- [x] All "MAYBE in v1" items from PRD §13.4 are either resolved or explicitly tracked as Day-30 telemetry items.
- [x] No story introduces payment surface, multi-room support, or any banned-list mechanic.

### Story count

- **8 epics, 33 stories** total. (Updated 2026-05-11 after Sprint Change Proposal integration + readiness review story expansions.)
- Per-epic: Epic 1 (Survival State + Foundation): **7** (1.1–1.7). Epic 2 (Spectator): **3**. Epic 3 (Revival Economy): **5** (3.1–3.5). Epic 4 (Pool): **3** (4.1–4.3 incl. 5-stage SVG asset story). Epic 5 (Leader): **4**. Epic 6 (Kakao SDK): **3**. Epic 7 (Ceremony): **3**. Epic 8 (Brand Voice + Analytics): **5** (8.1–8.5 incl. Analytics SDK).
- 7+3+5+3+4+3+3+5 = 33 named stories.

### V11 migration scope (deviation-by-design note, 2026-05-11)

Story 1.4 intentionally batches all v1 schema deltas (12 steps) into a single Flyway migration. This **deviates** from the BMad greenfield convention of "each story creates the tables it needs" but is **required** for brownfield Postgres + Flyway projects per `_bmad-output/project-context.md` migration conventions (V8/V9 reference pattern). Per-story migration splitting (V11/V12/V13/…) would create ordering complexity and risk partial-deploy states. Accepted by readiness review 2026-05-11.

### FR Coverage Map (refreshed 2026-05-11)

| FR-ID | Epic | Story |
|-------|------|-------|
| FR-8.1.1, .2, .4 | Epic 1 | Story 1.1 (room create + grace), Story 1.6 (WelcomeWindow J0) |
| FR-8.1.3, .5, .7 | Epic 1 | Story 1.2 (evaluator job) |
| FR-8.1.6 | Epic 1 | Story 1.3 (privacy-filtered survival API) |
| FR-8.2.1, .2, .5, .6 | Epic 2 | Story 2.1 (spectator routing + reads) |
| FR-8.2.3 | Epic 2 | Story 2.2 (spectator daily digest) |
| FR-8.2.4 | Epic 2 | Story 2.3 (record visibility prefs) |
| FR-8.3.1, .2, .8 | Epic 3 | Story 3.1 (free ticket + self-revival) |
| FR-8.3.3, .4, .5, .7 | Epic 3 | Story 3.2 (Friend Gift Modal) |
| FR-8.3.6 | Epic 3 | Story 3.3 (Wallet badge), Story 3.4 (Wallet UI) |
| FR-8.3.9 | Epic 3 | Story 3.5 (Kudos endpoint + V11 enum extension), Story 3.2 (FriendGiftModal 2nd CTA) |
| FR-8.4.1, .2, .3, .5 | Epic 4 | Story 4.1 (pool counter) |
| FR-8.4.4 | Epic 4 | Story 4.2 (phase-2 promise UI) |
| FR-8.4 visual asset | Epic 4 | Story 4.3 (Pool 5-stage SVG/PNG asset pipeline) |
| FR-8.5.1, .2, .3, .4, .5, .6 | Epic 5 | Stories 5.1, 5.2 (rule versioning + member cap + transfer + creator-becomes-leader) |
| FR-8.5.7 | Epic 5 | Story 5.3 (auto leader promotion) |
| FR-8.5.8 | Epic 5 | Story 5.4 (rule-change broadcast message) |
| FR-8.6.1, .2 | Epic 6 | Story 6.1 (preview card service) |
| FR-8.6.3, .4, .5 | Epic 6 | Story 6.2 (Kakao Share SDK + platform deep-linking) |
| FR-8.6.6 | Epic 6 | Story 6.3 (RUNBOOK update) |
| FR-8.7.1, .2, .3, .5, .6 | Epic 7 | Stories 7.1, 7.2 (renderer + scheduled job) |
| FR-8.7.4 | Epic 7 | Story 7.3 (Home tab share) |
| FR-8.8.1 | Epic 8 | Story 8.1 (5-screen onboarding) |
| FR-8.8.2, .3, .5 | Epic 8 | Story 8.2 (brand-voice copy pass + lint) |
| FR-8.8.4 | Epic 8 | Story 8.3 (ASO copy lock) |
| FR-8.8.6 | Epic 8 | Story 8.4 (release-gate checklist) |
| §14.2 visual identity / NFR-9.6.1 / NFR-9.6.3 | Epic 1 | Story 1.5 (Design System Foundation v2 — token packed type + codegen + `<SurvivalChip>` + `<SubModeProvider>`) |
| PRD §4.3 J0 | Epic 1 | Story 1.6 (WelcomeWindow) |
| PRD §6.4 principle 5 | Epic 1 | Story 1.7 (RitualMoment) |
| NFR-9.4 / §3.1 KPI measurability / §2.3 #5 falsification trigger | Epic 8 | Story 8.5 (Analytics SDK selection + event taxonomy) |
| NFR-9.5 (migration + backfill) | Epic 1 | Story 1.4 (V11 + backfill) |
| NFR-9.4 (telemetry instrumentation) | Cross-epic | Woven into Story 1.2 (Sentry), Stories 3.1/3.2/3.5 (revival events), Story 8.5 (analytics taxonomy) |

### Sprint alignment (updated 2026-05-11)

- **W1–W2**: Epic 1 (Stories **1.4** → 1.1, 1.2, 1.3, 1.5, 1.6, 1.7). Execution order: 1.4 (migration first) → 1.5 (token codegen + lint + `<SurvivalChip>` + `<SubModeProvider>`) → 1.1, 1.2, 1.3 (survival state) → 1.6 (WelcomeWindow with Kakao CTA in `disabled` placeholder per Option B), 1.7 (RitualMoment). Also: **Story 8.5 (Analytics SDK selection)** must complete in W1 to instrument all downstream stories.
- **W2–W3**: Epic 2 (Stories 2.1, 2.2, 2.3) + start Epic 5.
- **W3–W4**: Epic 3 + Epic 5 + Epic 4. **Execution order within Epic 3 (locked 2026-05-11):** Story 3.1 → **Story 3.5** (Kudos endpoint + V11 `kind='KUDOS'` migration) → **Story 3.2** (FriendGiftModal consumes 3.5 kudos endpoint) → 3.3 → 3.4. Epic 5: 5.1 → 5.2 → 5.3 → 5.4. Epic 4: 4.1 → 4.2 → 4.3 (5-stage SVG assets — design owner commission + threshold lock).
- **W5**: Epic 6 (Stories 6.1, 6.2, 6.3). **Story 1.6's Kakao CTA gets wired live** as part of Story 6.2 acceptance — disabled placeholder is removed when 6.2 ships.
- **W6**: Epic 7 (Stories 7.1, 7.2, 7.3).
- **W7**: Epic 8 (Stories 8.1, 8.2, 8.3, 8.4). (Story 8.5 already completed in W1.)
- **W8**: Bug fix, telemetry instrumentation, App Store + Google Play submission, internal QA, production deploy.

### Recommended next BMad steps

1. **`/bmad-check-implementation-readiness`** — readiness gate confirming PRD ↔ Architecture ↔ Stories alignment and surfacing any inconsistencies before sprint planning.
2. **`/bmad-sprint-planning`** — generate sprint status from this epics breakdown; align Story 1.1 onwards to W1.
3. **`/bmad-create-story`** — once sprint planning kicks off, individual stories receive their dedicated story files for the dev-story cycle.
4. **`/bmad-validate-prd`** *(optional)* — independent PRD validation against project standards.

Each subsequent skill should be run in a **new context window**.
