---
stepsCompleted:
  - step-01-init
  - step-02-discovery
  - step-02b-vision
  - step-02c-executive-summary
  - step-03-success
  - step-04-journeys
  - step-05-domain
  - step-06-innovation
  - step-07-project-type
  - step-08-scoping
  - step-09-functional
  - step-10-nonfunctional
  - step-11-polish
inputDocuments:
  - '_bmad-output/planning-artifacts/prfaq-yeolsal.md'
  - '_bmad-output/planning-artifacts/prfaq-yeolsal-distillate.md'
  - '_bmad-output/planning-artifacts/product-brief-yeolsal.md'
  - '_bmad-output/planning-artifacts/product-brief-yeolsal-distillate.md'
  - '_bmad-output/planning-artifacts/research/market-todo-survival-habit-app-market-research-2026-05-09.md'
  - '_bmad-output/brainstorming/brainstorming-session-2026-05-09-2305.md'
  - '_bmad-output/project-context.md'
  - 'docs/index.md'
  - 'docs/architecture-fe.md'
  - 'docs/architecture-be.md'
  - 'docs/api-contracts-be.md'
  - 'docs/data-models-be.md'
  - 'docs/integration-architecture.md'
  - 'docs/deployment-guide.md'
  - 'docs/source-tree-analysis.md'
  - 'docs/project-overview.md'
  - 'docs/project-parts.json'
workflowType: 'prd'
project_name: 'yeolsal (열살방)'
project_type: 'commercial product · consumer mobile app · KR-first · brownfield pivot'
status: 'draft'
date: '2026-05-10'
---

# Product Requirements Document — yeolsal (열살방)

**Author:** rearleg
**Date:** 2026-05-10
**Project type:** Commercial product · consumer mobile app · KR-first · **brownfield pivot** of an existing Expo + Spring Boot app
**Status:** Draft for engineering review

> This PRD is the canonical specification for the v1 build of 열살방. It supersedes the product brief and PRFAQ in the planning pipeline and is the input document for `/bmad-create-architecture`, `/bmad-create-epics-and-stories`, `/bmad-check-implementation-readiness`, and `/bmad-sprint-planning`. All prior decisions are locked unless a section here explicitly reopens them. Prior context is preserved in the appendix.

---

## 1. Executive Summary

**열살방 (yeolsal)** is a friend-group survival room for daily promises. Members commit to a simple daily todo rule and either survive together or fall and rebuild together. When a member fails, they enter read-only spectator mode; they return by spending personal points (earned through surviving) **or by accepting a friend's points spent on their behalf**. Every revival adds points to the room's collective pool, seeding a future shared reward (gifticon, in phase-2). v1 ships **without any payment surface** — the loop is built and validated entirely on effort, social pressure, and the "함께하고 싶다 / 소외감" emotional core.

This PRD covers **the v1 pivot of an already-running product**. The yeolsal app today exposes a generic todo + reflections + friends + rooms experience on top of Spring Boot 3.3.5 + Postgres + Flyway V1–V10 + STOMP realtime + JWT auth + Expo SDK 54. The v1 build reframes that experience around a **survival mechanic** with dignity-first guardrails. The pivot is not a rewrite — it adds one V11+ migration, ~7 new tables, 3 new STOMP topics, a spectator-mode FE branch, a friend-gift flow, KakaoTalk SDK invite virality, a Final-3 monthly poster, and a tightly-scoped brand-voice copy pass. Existing infrastructure (rooms, room_members, invite codes, friends, daily_entries, reflections, chat, push, JWT, STOMP fan-out) carries over.

**Why now:** 챌린저스 pivoted away from pure habit-formation in 2023, leaving a vacuum in the KR habit-app category. Habitica's 39% retention lift after group challenges (mid-2023) proves the social mechanic. Duolingo's streak freeze + gem economy (4.5× DAU 2021–2023, +21% retention) proves loss aversion can be monetized non-extractively. Squad's 8-member cohort, the closest structural competitor, has no Korean presence. Yeolsal's existing infra plus the brand-distinct Risograph/neobrutalist identity make this an 8-week reframe instead of a 6-month new build.

**Verdict status (from PRFAQ):** Forged with one heated edge to watch. Top three risks tracked in §13.

---

## 2. Strategic Vision & Differentiation

### 2.1 Differentiation Thesis

> 열살방 wins by being the only product that **gamifies group survival without humiliation**, monetizes (when monetization arrives) through a *group-positive* economy (revival = gift), and ships with a culturally distinct visual identity native to Korean group-chat life.

### 2.2 The Five Whitespaces yeolsal Occupies

1. **Group-survival framing without humiliation.** Habitica is closest psychologically (boss damages everyone) but locked in an RPG aesthetic that does not travel to Korea. Squad has the cohort shape but no narrative tension. None couple stakes with dignity rules.
2. **Effort-only v1 economy.** Every surveyed competitor either monetizes immediately (Stickk, Beeminder, Habitica IAP) or never (Cohorty). yeolsal v1 has *no* money in the loop — survival earns points, points revive friends, group pool grows as a promise. The economy is positive-sum about effort before it ever touches money.
3. **Friend-revives-friend** as load-bearing emotional moment. No surveyed competitor has it. The cleanest possible expression of "함께하고 싶다 / 소외감".
4. **KakaoTalk-native distribution.** Surveyed competitors have no KR localization or invite flow. v1 ships native Kakao SDK invite — the substrate Korean accountability already uses.
5. **Oxblood Editorial visual identity (Dark Luxury × Editorial 융합).** Habit apps trend toward minimalist Calm/Notion aesthetics. yeolsal v2 ships an opinionated dark-luxury system with oxblood as key color, editorial typography, and high-contrast hierarchy. The Final-3 monthly poster makes survival a *shareable* visual artifact — every win produces a free marketing asset. Brand uniqueness remains a 5축 차별화 axis; only the *expression* changes from Risograph to Oxblood Editorial. *Falsification trigger:* if Day-30 share-rate of Final-3 poster < 15% of surviving members, revisit visual direction. (Replaces Risograph + Neobrutalist per Sprint Change Proposal 2026-05-10.)

### 2.3 Strategic Bets (and what would falsify them)

| Bet | Falsified by |
|-----|--------------|
| Spectator-mode FOMO is the engine that converts eliminated users back into members | Day-7 spectator → revival conversion < 15% across cohorts |
| Friend-gift revival is the load-bearing emotional moment | < 1 friend-gift revival per active room per month sustained for 30 days |
| KR users will adopt an effort-only economy without the 챌린저스 deposit-refund mental model | Activation < 60% within 24h despite functional onboarding |
| Existing yeolsal infra + 8-week reframe is enough | MVP scope cannot fit in 8 weeks of focused build with the named team |
| Effort-only v1 sets up a clean phase-2 monetization path | Day-60 phase-2 trigger gates miss with no clear path to recovery |

