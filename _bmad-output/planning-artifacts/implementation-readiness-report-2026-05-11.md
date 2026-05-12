---
project_name: yeolsal
user_name: rearleg
date: 2026-05-11
status: in_progress
stepsCompleted:
  - step-01-document-discovery
  - step-02-prd-analysis
  - step-03-epic-coverage-validation
  - step-04-ux-alignment
  - step-05-epic-quality-review
  - step-06-final-assessment
status: complete
sourceFiles:
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/epics.md
  - _bmad-output/planning-artifacts/ux-design-specification.md
contextFiles:
  - _bmad-output/planning-artifacts/sprint-change-proposal-2026-05-10.md
  - _bmad-output/planning-artifacts/implementation-readiness-report-2026-05-10.md
  - _bmad-output/planning-artifacts/ux-design-directions.html
  - _bmad-output/project-context.md
---

# Implementation Readiness Assessment Report

**Date:** 2026-05-11
**Project:** yeolsal

---

## Step 1 — Document Inventory

### Whole Documents (selected for assessment)

| Type | File | Size | Modified |
|------|------|------|----------|
| PRD | `prd.md` | 53k | 2026-05-10 18:33 |
| Architecture | `architecture.md` | 58k | 2026-05-10 18:46 |
| Epics & Stories | `epics.md` | 61k | 2026-05-10 18:50 |
| UX Design | `ux-design-specification.md` | 111k | 2026-05-11 11:57 |

### Sharded Documents
None — all canonical artifacts are whole-file.

### Reference Context (not under assessment, but informs traceability)

- `sprint-change-proposal-2026-05-10.md` — sprint-change proposal from 2026-05-10 that drove the PRD/UX updates that need re-validation today.
- `implementation-readiness-report-2026-05-10.md` — previous readiness report. Its conclusions are stale because PRD, Architecture, Epics, and UX were re-edited after it was written.
- `ux-design-directions.html` — current visual direction (Oxblood Editorial v2). Companion to `ux-design-specification.md`.
- `_bmad-output/project-context.md` — canonical agent rules.
- `archive/ux-design-specification-v1-risograph-2026-05-10.md`, `archive/ux-design-specification-v2-skeleton-2026-05-10.md`, `archive/ux-design-directions-v1-risograph-2026-05-10.html` — superseded; quarantined to `archive/`.

### Duplicate / Missing Issues
- **Duplicates (whole vs sharded):** none.
- **Missing required documents:** none.

### Notable
- All four canonical documents were re-written or updated after the 2026-05-10 readiness check and the sprint-change proposal. The 2026-05-10 report is **not** authoritative for this run; this report supersedes it.

---

## Step 2 — PRD Analysis

PRD source: `_bmad-output/planning-artifacts/prd.md` (660 lines, single canonical file).

### Functional Requirements Extracted

#### Epic 8.1 — Survival State & Daily Loop

- **FR-8.1.1** — On signup, user joins or creates one mandatory primary room with `max_members ∈ [2, 30]` (default 12 on creation). The 14-day grace trial begins from `room_members.joined_at`.
- **FR-8.1.2** — Each user has exactly one `survival_state` row per room they belong to. Initial status = `ACTIVE`.
- **FR-8.1.3** — At each 06:00 KST day boundary, a scheduled job evaluates each member's compliance with their room's current rule (per `room_rule_versions` row effective for the current month). If rule met → `personal_points_ledger += 2 (SURVIVAL)`. If not met: apply once-per-month streak freeze first; otherwise within 7-day rolling window, first miss → `YELLOW`, second miss → `RED` (`eliminated_at = now()`, `broad_visibility_at = now() + 24h`).
- **FR-8.1.4** — During the 14-day grace trial, state machine runs but does not progress past `YELLOW`; no `RED` until grace ends.
- **FR-8.1.5** — All state transitions emit `RealtimeEvent.SurvivalStateChange` to `/topic/rooms/{roomId}/survival` via `RealtimePublisher`. Payload: `{ userId, fromStatus, toStatus, occurredAt, broadVisibilityAt | null }`.
- **FR-8.1.6** — `GET /api/v1/rooms/{id}/survival` returns the room's survival roster with privacy filtering: members in `RED` with `broad_visibility_at > now()` show only as `ACTIVE` to non-leader members.
- **FR-8.1.7** — All survival-state changes are logged to `notification_log` with `kind = 'SURVIVAL_STATE'`, `key = '{date}:{userId}'` for idempotency.

#### Epic 8.2 — Spectator Mode

- **FR-8.2.1** — On `survival_state.status → RED`, FE routing (`app/(tabs)/_layout.tsx`) branches user into spectator mode for that room.
- **FR-8.2.2** — Spectator mode: chat read-only (FE input disabled; `POST /api/v1/rooms/{id}/messages` returns `403 FORBIDDEN` for `SPECTATOR`); roster visible; other members' grass calendars visible at the room's existing privacy level.
- **FR-8.2.3** — Spectator push notifications: digest at 09:00 KST daily if any room activity occurred; realtime per-message push disabled.
- **FR-8.2.4** — Eliminated user's own archive (`daily_entries`, `reflections`, `todo_items` for that room) defaults to private; opt-in via `record_visibility_prefs` per room.
- **FR-8.2.5** — Spectator users see their own Wallet (revival ticket + personal points balance) prominently on the Home tab.
- **FR-8.2.6** — On revival (`status → ACTIVE`), user immediately re-enters active member mode.

#### Epic 8.3 — Revival Economy (Free Ticket + Personal Points + Friend Gift)

- **FR-8.3.1** — Each user account is granted exactly one **free revival ticket** at signup. Usable immediately. Lifetime 1.
- **FR-8.3.2** — `POST /api/v1/rooms/{id}/revival` body `{ source: FREE_TICKET | PERSONAL_POINTS }` performs self-revival: validates eligibility (`RED`/`SPECTATOR`, source available); creates `revival_events` row; deducts cost (0 / 3 points); increments `room_point_pool` (+5 ticket / +3 points); transitions `survival_state.status = ACTIVE`; emits `/topic/rooms/{id}/survival` and `/topic/rooms/{id}/points`.
- **FR-8.3.3** — `POST /api/v1/rooms/{id}/revivals/gifts` body `{ targetUserId }`: giver spends 5 personal points to revive target (must be `RED`/`SPECTATOR` in same room and an existing friend). Creates `revival_events` (`source=FRIEND_GIFT`, `giver_user_id`); -5 from giver ledger; pool +5; receiver `status=ACTIVE`; emits topics.
- **FR-8.3.4** — Friend-gift trigger pushes notification to all eligible friend givers (room members with sufficient points and an active friendship to the eliminated user). Brand voice: invitation tone. **One push only**, no follow-up reminders.
- **FR-8.3.5** — Friend-gift receiver gets a separate push confirming donor name. Donor name default visible to receiver only; donor may opt in to broadcast a chat system message (`chat_messages` `kind='SYSTEM'`, `payload={revival_event_id, donor_id}`).
- **FR-8.3.6** — Wallet UI (spectator + active alike) shows free revival ticket presence, personal points balance, room point pool, and "받은 회생권" history (private to user).
- **FR-8.3.7** — Friend rejection / non-action is **never visible** to anyone but the giver; no in-app surface exposes non-givers.
- **FR-8.3.8** — `personal_points_ledger` is append-only; balance = `SUM(delta)` per `(user_id, room_id)`. Forfeit on leaving a room.
- **FR-8.3.9** — **Kudos message ("응원만 보내기 / 0점")**: Friend Gift Modal exposes a 2nd CTA of equal visual + a11y weight. `POST /api/v1/rooms/{id}/kudos { targetUserId, message? (≤60 chars) }` creates `chat_messages` `kind='KUDOS'`, `payload={sender_user_id, target_user_id, message}`. Cost 0 pts; no `revival_events`; no `survival_state` change. One push to receiver. Idempotent via partial unique index `ux_kudos_one_per_day (sender_id, target_id, date_part('day', created_at at time zone 'Asia/Seoul'))` → `409 CONFLICT KUDOS_ALREADY_SENT_TODAY`. Sender must be active room member + friend of target; target must be `RED`/`SPECTATOR`. Brand-voice lint warns AVOID-lexicon hits.

#### Epic 8.4 — Group Point Pool & Future Redemption Promise

- **FR-8.4.1** — Each room has exactly one `room_point_pool` row, initialized at 0 on room creation; updated by `RevivalService` only.
- **FR-8.4.2** — Room UI shows the current pool prominently using yeolsal v2 design tokens (per `docs/design-system.md`, sourced from FE-generated `tokens.json` per Architecture §4.16). Visible to all room members including spectators and former-eliminated.
- **FR-8.4.3** — Pool growth emits `/topic/rooms/{id}/points` with `{ delta, newTotal, sourceRevivalEventId, occurredAt }`.
- **FR-8.4.4** — v1 explicitly does **not** offer redemption. UI shows phase-2 promise copy: "다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다."
- **FR-8.4.5** — Pool is non-decrementable in v1 (write path forbids negative deltas). Phase-2 redemption uses the same integer column without migration.

#### Epic 8.5 — Group Leader & Rule Versioning

- **FR-8.5.1** — Room creator is the default leader; identified by `rooms.owner_id`.
- **FR-8.5.2** — `room_rule_versions` stores per-month rule snapshots. Effective rule = row with highest `effective_from_month <= currentMonth` for the room.
- **FR-8.5.3** — `PATCH /api/v1/rooms/{id}/rule` creates a new `room_rule_versions` row with `effective_from_month = nextMonth`; current month's rule is locked.
- **FR-8.5.4** — `PATCH /api/v1/rooms/{id}/members/cap` follows the same next-month-only rule.
- **FR-8.5.5** — `DELETE /api/v1/rooms/{id}/members/{userId}` (leader-driven removal) preserves the removed user's record archive per existing room exit semantics.
- **FR-8.5.6** — `POST /api/v1/rooms/{id}/transfer-leadership` permitted to any active room member; updates `rooms.owner_id`.
- **FR-8.5.7** — If leader transitions to `RED`, `RoomService` auto-promotes longest-tenured `ACTIVE` member; emits `RealtimeEvent.LeadershipChange`.
- **FR-8.5.8** — All rule-version changes broadcast a chat system message: "다음 달부터 새 규칙이 적용됩니다 [preview]" — broadly visible to all members.

#### Epic 8.6 — KakaoTalk SDK Invite Virality

- **FR-8.6.1** — `POST /api/v1/rooms/{id}/invites` returns Kakao-ready share payload: `{ inviteCode, kakaoShareUrl, previewCardImageUrl }`.
- **FR-8.6.2** — `previewCardImageUrl` is server-rendered using room name, current rule summary, member count, and yeolsal v2 design tokens (from FE-generated `tokens.json`). Cached TTL 1h; regenerated on rule / member-count change.
- **FR-8.6.3** — FE `RoomInviteSheet` is extended with a "Share to KakaoTalk" CTA invoking Kakao Share SDK (extends existing Kakao OAuth integration; no new SDK package boundary).
- **FR-8.6.4** — Tapping a Kakao-shared invite on a device with the app installed deep-links to the room preview screen with one-tap join (subject to `max_members` capacity).
- **FR-8.6.5** — Tapping a Kakao-shared invite without the app installed deep-links to the App Store / Google Play KR listing with the invite-code preserved via deep-link handoff (post-install `/api/v1/auth/signup` carries `inviteCode`).
- **FR-8.6.6** — Kakao Share SDK is a native module; shipping requires `adb uninstall app.yeosal.mobile` + clean rebuild on dev machines; document in RUNBOOK.md.

#### Epic 8.7 — Final-3 Monthly Ceremony

