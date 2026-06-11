# Story 8.4: Release-gate brand-voice review

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the PM and lead designer,
I want a documented, version-controlled release-gate **checklist** for brand-voice review plus the **sign-off mechanism** that enforces it (a per-release instance file signed via GitHub PR approval from a PM reviewer + a designer reviewer, backed by `CODEOWNERS`, a required role-verification workflow, and branch/tag rulesets),
so that brand integrity is preserved as new copy is written for new features — no production release ships without a joint PM + designer brand-voice sign-off.

**Strategic context (read once before opening any file):**

- **This is a docs + repo-config story — zero app code.** No FE source, no BE source, no migration, no new endpoint, no STOMP topic, no `tokens.json` edit, no native surface, no new dependency, and **no new lint tool**. The deliverable is (1) a versioned Markdown checklist, (2) a per-release instance template, and (3) a `CODEOWNERS` file that wires the human sign-off gate. This is the **final story of Epic 8 and of the v1 W7 sprint** — it closes the brand-voice loop that every prior copy story (8.1 onboarding, 8.2 lint, 8.3 ASO) fed into.
- **The checklist is the primary deliverable; the required workflow is the enforcement.** `docs/brand-voice-review.md` is the canonical checklist. Each release adds one per-release instance. `CODEOWNERS` requests review, while `.github/workflows/brand-voice-release-gate.yml` verifies the checklist and one approval from each configured role. A `main` branch ruleset requires that check; a separate `v*` tag ruleset protects tag creation.
- **The sign-off mechanism + SLA are LOCKED (2026-05-11) — implement, do not redesign.** The epics AC froze: Markdown checklist in repo → per-release instance `docs/releases/brand-voice-review-<version>.md` → GitHub PR review approval from the PM-designated reviewer (GH handle in `CODEOWNERS`) **and** the designer-designated reviewer → CI/branch-protection requires both approvals before the release-tag push (epics.md:1068). Needs-rewrite SLA: flagged items addressed within **1 business day**; if the next release window is **< 24h** away, the affected feature is **feature-flagged off** rather than shipped with an unresolved brand-voice flag (epics.md:1081). Do NOT invent a different mechanism (no Slack approvals, no Google-form sign-off, no custom CI script that "auto-passes" the gate).
- **The existing lint tools are the automated PRE-PASS, not the gate.** `tools/brand-voice-lint.ts` (Story 8.2), `tools/aso-copy-lint.ts` (Story 8.3), and `tools/contrast-check.ts` already run in `scripts/test.sh`. They are **warn-only** (except the one NFR-9.6.1 hard sub-gate in brand-voice-lint) by Architecture §4.15 — "human review remains authoritative." Story 8.4 does NOT add a tool and does NOT promote any lint to a hard gate. The checklist instructs the reviewer to attach the lint output as the automated baseline, then apply human judgment on top. This is the deliberate division of labor: machines flag candidates, humans decide (epics.md:1079–1081, architecture.md:404, 415).
- **The checklist enumerates the FIVE copy surfaces named in the epics AC — with real source pointers.** The AC (epics.md:1070–1075) fixes exactly five categories: push notification copy paths (BE/FE), all onboarding screens, all error message strings, all store metadata (KR + EN), and system message templates in `chat_messages`. The doc must make each category **actionable** — list the actual files/sites in this codebase a reviewer opens (see AC1 + Dev Notes), not a vague "review the copy."
- **Rulesets are GitHub settings, not committed files.** The repository ships the required workflow and CODEOWNERS review requests; post-merge actions configure the disjoint role variables, require the check on `main`, and add a separate `v*` tag ruleset.
- **Reviewer handles default to the solo maintainer with an honest caveat.** The repo's git user is `rearleg` and v1 is a solo build, so the committed `CODEOWNERS` assigns the review paths to `@rearleg` as the role placeholder for both PM and designer, with an inline comment + a Post-merge action to split into two **distinct** GitHub accounts when the team grows. Be truthful about the limitation: GitHub will not let one account satisfy a "require 2 approvals + require Code Owner review" rule by approving its own PR — a genuine two-party gate needs two distinct handles. Do NOT pretend a single-account setup enforces dual sign-off; document the real behavior and the upgrade path.

## Acceptance Criteria

### AC0 — Existing infrastructure inventory (NO REWORK, READ ONLY)

**Read these before writing a line. Each MUST stay byte-identical unless it appears in the AC8 MODIFIED list.**

