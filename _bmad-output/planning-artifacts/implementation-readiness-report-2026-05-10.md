---
date: 2026-05-10
project: yeolsal
status: in-progress
stepsCompleted:
  - step-01-document-discovery
  - step-02-prd-analysis
  - step-03-epic-coverage-validation
  - step-04-ux-alignment
  - step-05-epic-quality-review
  - step-06-final-assessment
status: complete
verdict: NEEDS WORK
filesIncluded:
  prd: _bmad-output/planning-artifacts/prd.md
  architecture: _bmad-output/planning-artifacts/architecture.md
  epics: _bmad-output/planning-artifacts/epics.md
  ux: _bmad-output/planning-artifacts/ux-design-specification.md
  uxDirections: _bmad-output/planning-artifacts/ux-design-directions.html
supportingArtifacts:
  - _bmad-output/planning-artifacts/product-brief-yeolsal.md
  - _bmad-output/planning-artifacts/product-brief-yeolsal-distillate.md
  - _bmad-output/planning-artifacts/prfaq-yeolsal.md
  - _bmad-output/planning-artifacts/prfaq-yeolsal-distillate.md
  - _bmad-output/planning-artifacts/research/market-todo-survival-habit-app-market-research-2026-05-09.md
---

# Implementation Readiness Assessment Report

**Date:** 2026-05-10
**Project:** yeolsal

## Step 1 — Document Discovery

### PRD
- Whole: `_bmad-output/planning-artifacts/prd.md` (49 KB, 2026-05-10 12:04)
- Sharded: none

### Architecture
- Whole: `_bmad-output/planning-artifacts/architecture.md` (53 KB, 2026-05-10 12:22)
- Sharded: none

### Epics & Stories
- Whole: `_bmad-output/planning-artifacts/epics.md` (47 KB, 2026-05-10 12:34)
- Sharded: none
- Note: No standalone story files; stories assumed embedded in `epics.md` (to be validated in Step 5).

### UX Design
- Whole spec: `_bmad-output/planning-artifacts/ux-design-specification.md` (87 KB, 2026-05-10 15:06)
- Design directions explorer: `_bmad-output/planning-artifacts/ux-design-directions.html` (28 KB, 2026-05-10 14:40) — informational HTML
- Sharded: none

### Supporting (out of readiness scope, retained for context)
- `product-brief-yeolsal.md` + `product-brief-yeolsal-distillate.md`
- `prfaq-yeolsal.md` + `prfaq-yeolsal-distillate.md`
- `research/market-todo-survival-habit-app-market-research-2026-05-09.md`

### Issues Detected
- No whole-vs-sharded duplicate conflicts.
- All four required document types (PRD, Architecture, Epics, UX) are present.
- No standalone stories file — to be confirmed inline within `epics.md` in Step 5.
- Prior implementation-readiness report at this same path was overwritten by user direction (option A).

### User Confirmation
- User confirmed file selection and approved overwrite of prior report on 2026-05-10.

## Step 2 — PRD Analysis

Source: `_bmad-output/planning-artifacts/prd.md` (read in full, 634 lines).

### Functional Requirements

#### Epic 8.1 — Survival State & Daily Loop (7 FRs)

- **FR-8.1.1** — On signup, user joins or creates one mandatory primary room with `max_members ∈ [2, 30]` (default 12 on creation). The 14-day grace trial begins from `room_members.joined_at`.
- **FR-8.1.2** — Each user has exactly one `survival_state` row per room they belong to. Initial status = `ACTIVE`.
- **FR-8.1.3** — At each 06:00 KST day boundary, a scheduled job evaluates each member's compliance with their room's current rule (per `room_rule_versions` row effective for the current month). If the rule was met for the prior day → `personal_points_ledger += 2 (SURVIVAL)`. If not met: if `streak_freezes` for `(user_id, month)` does not yet exist → create row, no state change. Otherwise: within 7-day rolling window: if no prior YELLOW → status = `YELLOW`. If prior YELLOW exists → status = `RED`, `eliminated_at = now()`, `broad_visibility_at = now() + 24h`.
- **FR-8.1.4** — During grace trial (first 14 days from `joined_at`), state machine runs but does not progress past `YELLOW` — no `RED` until grace ends.
- **FR-8.1.5** — All state transitions emit a `RealtimeEvent.SurvivalStateChange` to `/topic/rooms/{roomId}/survival` via `RealtimePublisher`. Topic schema: `{ userId, fromStatus, toStatus, occurredAt, broadVisibilityAt | null }`.
- **FR-8.1.6** — REST endpoint `GET /api/v1/rooms/{id}/survival` returns the room's survival roster with privacy filtering: members in `RED` status with `broad_visibility_at > now()` show only as `ACTIVE` to non-leader members.
- **FR-8.1.7** — All survival-state changes are logged to `notification_log` with `kind = 'SURVIVAL_STATE'`, `key = '{date}:{userId}'` for idempotency.

#### Epic 8.2 — Spectator Mode (6 FRs)

- **FR-8.2.1** — When `survival_state.status` transitions to `RED`, FE routing (`app/(tabs)/_layout.tsx`) branches the user into spectator mode for that room.
- **FR-8.2.2** — In spectator mode: chat is read-only (FE input disabled; `POST /api/v1/rooms/{id}/messages` returns `403 FORBIDDEN` for `SPECTATOR` users); roster visible; grass calendars of other members visible at the room's existing privacy level.
- **FR-8.2.3** — Push notifications for spectators: digest at 09:00 KST daily (if any room activity occurred), summarizing posts/reactions. Realtime push for individual messages is disabled.
- **FR-8.2.4** — Eliminated user's own archive (`daily_entries`, `reflections`, `todo_items` for that room) defaults to private; opt-in toggle in `record_visibility_prefs` per room.
- **FR-8.2.5** — Spectator users see their own Wallet (revival ticket + personal points balance) prominently on the Home tab.
- **FR-8.2.6** — When spectator user's `survival_state.status` transitions to `ACTIVE` (via revival), they immediately re-enter active member mode.

#### Epic 8.3 — Revival Economy (Free Ticket + Personal Points + Friend Gift) (8 FRs)

- **FR-8.3.1** — Each user account is granted exactly one free revival ticket at signup (revival_events not yet created; ticket implied by lifetime flag on `users` or new `revival_grants` table — implementation detail in architecture stage). Usable immediately. Lifetime 1.
- **FR-8.3.2** — `POST /api/v1/rooms/{id}/revival` body `{ source: FREE_TICKET | PERSONAL_POINTS }` performs self-revival: validates eligibility (user is `RED` or `SPECTATOR`, source available), creates `revival_events`, deducts cost (free for ticket, 3 points for personal points), increments `room_point_pool` by 5 (ticket) or 3 (points), transitions `survival_state.status = ACTIVE`, emits `/topic/rooms/{id}/survival` (revival) and `/topic/rooms/{id}/points` (pool delta).
- **FR-8.3.3** — `POST /api/v1/rooms/{id}/revivals/gifts` body `{ targetUserId }`: giver spends 5 personal points to revive `targetUserId` who must be `RED` or `SPECTATOR` in the same room and an existing friend. Creates `revival_events` with `source = FRIEND_GIFT`, `giver_user_id` set; deducts 5 from giver's `personal_points_ledger`; increments `room_point_pool` by 5; transitions receiver `status = ACTIVE`; emits topics.
- **FR-8.3.4** — Friend-gift trigger conditions surface a push to all eligible friend givers (room members with sufficient personal points and active `friendships` to the eliminated user). Push payload follows the brand-voice rule: invitation tone, never demand. One push only; no follow-up reminders.
- **FR-8.3.5** — Friend-gift receiver gets a separate push confirming the donor name. Donor name is default visible to receiver only; donor may opt in to broadcast a system message in chat (`chat_messages` row with `kind = 'SYSTEM'`, `payload = { revival_event_id, donor_id }`).
- **FR-8.3.6** — Wallet UI (in spectator and active mode alike): shows free revival ticket presence, personal points balance, room point pool, and "받은 회생권" history (private to user).
- **FR-8.3.7** — Revival rejection / non-action by friends is never visible to anyone but the giver. There is no in-app surface that exposes who chose not to revive.
- **FR-8.3.8** — `personal_points_ledger` is append-only; balance is computed as `SUM(delta)` per `(user_id, room_id)`. On leaving a room, balance is forfeit.

#### Epic 8.4 — Group Point Pool & Future Redemption Promise (5 FRs)

- **FR-8.4.1** — Each room has exactly one `room_point_pool` row, initialized at 0 on room creation. Updated by `RevivalService` only.
- **FR-8.4.2** — Room UI shows the current pool prominently (Risograph styling per design system). Pool is visible to all room members including spectators and former eliminated users still in the room.
- **FR-8.4.3** — Pool growth events emit `/topic/rooms/{id}/points` with `{ delta, newTotal, sourceRevivalEventId, occurredAt }`.
- **FR-8.4.4** — v1 explicitly does not offer redemption. UI shows phase-2 promise copy: "다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다."
- **FR-8.4.5** — When phase-2 ships, the existing pool integer in `room_point_pool` is the redeemable balance — no migration. v1 must not allow pool decrement (write path forbids negative deltas).

#### Epic 8.5 — Group Leader & Rule Versioning (8 FRs)

- **FR-8.5.1** — Room creator is the default leader. Leader is identified by `rooms.owner_id`.
- **FR-8.5.2** — `room_rule_versions` table stores per-month rule snapshots. Effective rule = row with highest `effective_from_month <= currentMonth` for that room.
- **FR-8.5.3** — `PATCH /api/v1/rooms/{id}/rule` accepts a rule edit; service layer creates a new `room_rule_versions` row with `effective_from_month = nextMonth`. Current month's rule is locked.
- **FR-8.5.4** — Member-cap edits (`PATCH /api/v1/rooms/{id}/members/cap`) follow the same next-month-only rule.
- **FR-8.5.5** — Leader-driven member removal (`DELETE /api/v1/rooms/{id}/members/{userId}`) is permitted; the removed user's record archive is preserved per existing room exit semantics.
- **FR-8.5.6** — Leader transfer (`POST /api/v1/rooms/{id}/transfer-leadership`) is permitted to any active room member. Updates `rooms.owner_id`.
- **FR-8.5.7** — If the leader transitions to `RED`, `RoomService` auto-promotes leadership to the longest-tenured `ACTIVE` member. Emits `RealtimeEvent.LeadershipChange`.
- **FR-8.5.8** — All rule-version changes broadcast a chat system message: "다음 달부터 새 규칙이 적용됩니다 [preview]". Broad visibility for all room members.

#### Epic 8.6 — KakaoTalk SDK Invite Virality (6 FRs)