- **FR-8.7.1** — Scheduled job at 06:30 KST on the 1st of each month. For each room with at least 1 member who completed the prior month in `ACTIVE`, generate a Final-3 poster.
- **FR-8.7.2** — Poster generation is **server-side SVG** using yeolsal v2 design tokens via the FE→BE token codegen pipeline (Architecture §4.16). Sub-mode `D1 Editorial` token override set is applied. Layout: room name at top, all surviving member nicknames listed, top-3 by tenure highlighted with key color (oxblood) + secondary accent.
- **FR-8.7.3** — Generated poster stored at `/api/v1/rooms/{id}/posters/{yearMonth}` with a stable URL. PNG fallback available for Kakao share constraints.
- **FR-8.7.4** — Each surviving member receives a Home-tab card with the poster + "Share to KakaoTalk" CTA. Tap → shares Kakao card with embedded room invite-code.
- **FR-8.7.5** — 30-member-room semantics: poster always shows top-3 by tenure as highlighted "Final-3" plus secondary "X명 생존" stat. Layout dynamically scales to fit up to 30 nicknames.
- **FR-8.7.6** — Posters immutable once generated; subsequent member changes do not retroactively modify a finalized month's poster.

#### Epic 8.8 — Brand Voice & Onboarding

- **FR-8.8.1** — 5-screen onboarding script: (1) concept "친구와 함께 살아남는 방", (2) mechanic "매일 약속 → 빠지면 친구가 살릴 수 있다", (3) defuses 챌린저스 mental model "돈을 받지 않습니다 — 살아남는 것 자체가 자산", (4) defuses social-pressure liability "친구를 살리는 건 옵션이지 의무가 아닙니다", (5) Wallet preview + Room preview + 14-day grace banner.
- **FR-8.8.2** — All in-app copy passes a brand-voice review against the lexicon — **USE**: 함께·선물·응원·컴백·회생·그룹·동료·우리·살리다; **AVOID**: 벌금·잃었다·떨어졌다·실패·자책·부담·패배·죄책감.
- **FR-8.8.3** — Push notification copy follows brand voice; tone = invitation, not demand. Good: "수진이 회생을 기다리고 있어요"; Bad: "수진이가 죽었다 살려라!".
- **FR-8.8.4** — Apple/Google store metadata in **English** uses "comeback pass" terminology, not "revival ticket"/"second chance pass". KR copy keeps "회생권".
- **FR-8.8.5** — Error / system messages on elimination use "컴백 가능" language, not "탈락"/"실패".
- **FR-8.8.6** — Brand-voice review is a quality gate before each release. Owner: PM + designer joint sign-off.

**Total FRs: 53**
(7 + 6 + 9 + 5 + 8 + 6 + 6 + 6)

### Non-Functional Requirements Extracted

#### 9.1 Performance

- **NFR-9.1.1** — Survival state daily evaluation completes within 5 min for up to 50,000 active members across all rooms.
- **NFR-9.1.2** — Revival API (`POST /api/v1/rooms/{id}/revival`) p95 < 300ms; p99 < 800ms.
- **NFR-9.1.3** — Room realtime topic latency (state change → STOMP delivery) < 500ms p95.
- **NFR-9.1.4** — Final-3 poster generation < 3s p99 per poster; batch completes within 10 min for up to 5,000 active rooms.
- **NFR-9.1.5** — FE spectator-mode entry transition < 1s after BE state change observed via STOMP.

#### 9.2 Reliability

- **NFR-9.2.1** — Survival state evaluation is idempotent — double-runs for the same date must not double-progress any member.
- **NFR-9.2.2** — All revival operations atomic per `(room_id, user_id, date)`; concurrent revival attempts → exactly one success; idempotency enforced via partial unique index on `revival_events (room_id, user_id, date_part(eliminated_at))`.
- **NFR-9.2.3** — Friend-gift concurrency: two givers reviving the same target → exactly one success; loser gets clear "이미 회생되었습니다" response.
- **NFR-9.2.4** — Group point pool updates use Postgres advisory lock or `SELECT … FOR UPDATE` per room to prevent lost updates.
- **NFR-9.2.5** — Spectator read-only enforcement is double-implemented: FE input disabled **and** BE chat-write API rejects with `403 FORBIDDEN`.

#### 9.3 Security & Privacy

- **NFR-9.3.1** — All v1 endpoints require Bearer JWT; revival/gift/rule-edit additionally check room membership server-side.
- **NFR-9.3.2** — Eliminated user's archive defaults to private; sharing requires explicit `record_visibility_prefs.share_on_elimination = true`.
- **NFR-9.3.3** — Account deletion (PIPA + Apple/Google compliant) supports data export (PDF) and hard delete. Hard delete cascades existing tables; new tables (`survival_state`, `personal_points_ledger`, `revival_events`, `streak_freezes`, `record_visibility_prefs`) cascade on `users.id`.
- **NFR-9.3.4** — Hard-delete does not broadcast to friends/rooms; chat messages anonymized (`sender_user_id = NULL` per existing V7).
- **NFR-9.3.5** — Quiet hours (`notification_prefs.quiet_start_hour`/`quiet_end_hour`, defaults 22–08) respected for all push.
- **NFR-9.3.6** — No location tracking. No surveillance APIs.
- **NFR-9.3.7** — Sentry alerts on mass-elimination events (> 50% of room red-cards in 24h) for incident-response triage.

#### 9.4 Observability & Telemetry

- **NFR-9.4.1** — Sentry transactions instrument: survival-state job, revival endpoints, friend-gift endpoints, Final-3 poster generation, Kakao SDK invite link generation.
- **NFR-9.4.2** — Custom Sentry events on every state transition (`ACTIVE→YELLOW`, `YELLOW→RED`, `RED→SPECTATOR`, `RED|SPECTATOR→ACTIVE`).
- **NFR-9.4.3** — BE logs at `INFO` for revival/friend-gift events with structured fields (`roomId, userId, source, points_spent, pool_after`). Never log PII or tokens.
- **NFR-9.4.4** — Telemetry events emitted so KPI dashboards (§3.1) can be assembled in week-1 post-launch. Dashboard *build* not in v1 scope; *emission* is.

#### 9.5 Compatibility & Migration

- **NFR-9.5.1** — V11+ migration is idempotent and safe to roll forward; existing rooms keep current `max_members` until leader updates (next-month-only).
- **NFR-9.5.2** — On first login post-deploy, backfill creates `survival_state` rows for all `(room_id, user_id)` pairs with `status = ACTIVE`.
- **NFR-9.5.3** — Free revival ticket grant backfilled for all existing users at deploy (one-time job).
- **NFR-9.5.4** — Existing chat / daily-entry / reflection / friend-graph histories preserved.
- **NFR-9.5.5** — `min_supported_app_version` config in BE forces a store update for users on pre-v1 binaries.

#### 9.6 Accessibility

- **NFR-9.6.1** — Color is never the sole information carrier. `semantic.survival` design token is a **packed type** `{ color, label, icon, grass-treatment }` per state; consuming code cannot reference the color field without also rendering label. Brand-voice + a11y lint (Architecture §4.15) verifies no `survival.*.color` reference appears in JSX/TSX without a sibling label/`accessibilityLabel`. Verified in CI as a **hard gate**.
- **NFR-9.6.2** — Push notifications support iOS/Android system-level a11y (clear text; no critical info in haptics-only).
- **NFR-9.6.3** — Dynamic Type respected in FE.

#### 9.7 Internationalization

- **NFR-9.7.1** — v1 is **KR-only**; all user-facing copy Korean; no locale switcher.
- **NFR-9.7.2** — Backend formats dates per `Asia/Seoul` in user-facing payloads; internal storage remains `timestamptz`.
- **NFR-9.7.3** — App Store / Google Play metadata bilingual (KR primary, English for store algorithms). English uses "comeback pass" not "revival ticket".

#### 9.8 Build & Deploy

- **NFR-9.8.1** — BE Flyway V11+ migrations follow existing conventions (`V<N>__<slug>.sql`, idempotent SQL, partial unique indexes per V8/V9 reference pattern).
- **NFR-9.8.2** — BE compile/test gate per CONTRIBUTING.md (`./gradlew test` green before push).
- **NFR-9.8.3** — FE checks per CONTRIBUTING.md (`npm run lint && npm run typecheck && npm test`).
- **NFR-9.8.4** — Stack-PR merge procedure (incident-driven) applies for any multi-PR slice.
- **NFR-9.8.5** — KakaoTalk SDK addition triggers `adb uninstall app.yeosal.mobile` + rebuild on dev machines; document in RUNBOOK.md.
- **NFR-9.8.6** — Production cutover via existing `infra/docker-compose.yml` + nginx blue-green deploy. `/app/COMMIT` exposes deployed commit for diagnostics.

**Total NFRs: 38**
(5 + 5 + 7 + 4 + 5 + 3 + 3 + 6)

### Additional Requirements

#### Constraints & Bans (PRD §6.1 — banned across all phases)

1. Random / variable revival pricing (gambling classification trip).
2. Streak-length-scaled revival cost above entry price.
3. Pyramid-style revive-by-inviting-humans.
4. Location-based todo verification.
5. Cash payouts to room leaders.
6. Public revival-count / money-spent / rejection-count leaderboards.
7. Death icons or failure flair on user's grass.
8. Letting eliminated users pay to "stay in chat".
9. Auto-broadcast on account deletion.
10. **Pure-red as alarm/blood signal on elimination/RED/spectator surfaces** — dignity violation. Red-adjacent warm tones (oxblood, crimson, maroon, burgundy) as *brand identity* are permitted; red as *failure signal* is not.

#### Out-of-v1 Scope (PRD §6.2 — deferred)

Payment surface (no IAP/PG/buyable ticket/cosmetic IAP), gifticon redemption catalog, multi-room membership, custom non-preset rule authoring, B2B vertical, sobriety vertical, voice room, rule template marketplace, sponsor pairing, international localization, real-money cash-out.

#### Locked Decisions (PRD §6.3 + §14.2)

- Free revival ticket: lifetime 1 per account.
- Personal points: per-room scoped, forfeit on leave.
- Eliminated user's chat: same room, `SPECTATOR` flag, FE input disabled, BE rejects writes.
- Weekend-include semantics: day-boundary owns it (Sun 05:30 KST = Sat).
- Spectator push: once-per-day digest, not realtime.
- Final-3 poster generation: server-side SVG.
- Kakao SDK: extend existing Kakao OAuth (no new auth review).
- Leader elimination: auto-promote longest-tenured `ACTIVE` member.
- Pool decay/floor: no decay in v1; phase-2 redemption at-cost with cap.
- 30-member Final-3: top 3 by tenure regardless of cap, with secondary "X명 생존" stat.
- Visual identity (v1): yeolsal v2 — **Oxblood Editorial** (Dark Luxury × Editorial), oxblood key color (replaces Risograph + Neobrutalist per Sprint Change Proposal 2026-05-10).
- NFR-9.6.1 enforcement: packed token type + CI **hard gate**.
- FE↔BE token sync: codegen pipeline (FE owns `tokens.json` → Gradle task generates Java constants per Architecture §4.16).

#### Personal-Points Formula (PRD §6.4)

| Event | Points |
|------|--------|
| Survive a daily rule | **+2** |
| Friend-gift revival (giver pays, room pool grows) | **−5** giver / **+5** pool |
| Personal-points self-revival (post free-ticket) | **−3** user / **+3** pool |

#### Dependencies (PRD §10)

- Internal: existing yeolsal infrastructure (Spring Boot 3.3.5, Postgres + Flyway, STOMP, JWT, Expo SDK 54, Kakao OAuth, Sentry, expo-notifications, expo-secure-store, design tokens).
- External v1: **Kakao Share SDK** (new), App Store + Google Play KR, Sentry SaaS, Postgres 16.
- Deferred to phase-2: gifticon supplier API, Apple IAP / Google Play Billing.