---

## 3. Success Criteria

### 3.1 Activation & Retention KPIs (must-track from launch)

| Metric | Target | Source |
|--------|--------|--------|
| Activation: first room-join + first daily entry within 24h | ≥60% of new signups | PRFAQ |
| Day-7 retention: still posting daily on day 7 | ≥45% of room members | PRFAQ; Duolingo loss-aversion lock-in window |
| Day-30 cohort survival: room still active with majority of original members | ≥25% of rooms | Hypothesis (Habitica +39% retention is the indirect baseline) |
| Free-ticket revival rate: redeemed within 7 days of elimination | ≥35% of eliminated users | PRFAQ |
| Friend-gift revival usage | ≥1 per active room per month | Confirms "함께하고 싶다" hypothesis |
| Personal-points revival | ≥15% of post-free-ticket revivals | Validates surviving has tangible payoff |
| Kakao-share invite acceptance | ≥30% of links convert to joined member | Validates distribution channel |
| Room point pool growth | average active room ≥50 pool points by day 30 | Leading indicator for phase-2 BM fuel |
| App-store policy review | first submission pass, no rating escalation | Existential gate; payment-free v1 makes near-trivial |
| Qualitative tone | users describe with 함께/선물/응원, not 벌금/잃었다 | Brand integrity check |

### 3.2 Phase-2 Trigger Gates (all four must hold at Day 60)

1. Day-7 retention ≥45%.
2. Friend-gift revival ≥1 per active room per month.
3. Average room point pool ≥50 by day 30.
4. App-store reviews / Sentry telemetry show no shame-event pattern.

If any single gate misses by a small margin → run a targeted phase-1.5 sprint of 30 days on that single metric. *Never* enter phase-2 with all gates red. *Never* skip phase-2 indefinitely.

### 3.3 What we explicitly will NOT track

- Revival count per user as a public metric.
- Time-to-revival as a public stat.
- Money-spent leaderboards (moot in v1; banned all phases).
- Friend-rejection counts.

---

## 4. User Personas & Journeys

### 4.1 Primary Persona — 친구 그룹 자기관리 동호인

**Demographics:** 20–40대 Korean self-managers, mostly women in primary segment (mirrors 챌린저스 base), already active in 단톡방 인증 culture.
**Need:** structure, narrative, and shareability that 단톡방s cannot provide.
**Activation gate:** receive a Kakao share link from a friend, tap, signup → first daily entry.
**Success state:** "I survived this month with my crew, and we made the room's point pool grow toward something."

### 4.2 Secondary Personas (post-MVP, not v1 build targets)

- **Study cohorts** — 수능 D-day groups, 토익 100일, 공무원 D-100. Themed-room rule presets unlock this segment.
- **Fitness / 식단 동호회** — group-gifticon BM (phase-2) maps directly to existing 단체 인증 culture.
- **Workplace 30-day onboarding cohorts** — B2B-lite vertical; phase-2.5.

### 4.3 v1 User Journeys

#### J0 — Cold-start leader's lonely 30 seconds (방장의 외로운 30초)

```
Leader 진수 → creates 운동방 → max_members picker (default 12, range 2-30) →
  POST /api/v1/rooms 성공 → Welcome 화면 (방원 = leader 본인 1명) →
  ❌ 진행 막대 ("11명 더 들어와야 시작") 표시 ❌ (A10 anti-pattern guard) →
  ✅ <WelcomeWindow> D3-Quiet 톤 (or v2 equivalent):
       headline: "친구를 초대하면 같이 살아남을 수 있어요"
       2 CTA 동등 비중: (a) 🥥 카카오로 초대 / (b) 🌿 먼저 오늘 기록하기
  → 멤버 합류 시마다 chat에 warm 시스템 메시지 ("민지 함께합니다 🌿") →
  방원 ≥ 2 + grace 종료 시 J2/J3 surfaces 진입 가능
```

- **KPI**: activation 60% / 24h (방장 이탈 방지).
- **PRD ref**: 신규 `<WelcomeWindow>` surface, Story 1.6.
- (Added per Sprint Change Proposal 2026-05-10 — readiness report U1 disposition ACCEPT.)

#### J1 — Cold-start friend-graph onboarding (load-bearing for first 100 users)

```
진수 (existing yeolsal user) → creates 운동방 → picks max_members=12, weekend-include=off →
  generates Kakao share link → posts to 친구 단톡방 →
  4 friends tap → see Kakao preview card (room name, rule, member count) →
  signup (existing email or Kakao OAuth) → see rule notice + 14-day grace banner →
  accept rule → land on Today screen → post first daily entry → activation complete
```

#### J2 — Spectator → Revival (the FOMO engine, falsifiable bet)

```
민지 misses Tuesday → yellow card (push notification with 응원 tone) →
  misses Saturday → red card (soft-public banner: visible to her + leader, not broader room for 24h) →
  enters spectator mode (read-only chat + roster + grass) →
  Sunday → opens app → sees room scrolling, point pool at 23 →
  taps Wallet → sees free revival ticket (usable now) →
  uses it → banner clears for everyone → room point pool +5 →
  resumes posting → earns +2 personal points per day surviving
```

#### J3 — Friend-revives-friend (the load-bearing emotional moment)

```
정민 has 12 personal points (earned over 6 surviving days) →
  best friend 수진 in same room got eliminated yesterday →
  push notification (invitation tone): "수진이 회생을 기다리고 있어요" →
  정민 taps → sees Friend Gift Modal (수진's status, point cost, his balance) →
  spends 5 points → 수진 receives push: "정민이 너의 회생권을 선물했어" →
  Optional system message in chat (donor opts in): "정민 → 수진" →
  Receiver 수진 sees post-revival "고맙다" UX → captured but never broadcast to room →
  Pool +5 → both remember the moment → load-bearing emotional moment hit
```

#### J4 — Day-30 Final-3 ceremony (free marketing asset)

```
운동방-3월기 reaches day 30 → 7 of original 9 survive →
  Server-rendered SVG poster auto-generated (Risograph palette: ink/paper/pink/green/acid) →
  Lists 7 surviving names + room name + "Final-3 진수·민지·수진" highlight →
  Each member sees on Home tab → tap → "Share to Kakao" button →
  Generates Kakao card with embedded room invite-code →
  New users tap card → see room preview → 1-tap join (subject to capacity)
```

#### J5 — Group leader rule change (next-month-only contract integrity)

```
운동방 leader 진수 → opens Room Settings → wants to add weekday-only rule →
  Rule editor shows preview: "변경된 규칙은 다음 달 1일부터 적용됩니다." →
  Confirms → room_rule_versions row inserted with effective_from_month = next month →
  All members see in-chat system message: "다음 달부터 새 규칙이 적용됩니다 [preview]" →
  Current month's contract remains untouched → trust held
```