- **FR-8.6.1** — Room invite-code generation (`POST /api/v1/rooms/{id}/invites`) returns a Kakao-ready share payload: `{ inviteCode, kakaoShareUrl, previewCardImageUrl }`.
- **FR-8.6.2** — `previewCardImageUrl` is generated by a server-side renderer using room name, current rule summary, member count, and Risograph design tokens. Cached with TTL 1h; regenerated on rule/member-count change.
- **FR-8.6.3** — FE's `RoomInviteSheet` component (existing) is extended with a "Share to KakaoTalk" CTA invoking the Kakao Share SDK (extends existing OAuth integration; no new SDK package).
- **FR-8.6.4** — Tapping a Kakao-shared invite-code on a device with the app installed deep-links to the room preview screen with one-tap join (subject to `max_members` capacity).
- **FR-8.6.5** — Tapping a Kakao-shared invite-code without the app installed deep-links to the App Store / Google Play KR listing with the invite-code preserved via a deep-link handoff (post-install `/api/v1/auth/signup` carries `inviteCode` parameter).
- **FR-8.6.6** — Kakao Share SDK is a native module addition. Project-context.md rule applies: shipping requires `adb uninstall app.yeosal.mobile` + clean rebuild on dev machines; document in RUNBOOK.md.

#### Epic 8.7 — Final-3 Monthly Ceremony (6 FRs)

- **FR-8.7.1** — A scheduled job runs at 06:30 KST on the first day of each month. For each room with at least 1 member who completed the prior month with `survival_state.status = ACTIVE`, generate a Final-3 poster.
- **FR-8.7.2** — Poster generation is server-side SVG rendering using the existing Risograph design tokens (`ink`, `paper`, `pink`, `green`, `acid`, `muted`). Layout: room name at top, all surviving member nicknames listed, top-3 by tenure highlighted with pink + green accents.
- **FR-8.7.3** — Generated poster is stored at `/api/v1/rooms/{id}/posters/{yearMonth}` with a stable URL. PNG fallback available for Kakao share constraints.
- **FR-8.7.4** — Each surviving member receives a Home-tab card displaying the poster + "Share to KakaoTalk" CTA. Tap → shares Kakao card with embedded room invite-code.
- **FR-8.7.5** — 30-member-room semantics: poster always shows top-3 by tenure as the highlighted "Final-3" with a secondary "X명 생존" stat. Layout dynamically scales to fit up to 30 nicknames.
- **FR-8.7.6** — Posters are immutable once generated; no edit. Subsequent member changes do not retroactively modify a finalized month's poster.

#### Epic 8.8 — Brand Voice & Onboarding (6 FRs)

- **FR-8.8.1** — Onboarding script (5 screens) is a v1 design deliverable. Sequence: (1) "열살방은 친구와 함께 살아남는 방입니다."; (2) "매일 약속을 지키면 살아남습니다. 빠지면 친구가 살릴 수 있어요."; (3) "v1에서는 돈을 받지 않습니다 — 살아남는 것 자체가 자산입니다."; (4) "친구를 살리는 건 옵션이지 의무가 아닙니다."; (5) Wallet preview + Room preview + 14-day grace banner.
- **FR-8.8.2** — All in-app copy passes a brand-voice review against the lexicon. Use: 함께, 선물, 응원, 컴백, 회생, 그룹, 동료, 우리, 살리다. Avoid: 벌금, 잃었다, 떨어졌다, 실패, 자책, 부담, 패배, 죄책감.
- **FR-8.8.3** — Push notification copy follows brand voice; tone is invitation, not demand. Sample: "수진이 회생을 기다리고 있어요" (good) not "수진이가 죽었다 살려라!" (bad).
- **FR-8.8.4** — Apple/Google Play store metadata in English uses "comeback pass" terminology, not "revival ticket" / "second chance pass". KR copy keeps "회생권".
- **FR-8.8.5** — Error / system messages on elimination use "컴백 가능" language, not "탈락" / "실패".
- **FR-8.8.6** — Brand-voice review is a quality gate before each release. Owner: PM + designer joint sign-off.

**Total FRs: 52** (8.1×7, 8.2×6, 8.3×8, 8.4×5, 8.5×8, 8.6×6, 8.7×6, 8.8×6)

### Non-Functional Requirements

#### 9.1 Performance (5)

- **NFR-9.1.1** — Survival state evaluation job (06:00 KST daily) must complete within 5 minutes for up to 50,000 active members across all rooms (10× current MAU expectation).
- **NFR-9.1.2** — Revival API (`POST /api/v1/rooms/{id}/revival`) p95 latency < 300ms; p99 < 800ms.
- **NFR-9.1.3** — Room realtime topic latency (state change → STOMP delivery) < 500ms p95.
- **NFR-9.1.4** — Final-3 poster generation < 3s p99 per poster; batch generation must complete within 10 minutes for up to 5,000 active rooms.
- **NFR-9.1.5** — FE Spectator-mode entry transition < 1s after BE state change is observed via STOMP.

#### 9.2 Reliability (5)

- **NFR-9.2.1** — Survival state evaluation must be idempotent. If the daily job runs twice for the same date due to retry, no member's state should double-progress.
- **NFR-9.2.2** — All revival operations are atomic per `(room_id, user_id, date)`. Concurrent revival attempts must result in exactly one success; idempotency enforced via partial unique index on `revival_events (room_id, user_id, date_part(eliminated_at))` for first-revival-after-elimination semantics.
- **NFR-9.2.3** — Friend-gift revival concurrency: two givers attempting to revive the same eliminated user must result in exactly one success and the loser receives a clear "이미 회생되었습니다" response.
- **NFR-9.2.4** — Group point pool updates use Postgres advisory lock or `SELECT … FOR UPDATE` per room to prevent lost updates.
- **NFR-9.2.5** — Spectator-mode read-only enforcement is double-implemented: FE input disabled + BE chat-write API rejects with `403 FORBIDDEN` for `SPECTATOR` status.

#### 9.3 Security & Privacy (7)

- **NFR-9.3.1** — All v1 endpoints require Bearer JWT (existing convention); revival, gift, and rule-edit endpoints additionally check room membership server-side.
- **NFR-9.3.2** — Eliminated user's archive defaults to private; sharing requires explicit `record_visibility_prefs.share_on_elimination = true`.
- **NFR-9.3.3** — Account deletion: PIPA + Apple/Google compliant. User can export all their data (PDF) and request hard delete. Hard delete cascades existing tables; new tables (`survival_state`, `personal_points_ledger`, `revival_events`, `streak_freezes`, `record_visibility_prefs`) cascade on `users.id` foreign key.
- **NFR-9.3.4** — Hard-deletion of a user does not broadcast to friends or rooms; chat messages from the user are anonymized (sender_user_id set NULL per existing V7 ON DELETE SET NULL rule).
- **NFR-9.3.5** — Quiet hours (`notification_prefs.quiet_start_hour`/`quiet_end_hour`, defaults 22–08) are respected for all push notifications including survival, revival, and friend-gift.
- **NFR-9.3.6** — No location tracking. No surveillance APIs.
- **NFR-9.3.7** — Sentry telemetry: alert on mass-elimination events (>50% of room red-cards in 24h) for incident response triage.

#### 9.4 Observability & Telemetry (4)

- **NFR-9.4.1** — Sentry transactions instrument: survival-state daily evaluation job, revival endpoints, friend-gift endpoints, Final-3 poster generation, KakaoTalk SDK invite link generation.
- **NFR-9.4.2** — Custom Sentry events on every state transition (`ACTIVE→YELLOW`, `YELLOW→RED`, `RED→SPECTATOR`, `RED|SPECTATOR→ACTIVE` via revival).
- **NFR-9.4.3** — Backend logs at `INFO` level for revival/friend-gift events with structured fields: `roomId`, `userId`, `source`, `points_spent`, `pool_after`. Never log PII or token contents.
- **NFR-9.4.4** — Dashboards (KPI tracking): one per success metric in §3.1. Dashboards are not in v1 scope to *build*, but events must be emitted such that dashboards can be assembled in week 1 post-launch.

#### 9.5 Compatibility & Migration (5)

- **NFR-9.5.1** — V11+ migration is idempotent and safe to roll forward. Existing rooms keep their current `max_members` until leader updates (next-month-only).
- **NFR-9.5.2** — Existing yeolsal users who already have rooms continue to function; on first login post-deploy, a backfill creates `survival_state` rows for all `(room_id, user_id)` pairs with status=`ACTIVE`.
- **NFR-9.5.3** — Free revival ticket grant is backfilled for all existing users at deploy (one-time job).
- **NFR-9.5.4** — Existing chat history preserved; existing daily entry / reflection history preserved; existing friend graph preserved.
- **NFR-9.5.5** — App version cutover: v1 ships with a `min_supported_app_version` config in BE that politely forces a Play Store / App Store update for users on pre-v1 binaries.

#### 9.6 Accessibility (3)

- **NFR-9.6.1** — Color is never the sole information carrier (per existing design-system.md): risograph palette decisions for survival state must include text labels (e.g., "ACTIVE", "노란 카드", "빨간 카드", "관전").
- **NFR-9.6.2** — Push notifications support iOS/Android system-level a11y (clear text, no critical info in haptics-only).
- **NFR-9.6.3** — Dynamic Type respected in FE (existing convention).

#### 9.7 Internationalization (3)

- **NFR-9.7.1** — v1 is KR-only. All user-facing copy is Korean. No locale switcher.
- **NFR-9.7.2** — Backend formats dates per `Asia/Seoul` timezone in user-facing payloads. Internal storage remains `timestamptz`.
- **NFR-9.7.3** — App Store / Google Play storefront metadata is bilingual (KR primary, English secondary for store algorithms only). Brand-voice rule for English: "comeback pass" not "revival ticket".

#### 9.8 Build & Deploy (6)

- **NFR-9.8.1** — BE Flyway V11+ migrations follow existing conventions (`V<N>__<slug>.sql`, idempotent SQL, partial unique indexes per V8/V9 reference pattern).
- **NFR-9.8.2** — BE compile/test gate per existing CONTRIBUTING.md (`./gradlew test` green before push).
- **NFR-9.8.3** — FE checks per existing CONTRIBUTING.md (`npm run lint && npm run typecheck && npm test`).
- **NFR-9.8.4** — Stack-PR merge procedure (CONTRIBUTING.md, incident-driven) applies for any multi-PR slice.
- **NFR-9.8.5** — KakaoTalk SDK addition triggers `adb uninstall app.yeosal.mobile` + rebuild on dev machines; document the cycle in RUNBOOK.md.
- **NFR-9.8.6** — Production cutover: blue-green deploy via existing `infra/docker-compose.yml` + nginx. `/app/COMMIT` exposes deployed commit for diagnostics.