- `tools/brand-voice-lint.ts` (Story 8.2, PR #104) — the AVOID-lexicon + NFR-9.6.1 packed-type + hex-literal lint. **Warn-only for the AVOID lexicon; one hard CI sub-gate for the `semantic.survival.*` sibling-label rule** (architecture.md:409–411). The checklist references it as the automated pre-pass for the push/onboarding/error/system-message surfaces. **Do NOT modify it.**
- `tools/aso-copy-lint.ts` + `docs/aso-copy.md` (Story 8.3, PR #105) — warn-only ASO copy lint over the KR/EN storefront regions. The checklist's "All store metadata (KR + EN)" item reviews `docs/aso-copy.md`; aso-copy-lint is its automated pre-pass. **Do NOT modify either.**
- `tools/contrast-check.ts` — WCAG 2.2 AA contrast gate (Story 1.5 AC13). Mentioned in the checklist's a11y note only; not in scope to change.
- `scripts/test.sh:19–30` — the tools block that runs brand-voice-lint → contrast-check → analytics-taxonomy-lint → aso-copy-lint → `tsx --test`, all inside a silent-skip-when-uninstalled guard. **Story 8.4 does NOT add a tool here.** This block is the "run the lint pre-pass" command the checklist points to. Leave it byte-identical.
- `.github/workflows/be-it-boot-smoke.yml` — the existing BE opt-in IT workflow. Story 8.4 does not modify it; the review patch adds a separate brand-voice release-gate workflow.
- `docs/aso-copy.md` — the **doc-shape precedent**: a human-readable Markdown governance doc with a "Governance / Enforcement" section and a "References (sources of truth)" section. `docs/brand-voice-review.md` follows the same register and cross-links back to it (the store-metadata checklist item).
- `_bmad-output/implementation-artifacts/8-3-aso-copy-lock-for-app-store-google-play-kr.md` — the immediately-prior Epic 8 sibling. Reuse its conventions: strategic-context preamble, AC0 read-only inventory, locked scope-fence allow list, grep-based verify-gate matrix, "Post-merge user action" framing, comment hygiene (no task/PR refs in committed artifacts).
- The locked sign-off + SLA text: `epics.md:1058–1083` (Story 8.4 ACs). The five checklist categories: `epics.md:1070–1075`. The brand-voice lexicon: `architecture.md:538–543` (§5.5), `prd.md:442–448` (FR-8.8.2/.3/.5/.6).

### AC1 — `docs/brand-voice-review.md` (NEW): the canonical release-gate checklist + governance

**File:** `docs/brand-voice-review.md` — NEW.

**Given** a release candidate is built,
**When** the release-gate runs,
**Then** the brand-voice review checklist exists in the repo, is signed off jointly by PM + designer, and the doc fully describes the sign-off mechanism, the inline decision recording, and the needs-rewrite SLA.

The doc MUST contain these sections:

1. **Purpose + how it gates a release** — one short paragraph: brand integrity gate before every major release; the hard stop is joint PM + designer sign-off enforced by `CODEOWNERS` + `release/*` branch protection; no override.
2. **Automated pre-pass (run first, attach output).** Lists the existing tools the reviewer runs before human review and attaches the output of:
   - `tools/brand-voice-lint.ts` — AVOID-lexicon WARN occurrences + the NFR-9.6.1 hard sub-gate result (must be 0 HARD).
   - `tools/aso-copy-lint.ts` — store-copy WARN occurrences.
   - `tools/contrast-check.ts` — a11y contrast (note only).
   - The single command: `bash scripts/test.sh` (tools block), or per-tool `tools/node_modules/.bin/tsx tools/<tool>.ts`. State plainly: **lint is advisory; the human sign-off is authoritative** (Architecture §4.15).
3. **The checklist — five surface categories (epics.md:1070–1075), each actionable.** A checkbox group per category, each naming the concrete sites a reviewer opens in *this* codebase:
   - **Push notification copy paths (BE + FE).** BE: `BE/src/main/java/com/yeosal/api/notification/` — esp. `NotificationService.java`, `SpectatorDigestScheduler.java`, `NotificationScheduler.java` (Korean push-body string literals, e.g. `SpectatorDigestScheduler.java:79` `"오늘도 %s 함께 살아남고 있어요"`). FE: push presentation in `FE/src/lib/notifications.ts` / `FE/src/lib/push.ts` and any notification-settings copy. Tone rule: **invitation, not demand** (FR-8.8.3; "수진이 회생을 기다리고 있어요" ✓, not "수진이가 죽었다 살려라!" ✗).
   - **All onboarding screens.** `FE/app/onboarding.tsx` + `FE/src/components/onboarding/OnboardingCarousel.tsx` (+ `OnboardingDotIndicator.tsx`) — the 5-screen script + change-summary screen + the PIPA consent copy (Story 8.1). Verify against the FR-8.8.1 locked strings.
   - **All error message strings.** BE: domain exceptions + `com.yeosal.api.common.ApiExceptionHandler` user-facing messages. FE: `FE/src/lib/toast.ts` + per-screen `ApiError.code` branch copy. Elimination/error copy uses **"컴백 가능" language, not "탈락"/"실패"** (FR-8.8.5).
   - **All store metadata (KR + EN).** `docs/aso-copy.md` (Story 8.3) — KR uses "회생권", EN uses "comeback pass", never "revival ticket"/"second chance pass". This item's artifact IS that file; aso-copy-lint is its pre-pass.
   - **System message templates in `chat_messages`.** `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` — the `publish…SystemMessage` methods (e.g. rule-change broadcast body "다음 달부터 새 규칙이 적용됩니다: …" from Story 5.4; the monthly no-survivors ceremony body "이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요" from Story 7.1). These are byte-locked SYSTEM-row templates; the review confirms any new ones stay in voice.
4. **Sign-off mechanism (LOCKED).** Verbatim-faithful to epics.md:1068: the checklist lives in the repo; each release's instance is duplicated as `docs/releases/brand-voice-review-<version>.md`; sign-off is a **GitHub PR review approval** from the PM-designated reviewer (GH handle in `CODEOWNERS`) **and** the designer-designated reviewer; the `release/*` branch-protection rule + `CODEOWNERS` require both approvals before the release-tag push. Cross-reference `.github/CODEOWNERS` (AC3) and the Post-merge branch-protection action (AC4).
5. **Inline decision recording (LOCKED).** When the lint flags an item (or a human spots one), the reviewer records the decision **inline in the per-release Markdown** as one of `accept` / `reject` / `needs-rewrite`, with the reviewer's GH handle and the date. Provide the exact row format used in the template (AC2).
6. **Needs-rewrite SLA (LOCKED, epics.md:1081).** Flagged items addressed within **1 business day**. If the next release window is **< 24h** away, the affected feature is **feature-flagged off** rather than shipped with an unresolved flag. No override; re-run the checklist after the fix.
7. **Versioning + maintenance.** The checklist is versioned in the repo and updated as new copy surfaces are added in future releases (epics.md:1083). State the rule: any PR that introduces a new user-facing copy surface adds it to this checklist's category list.

The doc is **evergreen** (no version number, no sign-off blanks) — it is the template-of-record. Per-release sign-offs live in the AC2 instance files.

### AC2 — `docs/releases/brand-voice-review-TEMPLATE.md` (NEW): per-release instance template

**File:** `docs/releases/brand-voice-review-TEMPLATE.md` — NEW (the directory `docs/releases/` is created by this file).

**Given** a release candidate is built,
**When** the release-gate runs,
**Then** a copy of this template is created as `docs/releases/brand-voice-review-<version>.md`, its checkboxes filled, its decision log completed, and it is approved by both `CODEOWNERS` reviewers in the release PR.

The template MUST contain:

- A header with `Release version: <fill>`, `Date: <fill>`, `Release PR: <fill>`.
- A **sign-off block** with two explicit slots — `PM reviewer (@handle): [ ] approved` and `Designer reviewer (@handle): [ ] approved` — and a note that the authoritative approval is the **GitHub PR review**, not the checkbox (the checkbox is a human-readable mirror).
- The **automated pre-pass attachment** slot: paste/link the `brand-voice-lint` (HARD count must be 0) + `aso-copy-lint` + `contrast-check` output for this release.
- The **five surface checklists** (same categories as AC1.3) as fillable checkboxes.
- A **decision log table** with the locked columns: `| Surface | Item / file:line | Lint? | Decision (accept/reject/needs-rewrite) | Reviewer (@handle) | Date | Notes |`.
- A one-line reminder of the needs-rewrite SLA (1 business day; feature-flag-off if < 24h).
- A clear "TEMPLATE — copy to `brand-voice-review-<version>.md`; do not sign this file" banner at the top so the template itself is never mistaken for a signed instance.

Keep the template lean and copy-pasteable. Use `<…>` placeholders, never real handles/versions/dates (those belong in the instance copy).

### AC3 — `.github/CODEOWNERS` + required workflow (NEW): request and verify both roles

**File:** `.github/CODEOWNERS` — NEW.

**Given** a release PR touches the brand-voice review artifacts,
**When** the PR is opened,
**Then** GitHub requests the code owners, and the required workflow independently verifies one PM approval and one designer approval.

Requirements:

- Assign the per-release sign-off files to both reviewer handles for review requests:
  ```
  /docs/releases/             @rearleg @rearleg
  /docs/brand-voice-review.md @rearleg @rearleg
  ```
  GitHub treats multiple owners on one line as alternatives; this file alone does not require both.
- `.github/workflows/brand-voice-release-gate.yml` verifies exactly one per-release checklist,
  complete surface checkboxes, no unresolved template placeholders, valid rewrite-disable evidence,
  and approval by accounts from the disjoint `BRAND_VOICE_PM_REVIEWERS` and
  `BRAND_VOICE_DESIGNER_REVIEWERS` repository variables.
- A header comment block (CODEOWNERS uses `#`) stating: these are the brand-voice release-gate reviewers — the **PM-designated** and **designer-designated** handles (epics.md:1068); replace the `@rearleg` placeholders with the real PM and designer GitHub accounts (Post-merge action, AC4); a single account cannot satisfy a two-approval rule by approving its own PR — distinct accounts are required for a genuine joint gate.
- Do **not** broadly own all copy surfaces in CODEOWNERS (it would route every FE/BE copy PR through brand-voice reviewers and create friction). Owning the **release-review artifacts** is the focused, AC-faithful rule. (An optional hardening note — owning `docs/aso-copy.md` / the onboarding dir too — may be mentioned in the doc as a future option, but is NOT added to CODEOWNERS in this story.)
- Place the file at `.github/CODEOWNERS` (GitHub resolves CODEOWNERS from repo root, `.github/`, or `docs/`; `.github/` is chosen to sit alongside the existing `.github/workflows/`). Use valid CODEOWNERS syntax (leading-slash anchored paths; `@handle` owners; `#` comments). Invalid syntax silently disables ownership — verify it parses (AC7 gate 5).

### AC4 — Post-merge user actions documented (GitHub-side settings)

**File:** captured in `docs/brand-voice-review.md` (a "Post-merge user action" section) AND surfaced in the eventual PR body.

These are GitHub repo settings that **cannot** be a committed file — document them precisely so the gate is actually live after merge:

1. **Replace CODEOWNERS placeholders.** Swap the `@rearleg` placeholders for the real **PM-designated** and **designer-designated** GitHub accounts. For a true two-party gate they must be two **distinct** accounts.
2. **Enable a `main` branch ruleset.** Release PRs use `release/v<x.y.z>` as head and `main` as base. Require the `Brand-voice release gate / PM + designer brand-voice approval` status check and dismiss stale approvals.
3. **Enable a separate `v*` tag ruleset.** Restrict release-tag creation/update/deletion to release maintainers; branch protection does not protect tags.
3. **Per-release ritual.** For each release: branch `release/v<x.y.z>`, copy `docs/releases/brand-voice-review-TEMPLATE.md` → `brand-voice-review-v<x.y.z>.md`, run the lint pre-pass, fill the checklist + decision log, open the release PR, obtain both code-owner approvals, then tag.

Do NOT attempt to configure branch protection from code (no committed file can do it; no `gh` call is made by this story — it's a documented human action, exactly like Story 8.3's screenshot upload and Story 1.4's prod migration).

### AC5 — Brand-voice lexicon + rule reference embedded (single source of truth link)

**File:** `docs/brand-voice-review.md` — a "Reference" section (mirrors `docs/aso-copy.md`'s reference section).

- **USE lexicon:** 함께, 선물, 응원, 컴백, 회생, 그룹, 동료, 우리, 살리다 (architecture.md:540, prd.md:443).
- **AVOID lexicon (8):** 벌금, 잃었다, 떨어졌다, 실패, 자책, 부담, 패배, 죄책감 (architecture.md:541, prd.md:444). Note the canonical machine list lives in `tools/brand-voice-lint.ts` `__testing.AVOID_LEXICON` — the doc names them for the human reviewer but does not redefine the lint.
- **Tone rule:** push/system copy is invitation, not demand (FR-8.8.3). **Elimination/error copy** uses "컴백 가능" language, never "탈락"/"실패" (FR-8.8.5).
- **Store copy:** EN "comeback pass", KR "회생권", never "revival ticket"/"second chance pass" (FR-8.8.4 → defer to `docs/aso-copy.md`).
- This section is **documentation** — it names the AVOID terms and the banned EN phrases. If a future automated scan over `docs/**` is ever added, it must exclude this doc the way `aso-copy-lint` scopes to copy-region markers. (No such scan exists today — `brand-voice-lint` roots are `FE/src`, `FE/app`, `BE/.../messages*.properties` only, so `docs/brand-voice-review.md` is **not** scanned and will not self-trigger. Verify gate 6.)

### AC6 — Out-of-scope fence

Explicitly NOT part of Story 8.4:

1. **Any FE/BE app code, migration, endpoint, STOMP topic, RealtimeEvent variant, NotificationKind, `tokens.json` edit, native surface.** Zero source under `FE/src`, `FE/app`, `BE/`, `infra/`. (The checklist *points at* copy sites; it does not change them. If the review finds a copy violation, fixing it is a separate change, not part of 8.4.)
2. **A new lint tool or script.** No `tools/*.ts`, no `scripts/*.sh` edit. The hard gate is CODEOWNERS + branch protection (epics.md:1068), and the lint pre-pass already exists (8.2/8.3). Adding a "release-gate-check" script would deviate from the locked decision.
3. **Modifying `brand-voice-lint.ts` / `aso-copy-lint.ts` / `contrast-check.ts` / `analytics-taxonomy-lint.ts` / `scripts/test.sh` / `tools/package.json` / `tokens.json`.** All byte-identical. (Story 8.4 references them; it does not touch them.)
4. **Editing the existing BE workflow.** `be-it-boot-smoke.yml` stays untouched. The review decision explicitly permits one new, focused brand-voice workflow.
5. **Actually configuring GitHub branch protection / rulesets, or changing repo settings.** That is a Post-merge user action (AC4), not a repo file.
6. **Real reviewer GitHub handles.** The committed `CODEOWNERS` uses the `@rearleg` placeholder; substituting real PM/designer accounts is a Post-merge action (AC4).
7. **Auditing/rewriting the actual v1 copy.** Stories 8.1/8.2/8.3 authored and lint-cleaned the copy. 8.4 ships the *process artifact* that reviews it going forward. (A first signed instance is optional — see AC7 note — but rewriting flagged copy is out of scope.)
8. **A `docs/index.md` entry for the new doc.** Optional hygiene; left out to keep the scope fence tight (mirrors Story 8.3 AC8 #9). A follow-up may add it.
9. **New dependencies.** None — this story adds Markdown + a CODEOWNERS text file only.

### AC7 — Pre-merge verify gates (structural; no executable tests)

This story ships Markdown, CODEOWNERS, and a GitHub Actions workflow. Verification is structural plus JavaScript/YAML syntax checks:

| # | Gate | How | Pass criterion |
|---|---|---|---|
| 1 | Checklist doc exists + has all 7 AC1 sections | `test -f docs/brand-voice-review.md && grep -cE "Automated pre-pass|Sign-off|decision|SLA|Versioning|Reference" docs/brand-voice-review.md` | file present; all named sections found |
| 2 | Five surface categories present | `grep -cE "Push notification|onboarding|error message|store metadata|System message" docs/brand-voice-review.md` | ≥ 5 (each epics.md:1070–1075 category named) |
| 3 | Per-release template exists + has sign-off + decision log | `test -f docs/releases/brand-voice-review-TEMPLATE.md && grep -cE "PM reviewer|Designer reviewer|Decision \(accept" docs/releases/brand-voice-review-TEMPLATE.md` | file present; both reviewer slots + the decision-log columns found |
| 4 | CODEOWNERS exists at `.github/CODEOWNERS` + assigns both review paths | `test -f .github/CODEOWNERS && grep -cE "brand-voice-review\.md|/docs/releases/" .github/CODEOWNERS` | file present; both paths owned |
| 5 | CODEOWNERS syntax valid | every non-comment, non-blank line is `^/?\S+(\s+@\S+)+$`; no tabs-as-owners typo | all lines parse; `gh` (if available) `gh api repos/:owner/:repo/codeowners/errors` reports 0 — else manual regex check |
| 6 | brand-voice-lint regression (doc not scanned) | `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts 2>&1 \| tail -1` | `0 HARD violation(s), 57 warning(s)` — unchanged; `docs/**` is not in lint roots so the new doc's AVOID-term reference does not register |
| 7 | aso-copy-lint regression | `tools/node_modules/.bin/tsx tools/aso-copy-lint.ts; echo $?` | exit `0`, unchanged warning count (`docs/aso-copy.md` untouched) |
| 8 | FE + BE delta = 0 | `git diff origin/main --stat -- FE/ BE/ infra/ \| wc -l` | `0` |
| 9 | Diff sanity (scope fence) | `git diff origin/main --stat \| grep -vE "docs/brand-voice-review\.md\|docs/releases/brand-voice-review-TEMPLATE\.md\|\.github/CODEOWNERS\|_bmad-output/"` | 0 unexpected files (AC8 allow-list only) |
| 10 | `scripts/test.sh` tools block still green | `bash scripts/test.sh` (FE/BE may skip per environment) | reaches + passes the existing tools block unchanged; no new failure (proves no tool/wiring was disturbed) |
| 11 | Release workflow syntax and contract | parse YAML; extract `github-script` body and run `node --check`; grep required role variables/checklist matcher/base+head constraints | YAML and JS parse; all enforcement tokens present |

**No manual EAS / device gate, no Docker-bound IT.** No new screen, navigation, or native surface; nothing to smoke on a simulator. The branch-protection configuration is a documented Post-merge user action (AC4), not a CI gate. The "merge on deferral" norm is therefore **not** invoked — all gates are local and non-manual.

**Optional (not required) — first signed instance:** the dev MAY produce a first `docs/releases/brand-voice-review-v1.0.0.md` filled instance as a worked example. This is OPTIONAL; if produced it adds one file to the allow list (gate 9) and must NOT be signed off as authoritative (no real release is happening). Default: ship the TEMPLATE only and leave the first real instance to the actual v1 release ritual.

### AC8 — File / scope fence (LOCKED ALLOW LIST)

**Story 8.4 creates/modifies exactly these files. The reviewer's diff-sanity gate (AC7 gate 9) MUST find no others.**

**NEW (4):**
```
docs/brand-voice-review.md                       (AC1 checklist + AC4 post-merge actions + AC5 reference)
docs/releases/brand-voice-review-TEMPLATE.md     (AC2 per-release instance template; creates docs/releases/)
.github/CODEOWNERS                               (AC3 two-reviewer ownership)
.github/workflows/brand-voice-release-gate.yml  (AC3 required role/checklist enforcement)
```

**MODIFIED — process (2):**
```
_bmad-output/implementation-artifacts/sprint-status.yaml                       (status flip + dated comment)
_bmad-output/implementation-artifacts/8-4-release-gate-brand-voice-review.md   (Tasks/File List/Completion Notes/Status)
```

**Banned paths (`git diff origin/main --stat` MUST show zero hits):**
- `FE/**` — no FE source of any kind
- `BE/**` — no backend of any kind (BE delta = 0)
- `infra/**` — no compose/nginx change
- `tools/**` — no new tool, no edit to brand-voice-lint / aso-copy-lint / contrast-check / analytics-taxonomy-lint / package.json / tsconfig
- `scripts/test.sh` — not touched (no tool to wire)
- `.github/workflows/**` except `brand-voice-release-gate.yml`
- `FE/src/theme/tokens.json` — not touched
- `docs/**` except the two new docs (no `docs/index.md`, no `docs/aso-copy.md` edit, no `docs/analytics.md` edit)

## Tasks / Subtasks

- [x] **Task 1 — Read-only inventory** (AC0)
  - [x] Read `tools/brand-voice-lint.ts` enough to cite its warn/HARD split + the `__testing.AVOID_LEXICON` 8 terms; read `docs/aso-copy.md` (doc-shape precedent + the store-metadata artifact); skim `scripts/test.sh:19–30` (the pre-pass command); confirm `.github/` holds only `be-it-boot-smoke.yml`.
  - [x] Confirm the five checklist categories (epics.md:1070–1075), the locked sign-off mechanism (epics.md:1068), and the needs-rewrite SLA (epics.md:1081) — do not re-derive.
  - [x] Spot-check the real copy sites the checklist will name: `notification/SpectatorDigestScheduler.java` push strings, `room/chat/ChatService.java` `publish…SystemMessage`, `app/onboarding.tsx` + `components/onboarding/OnboardingCarousel.tsx`, `lib/toast.ts`, `common/ApiExceptionHandler`. Cite paths that actually exist.
- [x] **Task 2 — Author `docs/brand-voice-review.md`** (AC1, AC4, AC5)
  - [x] All 7 AC1 sections; the five surface categories with real file pointers; the locked sign-off mechanism + inline-decision format + SLA verbatim-faithful to the epics AC.
  - [x] Post-merge user actions section (AC4): replace CODEOWNERS placeholders, enable `release/*` branch protection (require Code Owners + 2 approvals), per-release ritual.
  - [x] Reference section (AC5): USE/AVOID lexicon, tone rules, store-copy rule (defer to `docs/aso-copy.md`), note that `docs/**` is not lint-scanned.
- [x] **Task 3 — Author `docs/releases/brand-voice-review-TEMPLATE.md`** (AC2)
  - [x] Header (version/date/PR placeholders), two-slot sign-off block, automated-pre-pass attachment slot, five fillable surface checklists, decision-log table with the locked columns, SLA reminder, "TEMPLATE — do not sign" banner.
- [x] **Task 4 — Author `.github/CODEOWNERS`** (AC3)
  - [x] Header comment (PM + designer reviewers; replace placeholders; distinct accounts needed for a real two-party gate); own `/docs/releases/` and `/docs/brand-voice-review.md` with both `@rearleg` placeholders; valid syntax.
- [x] **Task 4b — Add required role-verification workflow** (review decision)
  - [x] Require one per-release checklist, complete surface checks, role-disjoint reviewer variables, one PM approval, one designer approval, and disable evidence for `needs-rewrite`.
- [x] **Task 5 — Verification** (AC7) — run the 10-gate structural matrix; confirm brand-voice-lint still `0 HARD / 57 warning(s)`, aso-copy-lint exit 0, FE/BE/infra/tools delta 0, scope fence = AC8 allow-list only, CODEOWNERS parses, `bash scripts/test.sh` tools block still green.
- [x] **Task 6 — Closeout** — fill File List + Completion Notes; flip sprint-status `8-4` → `review`.

### Review Findings

- [x] [Review][Patch] Add a required GitHub Actions release-gate check that verifies the per-release checklist exists and that both designated PM and designer approvals are present; CODEOWNERS alone treats multiple owners as alternatives and cannot enforce both roles. User decision: prioritize real enforcement. [.github/CODEOWNERS:3]
- [x] [Review][Patch] Define and document an executable v1 feature-disable mechanism for unresolved `needs-rewrite` items within 24 hours, and make the release-gate check require that evidence when applicable. User decision: prioritize real enforcement. [docs/brand-voice-review.md:171]
- [x] [Review][Patch] Correct the release PR, branch ruleset, and tag ruleset procedure; protection applies to the PR base branch, the documented wildcard branch-protection REST call is not valid, and branch protection does not restrict tag pushes. [docs/brand-voice-review.md:209]
- [x] [Review][Patch] Make the automated pre-pass fail closed or require attached evidence from each tool; `bash scripts/test.sh` exits successfully when `tools/node_modules` is absent and all three required tools are skipped. [docs/brand-voice-review.md:39]
- [x] [Review][Patch] Expand the error-copy inventory beyond `toast.ts` and screen-level branches to include component-local `COPY` constants, alerts, and other user-facing error strings. [docs/brand-voice-review.md:101]
- [x] [Review][Patch] Correct the completion and verification record: the remote CODEOWNERS API check returned 404, and the current configuration does not enforce the claimed two-role gate, so “all gates pass” and “zero spec deviations” are inaccurate. [_bmad-output/implementation-artifacts/8-4-release-gate-brand-voice-review.md:263]

## Dev Notes

### Implementation traps (ranked by likelihood of biting)

1. **Adding a "release-gate" script or CI job.** The strongest pull is to make the gate *executable* (a `tools/release-gate-check.ts` that fails CI if the instance file is unsigned). This **contradicts the locked decision** (epics.md:1068 — the gate is CODEOWNERS + branch protection, GitHub-native) and would add a tool that AC6 #2 forbids. Resist it. The repo artifact is `CODEOWNERS`; the enforcement is a GitHub *setting* documented as a Post-merge action.
2. **Trying to "create" branch protection from a file.** Branch protection / rulesets are repo settings, not files. No committed file enables them. Document the `gh api` / Settings steps as a Post-merge user action (AC4) and say plainly that CODEOWNERS alone only auto-requests review until the rule is on.
3. **Pretending a single account enforces dual sign-off.** With only `@rearleg`, GitHub will not let "require 2 approvals + Code Owner review" pass on a self-opened PR. Be honest in the CODEOWNERS comment + the doc: the placeholder is the current solo-maintainer reality; a genuine joint gate needs two distinct accounts. Do not write copy implying the gate is fully live with one account.
4. **The doc self-triggering a lint.** `docs/brand-voice-review.md` names the AVOID terms and (via the store item) the banned EN phrases. Today this is safe — `brand-voice-lint` roots are `FE/src` / `FE/app` / `BE messages*.properties`, and `aso-copy-lint` scans only `docs/aso-copy.md`'s copy regions — so neither scans the new doc. Verify gate 6 (`0 HARD, 57 warnings` unchanged). Do NOT add the new doc to any lint's roots.
5. **Vague checklist items.** "Review all copy" is useless to a reviewer. Each of the five categories MUST name real files (push → `notification/*.java` + `lib/notifications.ts`; onboarding → `app/onboarding.tsx` + `OnboardingCarousel.tsx`; errors → `ApiExceptionHandler` + `lib/toast.ts`; store → `docs/aso-copy.md`; system → `room/chat/ChatService.java`). The create-story anti-pattern this prevents: a reviewer skipping a surface because the checklist never told them where it lives.
6. **Wrong CODEOWNERS path location or syntax.** Put it at `.github/CODEOWNERS`. Paths are leading-slash-anchored (`/docs/releases/`). Two `@handle` tokens on a line = two owners. A syntax error silently disables ownership (no error surfaced in the PR) — that's a *false sense of a gate*. Validate (gate 5).
7. **Editing `scripts/test.sh` or a tool out of habit.** Prior Epic 8 stories (8.2/8.3/8.5) all wired a tool into `scripts/test.sh`. 8.4 does **not** — there is no tool. Touching it trips gate 9/10 and the AC8 banned-paths list.
8. **Scope creep into rewriting copy.** If the dev notices an AVOID term while authoring the checklist, that's a finding for a *future* review instance — not an 8.4 change. Record it as an out-of-scope note; do not edit FE/BE copy (AC6 #1, #7).
9. **Marking the TEMPLATE as a signed instance.** The template must carry a "do not sign this file" banner and use `<…>` placeholders. A filled, signed instance only exists per-release as `brand-voice-review-<version>.md`.
10. **Forgetting `docs/releases/` is created by the template file.** There is no separate "mkdir" deliverable — writing `docs/releases/brand-voice-review-TEMPLATE.md` creates the directory. Do not add a `.gitkeep` (the template file keeps the dir).

### Current state → what changes (per AC0 read-of-modified-files mandate)

- **No existing file is modified** except the two process files (sprint-status + this story). Every deliverable is NEW. There is therefore no regression surface in app code — the only "must preserve" is that the existing lint tools + `scripts/test.sh` stay byte-identical (proven by gates 6, 7, 10) so the automated pre-pass the checklist references still works.
- **`docs/aso-copy.md`** is the cross-linked artifact for the store-metadata checklist item — read it (already authored, Story 8.3) so the checklist points at it correctly; do not edit it.

### Why this story matters (the W7 close-out)

Epic 8's whole purpose (epics.md:978) is to defuse the 챌린저스 "penalty" mental model and make survival feel like dignity. Stories 8.1–8.3 authored the dignity-first copy and the lint that keeps it honest. Story 8.4 is the **governance** that prevents drift after launch: as new features add new copy, the release gate forces a joint PM + designer brand-voice sign-off before any production release. Without it, the lexicon discipline decays the first time a feature ships copy under deadline pressure with no human gate. This is the FR-8.8.6 quality gate (prd.md:448) and the last v1 W7 deliverable — it closes the brand-voice loop.

### Project context reference

- `project-context.md` — `apiRequest<T>`, SecureStore namespace, route registration, Flyway conventions, immutable updates, security filter order: **none apply** — this story is Markdown + a CODEOWNERS text file; zero API calls, zero routes, zero schema, zero React.
- Comment hygiene (project-context.md "Comments & Documentation"): no task/PR refs in committed artifacts; the CODEOWNERS header explains *why* (the gate) not *which task*. No emojis in source/docs unless requested.
- Branch + commit conventions (project-context.md): `feat/…` branch from `main`; `<type>: <description>`; no `Co-Authored-By` attribution.
- "Merge on deferral" norm (PRs #90/#93/#95/#98/#99/#100/#102/#103/#104/#105): **not invoked** — no Docker-bound IT, no EAS smoke. Branch-protection setup is a post-merge action (a GitHub setting), not a deferred CI gate.
- Test path note (Story 8.5/8.1 lesson): irrelevant here — no FE/BE tests are added (docs/config story).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 8.4 (1058–1083)] — the LOCKED sign-off mechanism (per-release instance + GitHub PR approval from PM + designer CODEOWNERS handles + `release/*` branch protection), the five checklist categories (1070–1075), the inline accept/reject/needs-rewrite decision recording, and the 1-business-day / feature-flag-off SLA.
- [Source: _bmad-output/planning-artifacts/epics.md#Epic 8 goal (978) + sprint (980, 1195)] — Epic 8 defuses the 챌린저스 model; Story 8.4 is the W7 release-gate close-out.
- [Source: _bmad-output/planning-artifacts/prd.md#FR-8.8.2/.3/.5/.6 (442–448)] — the brand-voice lexicon, push tone (invitation not demand), elimination copy ("컴백 가능" not "탈락"/"실패"), and the release-gate quality gate with PM + designer joint sign-off.
- [Source: _bmad-output/planning-artifacts/architecture.md#§4.15 (400–417)] — brand-voice enforcement is **manual sign-off + warn-only lint** (one NFR-9.6.1 hard sub-gate); the human review is authoritative; `docs/brand-voice-review.md` is the W7 Story-8.4 deliverable.
- [Source: _bmad-output/planning-artifacts/architecture.md#§5.5 (538–543)] — USE/AVOID lexicon + tone + store-copy ("comeback pass") patterns.
- [Source: docs/aso-copy.md] — Story 8.3 doc-shape precedent + the artifact the store-metadata checklist item reviews; its Governance section already names this 8.4 gate as the hard stop.
- [Source: tools/brand-voice-lint.ts (`__testing.AVOID_LEXICON`) + tools/aso-copy-lint.ts + scripts/test.sh:19–30] — the warn-only automated pre-pass the checklist runs before human review.
- [Source: _bmad-output/implementation-artifacts/8-3-aso-copy-lock-for-app-store-google-play-kr.md] — Epic 8 sibling conventions: strategic-context preamble, AC0 inventory, locked scope-fence allow list, grep-based verify matrix, Post-merge-user-action framing, comment hygiene.
- [Source: BE/src/main/java/com/yeosal/api/notification/SpectatorDigestScheduler.java:79; BE/src/main/java/com/yeosal/api/room/chat/ChatService.java; FE/app/onboarding.tsx; FE/src/components/onboarding/OnboardingCarousel.tsx; FE/src/lib/toast.ts; BE/.../common/ApiExceptionHandler] — the real copy sites the five checklist categories point at.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- AC7 gate run (all local, non-manual): brand-voice-lint `0 HARD / 57 warning(s)` (unchanged); aso-copy-lint exit `0` / `0 warning(s)` (unchanged); `bash scripts/test.sh` exit `0` end-to-end — FE 82 suites / 572 tests pass, tools block (brand-voice 0H/57, contrast 16/16, analytics-taxonomy 0 warn, aso-copy 0 warn, tsx `--test` 55/0), BE BUILD SUCCESSFUL.

### Completion Notes List

- **Review patch changed the enforcement design with user approval.** CODEOWNERS requests reviewers but does not require all listed owners. The new required workflow verifies exactly one release checklist, completed surface checks, disjoint role lists, one PM approval, one designer approval, and disable evidence for any `needs-rewrite` row.
- **Release topology is now accurate.** Release PRs use `release/v<x.y.z>` as head and `main` as base; a `main` branch ruleset requires the workflow. A separate `v*` tag ruleset protects tag creation.
- **The pre-pass is fail closed.** Release instructions install the tools workspace and run all three tools directly; skipped `scripts/test.sh` output is explicitly invalid evidence.
- **Error-copy coverage includes decentralized strings.** The checklist now covers component `COPY` constants, alerts, mutation error handlers, and inline failure/disabled messages.
- **Verification passed:** workflow YAML parses; extracted `github-script` body passes `node --check` in an async wrapper; CODEOWNERS regex validation passes; brand-voice lint `0 HARD / 57 WARN`; ASO `0 WARN`; contrast `16/16`; `bash scripts/test.sh` passed FE 82 suites / 572 tests / 9 snapshots, tools 55 tests, and BE BUILD SUCCESSFUL.
- **Post-merge settings remain required:** replace placeholder handles, configure disjoint reviewer variables, require the workflow on `main`, and create the `v*` tag ruleset.

### File List

**NEW (4):**
- `docs/brand-voice-review.md` — canonical evergreen checklist + governance (AC1) + Post-merge actions (AC4) + lexicon reference (AC5)
- `docs/releases/brand-voice-review-TEMPLATE.md` — per-release instance template (AC2); creates `docs/releases/`
- `.github/CODEOWNERS` — review requests for the release-review artifacts (AC3)
- `.github/workflows/brand-voice-release-gate.yml` — required checklist and role-approval verification (review patch)

**MODIFIED — process (2):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — status flip + dated comment
- `_bmad-output/implementation-artifacts/8-4-release-gate-brand-voice-review.md` — Tasks/File List/Completion Notes/Change Log/Status

### Change Log

- 2026-06-11 — Story 8.4 implemented (docs + repo-config; zero app code): brand-voice release-gate checklist + per-release template + CODEOWNERS two-reviewer gate. All 10 AC7 structural gates PASS; brand-voice-lint 0 HARD/57 unchanged. Status: ready-for-dev → review.
- 2026-06-11 — Code-review patches applied: replaced the incorrect CODEOWNERS-only enforcement model with a required role-verification workflow, corrected branch/tag ruleset guidance, added fail-closed pre-pass evidence and executable disable evidence, expanded error-copy coverage, and re-verified the full repository. Status: review → done.