---

## 5. Domain Model

### 5.1 Glossary

- **Room (열살방)** — a group of 3 to 30 members sharing one daily rule. Single mandatory primary room per user in v1.
- **Rule** — the daily commitment (v1 surface: daily-update + weekend-include toggle). Locked per current month; changes apply next month.
- **Day boundary** — 06:00 in Asia/Seoul. Owned by `EntryDateResolver` (existing infra, V2 migration).
- **Survival state** — `ACTIVE`, `YELLOW` (warning), `RED` (eliminated), `SPECTATOR` (read-only post-elimination).
- **Yellow card** — first miss inside a rolling 7-day window.
- **Red card** — second miss inside a rolling 7-day window. Triggers `SPECTATOR`.
- **Streak freeze** — 1 free auto-skip per calendar month per user, automatically applied.
- **Free revival ticket** — granted at signup, lifetime 1, usable immediately.
- **Personal points** — per-room scoped, earned by surviving days. Forfeit on leaving room.
- **Friend-gift revival** — a giver spends their personal points to revive an eliminated room-mate.
- **Group point pool** — per-room cumulative count of points spent on revivals (any source). Visible to all room members; does not redeem in v1.
- **Final-3** — at month-end, the top 3 longest-tenured surviving members (semantics for 30-member rooms: see §8.5).
- **Soft-public elimination** — red-card visible to user + leader for 24h before broadcast to wider room.

### 5.2 Existing Entities (carried over from V1–V10, no schema change)

`users`, `refresh_tokens`, `friendships`, `daily_entries`, `todo_items`, `reflections`, `monthly_goals`, `rooms`, `room_members`, `room_invites`, `notification_prefs`, `push_tokens`, `notification_log`, `login_codes`, `chat_messages`, `group_member_minimums`, `group_warnings`.

See [`docs/data-models-be.md`](../../docs/data-models-be.md) for full existing schema.

### 5.3 New Entities (v1 deltas)

| Table | Purpose |
|-------|---------|
| `streak_freezes` | One row per use, `(user_id, month)` partial unique → enforces 1/month |
| `survival_state` | Current state per `(room_id, user_id)` — `status`, `last_state_change_at`, `eliminated_at`, `broad_visibility_at` |
| `revival_events` | Append-only ledger of every revival — `source` ∈ {`FREE_TICKET`,`PERSONAL_POINTS`,`FRIEND_GIFT`}, `giver_user_id` (nullable for self-revivals) |
| `personal_points_ledger` | Append-only per-user-per-room ledger — `delta`, `reason` ∈ {`SURVIVAL`,`REVIVAL_SPEND`,`FRIEND_GIFT_SPEND`} |
| `room_point_pool` | Sum cache `(room_id, total, last_event_at)` |
| `room_rule_versions` | Per-month rule snapshot — `(room_id, effective_from_month, rule_payload jsonb)` enables next-month-only changes |
| `record_visibility_prefs` | Per-user per-room toggle — `share_on_elimination bool`, default false |

### 5.4 Modified Existing Entity

- `rooms.max_members`: extend allowed range from default 8 to **default 12, BETWEEN 2 AND 30**. Delivered in V11 migration. Existing rooms keep their current cap unless updated by leader (next-month-only rule).

### 5.5 State Machine: `survival_state.status`

```
                 ┌─────── streak freeze applied ───┐
                 │                                  ▼
ACTIVE ─ miss → YELLOW ─ miss within 7d ──→ RED ─ broad_visibility_at + 24h ──→ (visible to all)
   ▲              │                            │
   │              │ 7d passes w/o another miss │ revive (free ticket / points / friend gift)
   │              ▼                            ▼
   └───── back to ACTIVE ◀────────────────── ACTIVE
                                                ▲
                                            (after revival, state machine resets the rolling window)
RED ─ enter SPECTATOR (read-only) ─ revive ─→ ACTIVE
```

Concurrency: every state transition emits a `RealtimeEvent` via `RealtimePublisher` on `/topic/rooms/{id}/survival`. The `survival_state` table is the source of truth; FE mirrors via TanStack Query + STOMP dedupe pattern (see `useChatRealtime` reference impl).

---

## 6. Innovation & Constraints (what we are deliberately not doing)

### 6.1 Banned across all phases

- **Random / variable revival pricing** — gambling classification trip.
- **Streak-length-scaled revival cost** above entry price — hostile to long-tenured users.
- **Pyramid-style revive-by-inviting-humans** — ToS-unsafe, viral but gross.
- **Location-based todo verification** — surveillance.
- **Cash payouts to room leaders** — fraud risk + dignity-model break.
- **Public revival-count, money-spent, or rejection-count leaderboards** — shame engine.
- **Death icons or failure flair on the user's grass** — permanent stigma.
- **Letting eliminated users pay to "stay in chat"** — predatory.
- **Auto-broadcast on account deletion** — privacy violation.
- **Pure-red color (`oklch hue 20-30°` at high chroma) used as alarm/blood signal on elimination, RED-card, or spectator surfaces** — dignity violation. Red-adjacent warm tones (oxblood, crimson, maroon, burgundy) used as *brand identity* are permitted; red as *failure signal* is not. (Added per Sprint Change Proposal 2026-05-10.)

### 6.2 Out of v1 scope (deferred)

- Any payment surface (no IAP, no PG, no buyable revival ticket, no cosmetic IAP).
- Gifticon redemption catalog (room point pool accumulates without conversion in v1; phase-2 ships 1 SKU starter).
- Multi-room membership.
- Custom (non-preset) rule authoring.
- B2B / company onboarding vertical (phase-2.5).
- Sobriety / rehab vertical (deferred indefinitely).
- Live "co-working" voice room.
- Rule template marketplace.
- Sponsor pairing inside rooms.
- International localization (international fork = v3 conversation).
- Real-money cash-out of any kind.

### 6.3 Non-controversial defaults locked here (resolves PRFAQ open questions)

| Open question | Decision | Rationale |
|---------------|----------|-----------|
| Free revival ticket re-grant policy | **Lifetime 1 per account** | Simplest mental model; phase-2 may revisit |
| Personal points on leaving room | **Per-room scoped, forfeit on leave** | No inter-room farming abuse |
| Eliminated user's chat location | **Same room channel, `status = SPECTATOR` flag, FE input disabled, BE write API rejects** | Avoids parallel "ghost room" complexity |
| "Weekend-include off" semantics under 06:00 KST | **Day-boundary owns the answer** — Sunday 05:30 KST is Saturday, so it counts | Consistency with V2 + EntryDateResolver |
| Spectator-mode push frequency | **Once-per-day digest, not realtime** | Preserves FOMO without surveillance |
| Final-3 poster generation | **Server-side SVG render** | Visual fidelity + cacheable + ASO-indexable |
| Kakao SDK choice | **Extend existing Kakao OAuth integration with Share SDK** | One dependency, no new auth review |
| Room leader elimination handling | **Auto-promote longest-tenured surviving member** | Default; leader can override on creation |
| Group point pool decay/floor | **No decay in v1; phase-2 redemption at-cost with cap** | Decided when phase-2 ships |
| 30-member-room Final-3 ceremony semantics | **Final-3 always = top 3 by tenure** (regardless of room cap), with secondary "X명 생존" stat | Keeps brand consistent |