**Total NFRs: 38** (9.1×5, 9.2×5, 9.3×7, 9.4×4, 9.5×5, 9.6×3, 9.7×3, 9.8×6)

### Additional Requirements & Constraints

**Domain glossary & state machine (§5.1, §5.5)** — these are not numbered FRs but are load-bearing for traceability:
- Survival states: `ACTIVE`, `YELLOW`, `RED`, `SPECTATOR`. Yellow = first miss in rolling 7d; Red = second miss in rolling 7d → triggers SPECTATOR; revival sources reset the rolling window.
- Day boundary owned by `EntryDateResolver` (06:00 Asia/Seoul).
- Soft-public elimination — RED visible to user + leader for 24h before broadcast.

**New entities (§5.3)** — 7 new tables required: `streak_freezes`, `survival_state`, `revival_events`, `personal_points_ledger`, `room_point_pool`, `room_rule_versions`, `record_visibility_prefs`. (One existing entity modified: `rooms.max_members` range extended.)

**Banned patterns across all phases (§6.1)** — these are negative requirements that the implementation must respect: random/variable revival pricing, streak-scaled costs above entry, pyramid-style invite revives, location-based verification, cash payouts to leaders, public revival/spend/rejection leaderboards, death icons on grass, paid spectator chat, auto-broadcast on account deletion.

**Out of v1 scope (§6.2)** — explicit deferrals: payment surfaces, gifticon catalog, multi-room membership, custom rule authoring, B2B vertical, sobriety vertical, voice rooms, rule marketplace, sponsor pairing, i18n, real-money cash-out.

**Locked decisions (§6.3, §14.2)** — 16 decisions canonicalized in §14.2 (room cap default 12 / max 30; payment in v1 = none; free ticket lifetime = 1; KakaoTalk SDK in v1; gifticon deferred; friend-gift in v1; international = v3; etc.). All are inputs to architecture/epics traceability.

**Personal-points formula (§6.4)** — locked: +2 per survive day; 5 for friend-gift; 3 for self-revival via points. Tunable via config at Day-30 telemetry checkpoint (no migration required for value tweaks).

**KPIs / phase-2 trigger gates (§3.1, §3.2)** — drive the telemetry NFRs (9.4.4) and represent the "what must be measurable" surface.

**Test strategy (§12)** — high-level direction: unit + Testcontainers integration tests per new BE service, FE TanStack Query hook tests, E2E smoke for J1–J5 journeys, brand-voice review pre-release, `bash scripts/verify.sh` green gate.

### PRD Completeness Assessment (preliminary)

- **Structure:** Standard BMad PRD shape (Exec Summary, Vision, Success Criteria, Personas, Domain Model, Innovation/Constraints, Project Type, FRs by epic, NFRs by category, Dependencies, Roadmap, Test Strategy, Risks, Appendix). All 14 sections present.
- **Numbering:** FRs are uniformly numbered `FR-8.<epic>.<n>` (1-indexed within each epic). NFRs are `NFR-9.<category>.<n>`. Numbering is dense (no gaps observed).
- **Traceability hooks:** Each FR cites a concrete table/topic/endpoint where applicable, easing epic-coverage validation in Step 3.
- **Locked decisions:** 16 decisions canonicalized in §14.2 — strong basis for matching epic acceptance criteria.
- **Risks:** §13 names top-3 cracks with explicit kill criteria + 4 deferred open questions; none gate v1.
- **Open issues at PRD scope:** None blocking. Implementation-detail questions (e.g., free-ticket grant data shape — `users` flag vs new table) are intentionally deferred to architecture stage.

Initial verdict on PRD alone: **READY**, pending coverage validation against epics in Step 3.

## Step 3 — Epic Coverage Validation

Source: `_bmad-output/planning-artifacts/epics.md` (read in full, 901 lines). Cross-referenced both the document's own "FR Coverage Map" (lines 64-89) and each story's explicit `PRD ref:` footer.

### Epic & Story Inventory

| Epic | Title | Stories | Sprint |
|------|-------|---------|--------|
| 1 | Survival State & Daily Loop | 1.1 / 1.2 / 1.3 / 1.4 | W1–W2 |
| 2 | Spectator Mode | 2.1 / 2.2 / 2.3 | W2–W3 |
| 3 | Revival Economy | 3.1 / 3.2 / 3.3 / 3.4 | W3–W4 |
| 4 | Group Point Pool | 4.1 / 4.2 | W4 |
| 5 | Leader & Rule Versioning | 5.1 / 5.2 / 5.3 / 5.4 | W3–W4 |
| 6 | KakaoTalk SDK Invite | 6.1 / 6.2 / 6.3 | W5 |
| 7 | Final-3 Monthly Ceremony | 7.1 / 7.2 / 7.3 | W6 |
| 8 | Brand Voice & Onboarding | 8.1 / 8.2 / 8.3 / 8.4 | W7 |

**Actual story count: 27** (4+3+4+2+4+3+3+4). Epics doc claims "32 effective" by counting "+5 weave-in cross-epic items" (mostly NFR-9.4 telemetry) that are *not defined as discrete stories*. These weave-ins lack story IDs and acceptance criteria — flagged below.

### FR Coverage Matrix (52 PRD FRs)

| FR | PRD Summary | Story (per coverage map) | Story PRD-ref label match? | Status |
|----|-------------|---------------------------|----------------------------|--------|
| FR-8.1.1 | Mandatory primary room cap [2,30], default 12, 14d grace from joined_at | 1.1 | yes | ✓ Covered |
| FR-8.1.2 | One `survival_state` row per (user, room), init ACTIVE | 1.1 | yes | ✓ Covered |
| FR-8.1.3 | 06:00 KST evaluator, +2 SURVIVAL, streak_freeze, YELLOW/RED rolling-7d | 1.2 | yes | ✓ Covered |
| FR-8.1.4 | Grace 14d caps progression at YELLOW | 1.1 | yes | ✓ Covered |
| FR-8.1.5 | Emit `RealtimeEvent.SurvivalStateChange` on `/topic/rooms/{id}/survival` | 1.2 | yes | ✓ Covered |
| FR-8.1.6 | Privacy-filtered `GET /api/v1/rooms/{id}/survival` (24h soft-public) | 1.3 | yes | ✓ Covered |
| FR-8.1.7 | `notification_log` idempotency `{date}:{userId}` | 1.2 | yes | ✓ Covered |
| FR-8.2.1 | Spectator routing branch (FE) | 2.1 | yes | ✓ Covered |
| FR-8.2.2 | Read-only chat (FE+BE 403) | 2.1 | yes | ✓ Covered |
| FR-8.2.3 | 09:00 KST daily digest push | 2.2 | yes | ✓ Covered |
| FR-8.2.4 | Eliminated archive private + opt-in `record_visibility_prefs` | 2.3 | yes | ✓ Covered |
| FR-8.2.5 | Wallet prominent on Home tab in spectator | 2.1 | yes | ✓ Covered |
| FR-8.2.6 | On revival, ACTIVE re-entry | 2.1 | yes | ✓ Covered |
| FR-8.3.1 | Free revival ticket grant at signup, lifetime 1 | 3.1 | yes | ✓ Covered |
| FR-8.3.2 | `POST /rooms/{id}/revival` self-revival flow | 3.1 | yes | ✓ Covered |
| FR-8.3.3 | `POST /rooms/{id}/revivals/gifts` friend-gift flow | 3.2 | yes | ✓ Covered |
| FR-8.3.4 | One push to eligible givers, no follow-ups | 3.2 | yes | ✓ Covered |
| FR-8.3.5 | Receiver push w/ donor name; donor opt-in chat broadcast | 3.2 | yes | ✓ Covered |
| FR-8.3.6 | Wallet UI surface (ticket / points / pool / received history) | 3.4 | yes (3.3 also references) | ✓ Covered (double) |
| FR-8.3.7 | Rejection/non-action never visible to anyone but giver | 3.2 | yes | ✓ Covered |
| FR-8.3.8 | `personal_points_ledger` append-only; forfeit on leave | 3.1 | yes | ✓ Covered |
| FR-8.4.1 | One `room_point_pool` row per room, init 0 | 4.1 | yes | ✓ Covered |
| FR-8.4.2 | Room UI shows pool prominently | 4.1 | yes | ✓ Covered |
| FR-8.4.3 | Emit `/topic/rooms/{id}/points` `{ delta, newTotal, … }` | 4.1 | yes | ✓ Covered |
| FR-8.4.4 | Phase-2 promise copy ("커피로 교환됩니다") | 4.2 | yes | ✓ Covered |
| FR-8.4.5 | No redemption in v1; no negative deltas | 4.1 | yes | ✓ Covered |
| FR-8.5.1 | `rooms.owner_id` is leader | 5.1 / 5.2 (per map) | **NOT in any explicit PRD-ref label**; AC text uses `rooms.owner_id` invariant (Story 5.1) | ⚠ Implicit-only |
| FR-8.5.2 | `room_rule_versions`; effective rule = highest `effective_from_month <= now` | 5.1 | yes | ✓ Covered |
| FR-8.5.3 | `PATCH /rule` next-month-only | 5.1 | yes | ✓ Covered |
| FR-8.5.4 | Member-cap edits next-month-only | 5.1 / 5.2 (per map) | **NOT in any explicit PRD-ref label**; Story 5.2 AC text covers it | ⚠ Implicit-only |
| FR-8.5.5 | Leader-driven `DELETE /members/{id}` | 5.2 | yes | ✓ Covered |
| FR-8.5.6 | `POST /transfer-leadership` | 5.2 | yes | ✓ Covered |
| FR-8.5.7 | Auto-promote longest-tenured ACTIVE on leader RED | 5.3 | yes | ✓ Covered |
| FR-8.5.8 | Rule-change chat system message | 5.4 (also 5.1) | yes | ✓ Covered (double) |
| FR-8.6.1 | `POST /rooms/{id}/invites` Kakao share payload | 6.1 | yes | ✓ Covered |
| FR-8.6.2 | Server-rendered preview card, TTL 1h, invalidated on rule/member change | 6.1 | yes | ✓ Covered |
| FR-8.6.3 | `RoomInviteSheet` Kakao Share CTA | 6.2 | yes | ✓ Covered |
| FR-8.6.4 | Deep-link to room preview + 1-tap join (capacity-aware) | 6.2 | yes | ✓ Covered |
| FR-8.6.5 | Deep-link → store + invite-code preserved post-install | 6.2 | yes | ✓ Covered |
| FR-8.6.6 | Native module → adb uninstall + RUNBOOK update | 6.3 | yes | ✓ Covered |
| FR-8.7.1 | 06:30 KST 1st-of-month scheduled job | 7.2 | yes | ✓ Covered |
| FR-8.7.2 | Server-side SVG renderer w/ Risograph tokens | 7.1 | yes | ✓ Covered |
| FR-8.7.3 | `/api/v1/rooms/{id}/posters/{yearMonth}` stable URL + PNG fallback | 7.1 | yes | ✓ Covered |
| FR-8.7.4 | Home-tab card + Kakao share | 7.3 | yes | ✓ Covered |
| FR-8.7.5 | 30-member room semantics: Final-3 by tenure + "X명 생존" | 7.1 | yes | ✓ Covered |
| FR-8.7.6 | Posters immutable | 7.2 | yes | ✓ Covered |
| FR-8.8.1 | 5-screen onboarding | 8.1 | yes | ✓ Covered |
| FR-8.8.2 | Brand-voice lexicon Use/Avoid | 8.2 | yes | ✓ Covered |
| FR-8.8.3 | Push copy: invitation tone | 8.2 | yes | ✓ Covered |
| FR-8.8.4 | EN ASO uses "comeback pass", KR keeps "회생권" | 8.3 | yes | ✓ Covered |
| FR-8.8.5 | Elimination copy: "컴백 가능", not "탈락/실패" | 8.2 | yes | ✓ Covered |
| FR-8.8.6 | Brand-voice review release gate; PM + designer joint sign-off | 8.4 | yes | ✓ Covered |