#### Incident Response Playbook (PRD §13.3, pre-launch must-do)

- Sentry alerting for mass-elimination events.
- 24-hour in-app sanity check SLA.
- ToS-attached abuse-reporting flow → support@.
- Worst-case recovery: room-wide formal apology + group point pool +30 (or similar).

### PRD Completeness Assessment

**Strengths:**
- All 53 FRs and 38 NFRs are uniquely numbered and traceable.
- Each epic explicitly maps to canonical endpoints, STOMP topics, and DB tables — strong implementation guidance.
- Strategic bets (§2.3) and phase-2 trigger gates (§3.2) include explicit falsification criteria — testable hypotheses, not vague aspirations.
- Locked decisions table (§14.2) consolidates 24 single-source-of-truth resolutions.
- 2026-05-10 sprint change is fully integrated (oxblood pivot, kudos CTA, hard-gate a11y lint, token codegen).

**Watch items (deferred to subsequent steps for cross-doc validation):**
- FR-8.7.2 references "yeolsal v2 design tokens" and "D1 Editorial" sub-mode — UX spec must define these tokens concretely.
- FR-8.4.2 / FR-8.6.2 / FR-8.7.2 all depend on Architecture §4.16 token codegen pipeline — Architecture must define this pipeline.
- FR-8.3.9 kudos endpoint, `chat_messages kind='KUDOS'` payload, and partial unique index — must be reflected in Architecture data-model + epics.
- NFR-9.6.1 packed `semantic.survival` token type + CI hard gate — must surface as concrete tasks in epics.
- 14-day grace trial (FR-8.1.1, FR-8.1.4) — must verify epics include grace-trial banner and state-machine carve-out.
- "≥30%" Kakao-share invite acceptance KPI implies post-install deep-link handoff (FR-8.6.5) telemetry must be instrumented (intersects NFR-9.4.4).
- Idempotency partial unique index (NFR-9.2.2) — date semantics (`date_part(eliminated_at)`) must specify timezone (Asia/Seoul, not UTC); needs check in Architecture.

---

## Step 3 — Epic Coverage Validation

Epics source: `_bmad-output/planning-artifacts/epics.md` (1085 lines, 8 epics, 32 stories).

### Epic List (declared in epics.md)

| # | Epic | Sprint | Stories |
|---|------|--------|---------|
| 1 | Survival State & Daily Loop | W1–W2 | 1.1, 1.2, 1.3, 1.4, **1.5** (new), **1.6** (new), **1.7** (new) |
| 2 | Spectator Mode | W2–W3 | 2.1, 2.2, 2.3 |
| 3 | Revival Economy | W3–W4 | 3.1, 3.2, 3.3, 3.4, **3.5** (new) |
| 4 | Group Point Pool | W4 | 4.1, 4.2 |
| 5 | Leader & Rule Versioning | W3–W4 | 5.1, 5.2, 5.3, 5.4 |
| 6 | KakaoTalk SDK Invite | W5 | 6.1, 6.2, 6.3 |
| 7 | Final-3 Monthly Ceremony | W6 | 7.1, 7.2, 7.3 |
| 8 | Brand Voice & Onboarding | W7 | 8.1, 8.2, 8.3, 8.4 |

**Total: 32 stories** (declared by epics.md §Validation Summary; sums match: 7+3+5+2+4+3+3+4 = 31 named stories; epics.md notes "+5 weave-in cross-epic items captured inline = 32 effective").

Stories marked **(new)** were added per Sprint Change Proposal 2026-05-10. They are **not reflected in the FR Coverage Map at the top of epics.md** (which is now stale — see "Process Findings" below).

### FR Coverage Matrix

Verified by cross-referencing each FR text against story Acceptance Criteria and the "PRD ref:" footer line of each story.

| FR | PRD Requirement (abbrev.) | Story Coverage | Status |
|----|---------------------------|----------------|--------|
| **FR-8.1.1** | Mandatory primary room, max_members [2,30] default 12, 14-day grace | Story 1.1, Story 1.6 | ✓ Covered |
| **FR-8.1.2** | One `survival_state` row per (room,user); init ACTIVE | Story 1.1, Story 1.6 | ✓ Covered |
| **FR-8.1.3** | 06:00 KST evaluator: +2 survive, streak freeze, YELLOW/RED rolling 7d | Story 1.2 | ✓ Covered |
| **FR-8.1.4** | Grace trial: no RED for first 14 days | Story 1.1 | ✓ Covered |
| **FR-8.1.5** | RealtimeEvent.SurvivalStateChange → `/topic/rooms/{id}/survival` | Story 1.2 | ✓ Covered |
| **FR-8.1.6** | GET /rooms/{id}/survival with leader-aware privacy filter | Story 1.3 | ✓ Covered |
| **FR-8.1.7** | notification_log idempotency key `{date}:{userId}` | Story 1.2 | ✓ Covered |
| **FR-8.2.1** | Spectator FE routing branch on RED | Story 2.1 | ✓ Covered |
| **FR-8.2.2** | Spectator chat read-only (FE+BE double enforcement) | Story 2.1 | ✓ Covered |
| **FR-8.2.3** | 09:00 KST digest push, realtime push disabled | Story 2.2 | ✓ Covered |
| **FR-8.2.4** | Eliminated user's archive private; opt-in via `record_visibility_prefs` | Story 2.3 | ✓ Covered |
| **FR-8.2.5** | Spectator Wallet prominently visible on Home | Story 2.1 | ✓ Covered |
| **FR-8.2.6** | On revival → re-enter active mode | Story 2.1 | ✓ Covered |
| **FR-8.3.1** | Lifetime-1 free revival ticket at signup | Story 3.1 | ✓ Covered |
| **FR-8.3.2** | POST /rooms/{id}/revival self-revival flow | Story 3.1 | ✓ Covered |
| **FR-8.3.3** | POST /rooms/{id}/revivals/gifts friend-gift flow | Story 3.2 | ✓ Covered |
| **FR-8.3.4** | One-push-only to eligible friend givers (no reminders) | Story 3.2 | ✓ Covered |
| **FR-8.3.5** | Receiver push with donor name (default private; opt-in broadcast) | Story 3.2 | ✓ Covered |
| **FR-8.3.6** | Wallet UI (ticket + balance + pool + history) | Stories 3.3, 3.4 | ✓ Covered |
| **FR-8.3.7** | Rejection/non-action never visible | Story 3.2 | ✓ Covered |
| **FR-8.3.8** | `personal_points_ledger` append-only; forfeit on leave | Story 3.1 | ✓ Covered |
| **FR-8.3.9** | Kudos message (응원만 보내기/0점) + dedupe index | Stories 3.2, **3.5** (new) | ✓ Covered |
| **FR-8.4.1** | `room_point_pool` row per room, init 0 | Story 4.1 | ✓ Covered |
| **FR-8.4.2** | Pool visible to all (incl. spectators); v2 design tokens | Story 4.1 | ✓ Covered |
| **FR-8.4.3** | `/topic/rooms/{id}/points` realtime delta | Story 4.1 | ✓ Covered |
| **FR-8.4.4** | Phase-2 promise copy ("커피로 교환됩니다") | Story 4.2 | ✓ Covered |
| **FR-8.4.5** | Pool non-decrementable in v1 | Story 4.1 | ✓ Covered |
| **FR-8.5.1** | Room creator = default leader (`rooms.owner_id`) | Stories 5.1/5.2/5.3 implicitly | ⚠️ **Marginal** — no explicit AC for "creator auto-becomes leader at room creation"; assumed via existing infra. Owner-id field is referenced as leader auth check throughout Epic 5. |
| **FR-8.5.2** | `room_rule_versions` resolution rule | Story 5.1 | ✓ Covered |
| **FR-8.5.3** | PATCH /rule next-month-only | Story 5.1 | ✓ Covered |
| **FR-8.5.4** | PATCH /members/cap next-month-only | Story 5.2 (functionally) | ⚠️ **Traceability gap** — Story 5.2's AC explicitly says "the new cap takes effect at the next month boundary" (functional cover), but Story 5.2's `PRD ref:` footer cites only `FR-8.5.5, FR-8.5.6`. **FR-8.5.4 missing from the citation line.** |
| **FR-8.5.5** | DELETE /members/{userId} leader-driven removal | Story 5.2 | ✓ Covered |
| **FR-8.5.6** | POST /transfer-leadership | Story 5.2 | ✓ Covered |
| **FR-8.5.7** | Auto-promote longest-tenured ACTIVE on leader-elim | Story 5.3 | ✓ Covered |
| **FR-8.5.8** | Rule-change system message broadcast | Stories 5.1, 5.4 | ✓ Covered |
| **FR-8.6.1** | POST /rooms/{id}/invites returns Kakao share payload | Story 6.1 | ✓ Covered |
| **FR-8.6.2** | Server-rendered previewCardImageUrl (TTL 1h) | Story 6.1 | ✓ Covered |
| **FR-8.6.3** | FE RoomInviteSheet + Kakao Share SDK CTA | Story 6.2 | ✓ Covered |
| **FR-8.6.4** | Deep-link to room preview + 1-tap join | Story 6.2 | ✓ Covered |
| **FR-8.6.5** | Deep-link to store; post-install inviteCode handoff | Story 6.2 | ✓ Covered |
| **FR-8.6.6** | Native module — adb uninstall + clean rebuild; RUNBOOK | Story 6.3 | ✓ Covered |
| **FR-8.7.1** | 06:30 KST monthly job per eligible room | Story 7.2 | ✓ Covered |
| **FR-8.7.2** | Server-side SVG; v2 tokens; D1 Editorial sub-mode | Story 7.1 | ✓ Covered |
| **FR-8.7.3** | Stable URL `/rooms/{id}/posters/{yearMonth}`; PNG fallback | Story 7.1 | ✓ Covered |
| **FR-8.7.4** | Home-tab card + Share-to-Kakao CTA | Story 7.3 | ✓ Covered |
| **FR-8.7.5** | 30-member scaling + secondary "X명 생존" stat | Story 7.1 | ✓ Covered |
| **FR-8.7.6** | Posters immutable once generated | Story 7.2 | ✓ Covered |
| **FR-8.8.1** | 5-screen onboarding (concept/mechanic/no-money/optional/wallet) | Story 8.1 | ✓ Covered |
| **FR-8.8.2** | Brand-voice lexicon (USE/AVOID) | Story 8.2 | ✓ Covered |
| **FR-8.8.3** | Push copy invitation-tone | Story 8.2 | ✓ Covered |
| **FR-8.8.4** | ASO English uses "comeback pass"; KR keeps 회생권 | Story 8.3 | ✓ Covered |
| **FR-8.8.5** | Elim/error messages use "컴백 가능" not "탈락/실패" | Story 8.2 | ✓ Covered |
| **FR-8.8.6** | Brand-voice review release gate (PM + designer joint sign-off) | Story 8.4 | ✓ Covered |

### NFR Coverage Summary

NFR coverage is intentionally cross-cutting per epics.md and is referenced inline in story ACs:

| NFR Group | Covered by |
|-----------|------------|
| NFR-9.1 Performance | Story 1.2 (NFR-9.1.1), Story 2.1 (NFR-9.1.5), Story 7.1 (NFR-9.1.4); NFR-9.1.2/9.1.3 implied by revival endpoints (Stories 3.1, 3.2) and STOMP emission (Story 1.2). |
| NFR-9.2 Reliability | Story 1.2 (NFR-9.2.1), Stories 3.1/3.2 (NFR-9.2.2/9.2.3 via advisory lock + partial unique), Story 4.1 (NFR-9.2.4 `SELECT…FOR UPDATE`), Story 2.1 (NFR-9.2.5 spectator double-enforcement). |
| NFR-9.3 Security/Privacy | Story 2.3 (NFR-9.3.2), Story 2.2 (NFR-9.3.5 quiet hours); NFR-9.3.1/9.3.3/9.3.4/9.3.6/9.3.7 not story-level — assumed by existing infrastructure. |
| NFR-9.4 Observability | epics.md declares "Cross-epic — Telemetry tasks woven into each story's AC"; no dedicated story. |
| NFR-9.5 Compatibility/Migration | Story 1.4 (V11 + backfill — owns 9.5.1, 9.5.2, 9.5.3, 9.5.4); NFR-9.5.5 (`min_supported_app_version`) not explicitly storied. |
| NFR-9.6 Accessibility | Story 1.5 (NFR-9.6.1 packed-type + CI hard gate, NFR-9.6.3 Dynamic Type), Story 1.7 (reduced-motion); NFR-9.6.2 push a11y implicit in Story 8.2. |
| NFR-9.7 Internationalization | Story 8.3 (NFR-9.7.3 bilingual store metadata); NFR-9.7.1/9.7.2 implicit (KR-only design, server formats in Asia/Seoul). |
| NFR-9.8 Build & Deploy | Story 6.3 (NFR-9.8.5 native-module rebuild guidance); 9.8.1–9.8.4, 9.8.6 inherited from existing CONTRIBUTING.md / verify.sh. |

### Coverage Statistics