### 6.4 Personal-points formula (locked for v1; revisit at Day-30 telemetry)

| Event | Points |
|-------|--------|
| Survive a daily rule | **+2 personal points** |
| Friend-gift revival (giver pays, room pool grows) | **5 points** (giver loses 5; pool +5) |
| Personal-points self-revival (post free-ticket) | **3 points** (user loses 3; pool +3) |

→ A user surviving 7 days clean has 14 points → enough for 1 self-revival (3) + 2 friend-gift revivals over time (10) with margin to spare. Tunable at Day-30 telemetry checkpoint.

---

## 7. Project Type Classification

- **Type:** Commercial product · consumer mobile app · KR-first.
- **Lifecycle:** Brownfield pivot of running app (yeolsal v1–V10).
- **Scope:** Full v1 reframe; phase-2 (gifticon catalog + cosmetic IAP) gated by Day-60 trigger gates (§3.2).
- **Distribution:** App Store + Google Play, KR storefronts only at v1.
- **Build estimate:** ~8 weeks focused work (per PRFAQ resource estimate).
- **Replaces:** existing yeolsal app's "generic todo+reflections+rooms" surface area.
- **Preserves:** existing accounts, friend graphs, daily_entries history, chat history, push tokens. Migration UX must respect this — no data loss.

---

## 8. Functional Requirements (v1 epics)

The PRD organizes scope into 8 epics. Each epic ships as one or more stories generated by `/bmad-create-epics-and-stories`.

### Epic 8.1 — Survival State & Daily Loop

**Goal:** Replace the existing daily-entries surface with a survival-state-aware experience; maintain backward compatibility with the existing schema.

**FR-8.1.1** — On signup, user joins or creates one mandatory primary room with `max_members ∈ [2, 30]` (default 12 on creation). The 14-day grace trial begins from `room_members.joined_at`.
**FR-8.1.2** — Each user has exactly one `survival_state` row per room they belong to. Initial status = `ACTIVE`.
**FR-8.1.3** — At each 06:00 KST day boundary, a scheduled job evaluates each member's compliance with their room's current rule (per `room_rule_versions` row effective for the current month). If the rule was met for the prior day → `personal_points_ledger += 2 (SURVIVAL)`. If not met:
  - If `streak_freezes` for `(user_id, month)` does not yet exist → create row, no state change. Otherwise:
  - Within 7-day rolling window: if no prior YELLOW → status = `YELLOW`. If prior YELLOW exists → status = `RED`, `eliminated_at = now()`, `broad_visibility_at = now() + 24h`.
**FR-8.1.4** — During grace trial (first 14 days from `joined_at`), state machine runs but does not progress past `YELLOW` — no `RED` until grace ends.
**FR-8.1.5** — All state transitions emit a `RealtimeEvent.SurvivalStateChange` to `/topic/rooms/{roomId}/survival` via `RealtimePublisher`. Topic schema: `{ userId, fromStatus, toStatus, occurredAt, broadVisibilityAt | null }`.
**FR-8.1.6** — REST endpoint `GET /api/v1/rooms/{id}/survival` returns the room's survival roster with privacy filtering: members in `RED` status with `broad_visibility_at > now()` show only as `ACTIVE` to non-leader members.
**FR-8.1.7** — All survival-state changes are logged to `notification_log` with `kind = 'SURVIVAL_STATE'`, `key = '{date}:{userId}'` for idempotency.

### Epic 8.2 — Spectator Mode

**Goal:** Eliminated users see what they're missing; this is the FOMO engine.

**FR-8.2.1** — When `survival_state.status` transitions to `RED`, FE routing (`app/(tabs)/_layout.tsx`) branches the user into spectator mode for that room.
**FR-8.2.2** — In spectator mode: chat is read-only (FE input disabled; `POST /api/v1/rooms/{id}/messages` returns `403 FORBIDDEN` for `SPECTATOR` users); roster visible; grass calendars of other members visible at the room's existing privacy level.
**FR-8.2.3** — Push notifications for spectators: digest at 09:00 KST daily (if any room activity occurred), summarizing posts/reactions. Realtime push for individual messages is disabled.
**FR-8.2.4** — Eliminated user's own archive (`daily_entries`, `reflections`, `todo_items` for that room) defaults to private; opt-in toggle in `record_visibility_prefs` per room.
**FR-8.2.5** — Spectator users see their own Wallet (revival ticket + personal points balance) prominently on the Home tab.
**FR-8.2.6** — When spectator user's `survival_state.status` transitions to `ACTIVE` (via revival), they immediately re-enter active member mode.

### Epic 8.3 — Revival Economy (Free Ticket + Personal Points + Friend Gift)

**Goal:** v1's full economy. No money. Three revival sources — free ticket, personal points, friend gift.