### FR Coverage Statistics

- **Total PRD FRs:** 52
- **Fully covered (explicit PRD-ref label match):** 50 / 52 = **96.2%**
- **Implicit-only coverage (story AC text covers it but PRD-ref label does not list it):** 2 (FR-8.5.1, FR-8.5.4)
- **Missing entirely:** 0
- **Effective coverage (including implicit):** 52 / 52 = **100%**

### Coverage Map vs PRD-ref Label Inconsistencies

The epics document's own coverage map has minor mislabels worth correcting before sprint planning kicks off:

1. **Map line "FR-8.3.3, .4, .5, .7 → Stories 3.2, 3.3"** — Story 3.3's actual `PRD ref` is `FR-8.3.6` (not `8.3.3/4/5/7`). Story 3.2 alone covers 8.3.3/4/5/7. Recommend: change map line to "→ Story 3.2"; add a separate line "FR-8.3.6 → Story 3.3 + Story 3.4 (double-coverage)".
2. **FR-8.5.1** is not labeled in any story's `PRD ref:` footer. Recommend: add `FR-8.5.1` to Story 5.1's PRD ref line (the AC already enforces `rooms.owner_id == authenticatedUserId`).
3. **FR-8.5.4** is not labeled in any story's `PRD ref:` footer. Recommend: add `FR-8.5.4` to Story 5.2's PRD ref line (the AC already specifies "the change applies on the same next-month-only basis as rule edits").

These are documentation-hygiene fixes, not implementation gaps. They matter for traceability tooling and for `/bmad-create-story` if it is later run with `PRD ref` parsing.

### NFR Coverage (38 NFRs)

The epics document explicitly maps NFRs at the bottom of the coverage map:
- `NFR-9.5 (migration + backfill) → Epic 1 / Story 1.4`
- `NFR-9.4 (observability) → Cross-epic: Telemetry tasks woven into each story's AC`

A finer-grained scan of the 38 NFRs against story AC text:

| NFR | Coverage | Notes |
|-----|----------|-------|
| 9.1.1 (eval job ≤5min @ 50k members) | ✓ Story 1.2 | AC line includes wall-clock budget |
| 9.1.2 (revival p95 < 300ms / p99 < 800ms) | ⚠ Not story-owned | No AC asserts the latency budget |
| 9.1.3 (STOMP latency < 500ms p95) | ⚠ Not story-owned | Implicit via existing realtime infra |
| 9.1.4 (poster gen < 3s p99 / batch < 10min) | ✓ Stories 7.1 + 7.2 | Both ACs cite the budget |
| 9.1.5 (spectator-mode entry < 1s) | ✓ Story 2.1 | AC cites the budget |
| 9.2.1 (eval idempotency) | ✓ Story 1.2 | `notification_log` key |
| 9.2.2 (revival atomicity / partial unique) | ✓ Story 3.1 | advisory lock + `ux_revival_events_one_per_elimination` |
| 9.2.3 (concurrent friend-gift exactly-one-success) | ✓ Story 3.2 | `ALREADY_REVIVED` 409 path |
| 9.2.4 (pool `SELECT … FOR UPDATE`) | ✓ Story 3.1 / 4.1 | both cite |
| 9.2.5 (spectator read-only double-implementation) | ✓ Story 2.1 | FE input + BE 403 |
| 9.3.1 (Bearer JWT + room-membership server-side) | ⚠ Not story-owned | Implicit via existing security chain; some endpoints (5.x) check explicitly |
| 9.3.2 (eliminated archive default-private) | ✓ Story 2.3 | AC cites NFR |
| **9.3.3 (PIPA + Apple/Google account deletion + cascade for new tables)** | ❌ **No story owns this** | New tables (`survival_state`, `personal_points_ledger`, `revival_events`, `streak_freezes`, `record_visibility_prefs`) are created in V11 but cascade behavior on `users.id` is not asserted in any story AC |
| **9.3.4 (anonymized chat on hard delete; SET NULL)** | ❌ **Not story-owned** | Existing V7 ON DELETE SET NULL covers chat; but no story asserts behavior for new tables |
| 9.3.5 (quiet hours respected for survival/revival/friend-gift pushes) | ✓ Story 2.2 | digest push cites; not asserted on Story 3.2 friend-gift push |
| 9.3.6 (no location tracking) | ⚠ Implicit via §6.1 banned list | Not story-owned (negative requirement) |
| **9.3.7 (Sentry alert on mass-elimination >50% in 24h)** | ❌ **No story owns this** | PRD §13.3 lists this as an incident-response must-do; no story carries it |
| 9.4.1 (Sentry transactions on listed jobs/endpoints) | ⚠ Coverage map says "weave-in"; not in any story's AC | |
| 9.4.2 (custom Sentry events on every state transition) | ⚠ Same | |
| 9.4.3 (structured BE logs at INFO; no PII/tokens) | ⚠ Same | |
| 9.4.4 (KPI dashboards built post-launch from emitted events) | ⚠ Same | |
| 9.5.1 (V11 idempotent + roll-forward + existing rooms keep cap) | ✓ Story 1.4 | AC cites |
| 9.5.2 (post-deploy backfill creates `survival_state` ACTIVE) | ✓ Story 1.4 | AC cites |
| **9.5.3 (free-ticket grant backfill for existing users)** | ⚠ Story 1.4 *header* mentions it but **no AC asserts the backfill SQL** | Recommend adding an AC "every existing `users` row gets `free_revival_ticket_used = false` (default value); migration default ensures backfill" |
| 9.5.4 (existing chat / daily / friend graph preserved) | ✓ Story 1.4 (implicit; V11 only adds tables, alters `rooms.max_members`) | |
| **9.5.5 (`min_supported_app_version` config + polite force-update)** | ❌ **No story owns this** | Cutover requirement |
| **9.6.1 (color + text labels for survival state — a11y)** | ❌ **No story owns this** | UX spec may cover but not story AC |
| 9.6.2 (push a11y) | ⚠ Implicit | |
| 9.6.3 (Dynamic Type) | ⚠ Implicit (existing FE convention) | |
| 9.7.1 (KR-only; no locale switcher) | ⚠ Implicit (no story introduces i18n) | |
| 9.7.2 (BE formats `Asia/Seoul` in user-facing payloads; `timestamptz` storage) | ⚠ Implicit (existing convention) | |
| 9.7.3 (storefront bilingual; "comeback pass" in EN) | ✓ Story 8.3 | covers EN/KR copy |
| 9.8.1 (Flyway V11+ conventions) | ✓ Story 1.4 | |
| 9.8.2 (`./gradlew test` green) | ⚠ Existing convention; no story asserts | |
| 9.8.3 (FE lint+typecheck+test) | ⚠ Existing convention | |
| 9.8.4 (Stack-PR merge procedure) | ⚠ Existing convention | |
| 9.8.5 (Kakao SDK adb uninstall + RUNBOOK) | ✓ Story 6.3 | |
| 9.8.6 (blue-green deploy + `/app/COMMIT`) | ⚠ Existing convention | |

**NFR Coverage Statistics:**
- **Explicit story coverage:** 17 / 38
- **Implicit-via-existing-convention or "weave-in" placeholder:** 16 / 38
- **Uncovered (no owner; gap):** **5 / 38** — NFR-9.3.3, NFR-9.3.4, NFR-9.3.7, NFR-9.5.5, NFR-9.6.1
- **Soft gap (header mentions but AC missing):** **1** — NFR-9.5.3 (free-ticket backfill)

### Critical Gaps (require resolution before W1 sprint)

1. **NFR-9.3.3 — Account deletion + cascade for new tables.** PRD §9.3.3 mandates PIPA + Apple/Google compliance. New tables (`survival_state`, `personal_points_ledger`, `revival_events`, `streak_freezes`, `record_visibility_prefs`, `room_point_pool`, `room_rule_versions`) need explicit `ON DELETE CASCADE` semantics decided and tested. **Recommend:** add a Story 1.5 ("Account-deletion cascade for v1 tables + data export PDF").
2. **NFR-9.3.7 — Sentry mass-elimination alert.** PRD §13.3 lists this as an incident-response must-do before launch. **Recommend:** add it as an AC under Story 1.2 (or a dedicated W8 telemetry story).
3. **NFR-9.4.x — Observability "weave-in" is too vague.** "Telemetry tasks woven into each story's AC" with no concrete AC creates a sprint-planning hole. **Recommend:** convert into a single dedicated story per epic (W8 budget) or add explicit AC lines per story (e.g., "On each `RevivalService` success, emit a Sentry transaction tagged `revival` with span tags `roomId, source, points_spent`").
4. **NFR-9.5.5 — `min_supported_app_version`.** Brownfield cutover requires this; otherwise pre-v1 clients hit the new BE silently. **Recommend:** add a Story 1.6 (or W8 story) "Minimum app version gate".

### High-Priority Gaps (should be resolved by W1 but won't block kickoff)

5. **NFR-9.5.3 — Free-revival-ticket backfill AC missing.** Story 1.4 header mentions it but no AC asserts the SQL behavior. **Recommend:** add an AC "every existing `users` row receives `free_revival_ticket_used = false` (column default), and the V11 migration explicitly sets this for any rows where the column was added with NULL". Also confirm whether the implementation is a `users.free_revival_ticket_used boolean` flag (Architecture §4.12) or a `revival_grants` table — Architecture decision needed.
6. **FR-8.5.1 / FR-8.5.4 PRD-ref labels missing in stories.** Documentation-hygiene fix on Story 5.1 / 5.2 PRD-ref footers.
7. **Coverage map line "Stories 3.2, 3.3" for FR-8.3.3/4/5/7** is wrong — Story 3.3 covers FR-8.3.6 only. Correct the map.

