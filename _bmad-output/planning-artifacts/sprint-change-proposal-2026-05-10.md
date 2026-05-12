---
type: sprint-change-proposal
project: yeolsal
date: 2026-05-10
author: rearleg (with bmad-correct-course skill)
trigger: "디자인 시스템 재구축 — Risograph + Neobrutalist → yeolsal v2 Oxblood Editorial"
scope_classification: Major
status: APPROVED (auto-mode flow, 사용자 사전 승인)
supersedes_locks:
  - PRD §2.3 strategic bet #5 (Risograph + neobrutalist visual identity)
  - UX spec §Design System Foundation > Design System Choice (Risograph + Neobrutalist lock)
  - PRD FR-8.4.2 / FR-8.6.2 / FR-8.7.2 (Risograph design tokens reference)
  - UX spec §Visual Design Foundation Color System line 707-710 ("RED 컬러는 v1 시스템 어디에도 사용 금지")
adds_locks:
  - PRD §14.2 — Visual identity = yeolsal v2 Oxblood Editorial
  - PRD §14.2 — NFR-9.6.1 enforcement = packed type + CI lint hard gate
  - PRD §14.2 — FE↔BE token sync = codegen pipeline
  - PRD §6.1 — dignity-color guard (oxblood OK as brand identity, RED as failure signal NOT OK)