**FR-8.3.1** — Each user account is granted exactly one **free revival ticket** at signup (`revival_events` not yet created; ticket implied by lifetime flag on `users` or new `revival_grants` table — implementation detail in architecture stage). Usable immediately. Lifetime 1.
**FR-8.3.2** — Endpoint `POST /api/v1/rooms/{id}/revival` body `{ source: FREE_TICKET | PERSONAL_POINTS }` performs self-revival: validates eligibility (user is `RED` or `SPECTATOR`, source is available), creates `revival_events` row, deducts cost (free for ticket, 3 points for personal points), increments `room_point_pool` by 5 for ticket-source or 3 for points-source, transitions `survival_state.status = ACTIVE`, emits `/topic/rooms/{id}/survival` (revival) and `/topic/rooms/{id}/points` (pool delta).
**FR-8.3.3** — Endpoint `POST /api/v1/rooms/{id}/revivals/gifts` body `{ targetUserId }`: giver spends 5 personal points to revive `targetUserId` who must be `RED` or `SPECTATOR` in the same room and an existing friend (per `friendships`). Creates `revival_events` row with `source = FRIEND_GIFT`, `giver_user_id` set; deducts 5 from giver's `personal_points_ledger`; increments `room_point_pool` by 5; transitions `survival_state.status = ACTIVE` for receiver; emits topics.
**FR-8.3.4** — Friend-gift trigger conditions surface a push to all *eligible* friend givers (room members with sufficient personal points and active `friendships` to the eliminated user). Push payload follows the brand-voice rule: invitation tone, never demand. **One push only**; no follow-up reminders if the giver does not act.
**FR-8.3.5** — Friend-gift receiver gets a separate push confirming the donor name. Donor name is **default visible to receiver only**; donor may opt in to broadcast a system message in chat (`chat_messages` row with `kind = 'SYSTEM'`, `payload = { revival_event_id, donor_id }`).
**FR-8.3.6** — Wallet UI surface (in spectator mode and active mode alike): shows free revival ticket presence, personal points balance, room point pool, and "받은 회생권" history (private to the user).
**FR-8.3.7** — Revival rejection / non-action by friends is **never visible** to anyone but the giver. There is no in-app surface that exposes who chose not to revive.
**FR-8.3.8** — `personal_points_ledger` is append-only; balance is computed as `SUM(delta)` per `(user_id, room_id)`. On leaving a room, balance is forfeit (PRD §6.3 decision).
**FR-8.3.9** — **Kudos message ("응원만 보내기 / 0점")**: the Friend Gift Modal exposes a 2nd CTA equal in visual + a11y weight to the revival-gift CTA. Endpoint `POST /api/v1/rooms/{id}/kudos { targetUserId, message? (max 60 chars) }` posts a `chat_messages` row with `kind = 'KUDOS'`, `payload = { sender_user_id, target_user_id, message }`. Cost: 0 personal points. No `revival_events` row, no `survival_state` change. One push to receiver: invitation tone ("정민이 응원을 보냈어요"). Donor toast on success. Race: idempotent via partial unique index `ux_kudos_one_per_day (sender_id, target_id, date_part('day', created_at at time zone 'Asia/Seoul'))` — same sender+receiver same KST day → `409 CONFLICT` code `KUDOS_ALREADY_SENT_TODAY`. Sender must be active room member with friendship to target; target must be `RED`/`SPECTATOR`. Brand-voice lint warns AVOID-lexicon hits in message body. (Added per Sprint Change Proposal 2026-05-10 — readiness report U3 disposition ACCEPT.)

### Epic 8.4 — Group Point Pool & Future Redemption Promise

**Goal:** Visible accumulating shared progress that promises but does not redeem in v1.

**FR-8.4.1** — Each room has exactly one `room_point_pool` row, initialized at 0 on room creation. Updated by `RevivalService` only.
**FR-8.4.2** — Room UI shows the current pool prominently (yeolsal v2 design tokens per `docs/design-system.md`, sourced from FE-generated `tokens.json` per Architecture §4.16). Pool is visible to all room members including spectators and former eliminated users still in the room.
**FR-8.4.3** — Pool growth events emit `/topic/rooms/{id}/points` with `{ delta, newTotal, sourceRevivalEventId, occurredAt }`.
**FR-8.4.4** — v1 explicitly does **not** offer redemption. UI shows phase-2 promise copy: "다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다." Brand-voice approved.
**FR-8.4.5** — When phase-2 ships, the existing pool integer in `room_point_pool` is the redeemable balance — no migration. v1 must not allow pool decrement (write path forbids negative deltas).

### Epic 8.5 — Group Leader & Rule Versioning

**Goal:** Leader has authority but cannot break the contract members joined under.

**FR-8.5.1** — Room creator is the default leader. Leader is identified by `rooms.owner_id`.
**FR-8.5.2** — `room_rule_versions` table stores per-month rule snapshots. The rule effective at any moment is the row with the highest `effective_from_month <= currentMonth` for that room.
**FR-8.5.3** — `PATCH /api/v1/rooms/{id}/rule` accepts a rule edit; service layer creates a new `room_rule_versions` row with `effective_from_month = nextMonth`. Current month's rule is locked.
**FR-8.5.4** — Member-cap edits (`PATCH /api/v1/rooms/{id}/members/cap`) follow the same next-month-only rule.
**FR-8.5.5** — Leader-driven member removal (`DELETE /api/v1/rooms/{id}/members/{userId}`) is permitted; the removed user's record archive is preserved per existing room exit semantics.
**FR-8.5.6** — Leader transfer (`POST /api/v1/rooms/{id}/transfer-leadership`) is permitted to any active room member. Updates `rooms.owner_id`.
**FR-8.5.7** — If the leader transitions to `RED` (eliminated), `RoomService` auto-promotes leadership to the longest-tenured `ACTIVE` member (PRD §6.3 decision). Emits a `RealtimeEvent.LeadershipChange`.
**FR-8.5.8** — All rule-version changes broadcast a chat system message: "다음 달부터 새 규칙이 적용됩니다 [preview]". Broad visibility for all room members.

### Epic 8.6 — KakaoTalk SDK Invite Virality

**Goal:** Friction-free room invitation that lives where Korean accountability already lives.

**FR-8.6.1** — Room invite-code generation (`POST /api/v1/rooms/{id}/invites`) returns a Kakao-ready share payload: `{ inviteCode, kakaoShareUrl, previewCardImageUrl }`.
**FR-8.6.2** — `previewCardImageUrl` is generated by a server-side renderer using room name, current rule summary, member count, and yeolsal v2 design tokens (sourced from FE-generated `tokens.json` per Architecture §4.16). Cached with TTL 1h; regenerated on rule/member-count change.
**FR-8.6.3** — FE's `RoomInviteSheet` component (existing) is extended with a "Share to KakaoTalk" CTA invoking the Kakao Share SDK (extends existing OAuth integration; no new SDK package).
**FR-8.6.4** — Tapping a Kakao-shared invite-code on a device with the app installed deep-links to the room preview screen with one-tap join (subject to `max_members` capacity).
**FR-8.6.5** — Tapping a Kakao-shared invite-code without the app installed deep-links to the App Store / Google Play KR listing with the invite-code preserved via a deep-link handoff (post-install `/api/v1/auth/signup` carries `inviteCode` parameter).
**FR-8.6.6** — Kakao Share SDK is a native module addition. Project-context.md rule applies: shipping requires `adb uninstall app.yeosal.mobile` + clean rebuild on dev machines; document in RUNBOOK.md.

### Epic 8.7 — Final-3 Monthly Ceremony

**Goal:** Turn survival into a shareable visual artifact every month.

**FR-8.7.1** — A scheduled job runs at 06:30 KST on the first day of each month. For each room with at least 1 member who completed the prior month with `survival_state.status = ACTIVE`, generate a Final-3 poster.
**FR-8.7.2** — Poster generation is **server-side SVG** rendering using yeolsal v2 design tokens, consumed from the FE→BE token codegen pipeline (Architecture §4.16). Sub-mode `D1 Editorial` token override set is applied. Layout: room name at top, all surviving member nicknames listed, top-3 by tenure highlighted with key color (oxblood) + secondary accent. Specific token names are TBD per `/bmad-create-ux-design` round and codified in `docs/design-system.md`.
**FR-8.7.3** — Generated poster is stored at `/api/v1/rooms/{id}/posters/{yearMonth}` with a stable URL. PNG fallback available for Kakao share constraints.
**FR-8.7.4** — Each surviving member receives a Home-tab card displaying the poster + "Share to KakaoTalk" CTA. Tap → shares Kakao card with embedded room invite-code.
**FR-8.7.5** — 30-member-room semantics: poster always shows top-3 by tenure as the highlighted "Final-3" with a secondary "X명 생존" stat. Layout dynamically scales to fit up to 30 nicknames.
**FR-8.7.6** — Posters are immutable once generated; no edit. Subsequent member changes do not retroactively modify a finalized month's poster.