### Medium-Priority Notes (not blocking)

8. **Story 3.3 (Wallet badge) implements a v1.5-deferred discoverability surface in v1.** PRD §13.4 lists "friend-gift discoverability" as a v1.5 contingency depending on Day-30 telemetry. Pulling this surface into v1 is a deliberate scope expansion. The story even calls out the Day-30 telemetry checkpoint. **Recommend:** confirm with PM that this expansion is intended.
9. **27 actual stories vs "32 effective" claim** — the 5 "weave-in" cross-epic items are not real stories. For sprint planning to size correctly, either define them as discrete W8 stories (telemetry, KPI dashboard scaffolding, mass-elimination alert, account-deletion cascade, min-app-version gate) or stop counting them as "effective stories".
10. **NFR-9.6.x (a11y).** UX spec may cover; will validate in Step 4.

### Coverage Verdict

- **FRs:** **52 / 52 effectively covered** (50 explicit, 2 implicit-only via AC text). Document hygiene fixes recommended for traceability.
- **NFRs:** **5 hard gaps + 1 soft gap.** Hard gaps must be resolved before the W1 sprint kicks off; they are concrete, named, and small.
- **No FR appears in epics that is absent from the PRD.**
- **No banned-pattern from §6.1 is introduced by any story.**

Step 3 verdict: **NEEDS WORK (small).** All FRs traceable; the 5 NFR gaps + 3 documentation hygiene fixes are well-defined and tractable inside W1.

## Step 4 — UX Alignment

Sources read in full:
- `_bmad-output/planning-artifacts/ux-design-specification.md` (1,557 lines; 87 KB).
- `_bmad-output/planning-artifacts/architecture.md` (810 lines; 53 KB).
- (UX directions HTML viewer `ux-design-directions.html` is informational; not part of textual alignment scope.)

### UX Document Status