related_artifacts:
  - prd.md
  - epics.md
  - architecture.md
  - ux-design-specification.md
  - implementation-readiness-report-2026-05-10.md
  - docs/design-system.md (to be regenerated)
  - FE/src/theme/* (to be rebuilt)
  - BE/src/main/java/com/yeosal/api/ceremony/SvgRenderer.java (to be updated)
---

# Sprint Change Proposal — yeolsal v2 Design System Pivot

**Date**: 2026-05-10
**Author**: rearleg (via `/bmad-correct-course`)
**Scope**: Major

---

## 1. Issue Summary

### 1.1 Trigger

The design system foundation (Risograph + Neobrutalist) had been locked across UX spec §Design System Foundation, PRD strategic bet #5, and PRD FR-8.4.2 / FR-8.6.2 / FR-8.7.2. The lock was a strategic-differentiation bet (brand uniqueness as 5축 차별화 axis #5; Final-3 poster as free marketing asset).

The user opted to **release** the Risograph lock and pivot the visual identity to **"yeolsal v2 — Oxblood Editorial"** (Dark Luxury × Editorial fusion, oxblood key color), trendy/modern direction with red-adjacent warm tones.

The pivot is concurrent with three pre-existing readiness gaps surfaced in the implementation-readiness report (2026-05-10):
- **NFR-9.6.1 token gap** — `semantic.survival` token spec exists in UX, but no story has the AC and no enforcement mechanism is defined (M5 / H4 in readiness report).
- **U1 / U3 / U4 disposition gap** — three UX-introduced components (`<WelcomeWindow>`, `<KudosButton>`, `<RitualMoment>`) have no PRD authority and no story owner.
- **A5 architecture gap** — D1 Editorial sub-mode token export FE → BE renderer has no sync mechanism; readiness report flagged "mitigatable by manual sync at W6" as brittle.

### 1.2 Issue Category

- **Strategic pivot** (user discretion to change visual identity expression).
- **Misunderstanding / incompletion** (readiness report flagged 9 UX additions + 3 architecture gaps + 1 NFR enforcement gap; resolution had been deferred).

### 1.3 Evidence

- `ux-design-specification.md` lines 478–565 (Design System Foundation), 674–825 (Visual Design Foundation), 826–895 (Design Direction Decision), 1090–1287 (Component Strategy).
- `prd.md` line 76 (§2.3 strategic bet #5), 261–272 (§6.1 banned), 396 / 407 (FR-8.6.2, 8.7.2 Risograph reference), 478 (NFR-9.6.1).
- `implementation-readiness-report-2026-05-10.md` lines 459–462 (U1/U3/U4 disposition options), 495 (A5 sub-mode token sync gap), 698 (M5 NFR-9.6.1), 549–555 (top warnings summary).
- `architecture.md` §4.9 (line 290–298 — SVG renderer hardcoded base tokens; no sub-mode plumbing).
- User decision sequence: 2026-05-10 conversation — Decision A (Sub-option 2 Red-adjacent + Dark Luxury direction + 8주 budget 인지).

---

## 2. Impact Analysis

### 2.1 Epic Impact

| Epic | Impact | Action |
|---|---|---|
| Epic 1 (Survival State & Daily Loop) | **High** — design system foundation absorbed here; new stories needed | Add Story 1.5 (Design System Foundation v2), Story 1.6 (WelcomeWindow J0), Story 1.7 (RitualMoment) |
| Epic 2 (Spectator) | **Low** — token swap only; GrassGrid dimmed variant logic unchanged | No story changes; tokens land via Story 1.5 |
| Epic 3 (Revival Economy) | **High** — Friend Gift Modal becomes 3-CTA (Kudos added); M3.5 + 7-day footnote land here | Expand Story 3.2 AC; new Story 3.5 (Kudos endpoint) |
| Epic 4 (Group Point Pool) | **Low** — Pool styling reference rewords (FR-8.4.2 token reference) | No story changes |
| Epic 5 (Leader & Rule Versioning) | **Low** — D5 Plate System sub-mode (rename from D5 Brutalist) | No story changes |
| Epic 6 (KakaoTalk SDK) | **Medium** — invite preview card uses codegen pipeline (FR-8.6.2 reword) | No new story; Story 6.1 BE renderer follows §4.16 |
| Epic 7 (Final-3 Monthly) | **High** — BE SVG renderer refactored to consume `GeneratedTokens` | Expand Story 7.1 AC (codegen + sub-mode override) |
| Epic 8 (Brand Voice & Onboarding) | **Medium** — brand-voice lint extended to NFR-9.6.1 + design-token literal guard | Expand Story 8.2 AC (lint extension) |

**No epic is invalidated. No new epic needed. Sequencing unchanged: design system foundation work was already W1 territory; the rewrite happens in the same slot.**

### 2.2 Artifact Conflicts

| Artifact | Conflict | Resolution |
|---|---|---|
| **PRD** | §2.3 #5 names Risograph; §6.1 silent on red-color dignity guard; FR-8.4.2/8.6.2/8.7.2 hardcode Risograph token names; §14.2 missing v2 design lock; NFR-9.6.1 not enforced at token level | 9 PRD edits (G1) |
| **UX spec** | 4 sections (Design System Foundation / Visual Design Foundation / Design Direction Decision / Component Strategy) are Risograph-specific | 4 UX edits (G2) — archive Risograph version, replace with placeholder + decided guards; full content via `/bmad-create-ux-design` round |
| **Architecture** | §4.9 hardcodes base tokens; §4.15 brand-voice lint missing NFR-9.6.1 lint; §4.16 sub-mode token sync mechanism missing | 3 Architecture edits (G3) |
| **Epics** | No design-system foundation story; U1/U3/U4 components have no story; Story 7.1 doesn't reference codegen | 6 Epics edits (G4) — 4 new stories + 2 AC expansions |
| **`docs/design-system.md`** | Risograph palette lock | Regenerate after design round; canonical doc derived from `tokens.json` |
| **`FE/src/theme/`** | Risograph token files | Rebuild as `tokens.json` schema per §4.16 |
| **`BE` SVG renderer** | Hardcoded hex literals expected | Refactor to consume `GeneratedTokens.java` (Gradle codegen) |
| **`tools/brand-voice-lint.ts`** | AVOID lexicon only | Extend to lint `survival.*.color` references and design-token hex literals |

### 2.3 Technical Impact

- **8주 budget**: ~14–20 working days additional load for design rebuild. W1–W3 likely fully consumed by foundation work. Phase-1.5 contingency for polish-tier deferrals (e.g., themed-room presets, NoiseOverlay re-evaluation) acknowledged.
- **No data migration required** — token rebuild is FE/BE code-only; `survival_state` enum values unchanged.
- **`feat/realtime-websocket` branch** (currently in flight) is unaffected — realtime infra is design-agnostic. Normal merge OK.
- **Strategic risk**: PRD §2.3 strategic bet #5 thesis (brand uniqueness as retention asset) preserved — only the *expression* changes. New falsification trigger added: Day-30 share-rate < 15% surviving members → revisit.

---

## 3. Recommended Approach

### 3.1 Selected Path: **Option C (Full design system replacement) + Sub-option 2 (Red-adjacent / Oxblood) + Dark Luxury × Editorial direction**

### 3.2 Rationale

- User explicitly opted for replacement (not minimal U1/U3/U4 disposition + minor token guard tightening).
- Sub-option 2 (oxblood / red-adjacent warm) preserves dignity-tone thesis (PRD §2.3 #1 + §6.1 + FR-8.8.5 + brand-voice lexicon FR-8.8.2) while honoring user intent (트렌디 + 모던 + 붉은 강조).
- Dark Luxury × Editorial direction is the trendy modern direction with the best red-key-color compatibility per current design references (`~/.claude/rules/web/design-quality.md` worthwhile directions matrix).
- 3 coupled decisions (NFR-9.6.1 enforcement / U1·U3·U4 disposition / D1 sub-mode BE↔FE sync) are resolved in the same pass — efficient single-pivot SCP rather than 3 separate course corrections.

### 3.3 Trade-offs

| Trade-off | Cost | Mitigation |
|---|---|---|
| 8주 budget pressure | W1–W3 consumed by foundation rebuild | Phase-1.5 contingency for polish; falsification trigger limits commitment |
| Loss of existing Risograph asset value (`docs/design-system.md`, `FE/src/theme/`) | ~5 dev-days of prior work archived | Risograph version archived to `archive/ux-design-specification-v1-risograph-2026-05-10.md` for revival reference if needed |
| Strategic-bet falsification risk | New visual identity unproven | Day-30 share-rate trigger (< 15% surviving members → revisit) |
| KR-cultural reception of dark luxury | Untested with persona Sarah | Validation Plan Day-7 includes 5-user diary study; surface "다크 톤이 압박적인가?" question |

### 3.4 Alternatives Considered

- **Option A (Minimal — keep Risograph, only resolve U1/U3/U4 dispositions + NFR-9.6.1 enforcement)** — rejected per user.
- **Option B (Hybrid — keep Risograph palette, refactor sub-mode model + token enforcement)** — rejected per user.
- **Option C with Sub-option 1 (Pure RED key color)** — rejected; conflicts with PRD §2.3 #1 dignity thesis, §6.1 anti-shame, FR-8.8.5 anti-탈락 language, FR-8.8.2 brand-voice lexicon. Would require parallel `/bmad-edit-prd` round to redefine product thesis.
- **Option C with Sub-option 3 (RED accent only, neutral key)** — rejected per user (user wants red as *key*, not just accent).

---

## 4. Detailed Change Proposals

### 4.1 PRD Edits (G1 — 9 edits)

#### G1.1 — §2.3 strategic bet #5 visual identity rename

```diff
- 5. **Risograph + neobrutalist visual identity.** Habit apps trend toward minimalist Calm/Notion aesthetics. The existing yeolsal `design-system.md` is genuinely distinctive. The Final-3 monthly poster makes survival a *shareable* visual artifact — every win produces a free marketing asset.
+ 5. **Oxblood Editorial visual identity (Dark Luxury × Editorial 융합).** Habit apps trend toward minimalist Calm/Notion aesthetics. yeolsal v2 ships an opinionated dark-luxury system with oxblood as key color, editorial typography, and high-contrast hierarchy. The Final-3 monthly poster makes survival a *shareable* visual artifact — every win produces a free marketing asset. Brand uniqueness remains a 5축 차별화 axis; only the *expression* changes from Risograph to Oxblood Editorial. *Falsification trigger:* if Day-30 share-rate of Final-3 poster < 15% of surviving members, revisit visual direction.
```

#### G1.2 — §6.1 dignity-color guard

```diff
  ### 6.1 Banned across all phases
  ...
  - **Auto-broadcast on account deletion** — privacy violation.
+ - **Pure-red color (`oklch hue 20-30°` at high chroma) used as alarm/blood signal on elimination, RED-card, or spectator surfaces** — dignity violation. Red-adjacent warm tones (oxblood, crimson, maroon, burgundy) used as *brand identity* are permitted; red as *failure signal* is not.
```

#### G1.3 — §4.3 J0 user journey insertion

```diff
  ### 4.3 v1 User Journeys

+ **J0 — Cold-start leader's lonely 30 seconds (방장의 외로운 30초)**
+ - Trigger: Leader 방 생성 → max_members picker → POST `/api/v1/rooms` 성공 → Welcome 화면 (방원 = leader 1명).
+ - Anti-pattern guard: 진행 막대 ("11명 더 들어와야 시작") ❌ — A10 anti-pattern.
+ - Surface: `<WelcomeWindow>` D3-Quiet (or v2 equivalent) 톤. CTA 2개 동등: (a) 카카오로 친구 초대, (b) 먼저 오늘 기록하기.
+ - Member growth 시 system message warm tone broadcast.
+ - KPI: activation 60% / 24h (방장 이탈 방지).
+ - PRD ref: 신규 §FR-8.1.8 (J0 surface), Story 1.6.

  **J1 — Cold-start friend-graph onboarding** ...
```

#### G1.4 — FR-8.3.9 Kudos message endpoint

```diff
  **FR-8.3.8** — `personal_points_ledger` is append-only; balance is computed as `SUM(delta)` per `(user_id, room_id)`. On leaving a room, balance is forfeit (PRD §6.3 decision).

+ **FR-8.3.9** — **Kudos message ("응원만 보내기 / 0점")**: the Friend Gift Modal exposes a 2nd CTA equal in visual + a11y weight to the revival-gift CTA. Endpoint `POST /api/v1/rooms/{id}/kudos { targetUserId, message? }` posts a `chat_messages` row with `kind = 'KUDOS'`, `payload = { sender_user_id, target_user_id, message }`. Cost: 0 personal points. No `revival_events` row, no `survival_state` change. One push to receiver: invitation tone ("정민이 응원을 보냈어요"). Donor toast on success. Race: idempotent via partial unique index `ux_kudos_one_per_day (sender_id, target_id, date_part('day', created_at at time zone 'Asia/Seoul'))` — 같은 날 같은 receiver에게 1회만 송신 가능.
```

#### G1.5 — FR-8.4.2 token reference reword

```diff
- **FR-8.4.2** — Room UI shows the current pool prominently (Risograph styling per design system). Pool is visible to all room members including spectators and former eliminated users still in the room.
+ **FR-8.4.2** — Room UI shows the current pool prominently (yeolsal v2 design tokens per `docs/design-system.md`). Pool is visible to all room members including spectators and former eliminated users still in the room.
```

#### G1.6 — FR-8.6.2 token reference reword

```diff
- **FR-8.6.2** — `previewCardImageUrl` is generated by a server-side renderer using room name, current rule summary, member count, and Risograph design tokens. Cached with TTL 1h; regenerated on rule/member-count change.
+ **FR-8.6.2** — `previewCardImageUrl` is generated by a server-side renderer using room name, current rule summary, member count, and yeolsal v2 design tokens (sourced from FE-generated `tokens.json` per Architecture §4.16). Cached with TTL 1h; regenerated on rule/member-count change.
```

#### G1.7 — FR-8.7.2 token reference reword + sub-mode codegen

```diff
- **FR-8.7.2** — Poster generation is **server-side SVG** rendering using the existing Risograph design tokens (`ink`, `paper`, `pink`, `green`, `acid`, `muted`). Layout: room name at top, all surviving member nicknames listed, top-3 by tenure highlighted with pink + green accents.
+ **FR-8.7.2** — Poster generation is **server-side SVG** rendering using yeolsal v2 design tokens, consumed from the FE→BE token codegen pipeline (Architecture §4.16). Sub-mode `D1 Editorial` token override set is applied. Layout: room name at top, all surviving member nicknames listed, top-3 by tenure highlighted with key color (oxblood) + secondary accent. Specific token names are TBD per `/bmad-create-ux-design` round and codified in `docs/design-system.md`.
```

#### G1.8 — NFR-9.6.1 packed type + CI lint hard gate

```diff
- **NFR-9.6.1** — Color is never the sole information carrier (per existing design-system.md): risograph palette decisions for survival state must include text labels (예: "ACTIVE", "노란 카드", "빨간 카드", "관전").
+ **NFR-9.6.1** — Color is never the sole information carrier. The `semantic.survival` design token is a **packed type** of `{ color, label, icon, grass-treatment }` per state (`ACTIVE` / `YELLOW` / `RED` / `SPECTATOR`); consuming code cannot reference the color field without also rendering the label. Brand-voice + a11y lint (Architecture §4.15) verifies no `survival.*.color` reference appears in JSX/TSX without a sibling label or `accessibilityLabel`. Verified in CI as a hard gate.
```

#### G1.9 — §14.2 locked decisions additions

```diff
  | International expansion | v3 (KR-deep first 24 months minimum) | User Q7 lock-in |
+ | Visual identity (v1) | yeolsal v2 — Oxblood Editorial (Dark Luxury × Editorial), oxblood key color, dignity-tone preserved | Sprint Change Proposal 2026-05-10, replaces Risograph + Neobrutalist |
+ | NFR-9.6.1 enforcement | Token packed type (`semantic.survival`) + CI lint hard gate | Sprint Change Proposal 2026-05-10 |
+ | FE↔BE token sync | Codegen pipeline — FE owns `tokens.json` → Gradle task generates Java constants | Sprint Change Proposal 2026-05-10 (Architecture §4.16) |
```

### 4.2 UX Spec Edits (G2 — 4 edits + archive)

#### G2.0 — Risograph version archive

Copy current `_bmad-output/planning-artifacts/ux-design-specification.md` → `_bmad-output/planning-artifacts/archive/ux-design-specification-v1-risograph-2026-05-10.md` (preservation; canonical reference if v2 fails Day-30 falsification trigger).

#### G2.1 — §Design System Foundation (lines 476–565) replaced with placeholder + decided guards

Placeholder retains 4-layer token-driven structure, NFR-9.6.1 packed type guard, FE→BE codegen reference, dignity-tone preservation, falsification trigger. Specific palette/typography/motion values land via `/bmad-create-ux-design` round.

#### G2.2 — §Visual Design Foundation (lines 674–825) replaced

- **Color System**: TBD via design round; key=oxblood; base=dark luxury; L2 survival packed type structure preserved (color values TBD).
- **Typography**: Pretendard primary retained; type scale (4px base) retained; specific weights TBD.
- **Spacing**: 4px base preserved; hard-offset shadow guard *released* (subtle blur permitted in dark luxury).
- **Motion**: token names preserved; values TBD; Reanimated 3 layer + Skia constraint preserved.
- **Accessibility**: NFR-9.6.1 packed type + lint; WCAG 2.2 AA contrast re-verified against v2 palette.

#### G2.3 — §Design Direction Decision (lines 826–895) replaced

5 sub-mode candidates (D1 Editorial Spread / D2 Bento Density / D3 Quiet Dark / D4 Postcard Mythic / D5 Plate System) — actual mockups via design round. Surface-Hybrid approach preserved. Implementation Approach (subMode prop, page-level injection, BE codegen for D1) preserved.

#### G2.4 — §Component Strategy U1–U9 disposition lock

Component Inventory tables now include `Disposition` and `Story ownership` columns. U1–U9 dispositions:

| # | Element | Disposition | Owner |
|---|---|---|---|
| U1 | `<WelcomeWindow>` | ACCEPT | Story 1.6 (NEW) |
| U2 | M3.5 lifetime-1 | ACCEPT | Story 3.2 AC expansion |
| U3 | `<KudosButton>` | ACCEPT | Story 3.5 (NEW) + Story 3.2 AC |
| U4 | `<RitualMoment>` | ACCEPT | Story 1.7 (NEW) |
| U5 | 48h recovery window | DEFER (v1.5) | — |
| U6 | Donor-protection signal | DEFER (v1.5) | — |
| U7 | Auto digital sabbatical | DEFER (conflicts FR-8.3.1) | — |
| U8 | 7-day echo footnote | ACCEPT | Story 3.2 AC expansion |
| U9 | Spectator gentle prompt | DEFER (v1.5) | — |

W1 spec lock items updated to 5 (Design 라운드 lock / FE→BE codegen / WS event schema for `gift.revive.*` + `kudos.sent` / Analytics SDK / NFR-9.6.1 lint spec).

### 4.3 Architecture Edits (G3 — 3 edits)

#### G3.1 — §4.9 SVG renderer consumes `GeneratedTokens`

Decision reworded: tokens via codegen (`GeneratedTokens` Java class), no hex literals in renderer code, sub-mode `D1 Editorial` override applied at render time, FE/BE drift structurally impossible.

#### G3.2 — §4.15 brand-voice lint extended

Lint adds (1) AVOID lexicon (existing, warn), (2) NFR-9.6.1 enforcement (NEW, *hard CI gate*), (3) design-token literal guard for hex/rgb in component code (warn).

#### G3.3 — §4.16 NEW: FE↔BE Design Token Codegen Pipeline

`FE/src/theme/tokens.json` is canonical source. BE Gradle task `generateTokens` reads it, emits `BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java`. Schema includes `color`, `semantic.survival` (packed), `subMode.{editorial|bento|quiet|postcard|plate}` overrides. Replaces readiness report Step-4 A5 gap.

```json
{
  "version": "2.0.0",
  "system": "yeolsal v2 — Oxblood Editorial",
  "color": { "key": "oklch(...)", "ink": "oklch(...)", "paper": "oklch(...)", "...": "..." },
  "semantic": {
    "survival": {
      "ACTIVE":    { "color": "...", "label": "활동 중",   "icon": "✓" },
      "YELLOW":    { "color": "...", "label": "노란 카드", "icon": "⚠" },
      "RED":       { "color": "...", "label": "빨간 카드", "icon": "❤" },
      "SPECTATOR": { "color": "...", "label": "관전 중",   "icon": "◐" }
    }
  },
  "subMode": {
    "editorial": { "...": "overrides" },
    "bento":     { "...": "overrides" },
    "quiet":     { "...": "overrides" },
    "postcard":  { "...": "overrides" },
    "plate":     { "...": "overrides" }
  }
}
```

### 4.4 Epics Edits (G4 — 6 edits)

#### G4.1 — Story 1.5 (NEW): Design System Foundation v2 — token packed type + FE↔BE codegen

5 ACs: tokens.json schema validation; Gradle `generateTokens` task produces `GeneratedTokens.java`; brand-voice lint hard gate for `survival.*.color` without label; subMode prop consumes overrides; v2 palette WCAG 2.2 AA verified. PRD ref §14.2, NFR-9.6.1, NFR-9.6.3. Architecture §4.15 / §4.16.

#### G4.2 — Story 1.6 (NEW): WelcomeWindow J0

7 ACs: J0 trigger render; no progress-bar; 2 equal CTA (Kakao / Today); state machine solo→growing→full; warm system message on member growth; brand-voice lint pass. PRD ref §4.3 J0, FR-8.1.1, FR-8.1.2.

#### G4.3 — Story 3.2 AC expansion: 3 CTA + Kudos + 안심 + lifetime-1 + 7-day footnote

Modal renders 3-CTA equal weight (회생권/응원만/닫기) + 안심 message; Kudos path inserts chat_messages KIND='KUDOS'; balance < 5 disables 회생권 CTA only; ALREADY_REVIVED 409 path; M3.5 lifetime-1 marker on first FRIEND_GIFT *send*; 7-day echo footnote on receiver daily-entry footer with auto-decay.

#### G4.4 — Story 3.5 (NEW): Kudos endpoint + chat_messages.kind extension

Migration adds `'KUDOS'` to chat_messages.kind enum; `POST /api/v1/rooms/{id}/kudos` validates membership/friendship/spectator-write; partial unique index `ux_kudos_one_per_day` enforces 1/day/receiver; push to receiver invitation tone; `RealtimeEvent.KudosSent` to /topic/rooms/{id}/kudos.

#### G4.5 — Story 1.7 (NEW): RitualMoment 06:00 KST sacred wrapper

5-second ritual on app open in 06:00–06:05 KST window; variant text by KST weekday + month-1 ceremony hint; reduced-motion = 1s fade fallback; idempotent per KST date; spectator dimmed variant; VoiceOver/TalkBack announce; non-blocking app start. PRD root authority §6.4 principle 5.

#### G4.6 — Story 7.1 AC expansion: codegen + sub-mode override + drift integration test

Renderer uses `GeneratedTokens` constants (no hex literals); `tokens.json`-only change → SVG output diffs in integration test; new BE renderers blocked from hex literals via Checkstyle/ArchUnit rule.

---

## 5. Implementation Handoff

### 5.1 Scope Classification: **Major**

### 5.2 Three-phase Routing

**Phase H1 — Design 라운드 (Owner: PM + UX designer + 사용자)**

- Command: `/bmad-create-ux-design`
- Deliverables: palette/typography/motion oklch values; 5 sub-mode mockups; `ux-design-directions.html` v2; `docs/design-system.md` v2; `FE/src/theme/tokens.json` (Architecture §4.16 schema).
- Estimate: 3–5 working days. **Hard W1 deadline: round-start + 5 working days.**

**Phase H2 — Architect implementation prep (Owner: Architect / 사용자 본인)**

- `BE/build.gradle` `generateTokens` Gradle task + JSON schema validator.
- `tools/brand-voice-lint.ts` NFR-9.6.1 lint extension spec.
- Decide V11 vs V12 placement for `chat_messages.kind` enum extension + `ux_kudos_one_per_day` partial unique index.
- Lock WS event schema: `gift.revive.sent` / `gift.revive.received` / `kudos.sent`.
- Estimate: 1–2 working days.

**Phase H3 — Developer agent implementation (Owner: dev-story / dev agent)**

Sequencing (post H1 + H2 lock):

| Week | Stories |
|---|---|
| W1 | Story 1.5 (foundation + codegen) → Story 1.4 update (V11 / V12 schema additions) |
| W2 | Story 1.6 (WelcomeWindow J0) |
| W3 | Story 3.5 (Kudos endpoint) → Story 3.2 (Modal AC expansion) |
| W4 | Story 3.4 (Wallet — token swap only) |
| W5 | Epic 6 (Kakao SDK — codegen consumption) |
| W6 | Story 7.1 expansion (BE renderer codegen) + Story 1.7 (RitualMoment) |
| W7 | Onboarding + brand-voice-lint extension |

Estimate: 14–18 working days. Phase-1.5 contingency for polish-tier deferrals (themed-room presets, NoiseOverlay re-evaluation, U5/U6/U9).

### 5.3 Success Criteria

- All 22 edits applied to source artifacts (PRD / UX spec / Architecture / Epics) in a single PR per artifact (or one mega-PR if branch hygiene allows).
- `tokens.json` lands in `FE/src/theme/` and validates against §4.16 schema.
- `BE` builds with new `generateTokens` task; `SvgRenderer.java` references `GeneratedTokens` constants only.
- `tools/brand-voice-lint.ts` extension fails CI on any `survival.*.color` reference without sibling label.
- W1 design 라운드 산출물 (palette + 5 sub-mode mockups + tokens.json) signed off by user before Story 1.5 land.
- Day-30 falsification trigger telemetry instrumented (Final-3 poster share-rate per surviving member).

### 5.4 Risk Monitor

- **Design round delay** → Story 1.5 + cascading Epic 1 delay; W1 hard deadline = +5 working days.
- **Day-30 falsification** → if share-rate < 15%, separate SCP to revisit visual direction (likely partial rollback or third pivot).
- **`feat/realtime-websocket` branch** → no impact; normal merge OK.
- **Hex-literal regression risk** → ArchUnit rule (Story 7.1 AC) blocks future renderers from bypassing codegen.

---

## 6. Approval

User pre-approved this proposal in auto-mode flow on 2026-05-10 (Decision A: Sub-option 2 Red-adjacent + Dark Luxury × Editorial direction + 8주 budget 인지).

3 coupled decisions resolved per default values:

1. NFR-9.6.1 → packed type + lint hard gate
2. U1 / U3 / U4 → ACCEPT (Stories 1.6 / 3.5 / 1.7) + U2 / U8 ACCEPT (Story 3.2 AC) + U5 / U6 / U7 / U9 DEFER to v1.5
3. D1 sub-mode FE↔BE → codegen pipeline (Architecture §4.16)

---

## 7. Next Steps

1. **Immediately (today)**: Apply 22 source-artifact edits per §4 to `prd.md` / `ux-design-specification.md` / `architecture.md` / `epics.md`. Archive Risograph UX spec.
2. **Within 1 working day**: Trigger `/bmad-create-ux-design` round (Phase H1).
3. **Within 5 working days** (W1 hard deadline): Lock `tokens.json` + sub-mode mockups + `docs/design-system.md` v2.
4. **Within 7 working days**: Phase H2 architect prep complete (Gradle task + lint extension spec + WS event schema lock).
5. **Week 1 of Phase H3**: Story 1.5 land.
6. **Day 30 post-launch**: Falsification trigger evaluation (Final-3 share-rate).

---

*End of Sprint Change Proposal.*