### Epic 8.8 — Brand Voice & Onboarding

**Goal:** Defuse 챌린저스 mental model in the first session; make survival feel like dignity, not penalty.

**FR-8.8.1** — Onboarding script (5 screens) is a v1 design deliverable. Sequence:
  1. "열살방은 친구와 함께 살아남는 방입니다." (concept)
  2. "매일 약속을 지키면 살아남습니다. 빠지면 친구가 살릴 수 있어요." (mechanic)
  3. "v1에서는 돈을 받지 않습니다 — 살아남는 것 자체가 자산입니다." (defuses 챌린저스 mental model)
  4. "친구를 살리는 건 옵션이지 의무가 아닙니다." (defuses social-pressure liability)
  5. Wallet preview (free revival ticket visible) + Room preview + 14-day grace banner.
**FR-8.8.2** — All in-app copy passes a brand-voice review against the lexicon:
  - Use: 함께, 선물, 응원, 컴백, 회생, 그룹, 동료, 우리, 살리다.
  - Avoid: 벌금, 잃었다, 떨어졌다, 실패, 자책, 부담, 패배, 죄책감.
**FR-8.8.3** — Push notification copy follows brand voice; tone is **invitation, not demand**. Sample: "수진이 회생을 기다리고 있어요" (good) not "수진이가 죽었다 살려라!" (bad).
**FR-8.8.4** — Apple/Google Play store metadata in **English** uses "comeback pass" terminology, not "revival ticket" / "second chance pass". KR copy keeps "회생권".
**FR-8.8.5** — Error / system messages on elimination use "컴백 가능" language, not "탈락" / "실패".
**FR-8.8.6** — Brand-voice review is a quality gate before each release. Owner: PM + designer joint sign-off.

---

## 9. Non-Functional Requirements

### 9.1 Performance

**NFR-9.1.1** — Survival state evaluation job (06:00 KST daily) must complete within 5 minutes for up to 50,000 active members across all rooms (10× current MAU expectation).
**NFR-9.1.2** — Revival API (`POST /api/v1/rooms/{id}/revival`) p95 latency < 300ms; p99 < 800ms.
**NFR-9.1.3** — Room realtime topic latency (state change → STOMP delivery) < 500ms p95.
**NFR-9.1.4** — Final-3 poster generation < 3s p99 per poster; batch generation must complete within 10 minutes for up to 5,000 active rooms.
**NFR-9.1.5** — FE Spectator-mode entry transition < 1s after BE state change is observed via STOMP.

### 9.2 Reliability

**NFR-9.2.1** — Survival state evaluation must be idempotent. If the daily job runs twice for the same date due to retry, no member's state should double-progress.
**NFR-9.2.2** — All revival operations are atomic per `(room_id, user_id, date)`. Concurrent revival attempts must result in exactly one success; idempotency enforced via partial unique index on `revival_events (room_id, user_id, date_part(eliminated_at))` for first-revival-after-elimination semantics.
**NFR-9.2.3** — Friend-gift revival concurrency: two givers attempting to revive the same eliminated user must result in exactly one success and the loser receives a clear "이미 회생되었습니다" response.
**NFR-9.2.4** — Group point pool updates use Postgres advisory lock or `SELECT … FOR UPDATE` per room to prevent lost updates.
**NFR-9.2.5** — Spectator-mode read-only enforcement is double-implemented: FE input disabled + BE chat-write API rejects with `403 FORBIDDEN` for `SPECTATOR` status.

### 9.3 Security & Privacy

**NFR-9.3.1** — All v1 endpoints require Bearer JWT (existing convention); revival, gift, and rule-edit endpoints additionally check room membership server-side.
**NFR-9.3.2** — Eliminated user's archive defaults to private; sharing requires explicit `record_visibility_prefs.share_on_elimination = true`.
**NFR-9.3.3** — Account deletion: PIPA + Apple/Google compliant. User can export all their data (PDF) and request hard delete. Hard delete cascades existing tables; new tables (`survival_state`, `personal_points_ledger`, `revival_events`, `streak_freezes`, `record_visibility_prefs`) cascade on `users.id` foreign key.
**NFR-9.3.4** — Hard-deletion of a user does **not** broadcast to friends or rooms; chat messages from the user are anonymized (sender_user_id set NULL per existing V7 ON DELETE SET NULL rule).
**NFR-9.3.5** — Quiet hours (`notification_prefs.quiet_start_hour` / `quiet_end_hour`, defaults 22–08) are respected for all push notifications including survival, revival, and friend-gift.
**NFR-9.3.6** — No location tracking. No surveillance APIs.
**NFR-9.3.7** — Sentry telemetry: alert on mass-elimination events (>50% of room red-cards in 24h) for incident response triage.

### 9.4 Observability & Telemetry

**NFR-9.4.1** — Sentry transactions instrument: survival-state daily evaluation job, revival endpoints, friend-gift endpoints, Final-3 poster generation, KakaoTalk SDK invite link generation.
**NFR-9.4.2** — Custom Sentry events on every state transition (`ACTIVE→YELLOW`, `YELLOW→RED`, `RED→SPECTATOR`, `RED|SPECTATOR→ACTIVE` via revival).
**NFR-9.4.3** — Backend logs at `INFO` level for revival/friend-gift events with structured fields: `roomId`, `userId`, `source`, `points_spent`, `pool_after`. Never log PII or token contents.
**NFR-9.4.4** — Dashboards (KPI tracking): one per success metric in §3.1. Dashboards are not in v1 scope to *build*, but events must be emitted such that dashboards can be assembled in week 1 post-launch.

### 9.5 Compatibility & Migration

**NFR-9.5.1** — V11+ migration is idempotent and safe to roll forward. Existing rooms keep their current `max_members` until leader updates (next-month-only).
**NFR-9.5.2** — Existing yeolsal users who already have rooms continue to function; on first login post-deploy, a backfill creates `survival_state` rows for all `(room_id, user_id)` pairs with status=`ACTIVE`.
**NFR-9.5.3** — Free revival ticket grant is backfilled for all existing users at deploy (one-time job).
**NFR-9.5.4** — Existing chat history preserved; existing daily entry / reflection history preserved; existing friend graph preserved.
**NFR-9.5.5** — App version cutover: v1 ships with a `min_supported_app_version` config in BE that politely forces a Play Store / App Store update for users on pre-v1 binaries.