**Found.** UX spec is comprehensive and explicitly cross-references PRD/Architecture/epics:
- `inputDocuments` frontmatter lists prd.md, architecture.md, and epics.md (all three).
- Each section ties decisions to PRD FR-IDs / NFR-IDs and Architecture §-numbers (e.g., "PRD FR-8.1.4", "Architecture §4.7", "NFR-9.6.1").
- 14 workflow steps completed (step-01 through step-14).
- 5 user journeys (J1–J5) match PRD §4.3 exactly + 1 additional journey (J0 — leader's lonely 30 seconds, called out as "Sally step-04 누락 발견").

### UX ↔ PRD Alignment

**Strong matches (no action needed):**
- J1–J5 user journeys mirror PRD §4.3.
- Survival states `ACTIVE / YELLOW / RED / SPECTATOR` match PRD §5.5.
- 14-day grace window ("환영 기간") matches FR-8.1.4.
- Personal-points formula (+2 / 5 / 3) matches PRD §6.4.
- 06:00 KST day boundary matches PRD §5.1.
- Spectator daily digest at 09:00 KST matches FR-8.2.3.
- Final-3 monthly poster at 06:30 KST 1st-of-month matches FR-8.7.1.
- Brand-voice Use/Avoid lexicon mirrors FR-8.8.2 verbatim.
- `<RisoSheet>` / `<FriendGiftModal>` / `<RevivalSequence>` map to Story 3.2's friend-gift modal spec.
- D5-style rule-change preview ("다음 달 1일부터 적용됩니다") matches FR-8.5.3.

**UX additions beyond PRD scope (require disposition):**

| # | UX-introduced element | Where defined in UX | PRD coverage | Disposition options |
|---|----------------------|---------------------|--------------|----------------------|
| U1 | **J0 — Cold-start leader's lonely 30 seconds** + `<WelcomeWindow>` component | UX §"Journey Inventory", `<WelcomeWindow>` spec, W7 W7 roadmap | None — PRD §4.3 has only J1–J5 | (a) add a new PRD §4.3 J0 entry + new epic story; (b) merge into Story 1.1 AC; (c) cut from UX |
| U2 | **M3.5 — "받은 자가 주는 자가 됨" lifetime-1 moment** + dedicated component | UX Phase 4 + `<RevivalSequence>` follow-up | None — Story 3.2 has no AC for the receiver's first send | (a) add an AC under Story 3.2; (b) new story under Epic 3; (c) cut |
| U3 | **`<KudosButton>` — "응원만 보내기 (0점)" Strava-style 0-point encouragement message** | UX §"Custom Component Specifications" + Friend Gift Modal 3-CTA layout | None — Story 3.2 only specifies the `회생권 선물하기` CTA; no FR for 0-point messages | (a) add FR-8.3.9 + new endpoint + chat_messages kind; (b) cut |
| U4 | **`<RitualMoment>` — 06:00 KST 5-second sacred ritual wrapper** | UX §"Custom Component Specifications", W6 roadmap | None — no PRD FR; principle 5 ("Ritual time is sacred") only | (a) add as a new FR under Epic 1 or Epic 8; (b) implement as decorative-only with no AC; (c) cut |
| U5 | **48-hour post-revival "recovery window" + auto streak-freeze bonus** | UX §"Emotional Conflict Buffers" #5 | None — would alter `streak_freezes` semantics (currently 1/month per FR-8.1.3) | (a) add FR + migration support; (b) cut |
| U6 | **Donor-protection signal** ("이번엔 다른 친구가 응원할 차례예요" after N revivals) | UX §"Missing Balancing Loops" #1 | None | (a) add FR; (b) cut |
| U7 | **Auto digital sabbatical** for spectators after 7+ days (push 0 + auto-grant free ticket) | UX §"Missing Balancing Loops" #2 | None — conflicts with FR-8.3.1 ("free revival ticket lifetime 1") | (a) add FR + reconcile lifetime-1 invariant; (b) cut |
| U8 | **7-day echo footnote** ("○○가 너를 살린 지 N일째") on receiver's daily entry | UX §"M3 4가지 서사 장치" #2 + Phase 4 + Feedback Patterns | None — Story 3.2 has no AC for footnote rendering or its 7-day decay | (a) add AC to Story 3.2; (b) FE-only enhancement (still needs AC); (c) cut |
| U9 | **Spectator-targeted negative-content gentle prompt** ("응원 메시지 어때요?") | UX §"Emotional Conflict Buffers" #4 | None — requires lightweight content-detection on chat for ACTIVE→SPECTATOR audience messages | (a) add FR + analyzer infra; (b) cut |

**Observation:** UX itself notes (line 358) that #5/#6/#7 ("Missing Balancing Loops") are "architecture 환류 후보" — i.e., should flow back into Architecture or step-13. None of them have done so. Architecture §4 has 15 numbered decisions; none cover any of U5–U7.

**The UX spec is internally consistent.** The misalignment is *between UX additions and PRD/epics scope*, not within UX itself.

### UX ↔ Architecture Alignment

**Strong matches:**
- Spectator branched layout — UX §"Navigation Patterns" + §"Spectator branching: layout-branched in `app/(tabs)/_layout.tsx`" ↔ Architecture §4.7 (verbatim match).
- Color tokens (`ink / paper / pink / green / acid / muted`, 3-4px borders, 5-7px hard offset shadows) ↔ Architecture §3.2 / §5.5.
- Server-side SVG renderer for Final-3 ↔ Architecture §4.9 (Plain Java string templating).
- Apache Batik PNG rasterization ↔ Architecture §3.3 + §4.9.
- 3 STOMP topics + post-commit `Spring TransactionalEventListener` emit ↔ Architecture §4.14 + UX §"Realtime post-commit" cross-cutting pattern.
- Advisory lock + partial unique index for revival idempotency ↔ Architecture §4.4 ↔ UX §J3 + §`<FriendGiftModal>` "Edge cases — 동시 race".
- 24-hour soft-public cooldown enforced server-side ↔ Architecture §4.14 ↔ UX §"Server-side privacy enforcement" cross-cutting rule.
- Touch-target ≥ 44/48 pt + Dynamic Type 1.0–1.5x + Color-not-sole-carrier ↔ NFR-9.6.* ↔ UX §"Accessibility Strategy".
- Brand-voice lint helper (`tools/brand-voice-lint.ts`) ↔ Architecture §4.15 ↔ UX §"Cross-cutting Pattern Rules" #4.
- Kakao Share SDK as v1 native module trigger ↔ Architecture §3.3 / §5.2 ↔ UX §"Platform Strategy".

**Architecture gaps surfaced by UX (not resolved in Architecture §4 or §7):**

| # | UX raises | Architecture status | Severity |
|---|-----------|---------------------|----------|
| A1 | **Analytics SDK selection** required for `onboarding.screen.dwell_ms`, friend-gift conversion, spectator→revival, KPI dashboards (UX line 154-155 + line 1281-1287, "현재 미선정 — W1 첫주 결정 필수") | Silent. Architecture §3.3 mentions Sentry only (errors); §7.2 does not address analytics SDK choice. NFR-9.4.x dashboards have no SDK pinned. | **High** — this is the same gap Step 3 flagged on NFR-9.4 |
| A2 | **Offline / 통신 음영 — TanStack Query mutation queue** for daily-checkin (subway / no-signal). UX explicitly defers: "Architecture §7.x 또는 step-08~09에서 결정" | Architecture §7 has no offline strategy. AsyncStorage persist exists for cache, but no mutation queue. | **High** — daily-checkin is the single load-bearing interaction (UX §"Defining Experience"); a missed entry due to no signal at 05:55 KST near deadline silently breaks dignity tone |
| A3 | **WS event schema for `gift.revive.sent` / `gift.revive.received`** — UX line 152-154 says "BE와 합의" required as W1 spec lock | Architecture §4.14 + §6.4 define topics but not WS event payload schemas at field level. Story 3.2 references payload at high level but no schema | **Medium** — covered in Story 3.2 AC but not formally defined in Architecture |
| A4 | **PNG noise overlay implementation** (Android 6.0+ `mixBlendMode multiply` vs opacity-only fallback) — UX `<NoiseOverlay>` spec | Architecture is silent (FE-only design concern). UX-specified | Low (FE-only) |
| A5 | **D1 Editorial sub-mode token export from FE → BE renderer** — UX §"Implementation Approach" line 887-888 ("Final-3 SVG renderer (BE)도 D1 Editorial sub-mode 토큰을 server-side로 매핑") | Architecture §4.9 cites only basic Risograph color tokens (`ink/paper/pink/green/acid/muted`). No sub-mode token sync mechanism. Story 7.1 also cites only base tokens | Low — FE/BE token drift risk for Final-3 poster, mitigatable by manual sync at W6 |
| A6 | **Reduced motion fallback** for M3 5-second sequence (1s shrink) and ritual shift skip | Architecture is silent (FE-only). UX-specified ↔ NFR-9.6.* | Low (FE-only) — needs to make it into a story AC (currently no story owns reduced-motion path) |
| A7 | **`pending_realtime_broadcasts` table cleanup job** | Architecture §7.4 lists this as a risk with weekly cleanup mitigation | OK |

### UX-Required Stories Missing from Epics

Cross-referencing UX components vs `epics.md` story ACs:

| UX component / pattern | Owning story in epics.md? | Notes |
|------------------------|---------------------------|-------|
| `<RisoSheet>` (W3) | Story 3.2 (used by FriendGiftModal) — implicit | Component itself isn't given a build AC; implied prerequisite |
| `<RisoButton>` extension (W1) | None — implied prerequisite | |
| `<RisoCard>` extension (W2) | None — implied prerequisite | |
| `<NoiseOverlay>` (W1) | None | Risograph foundation |
| `<HardShadow>` (W1) | None | |
| `<PoolMeter>` 5-stage swap | Story 4.1 — implicit (UI rendering not in AC) | UX explicitly calls out 5-stage SVG/PNG swap; epics doesn't |
| `<GrassGrid>` gray-shifted spectator variant | Story 2.1 — implicit | UX adds grayscale variant; epics doesn't enumerate |
| `<SystemMessage>` rule-change tone extension | Story 5.4 — implicit | |
| `<RitualMoment>` (06:00 KST 5s) | **None** — U4 above | |
| `<KudosButton>` (응원만 0점) | **None** — U3 above | |
| `<SurvivalBanner>` Yellow/Red display | None — implicit (FR-8.1.6 covers privacy filter, not banner) | |
| `<Wallet>` 4-track Bento | Story 3.4 — implicit (high-level only) | UX defines 4 sections in order; epics doesn't enumerate |
| `<FriendGiftModal>` 3 CTA + 안심 message | Story 3.2 — partial (3 CTA implied; 안심 message + Kudos CTA not in AC) | |
| `<RevivalSequence>` 5-phase animation (0-5s) | **None** | UX-defined 3-5s mythic delay is M3 emotional core; no story owns the FE animation |
| `<ReceivedGiftToast>` post-revival 후일담 toast | Story 3.2 — implicit | |
| `<FinalThreeCard>` D1 Editorial home-tab | Story 7.3 — implicit (high-level only) | |
| `<RoomInviteSheet>` Kakao Share | Story 6.2 | |
| `<WelcomeWindow>` (J0 leader 30s) | **None** — U1 above | |
| 5-screen onboarding S1–S5 | Story 8.1 ✓ | |
| `tools/brand-voice-lint.ts` | Story 8.2 ✓ | |
| Reduced-motion + a11y audit | **None** — UX defers to step-13 a11y-architect agent | A6 above |
| 7-day echo footnote | **None** — U8 above | |

**Implicit-only coverage** is acceptable for low-emotional-stakes UI elements (atoms/molecules), but **emotionally load-bearing elements (RevivalSequence, RitualMoment, WelcomeWindow, KudosButton, M3.5 lifetime-1 moment) should have explicit story-level ACs** so the dev-story cycle doesn't accidentally cut them as "polish".

### Spec-Lock Items Flagged by UX (W1 kickoff blockers per UX line 1281-1287)

UX explicitly calls out 3 items that MUST be locked before W1:
1. **PNG noise overlay fallback** — `<NoiseOverlay>` Android `mixBlendMode` vs opacity-only matrix. Architecture is silent.
2. **WS event schema `gift.revive.*`** — BE↔FE field-level alignment.
3. **Analytics SDK selection** — for KPI measurement.

These are part of the same gap surface as the NFR-9.4.x observability hole flagged in Step 3.

### UX-Side Validation Plan (independent of readiness)

UX §"Validation Plan (Maya)" proposes a **7-day pre-launch revival simulation diary study with 5 paired participants (high/medium/low intimacy mix)** to test whether M3 is a true retention anchor or a quiet-churn trigger. PRD §3 / §13 don't mention this. Useful but not implementation-readiness gating; record as a qualitative gate in the W7/W8 release plan.

### UX Alignment Verdict

- **PRD ↔ UX:** **9 UX-introduced elements (U1–U9) are not authorized by PRD.** Each must be (a) added to PRD as a new FR + epic story, (b) acknowledged as implementation-detail-of-existing-FR with a story AC update, or (c) cut from UX. *None* are in epics.md story ACs as authorized scope.
- **UX ↔ Architecture:** Strong alignment on the 15 numbered architecture decisions. **Three Architecture-level gaps remain (A1 Analytics SDK / A2 Offline mutation queue / A3 WS event schema)** that UX explicitly flags but Architecture §7 does not address.
- **UX ↔ Epics/Stories:** **5 emotionally load-bearing UX components (RevivalSequence, RitualMoment, WelcomeWindow, KudosButton, M3.5 lifetime-1 moment) have no explicit story AC.** Risk: dev-story cycle silently drops them.

### Warnings

1. **W1 SCOPE BLOCKER** — Analytics SDK selection (A1) blocks both UX KPI measurement and Step 3's NFR-9.4 hard gap. This is a single decision that closes two gaps; resolve before Story 1.1 starts.
2. **DAY-1 RELIABILITY RISK** — Offline mutation queue (A2) for daily-checkin in subway/no-signal scenarios is undefined. Daily-checkin is the single load-bearing interaction; a silent-loss event near 06:00 KST = brand voice violation. Architecture §7 must add an offline strategy decision.
3. **EMOTIONAL LOAD-BEARING ELEMENTS WITHOUT ACS** — 5 components (RevivalSequence M3 mythic 3-5s, RitualMoment 06:00 KST, WelcomeWindow J0, KudosButton 0-point, M3.5 lifetime-1 moment) need explicit story ACs OR explicit cut decisions. Without them, the readiness gate cannot guarantee they ship.
4. **SCOPE-EXPANSION DISPOSITION REQUIRED** — U1–U9 scope additions need PM/architect joint decision. Pulling all 9 in is a 1–2 week scope expansion against the 8-week budget; cutting all 9 sacrifices part of UX's emotional spine. Pick deliberately.

Step 4 verdict: **NEEDS WORK (medium).** UX is rich and well-aligned with PRD/Architecture on the locked surface, but introduces 9 unauthorized scope elements and surfaces 3 architecture gaps that all converge on W1 spec-lock items.

## Step 5 — Epic Quality Review

Standards applied: `create-epics-and-stories` best practices (user value, epic independence, no forward dependencies, AC quality, story sizing, brownfield migration patterns).

### Epic-Level User Value Check

| # | Epic | User-centric goal? | Verdict |
|---|------|---------------------|---------|
| 1 | Survival State & Daily Loop | "Replace the existing daily-entries surface with a survival-state-aware experience" — partly technical phrasing, but stories deliver tangible user outcomes (room creation, grace period, evaluator). | ✓ |
| 2 | Spectator Mode | "Eliminated users see what they're missing" — clear user outcome. | ✓ |
| 3 | Revival Economy | "v1's full economy — three revival sources, no money. Defines the load-bearing emotional moment." | ✓ |
| 4 | Group Point Pool | "Visible accumulating shared progress that promises but does not redeem in v1." | ✓ |
| 5 | Leader & Rule Versioning | "Leader has authority but cannot break the contract members joined under." | ✓ |
| 6 | KakaoTalk SDK Invite | "Friction-free room invitation that lives where Korean accountability already lives." | ✓ |
| 7 | Final-3 Monthly Ceremony | "Turn survival into a shareable visual artifact every month." | ✓ |
| 8 | Brand Voice & Onboarding | "Defuse the 챌린저스 mental model in the first session." | ✓ |

All 8 epic goals pass the "user value" gate. **No epic is purely technical**.

### Epic Independence Test (Epic N stand-alone)

- **Epic 1** is foundational (state machine, V11 schema). Stand-alone? Yes — produces a working state machine + room creation flow.
- **Epic 2** depends on Epic 1 (RED state must exist). Backward dependency only.
- **Epic 3** depends on Epic 1 (state machine). Backward dependency only. Without Epic 2, the eliminated-user UX is degenerate but Revival API still works.
- **Epic 4** depends on Epic 3 (revival events grow the pool). Without Epic 3, pool stays at 0 — degenerate but functional.
- **Epic 5** depends on Epic 1 (state transitions for auto-promotion); rule editing endpoints are otherwise independent.
- **Epic 6** depends on Epic 1 (room creation). Otherwise independent.
- **Epic 7** depends on Epic 1 (survival_state at month-end). Otherwise independent.
- **Epic 8** depends on prior epics for *content surfaces* (Wallet preview = Epic 3; spectator copy = Epic 2). The onboarding flow itself can be assembled mid-cycle.

**No forward references** (Epic N → Epic N+m). All dependencies are backward. ✓

### Story-Level Dependency Check

#### Within-Epic Sequencing

- **Epic 1**: Story 1.4 (V11 migration) is logically a prerequisite for Stories 1.1 / 1.2 / 1.3, but is numbered last. **The numbering misleads**: Story 1.4 should be 1.0 or 1.1 for sequence. The sprint plan W1–W2 batches them all so it's not a hard violation, but a dev-story agent reading 1.1 → 1.4 in order would attempt to evaluate state without the schema applied.
- **Epic 5**: Story 5.4 (rule-change broadcast) depends on Story 5.1 (rule version insertion triggers the broadcast). Within-epic backward dependency. ✓
- Other within-epic sequences are linear (3.1→3.2→3.3→3.4 / 7.1→7.2→7.3 / 8.1→8.2→8.3→8.4) and consistent.

#### Cross-Epic Dependencies

- All feature stories implicitly require Story 1.4's V11 schema. Per Architecture §4.11, this is a deliberate "all-tables-in-one-V11-migration" pattern (vs. per-story migration). This **violates the step-05 best practice** ("Each story creates tables it needs") but is **explicitly authorized by Architecture** for brownfield idempotency.
- Story 4.1 (room_point_pool counter) requires `RevivalService` from Epic 3 to actually increment it. Epic 4 ships W4 in parallel with Epic 3 (W3-W4) — works in practice, but Story 4.1 read independently would not be testable end-to-end without Story 3.1.

### Acceptance Criteria Quality Audit

#### Strengths

- Most stories use Given/When/Then with concrete BE flow detail (Stories 3.1, 3.2, 1.2, 1.4 are exemplary).
- Architecture decisions are cited inline (e.g., "Architecture §4.4, §4.6, §4.12, §6.3 V11 (5, 6, 7), §6.4").
- Concurrency / race-condition paths are explicit (e.g., 409 ALREADY_REVIVED, advisory lock + partial unique index).
- Privacy filters are server-side asserted (Story 1.3's leader-aware filter; Story 2.3's BE-side empty payload).

#### Weaknesses

- **Story 5.2 — member-cap edit storage model is undefined.** AC says "the change applies on the same next-month-only basis as rule edits" but does NOT specify whether `rooms.max_members` is mutated directly or whether the change rides via `room_rule_versions.rule_payload.maxMembers`. Architecture §6.4's table says `PATCH /rooms/{id}/members/cap` returns `RoomDto`. Schema is silent. **A dev-story agent would have to invent.** Recommend an explicit AC clarifying the persistence model.
- **Story 1.4 is missing an AC for `users.free_revival_ticket_used = false` backfill** (already flagged as Step 3 soft gap NFR-9.5.3). The story header mentions it; the ACs do not.
- **Story 5.4's "system message body uses brand-voice lexicon" AC** is non-specific ("no 벌금/실패"). Compare to Story 8.2 which defines the linter as the check.
- **Story 8.4 (release-gate brand-voice review)** is a process checklist with sign-off requirements — fine for a gate story but provides no testable AC for the dev-story agent. Acceptable as a release-process story, but should be tagged distinctly from buildable stories in sprint planning.
- **Reduced-motion AC missing across the board.** UX requires reduced-motion fallbacks for M3 5-second sequence + ritual-shift. No story carries this AC. Closest is implicit a11y obligation under NFR-9.6.* — already flagged in Step 3 (NFR-9.6.1) and Step 4 (A6).

### Story Sizing Audit

- Story 1.4 (V11 migration + 12 schema changes + 3 backfill SQL blocks) is **the largest story in the epic set**. Splittable but defensibly atomic for a one-shot brownfield rollforward.
- Story 3.1 combines two revival sources (FREE_TICKET + PERSONAL_POINTS). 90% shared logic; combining is reasonable.
- Story 3.2 covers push prompt, modal, BE flow, and race conditions. Reasonable as one story.
- Story 5.2 combines member-cap edit + leader transfer (two PRD FRs). **Acceptable** but should split if W3 schedule pressure.
- Other stories are normally sized.

### CRITICAL FINDING — Escalated FR Coverage Gap (re-audit of Step 3)

While re-reading Story 5.2's AC text in detail, I discovered that **the PRD-ref label `FR-8.5.5, FR-8.5.6` does not match the AC content**:

- **FR-8.5.5** ("Leader-driven member removal `DELETE /api/v1/rooms/{id}/members/{userId}`") is NOT delivered by Story 5.2's ACs. Story 5.2's first AC is about `PATCH /api/v1/rooms/{id}/members/cap` (member-cap edit = FR-8.5.4), and the second AC is about `POST /transfer-leadership` (= FR-8.5.6). The DELETE endpoint never appears in any story AC.

**Step 3 marked FR-8.5.5 as covered ✓** based on the coverage-map line "FR-8.5.1, .2, .3, .4, .5, .6 → Stories 5.1, 5.2". This audit reveals the coverage map's claim is wrong — **FR-8.5.5 is genuinely uncovered** (no story AC delivers leader-driven member removal).

**Updated coverage:**
- Step 3's "All 52 FRs effectively covered" must be revised: **FR-8.5.5 is uncovered**.
- Effective FR coverage: **51 / 52 = 98.1%**.
- This becomes a 6th hard NFR/FR gap requiring resolution before W1.

### Special Implementation Checks

- **Brownfield indicators** (per step-05 guide): ✓ Story 1.4 is a migration-and-compatibility story; integration points with existing systems are documented in Architecture §4.11.
- **Starter template requirement**: N/A — brownfield, no new starter project.
- **Database creation timing**: V11 single-shot migration violates the "tables when needed" best practice. Architecture §4.11 explicitly authorizes this pattern. Defensible but worth flagging as an exception.

### Emotionally Load-Bearing UX Components Without Story ACs

(Re-summarized from Step 4 in the context of story-quality enforcement.)

| UX component | Owning story? | Risk |
|--------------|---------------|------|
| `<RevivalSequence>` (M3 mythic 3-5s, 5-phase animation) | None — Story 3.2's ACs cover BE flow, not the FE 5-second sequence | Dev-story agent ships a plain "ACTIVE" toast; mythic anchor lost |
| `<RitualMoment>` (06:00 KST 5s sacred wrapper) | None | Cut as polish; ritual principle lost |
| `<WelcomeWindow>` (J0 leader's lonely 30s) | None | A10 anti-pattern (progress-bar pressure) ships by default |
| `<KudosButton>` (응원 0-point) | None | Friend Gift Modal ships with single CTA; "응원만 보내기" alternative lost |
| M3.5 lifetime-1 "받은 자가 주는 자" moment | None | Lifetime-1 first-send recognition lost |

These would each need either (a) AC injection into existing stories, (b) new dedicated stories, or (c) explicit cut decisions. **Currently in implicit-only state**, which is the highest-risk failure mode for the dev-story cycle.

### Best-Practices Compliance Checklist (per epic)

| Epic | User value | Independence | Story sizing | No forward deps | DB timing | AC quality | FR traceability |
|------|------------|--------------|--------------|------------------|-----------|------------|-----------------|
| 1 | ✓ | ✓ (foundational) | ⚠ 1.4 large; 1.4 numbered last | ✓ within-epic | ⚠ V11 monolith authorized | ✓ except free-ticket backfill | ✓ |
| 2 | ✓ | ✓ | ✓ | ✓ | ✓ (uses Epic 1 schema) | ✓ | ✓ |
| 3 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ except M3 sequence | ✓ |
| 4 | ✓ | ⚠ degenerate w/o Epic 3 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 5 | ✓ | ✓ | ⚠ 5.2 storage-model gap | ✓ | ✓ | ⚠ 5.2 storage; 5.4 lexicon | **❌ FR-8.5.5 uncovered** |
| 6 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 7 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 8 | ✓ | ⚠ content-dep on others | ⚠ 8.4 process-only | ✓ | N/A | ⚠ 8.4 sign-off-only | ✓ |

### Findings by Severity

#### 🔴 CRITICAL (block Phase-4 kickoff)

C1. **FR-8.5.5 (leader-driven member removal `DELETE /members/{id}`) is uncovered** — Story 5.2's PRD-ref label says it's covered, but the AC text does not deliver it. **Escalates from Step 3's "100% effective coverage" claim to 51/52 = 98.1%.** Action: add explicit AC in Story 5.2 (or new Story 5.5) for `DELETE /api/v1/rooms/{id}/members/{userId}`, including authorization (leader-only), removed-user record-archive preservation, and leader-cannot-remove-self semantics.

C2. **(Carried from Step 3) NFR-9.3.3 — Account deletion + cascade for v1 tables** has no story owner. Hard-delete cascade for the 7 new tables must be designed and tested.

C3. **(Carried from Step 3) NFR-9.3.7 — Sentry mass-elimination alert** (PRD §13.3 must-do for incident response) has no story owner.

C4. **(Carried from Step 3 + Step 4) NFR-9.4.x — Observability "weave-in" placeholder** is not concrete. Combined with Step 4's A1 (Analytics SDK selection), this is the single largest hole. No KPI dashboards can be assembled at launch without an SDK + per-story event ACs.

C5. **(Carried from Step 4) A2 — Offline mutation queue for daily-checkin** is undefined. Daily-checkin is the load-bearing interaction; subway/no-signal silent loss = brand-voice violation. Architecture §7 must add an offline strategy.

#### 🟠 MAJOR (must resolve in W1)

M1. **9 UX-introduced scope additions (U1–U9 in Step 4)** require disposition decisions. Of these, 5 are emotionally load-bearing (RevivalSequence, RitualMoment, WelcomeWindow, KudosButton, M3.5).

M2. **Story 5.2 storage-model AC gap** — does member-cap edit persist in `rooms.max_members` (existing) or `room_rule_versions.rule_payload.maxMembers` (new)? Architecture §6.4 is silent. Add explicit AC.

M3. **NFR-9.5.3 — Free-revival-ticket backfill AC missing in Story 1.4** (header mentions; AC missing).

M4. **NFR-9.5.5 — `min_supported_app_version` cutover gate** has no story owner. Brownfield cutover risk.

M5. **NFR-9.6.1 — Color + text labels for survival state** — UX delivers via `semantic.survival` token but no story has the AC for it. Add to Story 1.3 or to a new design-token story under Epic 1.

M6. **Reduced-motion fallbacks** for M3 sequence + ritual shift have no story AC.

M7. **Story 1.4 is one big migration story** with no per-table sub-AC structure — defensible but creates concentrated risk if any of the 12 schema changes regresses.

M8. **Story 3.3 (Wallet badge) implements a PRD §13.4 v1.5-deferred surface** in v1 — confirm intentional with PM.

#### 🟡 MINOR (documentation hygiene)

m1. Story 5.2 PRD-ref label `FR-8.5.5, FR-8.5.6` should be `FR-8.5.4, FR-8.5.6` (escalates to C1 because FR-8.5.5 is now revealed as truly uncovered).

m2. Coverage map line "FR-8.3.3, .4, .5, .7 → Stories 3.2, 3.3" mislabels — Story 3.3 covers FR-8.3.6.

m3. FR-8.5.1 / FR-8.5.4 not labeled in any story PRD-ref footer (covered by AC text).

m4. Story numbering in Epic 1 (1.1 → 1.4) misleads on execution order — Story 1.4 (V11 migration) should be 1.0/1.1.

m5. Validation Summary in epics.md claims "8 epics, 32 stories" but actual count is 27; +5 "weave-in" cross-epic items that lack story IDs.

m6. Stories 1.4 / 6.3 / 8.2 are borderline technical/developer-experience stories — defensible but should be acknowledged as exceptions to the "user value" rule.

### Step 5 Verdict

**NEEDS WORK (medium-high).** Strong epic structure overall — 8 user-value-positive epics, no forward dependencies, brownfield-appropriate migration consolidation. But the audit reveals **one previously-undetected FR coverage gap (FR-8.5.5)** that escalates the readiness picture, plus all the carried-forward NFR/UX gaps now have concrete remediation paths. Remediable inside W1 with focused work; not Phase-4-ready as currently written.

## Step 6 — Final Assessment

**Date:** 2026-05-10
**Assessor:** PM (BMad bmad-check-implementation-readiness skill)
**Project:** yeolsal (열살방) — v1 brownfield pivot
**Documents assessed:** PRD (49 KB) · Architecture (53 KB) · Epics (47 KB) · UX Spec (87 KB)

### Overall Readiness Status

# 🟠 NEEDS WORK

The planning artifacts are **mature and tightly coupled** — PRD, Architecture, Epics, and UX cross-reference each other consistently and the bulk of FR coverage holds (51/52 = 98.1%). What blocks Phase-4 is **5 hard NFR/FR gaps that remain unowned by any story**, **3 architecture-level holes that converge on W1 spec-lock items**, and **9 UX scope additions that need explicit accept-or-cut decisions**. None of these are research-grade unknowns — they are concrete, named, and tractable. With 1–2 days of focused PM/architect work to close the disposition decisions and update epics.md, the project becomes **READY**.

### Coverage Summary at a Glance

| Layer | Items | Effective coverage | Gaps |
|-------|-------|--------------------|------|
| PRD FRs | 52 | **51 (98.1%)** | FR-8.5.5 |
| PRD NFRs | 38 | **17 explicit · 16 implicit/convention · 5 hard gaps** | NFR-9.3.3, 9.3.4, 9.3.7, 9.5.5, 9.6.1 (+1 soft: 9.5.3) |
| UX-introduced scope | 9 additions (U1–U9) | **0 authorized in PRD/epics** | All 9 require disposition |
| UX-flagged Architecture gaps | 3 (A1/A2/A3) | **0 resolved** | Analytics SDK, offline queue, WS schema |
| Epic story count | 27 (claimed "32 effective") | — | 5 weave-in items lack story IDs |
| Emotionally load-bearing UX components | 5 | **0 with explicit ACs** | RevivalSequence, RitualMoment, WelcomeWindow, KudosButton, M3.5 |

### Critical Issues Requiring Immediate Action (BEFORE W1 kickoff)

#### 🔴 BLOCK-LEVEL — must resolve before Story 1.1 starts

**B1. FR-8.5.5 (leader-driven `DELETE /members/{id}`) is uncovered.**
The coverage map claims it; Story 5.2's AC text doesn't deliver it. The endpoint is required by the PRD's "leader has authority" guarantee.
*Action:* Add explicit AC in Story 5.2 (or new Story 5.5) for `DELETE /api/v1/rooms/{id}/members/{userId}` — leader-only authz, removed-user record-archive preservation, leader-cannot-remove-self. Estimate: 0.5d.

**B2. NFR-9.3.3 — Account deletion + cascade for 7 new tables is unowned.**
PRD §9.3.3 mandates PIPA + Apple/Google compliance. New tables need explicit `ON DELETE CASCADE` semantics + integration tests. Architecture §6.3 schema does declare cascades, but no story validates them.
*Action:* Add Story 1.5 ("Account-deletion cascade + data export PDF for v1 tables") under Epic 1. Estimate: 1.5d.

**B3. NFR-9.3.7 — Mass-elimination Sentry alert is unowned.**
PRD §13.3 lists this as an incident-response must-do for launch. No story carries the alert rule or the trigger logic.
*Action:* Add an AC under Story 1.2's evaluator job ("On any room with >50% RED transitions in 24h, emit a Sentry custom event with severity=critical"). Estimate: 0.25d.

**B4. NFR-9.4.x + UX A1 — Analytics SDK selection.**
PRD requires KPI dashboards (NFR-9.4.4); UX line 1283-1287 requires it for `onboarding.screen.dwell_ms`, friend-gift conversion, spectator→revival. Architecture §3.3 mentions Sentry only. Without an SDK, *none of the trigger gates can be measured*, and Day-60 Phase-2 decision becomes data-blind.
*Action:* Architect + PM joint decision in W1 day 1. Candidates: Amplitude (KR-popular), Mixpanel, PostHog (self-host), or a thin first-party event pipeline to Postgres + scheduled rollups. Add Story 1.6 ("KPI event pipeline + dashboard scaffolding") under Epic 1. Estimate: 1d decision + 2d implementation.

**B5. UX A2 — Offline / 통신 음영 mutation queue.**
Daily-checkin is the load-bearing interaction; subway/no-signal silent loss = brand-voice violation. Architecture §7 has no offline strategy.
*Action:* Architect adds §7.x decision ("TanStack Query mutation queue with optimistic-rollback on Reconnect") and adds AC to Story 1.1 (room creation flow includes mutation queue init) and to whatever story owns daily-entry submission (currently in existing `daily/` module). Estimate: 0.5d decision + 1.5d implementation.

#### 🟠 HIGH-PRIORITY — must resolve in W1 but does not block kickoff

**H1. UX U1–U9 disposition.** 9 UX-introduced scope items need accept/cut/integrate decisions. Pulling all 9 in is a 1–2 week scope expansion against the 8-week budget. Cutting all 9 sacrifices part of UX's emotional spine. Recommended: accept 5 emotionally load-bearing items (RevivalSequence, RitualMoment-soft, WelcomeWindow, KudosButton, M3.5) and cut/defer the remaining 4 system-level enhancements (recovery-window, donor protection, auto sabbatical, negative-content prompt) to v1.5 telemetry-driven decisions.

**H2. Story 5.2 storage-model AC gap.** Member-cap edit storage model is undefined. Architect decision: store in `room_rule_versions.rule_payload.maxMembers` (next-month-only contract integrity) vs `rooms.max_members` (existing direct mutation).

**H3. NFR-9.5.5 — `min_supported_app_version` brownfield cutover gate.** Add Story 8.5 ("Minimum app version gate") in W7-W8 release prep.

**H4. NFR-9.6.1 — Color + text labels for survival state.** UX `semantic.survival` token spec is ready; no story has the AC. Inject into Story 1.3 or new Story 1.7.

**H5. NFR-9.5.3 — Free-revival-ticket backfill AC missing in Story 1.4.** Header mentions; AC missing. Documentation hygiene fix.

**H6. UX A3 — WS event schema for `gift.revive.sent` / `gift.revive.received`.** Architect decision: field-level payload contract before Story 3.2 starts.

**H7. Reduced-motion fallback ACs** for M3 sequence + ritual shift have no story owner.

**H8. Story 3.3 (Wallet badge) v1.5-deferred surface in v1.** PRD §13.4 lists this as v1.5 contingency. Confirm intentional with PM.

#### 🟡 MEDIUM — documentation hygiene (W1, parallel to H-items)

**m1.** Story 5.2 PRD-ref label: change `FR-8.5.5, FR-8.5.6` → `FR-8.5.4, FR-8.5.6` (when B1 lands as Story 5.5, label lands cleanly).

**m2.** Coverage map line "FR-8.3.3, .4, .5, .7 → Stories 3.2, 3.3" → "→ Story 3.2 only"; add separate line "FR-8.3.6 → Story 3.4 (also Story 3.3 implicitly)".

**m3.** Add `FR-8.5.1` to Story 5.1 PRD-ref footer; `FR-8.5.4` to Story 5.2 PRD-ref footer.

**m4.** Renumber Epic 1 stories so the V11 migration is Story 1.1 (currently 1.4). Or explicitly mark execution order in the epic preamble.

**m5.** Validation Summary in epics.md: drop the "32 effective" claim or convert the 5 weave-in items into discrete W8 stories (telemetry, KPI dashboards, mass-elimination alert, account-deletion cascade, min-app-version gate — these align with B2-B4 + H3 above).

### Recommended Next Steps

1. **W0 — kickoff prep meeting (PM + Architect + Lead Eng + Designer):** in a single ~2-hour session, walk this readiness report top-to-bottom, decide each B/H item, and update PRD/Architecture/epics in place. Produce a small set of focused PRs against `prd.md`, `architecture.md`, and `epics.md` documenting the decisions.
2. **W1 day 1 spec-lock items:** Analytics SDK (B4), WS event schema (H6), PNG noise overlay fallback (UX line 1283), offline mutation queue strategy (B5).
3. **W1 sprint additions:** Stories 1.5 (cascade), 1.6 (KPI pipeline), 5.5 (member removal). Reorder Story 1.4 as Story 1.0 or 1.1 (m4).
4. **W7 release prep additions:** Story 8.5 (min_supported_app_version, H3).
5. **Re-run `/bmad-check-implementation-readiness` after PRD/epics updates** to confirm READY status before W1 kickoff.
6. **Maya-style 7-day diary study** (UX §"Validation Plan") — schedule in W6/W7 if real-user input is wanted before launch. Not Phase-4-blocking; qualitative validation only.
7. **Schedule a post-mortem at Day-30** specifically on UX U1–U9 decisions to evaluate which deferred items earned re-inclusion in v1.5.

### What's Genuinely Ready

To balance the gap list, the following are *ready for implementation* and need no further work:

- All 8 epics have user-value-positive goals and pass independence checks.
- 51 of 52 FRs have explicit story-AC coverage; the 52nd (FR-8.5.5) has a clear remediation path.
- Architecture's 15 numbered decisions cover the load-bearing concurrency, privacy, and migration questions definitively (advisory locks + partial unique indexes; server-side privacy; V11 single-shot rollforward).
- Risograph design tokens, brand-voice lexicon, and 5-screen onboarding script are concrete and signed off.
- Sprint plan W1–W8 maps cleanly to epic + story breakdowns.
- BE/FE source-tree deltas (Architecture §6.1, §6.2) are precisely drawn.
- V11 migration SQL is fully drafted and idempotent.
- Brownfield migration safety and existing-data preservation (chat history, daily entries, friendship graph) are explicitly architected.

### Final Note

This assessment identified **5 BLOCK-LEVEL issues** + **8 HIGH-priority issues** + **5 MEDIUM hygiene items** across 5 categories (FR coverage, NFR coverage, UX scope additions, UX-flagged architecture gaps, story quality). All findings have specific remediation paths and are tractable inside W0–W1.

The decision to proceed is the user's. Reasonable paths forward:

- **Path A (recommended):** Spend 1–2 days on the W0 prep meeting + targeted PRs against PRD/Architecture/epics, then re-run readiness, then start W1 confidently.
- **Path B (pragmatic):** Accept the report as-is, kick off W1 with the dev-story cycle pulling from epics.md, and patch the gaps as the dev-story agent encounters them. Higher risk but shorter critical path. Not recommended — the FR-8.5.5 gap and the analytics SDK gap will both get caught uncomfortably late.
- **Path C (minimal):** Resolve only the BLOCK-LEVEL items (B1–B5) before W1; defer the HIGH-priority items into a W1 mid-sprint scope correction. Acceptable risk profile if engineering capacity is constrained at W0.

Whatever path is chosen, the FR-8.5.5 escalation discovered in Step 5 should be considered a hard prerequisite — it is the only true content gap in an otherwise dense and well-drafted plan.

---

**Report status:** Complete
**Output file:** `_bmad-output/planning-artifacts/implementation-readiness-report-2026-05-10.md`
**Generated by:** `/bmad-check-implementation-readiness` (steps 1–6)
**Date:** 2026-05-10