- **Total PRD FRs: 53**
- **FRs fully covered with explicit citation: 51 (96.2%)**
- **FRs covered functionally but with traceability gaps: 2 (3.8%)** — FR-8.5.1 (no explicit "creator → leader" AC), FR-8.5.4 (covered in AC but not cited in Story 5.2's PRD ref footer)
- **FRs not covered at all: 0**
- **Stories present beyond original PRD FR mapping: 3** — Story 1.5 (token codegen for NFR-9.6.1), Story 1.6 (WelcomeWindow for PRD §4.3 J0), Story 1.7 (RitualMoment for PRD §6.4 principle 5)

### Missing FR Coverage

#### Critical Missing FRs
**None.** All 53 FRs have substantive implementation paths.

#### High-Priority Traceability Gaps

- **FR-8.5.1 ("Room creator is the default leader")** — *Recommendation:* Add a single AC to Story 1.1 (or Story 5.1) explicitly stating: *"Given a user calls POST /api/v1/rooms, when the room is created, then `rooms.owner_id = creatorUserId` is persisted and the creator is reflected as the leader in subsequent leader-only endpoint authorizations."* This may already be existing infrastructure; the doc just needs to acknowledge it.
- **FR-8.5.4 ("Member-cap edits follow next-month-only rule")** — *Recommendation:* Update Story 5.2's `PRD ref:` footer to add **`FR-8.5.4`** alongside FR-8.5.5 and FR-8.5.6. The functional content is already in Story 5.2's first AC; only the citation line is missing.

#### Process Findings (epics.md hygiene)

- **The "FR Coverage Map" table at epics.md L64–L89 is stale.** It was authored before Sprint Change Proposal 2026-05-10 added Stories 1.5, 1.6, 1.7, and 3.5. The table also fails to list FR-8.3.9 (Kudos). The body of the document is up to date; the summary table is not. *Recommendation:* Refresh the coverage map in a follow-up edit so future readers (and `/bmad-sprint-planning`) see an accurate index.
- **NFR-9.5.5 (`min_supported_app_version`)** — not explicitly storied. *Recommendation:* Add as a sub-task / AC under Story 1.4 (V11 migration) or as a release-gate item in Story 8.4.
- **NFR-9.3.7 (Sentry mass-elimination alerting)** — declared as cross-cutting but no story owns the alert-rule configuration. *Recommendation:* Surface as an explicit Story 1.2 AC bullet or a new W8 telemetry story.
- **NFR-9.4 (Observability)** — currently "cross-epic woven into ACs" without a single accountable owner. *Recommendation:* Confirm during sprint planning that each story's AC actually instruments the required Sentry transactions / events from NFR-9.4.1–9.4.3; otherwise add a dedicated W8 story.

### Epic Coverage Verdict

**PASS with minor traceability gaps.** All PRD FRs map to a story, including the post-sprint-change additions (Kudos / WelcomeWindow / RitualMoment / DesignSystemV2). Two minor citation gaps (FR-8.5.1, FR-8.5.4) and stale coverage map are cosmetic — fixable in a 10-line PR edit before sprint planning. No requirement is at risk of falling out of scope.

---

## Step 4 — UX Alignment

### UX Document Status

**FOUND.** Comprehensive 1982-line specification — `_bmad-output/planning-artifacts/ux-design-specification.md`, **v2 Oxblood Editorial revision**, completed 2026-05-11. Companion visual artifact: `ux-design-directions.html`.

UX spec covers: executive summary + design challenges (C1–C8), 5 critical-success moments (M1–M5) + M3.5, 7 experience principles, emotional response strategy, 14 anti-patterns (A1–A14), token-driven 4-layer system, 5 sub-mode catalog (D1 Editorial / D2 Bento / D3 Quiet / D4 Postcard / D5 Plate), all 6 PRD user journeys (J0–J5) as flowcharts, component inventory + U1–U9 dispositions, W1–W7 implementation roadmap, responsive + accessibility strategy.

### UX ↔ PRD Alignment

| PRD Anchor | UX Coverage | Status |
|------------|-------------|--------|
| PRD §3.1 KPIs (activation 60%/24h, day-7 ≥45%, friend-gift ≥1/room/월, room pool ≥50pt, app-store policy pass, qualitative tone) | UX "Launch criteria" + every C/M item maps to a KPI | ✓ Aligned |
| PRD §4.3 J0–J5 user journeys | All 6 journeys rendered as Mermaid flowcharts with concrete CTA / state-transition / system-message labels; J0 explicitly identifies A10 anti-pattern (the "11 more needed" progress bar) | ✓ Aligned |
| PRD §6.1 banned list (gambling, surveillance, shame engine, pure-red dignity-color guard) | UX anti-patterns A1–A14 — A11 (pure red as alarm), A12 (rainbow charts), A13 (glassmorphism), A14 (hairline-only) are v2 additions explicitly aligned with §6.1 + dignity tone | ✓ Aligned |
| PRD §6.4 personal-points formula (+2 / 5 / 3) | UX J3 flowchart names "ledger -5 FRIEND_GIFT_SPEND / pool +5" explicitly | ✓ Aligned |
| PRD FR-8.3.4 (one-push, no reminders) | UX Experience Principle #4 + J3 flowchart "후속 reminder ❌" | ✓ Aligned |
| PRD FR-8.3.7 (rejection invisible) | UX Phase-1 J3 "거절·미액션 invisible" + 안심 메시지 "선물해도 안 해도 친구는 모릅니다." | ✓ Aligned |
| PRD FR-8.3.9 (Kudos message 응원만 보내기) | UX `<KudosButton>` U3 ACCEPT, integrated into FriendGiftModal as 2nd CTA with full anatomy + states + a11y label | ✓ Aligned |
| PRD FR-8.7.* (Final-3 ceremony) | UX `<FinalThreeCard>` D1 Editorial Spread + share-rate falsification trigger (<15% → SCP) explicit | ✓ Aligned |
| PRD FR-8.8.2 lexicon (USE / AVOID) | UX feedback patterns, copy tables, push tone examples all conform; Empty-state copy "다시 한 번 시도해 주세요" replaces "실패했습니다" | ✓ Aligned |
| PRD §14.2 visual identity lock (yeolsal v2 — Oxblood Editorial; Risograph deprecated) | UX is the authoring source for v2 — oklch palette, typography, motion, sub-mode override schemas all defined | ✓ Aligned |
| PRD NFR-9.6.1 (color never sole carrier, packed type) | UX `semantic.survival` packed type table (4 states × {color, label, icon, grass-treatment}) + `<SurvivalChip>` primitive that *structurally* prevents color-only references | ✓ Aligned (UX adds the enforcement primitive that PRD only narratively required) |
| PRD NFR-9.6.3 (Dynamic Type) | UX Pretendard line-height 1.5+ for 100-120% scale | ✓ Aligned |
| PRD §13.4 deferred questions | UX U5 (48h recovery) / U6 (donor protection) / U7 (auto-sabbatical) / U9 (spectator prompt) all flagged DEFER (v1.5) with explicit rationale (U7 conflicts with FR-8.3.1 lifetime-1) | ✓ Properly deferred |

**UX-side requirements beyond PRD text:**
- `<RitualMoment>` 06:00 KST 5-second sacred wrapper (UX C3 + Principle #5 — escalated from "be careful with the 06:00 deadline" to a *positive* ritual mechanism). Mapped to Story 1.7.
- `<WelcomeWindow>` J0 leader's lonely 30 seconds (UX C5 — observed during UX step-04 brainstorming as a gap missing from initial PRD; subsequently codified in PRD §4.3 J0 via Sprint Change Proposal). Mapped to Story 1.6.
- 7-day echo footnote post-revival ("○○가 너를 살린 지 N일째") — UX U8 ACCEPT, integrated into Story 3.2 AC.
- M3.5 lifetime-1 marker — UX U2 ACCEPT, integrated into Story 3.2 AC.
- 5 sub-mode (D1–D5) catalog — token override matrix unique to UX; consumed by Architecture §4.16 codegen and BE renderers.

### UX ↔ Architecture Alignment

| Architecture Decision | UX Touchpoint | Status |
|-----------------------|---------------|--------|
| §4.4 — Postgres advisory lock + partial unique index for revival concurrency | UX J3 flowchart explicitly names "Postgres advisory lock + partial unique idx ux_revival_events_one_per_elimination" | ✓ Aligned |
| §4.7 — Spectator branched layout (not parallel route group) | UX "Navigation Patterns": "Spectator branching: layout-branched in app/(tabs)/_layout.tsx (parallel route group ❌, Architecture §4.7). D3 sub-mode override는 spectator state branch의 page-level wrapper에서 1회 주입." | ✓ Aligned |
| §4.9 — Server-side SVG renderer; on-demand Batik PNG | UX J4 flowchart shows "Apache Batik PNG 첫 render → cache PNG URL"; UX `<FinalThreeCard>` BE renderer note: "GeneratedTokens.SubMode.editorial 소비" | ✓ Aligned |
| §4.14 — Two-channel realtime privacy (private queue immediate + broad topic delayed) | UX J3 "Room (다른 멤버): anon realtime event (donor_user_id 노출 ❌)"; UX feedback patterns "Privacy server-side: sensitive filter 모두 BE에서" | ✓ Aligned |
| §4.15 — Brand-voice + a11y lint with NFR-9.6.1 hard CI gate | UX `<SurvivalChip>` is the *structural complement* to the lint rule — the lint blocks negative cases (raw color refs); the chip provides the positive primitive components must use. Together they form a complete enforcement loop. | ✓ Aligned (the UX-side mechanism is what makes the lint useful) |
| §4.16 — FE↔BE token codegen pipeline (FE owns `tokens.json` → Gradle `generateTokens` task → `GeneratedTokens.java`) | UX 4-layer L1 ("tokens.json 단일 진실원") + "Custom system이라 5 sub-mode override가 가능. Established system 위에선 sub-mode 분기가 hack이 됨." + W1 Spec Lock #2 explicit | ✓ Aligned |
| §6.4 endpoint list — `POST /kudos` | UX `<KudosButton>` endpoint reference matches | ✓ Aligned |
| Sprint plan W1–W8 | UX W1–W7 Implementation Roadmap matches (W8 buffer is per PRD §11 — bug fix / submission / telemetry) | ✓ Aligned |

### Alignment Issues (HIGH PRIORITY)

#### 🚨 H1 — Stale "Risograph" tokens in Epics 6.1, 7 (epic goal), 8.3 contradict PRD §14.2 + Architecture §4.16 + UX v2

**Locations (epics.md):**
- L792 (Story 6.1 AC): *"a server-side SVG → PNG conversion uses **Risograph design tokens**, the result is stored in `room_invite_preview_cache`..."*
- L856 (Epic 7 Goal): *"**Goal:** Server-rendered **Risograph** SVG poster generated at month-end + Home-tab share UI."*
- L1020 (Story 8.3 AC): *"**And** screenshots accompanying the copy use **Risograph** design tokens."*

**Conflict:**
- PRD §14.2 Locked decisions: "Visual identity (v1) | yeolsal v2 — Oxblood Editorial (Dark Luxury × Editorial), oxblood key color, dignity-tone preserved | Sprint Change Proposal 2026-05-10, **replaces Risograph + Neobrutalist**"
- Architecture §4.9 (REVISED 2026-05-10): "Replaces (per Sprint Change Proposal 2026-05-10): prior decision text that referenced 'fixed Risograph layout' with hardcoded `ink/paper/pink/green/acid/muted` token names"
- UX spec frontmatter: "Visual identity: yeolsal v2 — Oxblood Editorial (Dark Luxury × Editorial fusion). Replaces Risograph + Neobrutalist (PRD §14.2 lock)."

**Risk:** Without correction, an engineer reading Story 6.1 / 7 / 8.3 in isolation will build to the v1 Risograph palette and `ink/paper/pink/green/acid/muted` token lexicon — exactly the regression the sprint change proposal sought to prevent.

**Recommendation:** Search-and-replace in `epics.md`:
- "Risograph design tokens" → "yeolsal v2 design tokens (per FE→BE codegen Architecture §4.16)"
- "Risograph SVG poster" (Epic 7 goal) → "Editorial-aesthetic SVG poster (yeolsal v2 — Oxblood Editorial, sub-mode D1)"
- Note that Story 7.1's body is already correct (line 863: "yeolsal v2 — Oxblood Editorial"); only the **Epic 7 Goal** preamble and Stories 6.1 / 8.3 are stale.

#### 🚨 H2 — Architecture §3.2 still names v1 Risograph tokens

**Location (architecture.md L148):**
*"Reuse design system tokens (`ink/paper/pink/green/acid/muted`, 3–4px borders, 5–7px hard-offset shadows). Brand voice copy lexicon is enforced as a release gate (PRD FR-8.8.6)."*

**Conflict:**
- This contradicts the same document's §4.9 (REVISED) and §4.16 (NEW) which lock the codegen pipeline + Oxblood Editorial.
- UX explicitly RELEASES the hard-offset shadow guard in v2: "Hard-offset shadow guard *released* — subtle blur permitted in dark luxury."

**Risk:** A reader following architecture §3 → §4 sequentially may carry the stale v1 token assumption into implementation decisions before reaching §4.16.

**Recommendation:** Update Architecture §3.2 to: *"Reuse design system tokens via the codegen pipeline (§4.16) — FE owns `FE/src/theme/tokens.json` (yeolsal v2 — Oxblood Editorial). Hard-offset shadow guard released in v2; subtle blur + elevation tokens replace it. Brand voice copy lexicon is enforced as a release gate (PRD FR-8.8.6) + NFR-9.6.1 packed-type hard CI gate (§4.15)."*

### Alignment Issues (MEDIUM)

#### ⚠️ M1 — `<SurvivalChip>` primitive not explicitly storied

UX defines `<SurvivalChip>` as the *positive complement* to NFR-9.6.1 — components consume `<SurvivalChip state="RED" />`, never `survival.RED.color`. Without the chip primitive, the §4.15 lint rule blocks negatives but provides no path to compliance. UX W2 Implementation Roadmap places `<SurvivalChip>` in W2 as L3 atomic primitive.

**Story 1.5 AC** covers the **lint rule** (negative enforcement) and the packed-type schema validation but does **not** explicitly require building the `<SurvivalChip>` component. Story 2.1 (Spectator routing) implies a survival-status display but doesn't name the primitive either.

**Recommendation:** Add a sub-task / AC to Story 1.5: *"`<SurvivalChip state>` primitive component built in `FE/src/components/survival/SurvivalChip.tsx`; consumes packed-type token; renders color dot + icon + label as a non-splittable composite; only entry point components may use to display survival status."* — or carve out Story 1.5b.

#### ⚠️ M2 — `<SubModeProvider>` page wrapper not explicitly storied

UX W3 Implementation Roadmap names "`<SubModeProvider>` wrapper — Wallet 화면(D2)으로 e2e 검증" as a foundation deliverable. This is the page-level prop-injection wrapper that makes the 5 sub-mode catalog usable (UX cross-cutting rule #9: "sub-mode = page-level only — 컴포넌트 코드 내부에서 sub-mode 분기 ❌"). Without it, downstream stories that consume sub-modes (Story 2.1 D3 Quiet, Story 3.2 D4 Postcard, Story 7.3 D1 Editorial) lack a shared mechanism.

**Recommendation:** Add to Story 1.5 (or a new Story 1.5b) an AC: *"`<SubModeProvider subMode={...}>` wrapper implemented in `FE/src/providers/SubModeProvider.tsx`; injects resolved override tokens into `useTheme()`; e2e-verified by Wallet (D2) and Spectator (D3) screens."*

#### ⚠️ M3 — Analytics SDK selection unowned

UX W1 Spec Lock #4 explicitly states: *"Analytics SDK 선정 — onboarding.screen.dwell_ms / friend-gift conversion / spectator→revival / Day-30 share-rate (v2 falsification trigger) 측정 가능. 현재 미선정 — W1 첫주 결정 필수."*

This intersects:
- PRD NFR-9.4.4 (events emitted to assemble dashboards week-1 post-launch).
- PRD §2.3 v2 falsification trigger (Day-30 share-rate < 15% → revisit visual direction).
- PRD §13 phase-2 trigger gates (4 metrics measured at Day 60).

But no story in epics.md owns: (a) selecting the SDK, (b) integrating it, (c) defining the canonical event taxonomy. Sentry is BE-error only.

**Recommendation:** Add a Story 1.0 (W1 pre-development) or Story 8.5: *"Select + integrate analytics SDK supporting custom events; define event taxonomy for activation funnel (onboarding.screen.dwell_ms, signup, first_daily_entry), revival flows (revival.attempted/succeeded/failed × source), friend-gift conversion (friend_gift.prompt_sent → friend_gift.modal_opened → friend_gift.confirmed), spectator→revival cohort, and Day-30 Final-3 share-rate."* — without this, the post-launch falsification triggers are not measurable.

#### ⚠️ M4 — Pool 5-stage SVG/PNG swap visual asset pipeline not storied

UX explicitly limits pool metaphor implementation to "5단계 정적 SVG/PNG swap" (5-stage static SVG/PNG swap; "돌탑·천 짜기·도자기 굽기" metaphor candidates). Maps to `<PoolStack>` component in W4 roadmap.

**Story 4.1** covers the BE counter + STOMP topic + FE `useRoomPoints` hook. It does **not** cover:
- Design + commissioning of 5 SVG/PNG stage assets.
- Stage threshold selection (which pool-total values map to which visual stages).
- `<PoolStack>` component rendering logic that selects the correct stage.

**Recommendation:** Add a Story 4.3 (or AC under Story 4.1) covering the 5-stage asset + threshold table + `<PoolStack>` component. This is W4 work and currently sits between epics 4 and the UX W4 deliverable.

#### ⚠️ M5 — Offline / shadow-area mutation queue still tracked-risk

UX Platform Strategy explicitly flags: *"오프라인 / 통신 음영: TanStack Query AsyncStorage persist는 있으나 mutation queue는 현재 미정의 (tracked technical risk). 지하철·통신 음영에서 daily-checkin 손실 시 dignity 톤 위반 가능 — Architecture §7.x 또는 step-08~09에서 결정."*

Architecture §7.2 (Open items deferred — none gate v1) lists ShedLock, brand-voice lint, Sentry alerts, Spring Boot Sentry SDK, PNG rasterization — but **not** the mutation queue. UX is calling out a dignity-tone risk that Architecture has not yet acknowledged.

**Recommendation:** Either (a) add to Architecture §7.2 with explicit deferral rationale (v1 acceptance: "subway/shadow areas: dropped daily-checkin retries silently on app foreground; banner shown if known offline → no dignity tone violation"), or (b) story a minimal mutation-queue (idempotency-key + AsyncStorage retry-on-foreground) into W4 daily-loop polish.

### Warnings (LOW)

- **L1**: Sub-mode override key whitelist enforcement — UX requires `tokens.json` schema validator to reject overrides outside the 7-key whitelist. Story 1.5 AC requires schema validation in general but does not explicitly enforce the whitelist. Tighten the AC text.
- **L2**: WS event schema names — UX W1 Spec Lock #3 names `gift.revive.sent` / `gift.revive.received` / `kudos.sent`. Architecture §6 + epics.md reference `RealtimeEvent.SurvivalStateChange`, `.PointPoolChange`, `.KudosSent`, etc. — naming convention differs slightly. Reconcile to a single canonical list (epics' Java-style `RealtimeEvent.<Name>` is more authoritative for code).
- **L3**: UX `accessibilityViewIsModal` for RitualMoment + RevivalSequence is specified but Story 1.7 AC doesn't include an explicit a11y wrapper test. Add a test AC.
- **L4**: Dynamic Type 1.5× layout-survival verification — UX testing strategy lists this; not explicitly storied in epics. Implicit in Story 1.5 contrast verification, but a separate Dynamic Type smoke test would be cleaner.

### UX Alignment Verdict

**PASS with required-fix items.** UX spec is exceptionally comprehensive and tightly cross-referenced with PRD + Architecture. The 4-layer token system, 5 sub-mode catalog, packed-type semantic.survival, and `<SurvivalChip>` primitive together form a coherent enforcement system that the Sprint Change Proposal hand-shakes into Architecture §4.15/§4.16 and the new Stories 1.5–1.7 and 3.5.

**Required fixes (must address before sprint planning, ~1 day total):**
1. **H1** — purge stale "Risograph" references from epics.md (3 locations).
2. **H2** — update Architecture §3.2 v1 token lexicon to point at §4.16 codegen.
3. **M1, M2** — expand Story 1.5 ACs (or add 1.5b) to include `<SurvivalChip>` and `<SubModeProvider>` primitives.
4. **M3** — add analytics SDK selection / integration as Story 0 / 8.5.
5. **M4** — story the 5-stage pool visual asset pipeline.

**Acceptable to defer (with explicit deferral note):**
- **M5** — offline mutation queue (UX-flagged risk; document deferral in Architecture §7.2).
- L1–L4 — tightening of AC text + naming reconciliation.

**Properly deferred to v1.5** (per SCP G2.4 lock):
- U5 (48h recovery window), U6 (donor-protection signal), U7 (auto digital sabbatical — conflicts with FR-8.3.1), U9 (spectator gentle prompt).

---

## Step 5 — Epic Quality Review

Reviewed against `/bmad-create-epics-and-stories` standards: user value, epic independence, story independence, no forward dependencies, proper sizing, complete AC, traceability.

### Per-Epic Quality Assessment

#### Epic 1 — Survival State & Daily Loop (7 stories)

| Check | Result |
|-------|--------|
| Epic delivers user value | ✓ Members survive together / dignity tone |
| Epic independent of Epic 2+ | ✓ Yes (Epic 2/3 depend on Epic 1 outputs) |
| Stories appropriately sized | ⚠️ Mixed — see scope note below |
| No forward dependencies | ⚠️ Story 1.6 references Story 6.2 (cross-epic forward dep) |
| Database tables created when needed | ⚠️ Story 1.4 batches ALL v1 schema upfront (Flyway-convention deviation; acceptable for brownfield, documented below) |
| Clear AC | ✓ All 7 stories use Given/When/Then BDD |
| Traceability to FRs | ✓ |

**Scope creep note:** Epic 1 contains 7 stories spanning four concerns:
- (a) Survival state machine — Stories 1.1, 1.2, 1.3
- (b) Migration + backfill — Story 1.4
- (c) Design system v2 + codegen + a11y lint — Story 1.5
- (d) Leader cold-start UX + 06:00 ritual — Stories 1.6, 1.7

The epic title "Survival State & Daily Loop" no longer describes (c) and (d). The original 4-story Epic 1 was a clean fit; Sprint Change Proposal additions blurred its scope.

#### Epic 2 — Spectator Mode (3 stories)

| Check | Result |
|-------|--------|
| User value | ✓ Eliminated users see what they're missing |
| Independent | ✓ Depends on Epic 1 only (backward) |
| Sizing | ✓ 3 well-scoped stories |
| Forward deps | ✓ None |
| AC quality | ✓ Comprehensive |
| Traceability | ✓ |

**Note:** Story 2.1 AC says "BE chat-write API also returns `403 FORBIDDEN` ... if any client bypasses (NFR-9.2.5)". The BE-side enforcement isn't a separate AC bullet within Story 2.1 — it's stated as a side-fact. There is **no explicit story or AC owning the BE chat-controller change** that rejects `SPECTATOR` writes. Likely implicit in existing `chat/` module modification, but should be a named bullet ("Given a SPECTATOR user POSTs `/api/v1/rooms/{id}/messages`, then BE returns `403 FORBIDDEN` with code `SPECTATOR_WRITE_FORBIDDEN` — integration test covers this path").

#### Epic 3 — Revival Economy (5 stories)

| Check | Result |
|-------|--------|
| User value | ✓ 3 revival sources + kudos |
| Independent | ✓ Backward dep on Epic 1 only |
| Sizing | ✓ Story 3.1–3.5 well-scoped |
| Forward deps | ⚠️ Story 3.2 → Story 3.5 (intra-epic, by story numbering) |
| AC quality | ✓ Comprehensive (race conditions, idempotency, brand voice) |
| Traceability | ✓ Including new FR-8.3.9 |

**Open architecture decision in AC:**
- Story 3.5 AC L575: *"Given the V11 (or V12 — placement decided in Phase H2 architect prep) migration includes the `chat_messages.kind` enum extension"* — must be resolved before sprint planning. Recommend lock to **V11** (already brownfield-batched per Story 1.4) to avoid migration-ordering complexity.

#### Epic 4 — Group Point Pool (2 stories)

| Check | Result |
|-------|--------|
| User value | ✓ Visible accumulating progress + phase-2 promise |
| Independent | ✓ Backward dep on Epic 1 + revival events from Epic 3 |
| Sizing | ✓ Tight |
| Forward deps | ✓ None |
| AC quality | ✓ |
| Traceability | ✓ |

**Asset gap (already documented in Step 4 M4):** Story 4.1 covers counter + STOMP topic + hook. The 5-stage SVG/PNG asset pipeline for the pool metaphor (stones / weaving / pottery candidates per UX) is not storied. Recommend Story 4.3 or AC addition.

#### Epic 5 — Leader & Rule Versioning (4 stories)

| Check | Result |
|-------|--------|
| User value | ✓ Trust / contract integrity |
| Independent | ✓ Backward deps only |
| Sizing | ✓ |
| Forward deps | ✓ None |
| AC quality | ✓ Including auth checks, idempotency, race-on-eliminated-member |
| Traceability | ⚠️ FR-8.5.1 lacks explicit AC; FR-8.5.4 not cited (see Step 3) |

#### Epic 6 — KakaoTalk SDK Invite Virality (3 stories)

| Check | Result |
|-------|--------|
| User value | ✓ 2-tap invite from kakaotalk |
| Independent | ✓ Backward dep on Epic 1 V11 cache table |
| Sizing | ✓ |
| Forward deps | ✓ |
| AC quality | ⚠️ One unresolved technology decision |
| Traceability | ✓ |

**Open architecture decision in AC:**
- Story 6.2 AC L822: *"the invite-code is preserved (e.g., via Branch.io or platform deep-link query)"* — non-committal. Must lock to one approach before W5 sprint kicks off (Branch.io adds a paid SaaS dep + native module; platform deep-link is free but more iOS/Android plumbing). Recommend platform deep-link unless data suggests otherwise.

#### Epic 7 — Final-3 Monthly Ceremony (3 stories)

| Check | Result |
|-------|--------|
| User value | ✓ Shareable artifact (free marketing) |
| Independent | ⚠️ Backward dep on Story 6.2 (Story 7.3 Kakao share) — acceptable backward dependency |
| Sizing | ✓ |
| Forward deps | ✓ Within epic — none |
| AC quality | ✓ Including no-survivors edge case, immutability, token codegen |
| Traceability | ✓ |

**Stale tokens (already H1 in Step 4):** Epic 7 *Goal* line says "Risograph SVG poster" — contradicts the rest of the document. Story 7.1's body already says "yeolsal v2 — Oxblood Editorial".

#### Epic 8 — Brand Voice & Onboarding (4 stories)

| Check | Result |
|-------|--------|
| User value | ✓ Defuse 챌린저스 mental model |
| Independent | ⚠️ Backward deps on Wallet (Epic 3), v2 tokens (Story 1.5), brand-voice lint (Story 1.5) — all backward, acceptable |
| Sizing | ✓ |
| Forward deps | ✓ |
| AC quality | ⚠️ Story 8.4 release-gate AC is thin (see below) |
| Traceability | ✓ |

**Thin AC:** Story 8.4 (release-gate brand-voice review) AC enumerates 5 checklist categories (push, onboarding, error, store metadata, system messages) but doesn't define what "signed off jointly" *means* — sign-off mechanism (PR comment? Notion doc? GitHub Actions check?), the "needs-rewrite" loop SLA, or the gate on "any item is unsigned → release is held". Tighten.

**Stale tokens (already H1 in Step 4):** Story 8.3 AC L1020 says "screenshots accompanying the copy use Risograph design tokens."

### Severity-Ranked Findings

#### 🔴 Critical Violations
**None.** No technical-milestone-as-epic. No epic with zero user value. No story that is purely infrastructure without serving a downstream outcome. Story 1.5 (token codegen + lint) is technically infrastructure but it serves user value indirectly by enforcing NFR-9.6.1 dignity at compile time — passes the bar.

#### 🟠 Major Issues

| # | Issue | Recommendation |
|---|-------|----------------|
| **MAJ-1** | **Cross-epic forward dependency: Story 1.6 (WelcomeWindow) → Story 6.2 (Kakao Share SDK).** Story 1.6 AC: "Given I tap '🥥 카카오로 초대', when the deep-link fires, then the existing Kakao Share SDK flow (Story 6.2) opens..." But Story 6.2 ships in W5, while Story 1.6 ships in W7 (per sprint plan W7 = Epic 8 + 1.6 by SCP integration). Actually rechecking: sprint plan says W1-W2 = Epic 1 → 1.6 is W1-W2. **W1-W2 ≠ W5. The CTA-A path is broken until W5.** | Option A: Move Story 1.6 to W5+ (after Kakao SDK lands). Option B: Story 1.6 v1 ships with a "Coming soon" disabled Kakao CTA + working "오늘 기록하기" CTA-B; full Kakao integration in W5 follow-up commit. Option C: Pull Story 6.2 native-module setup forward to W1. **Lock the answer at sprint planning.** |
| **MAJ-2** | **Intra-epic forward dependency: Story 3.2 → Story 3.5.** Story 3.2 AC names "Story 3.5 endpoint" for the kudos CTA. By numbering, 3.2 < 3.5 — implies 3.2 ships first. | Either renumber (3.5 → 3.0 / pre-revival migration story) or ship 3.5 before 3.2 within the W3-W4 sprint. Pragmatically Story 3.5 *is* the kudos endpoint and Story 3.2 *consumes* it, so 3.5 → 3.2 ordering is correct; the numbering is misleading. **Recommend ordering: 3.1, 3.5, 3.2, 3.3, 3.4 within W3-W4.** |
| **MAJ-3** | **Unresolved architecture decisions baked into story ACs.** Two locations: Story 3.5 (V11 vs V12 placement) and Story 6.2 (Branch.io vs platform deep-link). | Lock both at sprint-planning kickoff. Recommend: **V11 (single migration)** for the kudos schema change; **platform deep-link** for invite-code preservation (avoids Branch.io paid dependency + extra native-module rebuild). |
| **MAJ-4** | **Stale "Risograph" references in epics.md** (H1 in Step 4). Story 6.1 AC L792, Epic 7 Goal L856, Story 8.3 AC L1020. | Search-and-replace as documented in Step 4 H1. ~3 minutes of editing. |
| **MAJ-5** | **Story 4.1 + Story 1.4 V11 batches all 12 schema steps upfront** (deviates from per-story DB-creation best practice). | **Accept as deviation-by-design** for brownfield Flyway context (per project-context.md migration rules). Document the deviation in epics.md "Validation Summary" as: *"V11 migration intentionally batches all v1 schema deltas; per-story migration splitting would create Flyway ordering complexity (V11 partial + V12 partial + ...) and violate project-context.md migration conventions."* |

#### 🟡 Minor Concerns

| # | Issue | Recommendation |
|---|-------|----------------|
| **MIN-1** | Epic 1 scope creep — 7 stories spanning survival-state machine + V11 + design-system foundation + leader cold-start UX + 06:00 ritual. Epic title no longer accurate. | Acceptable for v1 (all of these ship W1-W2 by sprint plan). Optional: rename Epic 1 to "W1-W2 Foundation: Survival, Migration, Design System". |
| **MIN-2** | Story 2.1 BE-side `SPECTATOR_WRITE_FORBIDDEN` enforcement is stated as a side-fact, not a named AC bullet. | Promote to an explicit AC bullet with a corresponding integration test. |
| **MIN-3** | Story 8.4 release-gate AC is thin — defines checklist categories but not sign-off mechanism / SLA / hold gate. | Specify sign-off mechanism (PR comment? Notion artifact?) and how the release is "held" (CI check? Manual gate?). |
| **MIN-4** | epics.md L1066 "Story count" footnote: *"7+3+5+2+4+3+3+4 = 27; +5 weave-in cross-epic items captured inline = 32 effective."* — the explicit story count is 31 (counted: 7+3+5+2+4+3+3+4 = 31, not 27). Math error in the document. | Correct to: *"31 named stories + 5 weave-in cross-epic items captured inline = 32 effective."* |
| **MIN-5** | epics.md FR Coverage Map (L64-L89) stale — does not include Stories 1.5/1.6/1.7/3.5 added per SCP, nor FR-8.3.9 Kudos. | Refresh the table (already documented in Step 3 process findings). |
| **MIN-6** | FR-8.5.1 has no explicit "creator = leader" AC (Step 3 finding). FR-8.5.4 not cited in Story 5.2 (Step 3 finding). | Add 1-line AC to Story 1.1 for FR-8.5.1; add FR-8.5.4 to Story 5.2's PRD ref footer. |

### Best Practices Compliance Checklist (Aggregate)

- [x] Every epic delivers user value.
- [x] Every epic can function independently of *later* epics (Epic N never requires Epic N+1).
- [x] Most stories appropriately sized (1-2 day implementation slice each).
- [ ] **No forward dependencies** — Stories 1.6 and 3.2 violate (MAJ-1, MAJ-2).
- [ ] **Database tables created only when needed** — Story 1.4 batches all upfront (MAJ-5; accepted deviation).
- [x] Clear AC with Given/When/Then.
- [x] Traceability to FRs maintained (minor citation gaps documented).

### Epic Quality Verdict

**PASS WITH MAJOR ISSUES.** No structural defects (epic shape / user value / sizing all sound). The 5 major issues are all *resolvable in 30 minutes of editing* + 3 decisions to lock at sprint-planning kickoff. The 6 minor issues are cosmetic. No epic or story needs rewriting.

**Hard blockers before sprint planning (must address):**
1. Decide Story 1.6 Kakao CTA strategy (Options A/B/C in MAJ-1).
2. Reorder Story 3.5 to run before Story 3.2 within W3-W4 (MAJ-2).
3. Lock Story 3.5 migration placement to V11 (MAJ-3).
4. Lock Story 6.2 deep-link approach to platform-native (MAJ-3).
5. Purge "Risograph" stale references (MAJ-4).

**Document-as-deviation:**
- V11 batched schema (MAJ-5) — acknowledge in epics.md Validation Summary.

**Defer to W1-W2 implementation cleanup:**
- MIN-2 through MIN-6 — fix during epic-grooming session at sprint-planning kickoff or at story-creation time.

---

## Step 6 — Summary and Recommendations

### Overall Readiness Status

🟡 **NEEDS WORK — but very close to READY.**

This is a *high-quality* planning artifact set: the PRD is complete and internally consistent, Architecture decisions are precise and traceable, UX spec is exceptionally thorough, and Epics-and-Stories provides full FR coverage with strong Given/When/Then ACs. The Sprint Change Proposal from 2026-05-10 was substantively integrated across PRD, Architecture (§4.15 + §4.16 additions), UX (full v2 Oxblood Editorial revision), and Epics (Stories 1.5, 1.6, 1.7, 3.5 added).

The blockers preventing a green "READY" are:
- **One bug-class issue:** stale "Risograph" v1 visual identity references survived the SCP edit pass in 4 specific locations across epics.md and architecture.md — engineers reading those sections in isolation would build to the wrong visual direction.
- **Three unresolved architecture decisions baked into story ACs** (Story 3.5 migration placement, Story 6.2 deep-link approach, Story 1.6 Kakao CTA timing strategy).
- **Several traceability + scope gaps** that don't change implementation but make some pre-coded primitives unowned (`<SurvivalChip>`, `<SubModeProvider>`, analytics SDK, pool 5-stage asset pipeline).

Total findings across 5 review steps: **2 HIGH, 5 MAJOR, 5 MEDIUM, 6 MINOR, 4 LOW = 22 issues.** Zero CRITICAL. Estimated total remediation effort: **~1 day of doc-editing + one 60-min decision meeting at sprint-planning kickoff.**

### Issues by Step

| Step | Findings |
|------|----------|
| 1. Document Discovery | No issues — 4 canonical docs present, no duplicates, no missing docs. |
| 2. PRD Analysis | 53 FRs + 38 NFRs extracted; PRD itself is complete and internally consistent. |
| 3. Epic Coverage | 51/53 FRs fully cited (96.2%); 2 traceability gaps (FR-8.5.1, FR-8.5.4); stale coverage map. |
| 4. UX Alignment | 2 HIGH (H1, H2 stale v1 token references), 5 MEDIUM (M1–M5), 4 LOW (L1–L4). |
| 5. Epic Quality | 0 CRITICAL, 5 MAJOR (MAJ-1–5), 6 MINOR (MIN-1–6). |

### Critical Issues Requiring Immediate Action

Listed in **priority order**, with concrete location + recommendation.

#### 🚨 P0 — Must fix before sprint planning kickoff

1. **Purge stale "Risograph" v1 token references** (H1 + H2 + MAJ-4 + Step 5 Epic 7 goal)
   - `epics.md:792` (Story 6.1 AC) — "Risograph design tokens" → "yeolsal v2 design tokens (per FE→BE codegen Architecture §4.16)"
   - `epics.md:856` (Epic 7 Goal) — "Risograph SVG poster" → "Editorial-aesthetic SVG poster (yeolsal v2 — Oxblood Editorial, sub-mode D1)"
   - `epics.md:1020` (Story 8.3 AC) — "screenshots ... use Risograph design tokens" → "screenshots ... use yeolsal v2 design tokens"
   - `architecture.md:148` (§3.2 FE patterns) — "Reuse design system tokens (`ink/paper/pink/green/acid/muted`, ... 5–7px hard-offset shadows)" → "Reuse design system tokens via the codegen pipeline (§4.16); FE owns `tokens.json` (yeolsal v2 — Oxblood Editorial). Hard-offset shadow guard released in v2."
   - **Effort: ~5 minutes of editing.** **Risk if ignored: engineers build to wrong visual direction.**

2. **Lock the three unresolved architecture decisions baked into story ACs** (MAJ-3 + MAJ-1)
   - Story 3.5 `chat_messages.kind='KUDOS'` migration placement → **V11** (single migration, brownfield convention). Update Story 3.5 AC to drop "V11 or V12 — placement TBD".
   - Story 6.2 invite-code deep-link preservation → **Platform-native deep-link query** (avoids Branch.io paid SaaS dependency + extra native rebuild). Update Story 6.2 AC to drop the "(e.g., via Branch.io or platform deep-link query)" hedge.
   - Story 1.6 WelcomeWindow "🥥 카카오로 초대" CTA → Pick A / B / C:
     - **(A) Move Story 1.6 to W5** (after Story 6.2 Kakao SDK lands).
     - **(B) v1 Story 1.6 ships "Coming soon" disabled Kakao CTA + working "오늘 기록하기" CTA-B**; Kakao CTA wired in W5 follow-up.
     - **(C) Pull Story 6.2 native-module setup forward to W1** (1-day native-module rebuild cost on dev machines).
     - **Recommended: Option B** — preserves J0 UX delivery in W1-W2 without blocking on W5; the "오늘 기록하기" CTA already addresses A10 anti-pattern.
   - **Effort: 60-min meeting + ~10 minutes of AC edits.**

3. **Reorder Story 3.5 before Story 3.2 within W3-W4 sprint** (MAJ-2)
   - The kudos endpoint (3.5) is consumed by the Friend Gift Modal (3.2). Renumbering would cause churn — instead, document the execution order in epics.md "Sprint alignment" section: *"W3-W4 execution order: 3.1 (free ticket + self-revival) → 3.5 (kudos endpoint + migration) → 3.2 (friend-gift modal consumes kudos endpoint) → 3.3 → 3.4."*
   - **Effort: ~2 minutes of editing.**

#### ⚠️ P1 — Should story / specify before W1 starts

4. **Story `<SurvivalChip>` primitive** (Step 4 M1)
   - Add AC to Story 1.5: *"`<SurvivalChip state>` primitive built in `FE/src/components/survival/SurvivalChip.tsx`; consumes packed-type token; renders {color dot + icon + label} as a non-splittable composite. This is the only allowed entry point for displaying survival status — direct `survival.*.color` references are blocked by the brand-voice lint hard gate (§4.15)."*

5. **Story `<SubModeProvider>` page wrapper** (Step 4 M2)
   - Add AC to Story 1.5: *"`<SubModeProvider subMode>` wrapper implemented in `FE/src/providers/SubModeProvider.tsx`; injects resolved override tokens into `useTheme()`. E2E-verified by Wallet (D2) and Spectator (D3) page wrappers. Sub-mode is never branched inside leaf components."*

6. **Story Analytics SDK selection + integration** (Step 4 M3)
   - Create Story 0 (W1 pre-development) or Story 8.5: *"Select + integrate analytics SDK supporting custom events; define event taxonomy: activation funnel (signup, onboarding.screen.dwell_ms, first_daily_entry), revival flows (revival.attempted/succeeded/failed × source), friend-gift conversion (prompt → modal_opened → confirmed), spectator→revival cohort, Day-30 Final-3 share-rate. Sentry remains BE-error only."*
   - **Required for measurability of:** PRD §3.1 KPIs, PRD §13 phase-2 trigger gates, PRD §2.3 v2 visual falsification trigger.

7. **Story 5-stage pool SVG asset pipeline** (Step 4 M4)
   - Add Story 4.3 or AC to Story 4.1: *"Design + commission 5-stage SVG/PNG assets for `<PoolStack>` (UX metaphor candidates: 돌탑/천 짜기/도자기 굽기). Lock stage thresholds (which pool-total values map to which visual stages). `<PoolStack>` component selects + renders the correct stage based on `total`."*

8. **Resolve offline / shadow-area mutation queue** (Step 4 M5)
   - **Option A — Accept and defer:** add to Architecture §7.2: *"Mutation queue for offline daily-checkin: acceptable v1 behavior is to retry-on-foreground via TanStack Query's existing AsyncStorage persist + ApiError network-class handling. Visible 'reconnecting' banner shown when known offline. No silent data loss; dignity tone preserved. Tracked as polish-tier candidate for v1.5."*
   - **Option B — Story it:** Story 1.8 (or under Epic 1 W2): *"Daily-checkin mutation queue: idempotency-key on every daily-entry POST; AsyncStorage retry-on-foreground; visible 'reconnecting' banner with brand-voice copy 'connection 톤'."*
   - **Recommended: Option A** for v1; A→B if Day-7 diary study shows dignity-tone violations.

#### 🟡 P2 — Cosmetic fixes (during epic-grooming session)

9. **FR-8.5.1 explicit AC** — Add to Story 1.1 or Story 5.1: *"Given a user calls POST /api/v1/rooms, when the room is created, then `rooms.owner_id = creatorUserId` is persisted and the creator is reflected as the leader in subsequent leader-only endpoint authorizations."*

10. **FR-8.5.4 citation** — Append `FR-8.5.4` to Story 5.2's `PRD ref:` footer line.

11. **Refresh `epics.md` FR Coverage Map (L64-L89)** — Add rows for Stories 1.5, 1.6, 1.7, 3.5 and FR-8.3.9. Correct the L1066 math error ("27" → "31").

12. **Story 2.1 — Promote BE SPECTATOR write-reject to explicit AC bullet** (Step 5 MIN-2).

13. **Story 8.4 — Tighten release-gate AC** with sign-off mechanism (PR comment? Notion artifact?), SLA for needs-rewrite loop, and CI / manual hold-gate (Step 5 MIN-3).

14. **NFR-9.5.5 `min_supported_app_version`** — Surface as AC under Story 1.4 or new story (Step 3 process finding).

15. **NFR-9.3.7 Sentry mass-elimination alerting** — Surface as AC under Story 1.2 or W8 telemetry story (Step 3 process finding).

16. **Document V11 batched-migration deviation** — Add to epics.md Validation Summary: *"V11 migration intentionally batches all v1 schema deltas per project-context.md brownfield Flyway conventions. Per-story migration splitting would create ordering complexity and is not recommended for this project."* (Step 5 MAJ-5).

### Recommended Next Steps

1. **Today (2026-05-11) afternoon** — Run a 60-min remediation pass:
   - Search-and-replace the 4 stale "Risograph" locations (P0 #1).
   - Hold 60-min decision meeting → lock Story 1.6 CTA strategy (Option B recommended), Story 3.5 V11 placement, Story 6.2 platform deep-link (P0 #2).
   - Add sprint-order note to epics.md for W3-W4 (P0 #3).

2. **Tomorrow (2026-05-12)** — Story expansion:
   - Expand Story 1.5 ACs for `<SurvivalChip>` + `<SubModeProvider>` (P1 #4, #5).
   - Add analytics SDK story (P1 #6).
   - Add pool-asset story or expand Story 4.1 (P1 #7).
   - Resolve offline mutation queue path (P1 #8 — Option A recommended for v1).

3. **At sprint planning** — Address P2 cosmetic items during epic-grooming pass before sprint W1 starts. Estimated 30-min batch.

4. **Once P0 + P1 are closed** — Project is **READY for `/bmad-sprint-planning`**.

### Strengths to Preserve

- **Sprint Change Proposal integration is exceptional.** PRD §14.2 + Architecture §4.9/§4.15/§4.16 + UX v2 rewrite + Stories 1.5/1.6/1.7/3.5 form a coherent system: token codegen + packed-type enforcement + a11y lint hard gate + new UX primitives all reinforce each other.
- **Brownfield migration discipline is strong.** V11 single migration + idempotent backfill + Testcontainers requirement + partial-unique-index pattern carried forward from V8/V9 incident learnings.
- **Concurrency story is robust.** Advisory lock + partial unique + `SELECT FOR UPDATE` triple-layered for revival flows. Two-channel realtime privacy (immediate user queue + delayed broad topic) prevents FE-side privacy leaks.
- **Dignity-tone enforcement is structural, not just narrative.** PRD §6.1 bans → UX packed-type `<SurvivalChip>` → Architecture §4.15 hard CI gate is a complete loop, not aspirational.
- **All 6 PRD user journeys (J0–J5) are rendered as Mermaid flowcharts in UX** with concrete CTAs / state transitions / system messages — engineers can implement against the flowcharts directly.

### Final Note

This assessment identified **22 issues across 5 review categories** (Document Discovery: 0, PRD: 0, Epic Coverage: 2 traceability + 4 process, UX Alignment: 2 HIGH + 5 MEDIUM + 4 LOW, Epic Quality: 0 CRITICAL + 5 MAJOR + 6 MINOR). **Zero critical-severity issues**; all blockers are doc-editing or single-meeting decisions. Address the P0 + P1 items above before proceeding to `/bmad-sprint-planning`; P2 items can be folded into epic-grooming at sprint kickoff.

The planning artifact set is in **better-than-typical** shape for an 8-week brownfield v1 build. Pre-implementation Phase (PRD → Architecture → UX → Epics) is substantively complete.

---

**Assessor:** rearleg + Claude (BMad PM agent)
**Report generated:** 2026-05-11
**Report path:** `_bmad-output/planning-artifacts/implementation-readiness-report-2026-05-11.md`
**Supersedes:** `implementation-readiness-report-2026-05-10.md` (now stale due to PRD/Architecture/UX/Epics re-edits after 2026-05-10 sprint change proposal).

---

## Step 7 — Remediation Applied (2026-05-11)

The user instructed: *"정리 작업과 한 번의 결정 미팅 진행"* — proceed with cleanup work and the decision meeting. This section records what was changed in which artifact.

### Decisions Locked (P0 #2)

| # | Decision | Rationale | Applied to |
|---|----------|-----------|------------|
| D1 | **Story 3.5 migration → V11** (single batched migration, not V12) | Brownfield Flyway convention; matches V8/V9 reference pattern. Per-story migration split would create ordering complexity. | `epics.md` Story 3.5 — two locations updated, "(decision locked 2026-05-11)" annotation added. |
| D2 | **Story 6.2 invite-code deep-link → Platform-native (iOS Universal Links + Android App Links)**, no Branch.io | Avoids paid SaaS dependency + extra native-module rebuild cycle. | `epics.md` Story 6.2 AC L822 area updated. |
| D3 | **Story 1.6 Kakao CTA → Option B** (disabled "곧 카카오 초대가 가능해질 거예요" tooltip in W1-W2; wired live in W5 as part of Story 6.2 acceptance) | Preserves J0 UX delivery in W1-W2 without blocking on W5. "오늘 기록하기" CTA-B remains functional, so A10 anti-pattern guard still holds. | `epics.md` Story 1.6 AC — disabled-state path added; W5 wiring path retained. **Also fixed sub-mode discrepancy** (epic said "D3 Quiet Dark" but UX spec assigns J0 to "D4 Postcard Mythic"). |

### Cleanup Applied

#### P0 #1 — Stale "Risograph" tokens purged (4 locations)

| File:Line | Before | After |
|-----------|--------|-------|
| `epics.md:792` (Story 6.1 AC) | "uses Risograph design tokens" | "uses yeolsal v2 design tokens via the FE→BE codegen pipeline (Architecture §4.16 — `GeneratedTokens` constants only, no hex literals) with sub-mode `D1 Editorial` overrides applied" |
| `epics.md:856` (Epic 7 Goal) | "Server-rendered Risograph SVG poster" | "Server-rendered Editorial-aesthetic SVG poster (yeolsal v2 — Oxblood Editorial, sub-mode `D1`) ... via the FE→BE token codegen pipeline (Architecture §4.16)" |
| `epics.md:1020` (Story 8.3 AC) | "screenshots ... use Risograph design tokens" | "screenshots ... use yeolsal v2 design tokens (Oxblood Editorial — Architecture §4.16)" |
| `architecture.md:148` (§3.2 FE patterns) | "Reuse design system tokens (`ink/paper/pink/green/acid/muted`, 3–4px borders, 5–7px hard-offset shadows)" | "Reuse design system tokens via the FE→BE codegen pipeline (§4.16). FE owns the canonical `FE/src/theme/tokens.json` (yeolsal v2 — Oxblood Editorial). Per Sprint Change Proposal 2026-05-10: v1 Risograph + Neobrutalist palette/shadows are deprecated — hard-offset shadow guard is **released** in favor of `elevation.*` subtle-blur tokens..." |

#### P0 #3 — W3-W4 execution order documented

`epics.md` Validation Summary § Sprint alignment now explicitly states:
> **Execution order within Epic 3 (locked 2026-05-11):** Story 3.1 → **Story 3.5** (Kudos endpoint + V11 `kind='KUDOS'` migration) → **Story 3.2** (FriendGiftModal consumes 3.5 kudos endpoint) → 3.3 → 3.4.

Resolves MAJ-2 (intra-epic forward dependency Story 3.2 → Story 3.5) without renumbering churn.

#### P1 #4-5 — Story 1.5 expanded with `<SurvivalChip>` and `<SubModeProvider>` ACs

Three new AC bullets added to `epics.md` Story 1.5:
1. **`<SurvivalChip state>` primitive** (M1 closure) — only allowed entry point for displaying survival state; structurally prevents color-only references; backed by `tools/brand-voice-lint.ts` hard CI gate.
2. **`<SubModeProvider subMode>` page wrapper** (M2 closure) — page-level prop injection; resolves override tokens into `useTheme()`; E2E verified by Wallet (D2) + Spectator (D3) page wrappers.
3. **Sub-mode override whitelist enforcement** (L1 closure) — `./gradlew validateTokens` fails on keys outside the 12-key whitelist documented by UX.

Also fixed: contrast WCAG AA test bullet now uses v2 token names (`text.primary`-on-`bg.canvas`, etc.) instead of stale v1 names (`ink`-on-`key`, `cream`-on-`ink`).

#### P1 #6 — Story 8.5 (Analytics SDK selection + event taxonomy) created

New story under Epic 8 covering: SDK selection + documentation, 5-funnel event taxonomy (activation / revival / friend-gift conversion / spectator→revival cohort / Day-30 Final-3 share-rate), user-property minimalism (no PII), PIPA-compliant opt-in surface, BE server-side capture path, taxonomy-lint CI rule. **Scheduled for W1**, not W7, so downstream stories can emit events from day one. Closes M3.

#### P1 #7 — Story 4.3 (`<PoolStack>` 5-stage SVG asset pipeline + threshold table) created

New story under Epic 4 covering: 5 SVG/PNG assets in `FE/src/assets/pool/`, threshold table aligned with PRD §3.1 ≥50pt-by-Day-30 KPI (Stage 4 = success bar), cross-fade animation with `motion.normal` + `ember.default` glow + reduced-motion fallback, WCAG AA contrast verification per stage, positive-only invariant matching FR-8.4.5 (BE pool non-decrementable). Closes M4.

#### P1 #8 — Offline mutation queue decision locked (Architecture §7.2)

Architecture §7.2 now records: **Accepted v1 behavior** — rely on TanStack Query AsyncStorage persist + `ApiError` network-class handling + "연결을 잠시 기다리고 있어요" reconnecting banner on offline submit. **Promotion criteria** — if Day-7 diary study surfaces dignity-tone violations from dropped check-ins, story a v1.5 mutation-queue improvement. Converts UX-flagged tracked-risk into known-accepted gap. Closes M5.

#### P2 cosmetic — 5 items applied

| Item | Applied to |
|------|------------|
| **FR-8.5.1 explicit AC** ("creator → leader") | `epics.md` Story 1.1 — new AC bullet + footer citation. |
| **FR-8.5.4 citation** added to Story 5.2 footer | `epics.md` Story 5.2 — PRD ref line now lists FR-8.5.4 alongside .5, .6. |
| **Story 2.1 explicit SPECTATOR write-reject AC** (MIN-2) | `epics.md` Story 2.1 — new AC bullet covering `403 FORBIDDEN SPECTATOR_WRITE_FORBIDDEN` + integration test path; NFR-9.2.5 added to citation line. |
| **Story 8.4 sign-off mechanism + SLA** (MIN-3) | `epics.md` Story 8.4 — sign-off mechanism: per-release `docs/releases/brand-voice-review-<version>.md` + CODEOWNERS-required GH PR review by PM + designer; needs-rewrite SLA: 1 business day or feature-flag-off; release-hold mechanism: branch-protection on `release/*`. |
| **Story count + FR Coverage Map refreshed** (MIN-4, MIN-5) | `epics.md` Validation Summary — story count corrected to 33 (7+3+5+3+4+3+3+5); refreshed FR Coverage Map now reflects Stories 1.5/1.6/1.7/3.5/4.3/8.5 + FR-8.3.9. The legacy stale map at L61 was retired and replaced with a forward-pointer to the refreshed map. **V11 batched-migration deviation-by-design note added** (MAJ-5 closure). |

### Outstanding Items After Remediation

| Item | Status | Notes |
|------|--------|-------|
| NFR-9.5.5 `min_supported_app_version` not explicitly storied | **Open** — minor | Recommend folding into Story 1.4 AC at story-creation time (`/bmad-create-story`). |
| NFR-9.3.7 Sentry mass-elimination alerting not explicitly storied | **Open** — minor | Recommend folding into Story 1.2 AC or W8 telemetry sub-task. |
| WS event schema naming reconciliation (L2) | **Open** — minor | epics.md uses `RealtimeEvent.<Name>` style; UX uses `gift.revive.sent` style. Reconcile to Java naming as canonical at story-creation time. |
| Story 1.7 RitualMoment a11y wrapper test AC (L3) | **Open** — minor | Add at story-creation time. |
| Dynamic Type 1.5× layout-survival smoke test (L4) | **Open** — minor | Implicit in Story 1.5 contrast verification; tighten at story-creation. |

These 5 remaining items are all minor / cosmetic, all addressable at story-creation time via `/bmad-create-story`, and **do not block sprint planning**.

### Updated Readiness Verdict

🟢 **READY for `/bmad-sprint-planning`.**

All 2 HIGH + 5 MAJOR + 5 MEDIUM (M1–M5) findings are closed. 5 of the 6 MINOR + 4 LOW items are closed. The 5 remaining low-severity items are all "fold into story creation" cleanups that don't block the sprint-planning workflow.

**Summary of files modified in this remediation pass:**
- `_bmad-output/planning-artifacts/epics.md` — 13 distinct edits across 9 stories + Validation Summary + Coverage Map retirement
- `_bmad-output/planning-artifacts/architecture.md` — 2 distinct edits (§3.2 FE patterns + §7.2 open items)
- `_bmad-output/planning-artifacts/implementation-readiness-report-2026-05-11.md` — this Step 7 section appended

**Next step:** invoke `/bmad-sprint-planning` to convert the 33-story breakdown into sprint status, then `/bmad-create-story` cycle for W1 stories (Story 8.5 — Analytics SDK first, then 1.4, 1.5, 1.1, 1.2, 1.3, 1.6, 1.7 in that recommended order).