### 9.6 Accessibility

**NFR-9.6.1** — Color is never the sole information carrier. The `semantic.survival` design token is a **packed type** of `{ color, label, icon, grass-treatment }` per state (`ACTIVE` / `YELLOW` / `RED` / `SPECTATOR`); consuming code cannot reference the color field without also rendering the label. Brand-voice + a11y lint (Architecture §4.15) verifies no `survival.*.color` reference appears in JSX/TSX without a sibling label or `accessibilityLabel`. Verified in CI as a **hard gate** (workflow fails, not warns). (Strengthened per Sprint Change Proposal 2026-05-10.)
**NFR-9.6.2** — Push notifications support iOS/Android system-level a11y (clear text, no critical info in haptics-only).
**NFR-9.6.3** — Dynamic Type respected in FE (existing convention).

### 9.7 Internationalization

**NFR-9.7.1** — v1 is **KR-only**. All user-facing copy is Korean. No locale switcher.
**NFR-9.7.2** — Backend formats dates per `Asia/Seoul` timezone in user-facing payloads. Internal storage remains `timestamptz`.
**NFR-9.7.3** — App Store / Google Play storefront metadata is bilingual (KR primary, English secondary for store algorithms only). Brand-voice rule for English: "comeback pass" not "revival ticket".

### 9.8 Build & Deploy

**NFR-9.8.1** — BE Flyway V11+ migrations follow existing conventions (`V<N>__<slug>.sql`, idempotent SQL, partial unique indexes per V8/V9 reference pattern).
**NFR-9.8.2** — BE compile/test gate per existing CONTRIBUTING.md (`./gradlew test` green before push).
**NFR-9.8.3** — FE checks per existing CONTRIBUTING.md (`npm run lint && npm run typecheck && npm test`).
**NFR-9.8.4** — Stack-PR merge procedure (CONTRIBUTING.md, incident-driven) applies for any multi-PR slice.
**NFR-9.8.5** — KakaoTalk SDK addition triggers `adb uninstall app.yeosal.mobile` + rebuild on dev machines; document the cycle in RUNBOOK.md.
**NFR-9.8.6** — Production cutover: blue-green deploy via existing `infra/docker-compose.yml` + nginx. `/app/COMMIT` exposes deployed commit for diagnostics.

---

## 10. Dependencies

- **Internal**: existing yeolsal infrastructure (Spring Boot 3.3.5, Postgres + Flyway, STOMP via WebSocketConfig, JWT auth, Expo SDK 54, Kakao OAuth integration, Sentry wiring, expo-notifications, expo-secure-store, design system tokens). All present per `docs/index.md`.
- **External**: Kakao Share SDK (v1 add). Apple App Store / Google Play KR storefronts. Sentry SaaS. Postgres 16. No new payment integrations.
- **Future (phase-2 only, not v1)**: gifticon supplier API (likely Kakao Gift, KakaoPay, or similar). Apple IAP / Google Play Billing.

---

## 11. Roadmap & Phasing

### v1 (this PRD): 8 weeks of focused build

- W1–W2: V11+ migration + new tables; survival-state daily job + state machine; backfill job design.
- W3–W4: Revival economy endpoints (free ticket / personal points / friend gift); spectator-mode FE branch; Wallet UI.
- W5: KakaoTalk Share SDK integration + invite preview card server renderer.
- W6: Final-3 monthly poster server renderer + Home-tab share UI.
- W7: Brand voice copy pass + onboarding 5-screen flow + ASO copy lock.
- W8: Bug fix, telemetry instrumentation, App Store + Google Play submission, internal QA, production deploy.

### Phase-1.5 (conditional, 30 days post-launch)

If 1 of 4 phase-2 trigger gates misses by a small margin at Day 60, run a targeted sprint on that single metric. Examples:
- Day-7 retention 38% → onboarding flow rework + push-copy A/B.
- Friend-gift revival 0.4/room/month → friend-gift discoverability rework.

### Phase-2 (~v1.5–v2): once trigger gates green

- Gifticon redemption catalog (1 SKU starter — 스타벅스 아메리카노).
- Cosmetic IAP (room banners, sticker drops).
- Sponsor pairing inside rooms.

### Phase-2.5 (B2B-lite)

- Workplace 30-day onboarding cohorts + 동아리 / 회사 챌린지 vertical (per-seat).

### Phase-3 (sponsor marketplace + international fork)

- 출판사·도서관·지자체 funded 100일 챌린지 marketplace.
- International fork (KR-deep first 24 months minimum).

---

## 12. Test Strategy (high-level; full plan in implementation-readiness check)

- **BE unit / integration**: existing test layout (`BE/src/test/java/com/yeosal/api/**`). Each new service (SurvivalStateService, RevivalService, FriendGiftService, RoomRuleService, FinalThreeService) gets unit tests + Testcontainers integration tests. Spring-security-test for endpoint authz.
- **BE migration tests**: V11+ migration tested with Testcontainers Postgres including the V8/V9-style partial-unique-index conflict path tests for `revival_events` idempotency.
- **FE tests**: TanStack Query hook tests (existing pattern) for `useSurvivalState`, `useRevival`, `useFriendGift`, `useRoomPoints`. Realtime hook tests mock `RealtimeProvider`.
- **E2E smoke (Playwright via Vercel Agent Browser preferred)**: J1 cold-start, J2 spectator→revival, J3 friend-revives-friend, J4 Final-3 ceremony, J5 leader rule change.
- **Brand voice review**: pre-release manual checklist against §FR-8.8.2 lexicon.
- **Pre-release verification**: `bash scripts/verify.sh` from repo root must be green.

---

## 13. Risks & Open Questions

### 13.1 Top three cracks (from PRFAQ verdict, tracked here)

| Risk | Mitigation in v1 | Decision criterion |
|------|------------------|---------------------|
| **Monetization gap** — BM not validated until phase-2 | v1 KPIs include qualitative WTP signal via in-app NPS prompt + 6-user interview round at Day 30 | Phase-2 trigger gates §3.2 |
| **Spectator-FOMO hypothesis untested** | Day-7 spectator → revival conversion is most-tracked KPI from launch. **Kill criterion: < 15% conversion at Day 30 → reduce spectator surface, redesign loop** | Day-30 cohort review |
| **KR mental-model retraining** (챌린저스 trained "habit = deposit-refund") | Onboarding screens 1-3 explicitly defuse this; ASO copy avoids "ticket" gambling adjacency | Activation < 60% → onboarding rework sprint |

### 13.2 Lower-tier risks tracked

- **Single mandatory group restricts power users** — mitigation: explicit messaging + tracked dropout cohort. Counter-decision: by design, multi-room dilutes "함께하고 싶다".
- **Cold-start beyond first 100 users** — mitigation: 3 backup channels in PRFAQ (1기-seed, creator partnership, dev community). PRD §11 W7 includes channel experiment plan.
- **30-member-room behaviors at scale (chat density, Final-3 semantics, friend-gift discoverability)** — mitigation: scale-cap watch in QA; experiment plan post-launch.
- **Personal-points formula is unvalidated** — mitigation: tunable at Day-30 via config (no migration required for value tweaks).
- **Incident response playbook not yet a living artifact** — mitigation: PRD §13.3 below.

### 13.3 Incident Response Playbook (must-do before launch)

- **Sentry alerting**: mass-elimination event (>50% of room red-cards in 24h) → on-call PM page.
- **First incident triage SLA**: 24-hour in-app sanity check (paused notifications + surveyed members).
- **ToS attached abuse-reporting flow**: in-app "I feel pressured" report → triages to support@.
- **Worst-case recovery gesture**: room-wide formal apology + group point pool +30 (or similar).

### 13.4 Open questions deferred (none gate v1; resolve at named milestones)

- **30-member chat density UX** — observe in production, redesign at v1.5 if needed.
- **Final-3 poster customization** — currently fixed Risograph layout; user customization deferred to phase-2.
- **Friend-gift discoverability** — current design = push-only on receipt; if discoverability is low, add Wallet "친구 살리기" surface in v1.5.
- **Spectator push-notification frequency tuning** — current = once-per-day digest; tune via telemetry.

---

## 14. Appendix

### 14.1 Document lineage

This PRD consolidates the following prior artifacts:

- [`prfaq-yeolsal.md`](./prfaq-yeolsal.md) — gauntlet-tested concept, customer + internal FAQ, verdict.
- [`prfaq-yeolsal-distillate.md`](./prfaq-yeolsal-distillate.md) — token-efficient PRD-input pack with rejected framings, requirements signals, scope signals, KPI canonical set.
- [`product-brief-yeolsal.md`](./product-brief-yeolsal.md) — executive brief with locked decisions in frontmatter.
- [`product-brief-yeolsal-distillate.md`](./product-brief-yeolsal-distillate.md) — brief detail pack.
- [`research/market-todo-survival-habit-app-market-research-2026-05-09.md`](./research/market-todo-survival-habit-app-market-research-2026-05-09.md) — competitive landscape, behavioral evidence, policy risk.
- [`../brainstorming/brainstorming-session-2026-05-09-2305.md`](../brainstorming/brainstorming-session-2026-05-09-2305.md) — 124 ideas across 4 techniques, organized by tier.
- [`../project-context.md`](../project-context.md) — must-read agent rules (130 critical rules across 7 categories).
- [`../../docs/index.md`](../../docs/index.md) — brownfield project documentation index.
- BE: [`architecture-be.md`](../../docs/architecture-be.md), [`api-contracts-be.md`](../../docs/api-contracts-be.md), [`data-models-be.md`](../../docs/data-models-be.md).
- FE: [`architecture-fe.md`](../../docs/architecture-fe.md).
- Cross: [`integration-architecture.md`](../../docs/integration-architecture.md), [`deployment-guide.md`](../../docs/deployment-guide.md).

### 14.2 Locked decisions (canonical reference)

| Decision | Value | Source |
|----------|-------|--------|
| Room cap default | 12 | User Q1 lock-in |
| Room cap max | 30 (creator-set at room creation) | User Q1 lock-in |
| Payment in v1 | None | User Q2 lock-in |
| Free revival ticket usability | Immediate at signup, lifetime 1 | User Q3 lock-in |
| KakaoTalk SDK in v1 | Yes, extending existing Kakao OAuth | User Q4 lock-in |
| Gifticon catalog in v1 | No (phase-2) | User Q5 lock-in |
| Friend → friend revival gift in v1 | Yes | User Q6 lock-in |
| International expansion | v3 (KR-deep first 24 months minimum) | User Q7 lock-in |
| Free revival ticket re-grant | Lifetime 1 per account | PRD §6.3 |
| Personal points on leaving room | Forfeit, per-room scoped | PRD §6.3 |
| Eliminated user's chat location | Same room, SPECTATOR flag | PRD §6.3 |
| Weekend-include semantics | Day-boundary owns it (Sun 05:30 = Sat) | PRD §6.3 |
| Spectator push frequency | Once-per-day digest | PRD §6.3 |
| Final-3 poster generation | Server-side SVG | PRD §6.3 |
| Kakao SDK choice | Extend existing Kakao OAuth | PRD §6.3 |
| Leader-elimination handling | Auto-promote longest-tenured ACTIVE | PRD §6.3 |
| Personal points formula | +2 survive / 5 friend-gift / 3 self | PRD §6.4 |
| Visual identity (v1) | yeolsal v2 — Oxblood Editorial (Dark Luxury × Editorial), oxblood key color, dignity-tone preserved | Sprint Change Proposal 2026-05-10, replaces Risograph + Neobrutalist |
| NFR-9.6.1 enforcement | Token packed type (`semantic.survival`) + CI lint hard gate | Sprint Change Proposal 2026-05-10 |
| FE↔BE token sync | Codegen pipeline — FE owns `tokens.json` → Gradle task generates Java constants | Sprint Change Proposal 2026-05-10 (Architecture §4.16) |
| Dignity-color guard | Pure-red as alarm/blood signal banned (§6.1); red-adjacent warm OK as brand identity | Sprint Change Proposal 2026-05-10 (PRD §6.1) |
| U1 / U2 / U3 / U4 / U8 disposition | ACCEPT — Stories 1.6 / 3.2 AC / 3.5 / 1.7 / 3.2 AC | Sprint Change Proposal 2026-05-10 (UX U-IDs from readiness report) |
| U5 / U6 / U7 / U9 disposition | DEFER to v1.5 (U7 also conflicts with FR-8.3.1 lifetime-1) | Sprint Change Proposal 2026-05-10 |

### 14.3 Recommended next BMad steps

1. **`/bmad-create-architecture`** — feed this PRD + `docs/architecture-be.md` + `docs/architecture-fe.md` + `docs/integration-architecture.md` + `_bmad-output/project-context.md`. Architecture stage resolves implementation-detail questions surfaced in §6 and §13.4.
2. **`/bmad-create-epics-and-stories`** — convert §8's 8 epics into individual stories with acceptance criteria; align with §11 W1–W8 phasing.
3. **`/bmad-check-implementation-readiness`** — readiness gate before sprint planning. Confirms PRD + Architecture + Epics-and-Stories are aligned.
4. **`/bmad-sprint-planning`** — generate sprint status; kick off dev-story cycle.
5. **`/bmad-validate-prd`** *(optional)* — independent validation pass against PRD standards if review is desired before architecture stage.

Each subsequent skill should be run in a **new context window** for clean cache utilization.
